package com.example.mcai.util;

import java.util.List;
import java.util.Locale;

/**
 * 截图分辨率配置的解析。
 *
 * <p>标准取值：{@code 360p} / {@code 720p} / {@code 1080p} / {@code original}。
 *
 * <p><b>为什么标准值是 ASCII 的 {@code original} 而不是中文</b>：
 * 这个值会写进 {@code mcai.json}，也会被 {@code /ai resolution} 当参数解析。
 * 1.21.1 的 {@code StringArgumentType.word()} 只接受 ASCII 字母数字，中文参数根本
 * 解析不了；而且配置里存中文会让国际玩家的配置文件出现乱码风险。
 * 所以<b>存储与解析一律用 ASCII</b>，只有<b>显示</b>的时候才翻译成
 * 「原始」/「Original」（见 {@link #label}）。
 *
 * <p>同时兼容早期版本写进配置文件的 {@code "原始"} / {@code "原图"} 和
 * {@code "1280x720"} 这种遗留写法，避免老配置导致分辨率莫名其妙失效。
 */
public final class VisionResolution {

    /** 配置里实际存储的值，ASCII，与语言无关。 */
    public static final String ORIGINAL = "original";
    public static final String DEFAULT = "720p";

    /** 给 /ai resolution 的 Tab 补全用。 */
    public static final List<String> OPTIONS = List.of("360p", "720p", "1080p", ORIGINAL);

    /**
     * 旧版本（只有中文标准值那一版）的等价写法。
     * 只在解析用户输入时作为别名接受，绝不会再被写回配置。
     */
    public static final List<String> LEGACY_ORIGINAL = List.of("原始", "原图");

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

    /** 是否是旧版中文写法（"原始" / "原图"）。 */
    public static boolean isLegacyOriginal(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return LEGACY_ORIGINAL.contains(v) || v.equalsIgnoreCase(ORIGINAL);
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
     * <p>比 {@link #isValid} 宽松：除了四个标准值，还接受旧版中文写法、
     * {@code 480p} / {@code 2160p} 这类自定义高度，以及 {@code 1280x720} 这种遗留写法。
     */
    public static boolean isUsable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        return OPTIONS.contains(v) || isLegacyOriginal(v) || targetHeight(v) > 0;
    }

    /** 把任意输入规范化成 OPTIONS 里的某一项（或 "&lt;数字&gt;p" 形式），用于写入配置。 */
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
        // "原始" / "原图" / "original" / "raw" 之类的同义词归一到 ORIGINAL
        if (targetHeight(v) == 0) {
            return ORIGINAL;
        }
        int h = targetHeight(v);
        return h + "p";
    }

    /** 给聊天栏显示用的原始值，例如 "720p"、"original"。 */
    public static String display(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        return value.trim();
    }

    /**
     * 给玩家看的名字：只有「不缩放」这一项需要翻译，其余（720p 等）本来就是通用写法。
     * 兼容旧配置里存的 "原始"。
     */
    public static String label(String value) {
        if (isLegacyOriginal(value) || targetHeight(value) == 0) {
            return Lang.tr("mcai.resolution.original");
        }
        return display(value);
    }
}
