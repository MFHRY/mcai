package com.example.mcai.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * 界面裁剪区域计算（#4 GUI 感知提问）。
 *
 * <p><b>要解决的问题：</b>玩家开着背包/箱子问 AI 时，整张截图里绝大部分是游戏世界，
 * 真正有用的只有中间那块界面。图片 token 大致与像素数成正比，所以只发界面那一块
 * 能把识别成本降到原来的四分之一左右，同时模型也更容易看清格子里的东西。
 *
 * <p><b>为什么不写 mixin：</b>{@code HandledScreen} 的 {@code x / y / backgroundWidth /
 * backgroundHeight} 是 {@code protected}，正常要加 accessor mixin。但本模组只需要"读四个
 * int"，用反射读更轻量，也不必改 mixin 配置（改错了会直接导致游戏启动失败）。
 * 反射失败、或当前不是容器界面时，返回 null 表示"不裁剪"，功能照常。
 *
 * <p>返回的坐标是 <b>0~1 的比例</b>，与截图实际像素尺寸无关，缩放分辨率怎么变都适用。
 */
public final class ScreenCrop {

    private ScreenCrop() {}

    /** 裁剪区域（比例，左上到右下）。 */
    public record Region(double x0, double y0, double x1, double y1) {
        public double width() { return Math.max(0.0, x1 - x0); }
        public double height() { return Math.max(0.0, y1 - y0); }
    }

    /** 界面四周留一点边，避免把边框和标题切掉。 */
    private static final int PADDING = 8;

    /**
     * 当前是否开着"容器类界面"（背包/箱子/工作台……）。这类界面才值得裁剪。
     */
    public static Region forCurrentScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen screen = client.currentScreen;
        if (!(screen instanceof HandledScreen<?> handled)) {
            return null;
        }

        int scaledWidth = client.getWindow().getScaledWidth();
        int scaledHeight = client.getWindow().getScaledHeight();
        if (scaledWidth <= 0 || scaledHeight <= 0) {
            return null;
        }

        int[] box = readBox(handled);
        if (box == null) {
            return null;
        }

        int x = box[0] - PADDING;
        int y = box[1] - PADDING;
        int w = box[2] + PADDING * 2;
        int h = box[3] + PADDING * 2;

        double x0 = clamp01(x / (double) scaledWidth);
        double y0 = clamp01(y / (double) scaledHeight);
        double x1 = clamp01((x + w) / (double) scaledWidth);
        double y1 = clamp01((y + h) / (double) scaledHeight);

        if (x1 - x0 < 0.05 || y1 - y0 < 0.05) {
            // 裁出来太小，多半是反射取到的值没意义，不如不裁
            return null;
        }
        return new Region(x0, y0, x1, y1);
    }

    /**
     * 用反射读 {@code x, y, backgroundWidth, backgroundHeight}。
     * 任何一个读不到就返回 null（调用方按"不裁剪"处理）。
     */
    private static int[] readBox(HandledScreen<?> screen) {
        try {
            int x = readInt(screen, "x");
            int y = readInt(screen, "y");
            int w = readInt(screen, "backgroundWidth");
            int h = readInt(screen, "backgroundHeight");
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new int[]{x, y, w, h};
        } catch (Throwable t) {
            // 混淆名变化 / 权限限制 —— 直接放弃裁剪，不影响识别
            return null;
        }
    }

    private static int readInt(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field field = null;
        Class<?> type = target.getClass();
        while (type != null && field == null) {
            try {
                field = type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
