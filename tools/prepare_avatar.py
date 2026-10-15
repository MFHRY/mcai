"""把用户提供的源图处理成模组内置的默认头图。

源图是 JPEG、纯白背景、左上角还有一个「好模型」对话气泡。
直接当 HUD 头像用会是一个白方块，所以要做四件事：

1. **抠背景**：不能用"白色变透明"——角色头顶的女仆头饰本身就是白色，那样会被挖出洞。
   正确做法是从图像四边做**泛洪填充**（形态学重建）：只有"能从外部连通的白色"才算背景，
   被头发轮廓包住的白色（头饰）会自然保留。
2. **抹掉对话气泡**：气泡内部是"封闭白色"，按"离左上角最近的封闭白色块"定位它，
   再膨胀几像素盖住深色轮廓和里面的文字。
3. **丢小碎块**：气泡尾巴之类的孤立小块按面积过滤掉。
4. **去白边**：抗锯齿边缘是"角色色 + 白背景"的混合，直接设 alpha 会留下白圈。
   按 alpha 反解出原始颜色（un-premultiply）就能消掉。

> **关键坑：泛洪和气泡定位必须在原分辨率上做。**
> 先缩到 400px 再算，那圈只有 1px 的气泡轮廓会被插值打断，泛洪从缺口漏进气泡内部，
> 于是"封闭白色"消失、气泡定位失败，最后留下一个没有填充的轮廓和几个文字笔画。

用法：python tools/prepare_avatar.py <源图> <输出png>
"""
import sys

import numpy as np
from PIL import Image

# 低于这个亮度就算"背景候选"。
# **不能定太低**：角色皮肤也是接近白的（约 250,240,235），阈值一低就会把脸和脖子
# 判成背景，抠图时直接在身上挖洞。
WHITE_LEVEL = 247
# 轮廓可能有 1-2px 的缺口（JPEG 压缩 + 抗锯齿），先膨胀这么多次把缺口补上，
# 封闭区域才认得出来；擦除时要把这份膨胀量加回去。
GAP_CLOSE = 3
# 对话气泡要擦掉的矩形区域，比例坐标（左上原点）。
# 这是用 tools/grid_overlay.py 在源图上逐格量出来的：
#   气泡主体    x 0%   -> 50%，y 0%  -> 39%
#   两个小尾巴圆 x 11%  -> 25%，y 38% -> 50%（y 上限留到 50% 是因为要含到下边缘）
# 呆毛（那撮卷发）从 x 53% 才开始，所以擦到 50% 不会碰到它。
BUBBLE_RECTS = [
    (0.00, 0.00, 0.505, 0.40),
    (0.11, 0.38, 0.25, 0.505),
]
# 小于这个面积占比的连通块直接丢掉（气泡尾巴、噪声）；在缩小的掩码上判断
MIN_COMPONENT_RATIO = 0.004
# 小碎块过滤和 alpha 平滑都在这个尺寸上做
MASK_SIZE = 400
OUT_SIZE = 256
# 泛洪的迭代上限（4 邻域每步只能前进 1px，所以要够大）
MAX_FLOOD_STEPS = 4000


def dilate(mask: np.ndarray) -> np.ndarray:
    """4 邻域膨胀一步。"""
    out = mask.copy()
    out[1:, :] |= mask[:-1, :]
    out[:-1, :] |= mask[1:, :]
    out[:, 1:] |= mask[:, :-1]
    out[:, :-1] |= mask[:, 1:]
    return out


def flood(mask: np.ndarray, seed: np.ndarray, label: str) -> np.ndarray:
    """在 mask 内部，从 seed 出发做形态学重建。"""
    current = seed & mask
    for step in range(1, MAX_FLOOD_STEPS + 1):
        grown = dilate(current) & mask
        if np.array_equal(grown, current):
            print(f"  {label}: converged after {step} steps")
            return current
        current = grown
    print(f"  {label}: hit the {MAX_FLOOD_STEPS} step cap")
    return current


def border_seed(mask: np.ndarray) -> np.ndarray:
    """图像四边中属于 mask 的像素，作为"外部背景"的种子。"""
    seed = np.zeros_like(mask)
    seed[0, :] = mask[0, :]
    seed[-1, :] = mask[-1, :]
    seed[:, 0] = mask[:, 0]
    seed[:, -1] = mask[:, -1]
    return seed


def bubble_region(shape: tuple[int, int], rects) -> np.ndarray:
    """把要擦掉的气泡区域拼成一个掩码，并向外扩一点，免得轮廓线残留成一道墙。"""
    height, width = shape
    region = np.zeros((height, width), dtype=bool)
    for (fx0, fy0, fx1, fy1) in rects:
        x0, y0 = int(width * fx0), int(height * fy0)
        x1, y1 = int(width * fx1), int(height * fy1)
        region[y0:y1, x0:x1] = True
    margin = max(2, width // 150)
    for _ in range(margin):
        region = dilate(region)
    return region


def label_components(mask: np.ndarray) -> list[tuple[int, tuple[int, int, int, int], np.ndarray]]:
    """把 mask 拆成连通块，返回 [(面积, bbox, 掩码), ...]，按面积降序。"""
    remaining = mask.copy()
    found = []
    while True:
        ys, xs = np.nonzero(remaining)
        if ys.size == 0:
            break
        seed = np.zeros_like(remaining)
        seed[ys[0], xs[0]] = True
        component = seed
        while True:
            grown = dilate(component) & remaining
            if np.array_equal(grown, component):
                break
            component = grown
        area = int(component.sum())
        yy, xx = np.nonzero(component)
        found.append((area, (int(xx.min()), int(yy.min()), int(xx.max()) + 1, int(yy.max()) + 1), component))
        remaining &= ~component
    found.sort(key=lambda item: -item[0])
    return found


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source, target = sys.argv[1], sys.argv[2]

    original = Image.open(source).convert("RGB")
    width, height = original.size
    print(f"source: {width}x{height}")

    # ---------- 一律在原分辨率上判断，见文件头的"关键坑" ----------
    full = np.asarray(original).astype(np.int16)
    # 先把气泡整块挖掉再算背景。顺序很重要：
    # 气泡被左/上边缘裁断，它自己的边缘像素会混进"边界背景"的种子里，泛洪于是从那儿
    # 灌进气泡内部；而只从右上角一个点起泛洪，又覆盖不到被角色分隔开的其它背景块。
    # 正确做法是先把气泡排除在外，再拿整条边界当种子。
    bubble = bubble_region((height, width), BUBBLE_RECTS)
    print(f"  bubble region: {bubble.mean() * 100:.1f}% of the image")

    background = (full.min(axis=2) >= WHITE_LEVEL) & ~bubble
    print(f"  background candidates: {background.mean() * 100:.1f}%")

    outside = flood(background, border_seed(background), "flood fill (background)")
    character = ~outside & ~bubble
    print(f"  character pixels: {character.mean() * 100:.1f}%")

    if not character.any():
        print("everything was erased; aborting")
        return 1

    # ---------- 小碎块过滤（缩到 MASK_SIZE 上做，快得多） ----------
    small = np.asarray(
        Image.fromarray((character * 255).astype(np.uint8), mode="L").resize(
            (MASK_SIZE, MASK_SIZE), Image.LANCZOS)
    ) > 127
    components = label_components(small)
    total = max(1, int(small.sum()))
    print(f"  connected components: {len(components)}")
    keep = np.zeros_like(small)
    for index, (area, bbox, mask) in enumerate(components):
        ratio = area / total
        if index < 6:
            mark = "keep " if ratio >= MIN_COMPONENT_RATIO else "drop "
            print(f"    [{index}] area={area:6d} ({ratio * 100:5.2f}%) bbox={bbox} {mark}")
        if ratio >= MIN_COMPONENT_RATIO:
            keep |= mask

    if not keep.any():
        print("nothing left after filtering; aborting")
        return 1

    # ---------- 合成 alpha 并反解白边 ----------
    alpha = Image.fromarray((keep * 255).astype(np.uint8), mode="L").resize(
        (width, height), Image.LANCZOS)
    alpha_array = np.asarray(alpha).astype(np.float32) / 255.0

    rgb = np.asarray(original).astype(np.float32)
    # 抗锯齿像素 = alpha * 真实色 + (1 - alpha) * 白底，反解掉白底，否则边缘留白圈
    safe = np.clip(alpha_array, 1e-3, 1.0)[..., None]
    rgb = np.clip((rgb - (1.0 - safe) * 255.0) / safe, 0, 255)

    out = np.dstack([rgb, alpha_array * 255.0]).astype(np.uint8)
    image = Image.fromarray(out, mode="RGBA")

    bbox = image.getbbox()
    print(f"  alpha bbox: {bbox}")
    image = image.crop(bbox)

    side = max(image.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(image, ((side - image.size[0]) // 2, (side - image.size[1]) // 2))
    square = square.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
    square.save(target, "PNG")
    print(f"wrote {target}  {square.size[0]}x{square.size[1]}  {square.mode}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
