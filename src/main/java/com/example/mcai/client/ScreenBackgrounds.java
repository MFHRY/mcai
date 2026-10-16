package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

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
 * <h2>1.20.1 为什么没有「模糊」这个选项</h2>
 *
 * <p>不是没做，是<b>做不到</b>：菜单模糊（{@code Screen.applyBlur} →
 * {@code GameRenderer.renderBlur}）是 <b>1.21 才加入的 API</b>，1.20.1 的
 * {@code Screen} 里根本不存在这两个方法，{@code Options} 里也没有
 * {@code menuBackgroundBlurriness}。所以在 1.20.1 上，ESC 暂停菜单自己都不模糊，
 * 这里自然也无从模仿。
 *
 * <h2>为什么要防止路径穿越</h2>
 *
 * <p>自定义背景是玩家自己填的本地路径，读的是自己机器上的文件，本身没有越权问题；
 * 但仍然只接受图片扩展名，并在失败时静默回退到 {@code dim}，
 * 避免一个坏路径把整个界面画成黑屏。
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
    private static final ResourceLocation CUSTOM_ID =
            new ResourceLocation(McaiMod.MOD_ID, "screen_background");

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
     * 画背景。在 {@code Screen.renderBackground(GuiGraphics)} 里调用。
     *
     * <p>注意 {@code dim} 走的是原版 {@link Screen#renderBackground(GuiGraphics)}，
     * 所以选它时观感与改动前完全一致 —— 这个功能的默认行为没有变。
     */
    public static void render(Screen screen, GuiGraphics context) {
        switch (currentMode()) {
            case DIRT -> screen.renderDirtBackground(context);
            case NONE -> { /* 什么都不画，游戏画面直接透出来 */ }
            case CUSTOM -> {
                if (renderCustom(context)) {
                    return;
                }
                // 图没准备好（还在加载 / 路径错）就先退回暗色渐变，
                // 否则界面会有一瞬间完全没有背景、看着像坏掉
                screen.renderBackground(context);
            }
            default -> screen.renderBackground(context);
        }
    }

    private static boolean renderCustom(GuiGraphics context) {
        if (!ensureCustomTexture()) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        // 平铺：界面通常比原图大，拉伸会糊，直接铺满
        context.blit(CUSTOM_ID, 0, 0, 0.0F, 0.0F, width, height, width, height);
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
            DynamicTexture texture = new DynamicTexture(image);
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
