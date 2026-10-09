package com.example.mcai.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人格预设（#9）。
 *
 * <p>每个人格有两套文案键：
 * <ul>
 *   <li><b>显示名</b>（{@code mcai.persona.xxx}）—— 给玩家看的短名字</li>
 *   <li><b>提示词后缀</b>（{@code mcai.persona.suffix.xxx}）—— 真正追加到 system 提示词里的角色设定</li>
 * </ul>
 * 两者分开，是为了让"建筑"这种短名字既能显示在聊天栏，又不至于把提示词写得太寒碜。
 *
 * <p>目前只做<b>预设</b>；在界面里自由编辑提示词需要自研多行输入框，属于更大的工作量。
 */
public final class PersonaCatalog {

    private PersonaCatalog() {}

    /** id -> 显示名键。 */
    private static final Map<String, String> DISPLAY_KEYS = new LinkedHashMap<>();
    /** id -> 提示词后缀键。default 没有后缀。 */
    private static final Map<String, String> SUFFIX_KEYS = new LinkedHashMap<>();

    static {
        put("default", "mcai.persona.default", null);
        put("builder", "mcai.persona.builder", "mcai.persona.suffix.builder");
        put("redstone", "mcai.persona.redstone", "mcai.persona.suffix.redstone");
        put("survival", "mcai.persona.survival", "mcai.persona.suffix.survival");
        put("english", "mcai.persona.english", "mcai.persona.suffix.english");
    }

    private static void put(String id, String displayKey, String suffixKey) {
        DISPLAY_KEYS.put(id, displayKey);
        if (suffixKey != null) {
            SUFFIX_KEYS.put(id, suffixKey);
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** 所有可用预设的 id（展示顺序）。 */
    public static List<String> ids() {
        return new ArrayList<>(DISPLAY_KEYS.keySet());
    }

    /** 是否为已知预设。 */
    public static boolean isKnown(String id) {
        return DISPLAY_KEYS.containsKey(normalize(id));
    }

    /** 取某个预设要追加到 system 里的后缀；default 或未知返回空串。 */
    public static String systemSuffix(String id) {
        String key = SUFFIX_KEYS.get(normalize(id));
        return key == null ? "" : Lang.tr(key);
    }

    /** 渲染成 {@code <id> (<显示名>)}。 */
    public static String describe(String id) {
        String display = DISPLAY_KEYS.get(normalize(id));
        String shown = display == null ? Lang.tr("mcai.value.unknown") : Lang.tr(display);
        String raw = (id == null || id.isBlank()) ? Lang.tr("mcai.value.unknown") : id.trim();
        return raw + " (" + shown + ")";
    }
}