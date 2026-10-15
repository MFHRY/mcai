"""给源图叠一层坐标网格，用来肉眼量出某个区域的像素范围。

一次性诊断工具：给图片画上每 5% 一条的网格线并标出百分比，
看图上就能读出「对话气泡」大概占了哪一块，再把范围写进 prepare_avatar.py。

用法：python tools/grid_overlay.py <源图> <输出png>
"""
import sys

from PIL import Image, ImageDraw

STEPS = 20  # 5% 一格


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source, target = sys.argv[1], sys.argv[2]

    image = Image.open(source).convert("RGBA")
    width, height = image.size
    draw = ImageDraw.Draw(image)

    for index in range(1, STEPS):
        fraction = index / STEPS
        x = int(width * fraction)
        y = int(height * fraction)
        major = index % 2 == 0  # 每 10% 画亮一点
        color = (255, 0, 0, 210) if major else (0, 200, 0, 150)
        draw.line([(x, 0), (x, height)], fill=color, width=2 if major else 1)
        draw.line([(0, y), (width, y)], fill=color, width=2 if major else 1)
        if major:
            draw.text((x + 3, 4), f"{int(fraction * 100)}", fill=(255, 0, 0, 255))
            draw.text((4, y + 3), f"{int(fraction * 100)}", fill=(255, 0, 0, 255))

    image.convert("RGB").save(target, "PNG")
    print(f"wrote {target}  {width}x{height}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
