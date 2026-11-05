package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * mcAI 各界面（{@code /ai config}、截图框选、发送预览）的可切换背景。
 *
 * <h2>可选值</h2>
 * <ul>
 *   <li>{@code dim} —— 暗色渐变盖住游戏画面。原版在游戏内打开界面时的默认观感。</li>
 *   <li>{@code dirt} —— 经典泥土平铺背景。原版在主菜单等场景用的那张。</li>
 *   <li>{@code none} —— 完全透明，游戏画面原样透出来（界面只剩控件）。</li>
 *   <li>{@code custom} —— 用 {@code screen_background_image} 指定的 PNG 平铺。</li>
 * </ul>
 *
 * <h2>26.1 改写说明（为什么不再调用 Screen 自己的方法）</h2>
 *
 * <p>1.20.1 这里直接调 {@code screen.renderBackground(...)} / {@code screen.renderDirtBackground(...)}
 * 复用原版画法。26.1 那边对应的方法搬进了 {@code extractBackground(...)} 家族，而且
 * {@code extractMenuBackground} 变成了 <b>protected</b> —— 从一个外部工具类里根本调不到。
 * 更麻烦的是：我们自己的界面就在 {@code extractBackground} 里调用本类，再去调
 * {@code screen.extractBackground(...)} 就是无限递归。
 *
 * <p>所以这四种背景改成本类<b>自己画</b>，语义保持：
 * <ul>
 *   <li>{@code dim}：一层由深到浅的黑色渐变（与游戏内界面的观感一致）；</li>
 *   <li>{@code dirt}：原版 {@link Screen#MENU_BACKGROUND} 那张泥土图，铺满整屏；</li>
 *   <li>{@code none}：什么都不画；</li>
 *   <li>{@code custom}：玩家指定的图片，铺满整屏。</li>
 * </ul>
 * 差别只在 {@code dirt} 是拉伸而不是逐格平铺 —— 为了不依赖那张图的原始像素尺寸，
 * 这是唯一不需要写死 16x16 的做法。
 */
public final class ScreenBackgrounds {

    public static final String DIM = "dim";
    public static final String DIRT = "dirt";
    public static final String NONE = "none";
    public static final String CUSTOM = "custom";

    /** 界面上循环切换的顺序。 */
    public static final List<String> OPTIONS = List.of(DIM, DIRT, NONE, CUSTOM);

    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp"};

    /** 自定义背景的纹理 id。用固定 id，换图时先释放再注册。 */
    private static final Identifier CUSTOM_ID =
            Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "screen_background");

    /** dim 模式的两端颜色：上浅下深，和原版游戏内界面同向。 */
    private static final int DIM_TOP = 0xC0101010;
    private static final int DIM_BOTTOM = 0xD0101010;

    private static String loadedPath;
    private static boolean customReady;
    private static boolean customFailed;

    private ScreenBackgrounds() {}

    /** 当前配置对应的背景模式；配置缺失或值非法时回退到 {@code dim}。 */
    public static String currentMode() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String mode = config == null ? null : config.screenBackground;
        if (mode == null || !OPTIONS.contains(mode)) {
            return DIM;
        }
        return mode;
    }

    /**
     * 画背景。在各个界面的 {@code extractBackground(GuiGraphicsExtractor, int, int, float)}
     * 里调用。
     */
    public static void render(Screen screen, GuiGraphicsExtractor context) {
        int width = screen.width;
        int height = screen.height;
        switch (currentMode()) {
            // MENU_BACKGROUND 是"纹理路径"而不是"精灵 id"，所以走 blit 而不是 blitSprite
            case DIRT -> context.blit(Screen.MENU_BACKGROUND, 0, 0, width, height,
                    0.0F, 1.0F, 0.0F, 1.0F);
            case NONE -> { /* 什么都不画，游戏画面直接透出来 */ }
            case CUSTOM -> {
                if (!renderCustom(context, width, height)) {
                    // 图没准备好（还在加载 / 路径错）就先退回暗色渐变，
                    // 否则界面会有一瞬间完全没有背景、看着像坏掉
                    context.fillGradient(0, 0, width, height, DIM_TOP, DIM_BOTTOM);
                }
            }
            default -> context.fillGradient(0, 0, width, height, DIM_TOP, DIM_BOTTOM);
        }
    }

    private static boolean renderCustom(GuiGraphicsExtractor context, int width, int height) {
        if (!ensureCustomTexture()) {
            return false;
        }
        // 铺满整屏：界面通常比原图大，按原始 uv 拉伸比逐格平铺实现简单且不会露出边
        context.blit(CUSTOM_ID, 0, 0, width, height, 0.0F, 1.0F, 0.0F, 1.0F);
        return true;
    }

    /**
     * 按需加载自定义背景。只在路径变化或上次失败后未重试时真正读盘。
     *
     * <p>全部失败路径都返回 false 而不是抛异常：背景图读不出来不该让界面崩掉。
     */
    private static boolean ensureCustomTexture() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String path = config == null ? null : config.screenBackgroundImage;
        if (path == null || path.isBlank()) {
            return false;
        }
        if (path.equals(loadedPath) && customReady) {
            return true;
        }
        if (path.equals(loadedPath) && customFailed) {
            return false;
        }

        loadedPath = path;
        customReady = false;
        customFailed = false;

        if (!hasImageExtension(path)) {
            McaiMod.LOGGER.warn("mcAI: screen background image is not a supported image type: {}", path);
            customFailed = true;
            return false;
        }

        try {
            Path file = Path.of(path).toAbsolutePath();
            if (!Files.isRegularFile(file)) {
                McaiMod.LOGGER.warn("mcAI: screen background image not found: {}", file);
                customFailed = true;
                return false;
            }
            NativeImage image;
            try (InputStream in = Files.newInputStream(file)) {
                image = NativeImage.read(in);
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                customFailed = true;
                return false;
            }

            Minecraft client = Minecraft.getInstance();
            // 26.1 的 DynamicTexture 第一个参数改成"标签"的 Supplier（调试/导出用）
            DynamicTexture texture = new DynamicTexture(() -> "mcai:screen_background", image);
            // 换图时先放掉上一张，否则反复换图会一直堆积显存
            client.getTextureManager().release(CUSTOM_ID);
            client.getTextureManager().register(CUSTOM_ID, texture);
            customReady = true;
            McaiMod.LOGGER.info("mcAI: loaded screen background image {}x{} from {}",
                    image.getWidth(), image.getHeight(), file);
            return true;
        } catch (Throwable t) {
            McaiMod.LOGGER.warn("mcAI: could not load screen background image {}: {}",
                    path, t.toString());
            customFailed = true;
            return false;
        }
    }

    private static boolean hasImageExtension(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 玩家在设置界面里改了图，调这个让下次渲染重新读盘。
     *
     * <p>不做成每帧检查路径变化：读盘很贵，而路径只会在玩家主动修改时变。
     */
    public static void invalidateCustomImage() {
        if (loadedPath != null && !loadedPath.isBlank()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                client.getTextureManager().release(CUSTOM_ID);
            }
        }
        loadedPath = null;
        customReady = false;
        customFailed = false;
    }

    /** 界面上的显示名；没有对应语言键就退回原值。 */
    public static String describe(String mode) {
        if (mode == null) {
            return com.example.mcai.util.Lang.tr("mcai.value.unknown");
        }
        String key = "mcai.config.background." + mode;
        return com.example.mcai.util.Lang.has(key)
                ? com.example.mcai.util.Lang.tr(key)
                : mode;
    }
}
