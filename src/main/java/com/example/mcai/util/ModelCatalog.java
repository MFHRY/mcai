package com.example.mcai.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型字典：把 API 返回的英文模型 ID 映射成「中文厂商 + 能力等级」。
 *
 * <p>用于聊天栏提示，格式严格为：
 * <pre>&lt;原英文模型ID&gt; (&lt;中文厂商&gt;, &lt;等级&gt;)</pre>
 * 例如 {@code deepseek-flash (深度求索, Low)}。
 *
 * <p>字典里查不到的模型，按需求追加 {@code (未知厂商, 未知等级)}。
 * 想支持新模型，只要在下面的 static 块里加一行 {@code put(...)} 即可。
 */
public final class ModelCatalog {

    /**
     * 单个模型的展示信息。
     *
     * @param name   中文厂商名
     * @param tier   能力等级
     * @param vision 是否支持图片输入。此字段是<b>实测</b>结论：
     *               {@code deepseek-flash} 能真正读图（喂了一张写着 MCAI-7391 的图，
     *               它准确读了出来）；而 {@code deepseek-v4-pro} <b>不支持图片输入</b>，
     *               它不会报错，只会礼貌地回一句"我无法读取这张图片"——既浪费 token
     *               又让人困惑，所以截图识别前会先用这个标记拦一道。
     */
    public record Vendor(String name, String tier, boolean vision) {
        /** 语义化的访问器，调用处读起来比 vision() 更清楚。 */
        public boolean supportsVision() {
            return vision;
        }
    }

    /** 查不到时的兜底值。故意声明在最前面，避免任何静态初始化顺序问题。 */
    public static final Vendor UNKNOWN = new Vendor("未知厂商", "未知等级", true);

    private static final Map<String, Vendor> MODELS = new LinkedHashMap<>();

    private ModelCatalog() {}

    /** 默认认为支持图片：没实测过的模型一律不拦，避免误伤。 */
    private static void put(String id, String vendor, String tier) {
        put(id, vendor, tier, true);
    }

    private static void put(String id, String vendor, String tier, boolean vision) {
        MODELS.put(id, new Vendor(vendor, tier, vision));
    }

    static {
        // ---- DeepSeek 深度求索 ----
        // vision 标记为实测结论：flash 能读图；v4-pro 与旧文本模型读不了图。
        put("deepseek-flash", "深度求索", "Low", true);
        put("deepseek-v4-flash", "深度求索", "Low", true);
        put("deepseek-v4-pro", "深度求索", "High", false);
        put("deepseek-chat", "深度求索", "Mid", false);
        put("deepseek-reasoner", "深度求索", "High", false);
        put("deepseek-v3", "深度求索", "Mid", false);

        // ---- OpenAI ----
        put("gpt-4o-mini", "OpenAI", "Low");
        put("gpt-4o", "OpenAI", "High");
        put("gpt-4.1-nano", "OpenAI", "Low");
        put("gpt-4.1-mini", "OpenAI", "Low");
        put("gpt-4.1", "OpenAI", "High");
        put("o1-mini", "OpenAI", "Mid");
        put("o1", "OpenAI", "High");
        put("o3-mini", "OpenAI", "Mid");

        // ---- Anthropic ----
        put("claude-3-5-haiku", "Anthropic", "Low");
        put("claude-3-5-sonnet", "Anthropic", "High");
        put("claude-3-opus", "Anthropic", "High");
        put("claude-sonnet-4", "Anthropic", "High");
        put("claude-opus-4", "Anthropic", "High");

        // ---- Google ----
        put("gemini-1.5-flash", "Google", "Low");
        put("gemini-1.5-pro", "Google", "High");
        put("gemini-2.0-flash", "Google", "Low");
        put("gemini-2.5-pro", "Google", "High");

        // ---- 国内厂商 ----
        put("qwen-turbo", "阿里通义", "Low");
        put("qwen-plus", "阿里通义", "Mid");
        put("qwen-max", "阿里通义", "High");
        put("glm-4-flash", "智谱AI", "Low");
        put("glm-4", "智谱AI", "Mid");
        put("glm-4-plus", "智谱AI", "High");
        put("moonshot-v1-8k", "月之暗面", "Low");
        put("moonshot-v1-32k", "月之暗面", "Mid");
        put("moonshot-v1-128k", "月之暗面", "High");
        put("ernie-speed", "百度文心", "Low");
        put("ernie-4.0", "百度文心", "High");
        put("hunyuan-lite", "腾讯混元", "Low");
        put("hunyuan-standard", "腾讯混元", "Mid");
    }

    /** 精确查找（忽略大小写与首尾空格）；查不到返回 {@link #UNKNOWN}。 */
    public static Vendor lookup(String modelId) {
        if (modelId == null) {
            return UNKNOWN;
        }
        Vendor vendor = MODELS.get(modelId.trim().toLowerCase(Locale.ROOT));
        return vendor != null ? vendor : UNKNOWN;
    }

    /** 渲染成 {@code <原英文模型ID> (<中文厂商>, <等级>)}。 */
    public static String describe(String modelId) {
        String id = (modelId == null || modelId.isBlank()) ? "未知" : modelId.trim();
        Vendor vendor = lookup(modelId);
        return id + " (" + vendor.name() + ", " + vendor.tier() + ")";
    }
}
