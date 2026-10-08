package com.example.mcai.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模型字典：把 API 返回的英文模型 ID 映射成「厂商 + 能力等级」。
 *
 * <p>用于聊天栏提示，格式严格为：
 * <pre>&lt;原英文模型ID&gt; (&lt;厂商&gt;, &lt;等级&gt;)</pre>
 * 例如中文环境下是 {@code deepseek-flash (深度求索, Low)}，
 * 英文环境下是 {@code deepseek-flash (DeepSeek, Low)}。
 *
 * <p>厂商名与等级都走语言文件（{@code mcai.vendor.*} / {@code mcai.tier.*}），
 * 所以同一个 jar 中英文都能正确显示。字典里查不到的模型显示
 * {@code <原ID> (未知厂商, 未知等级)}。
 *
 * <p>想支持新模型，只要在下面的 static 块里加一行 {@code put(...)} 即可。
 */
public final class ModelCatalog {

    /**
     * 单个模型的展示信息。
     *
     * @param vendorKey 厂商名的翻译键（例如 {@code mcai.vendor.deepseek}）
     * @param tierKey   能力等级的翻译键（例如 {@code mcai.tier.low}）
     * @param vision    是否支持图片输入。此字段是<b>实测</b>结论：
     *                  {@code deepseek-flash} 能真正读图（喂了一张写着 MCAI-7391 的图，
     *                  它准确读了出来）；而 {@code deepseek-v4-pro} <b>不支持图片输入</b>，
     *                  它不会报错，只会礼貌地回一句"我无法读取这张图片"——既浪费 token
     *                  又让人困惑，所以截图识别前会先用这个标记拦一道。
     */
    public record Vendor(String vendorKey, String tierKey, boolean vision) {
        /** 语义化的访问器，调用处读起来比 vision() 更清楚。 */
        public boolean supportsVision() {
            return vision;
        }
    }

    /** 查不到时的兜底值。故意声明在最前面，避免任何静态初始化顺序问题。 */
    public static final Vendor UNKNOWN = new Vendor("mcai.vendor.unknown", "mcai.tier.unknown", true);

    private static final Map<String, Vendor> MODELS = new LinkedHashMap<>();

    private ModelCatalog() {}

    /** 默认认为支持图片：没实测过的模型一律不拦，避免误伤。 */
    private static void put(String id, String vendorKey, String tierKey) {
        put(id, vendorKey, tierKey, true);
    }

    private static void put(String id, String vendorKey, String tierKey, boolean vision) {
        MODELS.put(id, new Vendor(vendorKey, tierKey, vision));
    }

    static {
        // ---- DeepSeek 深度求索 ----
        // vision 标记为实测结论：flash 能读图；v4-pro 与旧文本模型读不了图。
        put("deepseek-flash", "mcai.vendor.deepseek", "mcai.tier.low", true);
        put("deepseek-v4-flash", "mcai.vendor.deepseek", "mcai.tier.low", true);
        put("deepseek-v4-pro", "mcai.vendor.deepseek", "mcai.tier.high", false);
        put("deepseek-chat", "mcai.vendor.deepseek", "mcai.tier.mid", false);
        put("deepseek-reasoner", "mcai.vendor.deepseek", "mcai.tier.high", false);
        put("deepseek-v3", "mcai.vendor.deepseek", "mcai.tier.mid", false);

        // ---- OpenAI ----
        put("gpt-4o-mini", "mcai.vendor.openai", "mcai.tier.low");
        put("gpt-4o", "mcai.vendor.openai", "mcai.tier.high");
        put("gpt-4.1-nano", "mcai.vendor.openai", "mcai.tier.low");
        put("gpt-4.1-mini", "mcai.vendor.openai", "mcai.tier.low");
        put("gpt-4.1", "mcai.vendor.openai", "mcai.tier.high");
        put("o1-mini", "mcai.vendor.openai", "mcai.tier.mid");
        put("o1", "mcai.vendor.openai", "mcai.tier.high");
        put("o3-mini", "mcai.vendor.openai", "mcai.tier.mid");

        // ---- Anthropic ----
        put("claude-3-5-haiku", "mcai.vendor.anthropic", "mcai.tier.low");
        put("claude-3-5-sonnet", "mcai.vendor.anthropic", "mcai.tier.high");
        put("claude-3-opus", "mcai.vendor.anthropic", "mcai.tier.high");
        put("claude-sonnet-4", "mcai.vendor.anthropic", "mcai.tier.high");
        put("claude-opus-4", "mcai.vendor.anthropic", "mcai.tier.high");

        // ---- Google ----
        put("gemini-1.5-flash", "mcai.vendor.google", "mcai.tier.low");
        put("gemini-1.5-pro", "mcai.vendor.google", "mcai.tier.high");
        put("gemini-2.0-flash", "mcai.vendor.google", "mcai.tier.low");
        put("gemini-2.5-pro", "mcai.vendor.google", "mcai.tier.high");

        // ---- 国内厂商 ----
        put("qwen-turbo", "mcai.vendor.alibaba", "mcai.tier.low");
        put("qwen-plus", "mcai.vendor.alibaba", "mcai.tier.mid");
        put("qwen-max", "mcai.vendor.alibaba", "mcai.tier.high");
        put("glm-4-flash", "mcai.vendor.zhipu", "mcai.tier.low");
        put("glm-4", "mcai.vendor.zhipu", "mcai.tier.mid");
        put("glm-4-plus", "mcai.vendor.zhipu", "mcai.tier.high");
        put("moonshot-v1-8k", "mcai.vendor.moonshot", "mcai.tier.low");
        put("moonshot-v1-32k", "mcai.vendor.moonshot", "mcai.tier.mid");
        put("moonshot-v1-128k", "mcai.vendor.moonshot", "mcai.tier.high");
        put("ernie-speed", "mcai.vendor.baidu", "mcai.tier.low");
        put("ernie-4.0", "mcai.vendor.baidu", "mcai.tier.high");
        put("hunyuan-lite", "mcai.vendor.tencent", "mcai.tier.low");
        put("hunyuan-standard", "mcai.vendor.tencent", "mcai.tier.mid");
    }

    /** 精确查找（忽略大小写与首尾空格）；查不到返回 {@link #UNKNOWN}。 */
    public static Vendor lookup(String modelId) {
        if (modelId == null) {
            return UNKNOWN;
        }
        Vendor vendor = MODELS.get(modelId.trim().toLowerCase(Locale.ROOT));
        return vendor != null ? vendor : UNKNOWN;
    }

    /** 渲染成 {@code <原英文模型ID> (<厂商>, <等级>)}，厂商与等级按当前游戏语言显示。 */
    public static String describe(String modelId) {
        String id = (modelId == null || modelId.isBlank())
                ? Lang.tr("mcai.value.unknown")
                : modelId.trim();
        Vendor vendor = lookup(modelId);
        return id + " (" + Lang.tr(vendor.vendorKey()) + ", " + Lang.tr(vendor.tierKey()) + ")";
    }
}
