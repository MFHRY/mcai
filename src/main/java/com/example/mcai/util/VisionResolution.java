package com.example.mcai.util;

import java.util.List;
import java.util.Locale;

/**
 * 截图分辨率配置的解析。
 *
 * <p>标准取值：{@code 360p} / {@code 720p} / {@code 1080p} / {@code 原始}。
 * 同时兼容早期版本写进配置文件的 {@code "1280x720"} 这种遗留写法，
 * 避免老配置导致分辨率莫名其妙失效。
 */
public final class VisionResolution {

    public static final String ORIGINAL = "原始";
    public static final String DEFAULT = "720p";

    /** 给 /ai resolution 的 Tab 补全用。 */
    public static final List<String> OPTIONS = List.of("360p", "720p", "1080p", ORIGINAL);

    private VisionResolution() {}

    /**
     * 返回目标像素高度；返回 {@code 0} 表示不缩放（原始尺寸）。
     */
    public static int targetHeight(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);

        if (v.equals("原始") || v.equals("原图") || v.equals("original") || v.equals("raw")) {
            return 0;
        }

        // 360p / 720p / 1080p，也接受纯数字 "720"
        if (v.endsWith("p")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.matches("\\d{3,4}")) {
            return Integer.parseInt(v);
        }

        // 遗留写法 "1280x720" -> 取高度 720
        if (v.matches("\\d{3,5}\\s*[x×]\\s*\\d{3,5}")) {
            String[] parts = v.split("[x×]");
            return Integer.parseInt(parts[1].trim());
        }

        // 其它无法识别的值一律按"原始"处理，保证不会因为配置写错就截不出图
        return 0;
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return OPTIONS.contains(v);
    }

    /**
     * 是否是一个「能真正拿来用」的取值。
     *
     * <p>比 {@link #isValid} 宽松：除了四个标准值，还接受 {@code 480p}、{@code 2160p}
     * 这类自定义高度，以及 {@code 1280x720} 这种遗留写法。
     */
    public static boolean isUsable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        return OPTIONS.contains(v) || targetHeight(v) > 0;
    }

    /** 把任意输入规范化成 OPTIONS 里的某一项，用于写入配置。 */
    public static String normalize(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String v = value.trim();
        for (String option : OPTIONS) {
            if (option.equalsIgnoreCase(v) || option.equals(v)) {
                return option;
            }
        }
        // "原图"/"original" 之类的同义词归一到 "原始"
        if (targetHeight(v) == 0) {
            return ORIGINAL;
        }
        int h = targetHeight(v);
        return h + "p";
    }

    /** 给聊天栏显示用，例如 "720p"。 */
    public static String display(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return value.trim();
    }
}
