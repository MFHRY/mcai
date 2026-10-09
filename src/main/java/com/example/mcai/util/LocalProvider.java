package com.example.mcai.util;

/**
 * 本地推理服务识别：判断接口地址是不是跑在本机上的 OpenAI 兼容服务
 * （Ollama 的 {@code /v1} 端点、LM Studio、llama.cpp 的 server 等）。
 *
 * <p><b>为什么要单独做一个类：</b>本地服务通常<b>不需要鉴权</b>——Ollama 和 LM Studio
 * 都接受任何 {@code Authorization} 头（甚至完全不带）。但原版代码把「apiKey 为空」
 * 当成硬错误拦下来，玩家填了 {@code http://localhost:11434/v1} 也会被拒绝，
 * 看起来就像"这个 mod 必须花钱办 key"。
 *
 * <p>所以这里的职责是<b>回答一个二元问题</b>：当前配置需不需要 API Key？
 * 聊天链路和截图识别链路各有一个校验点，都问同一个方法，避免两处规则不一致。
 *
 * <p><b>判定范围故意画得很窄</b>：只认 {@code localhost} / {@code 127.0.0.1} /
 * {@code [::1]}，并且必须真的在 loopback 接口上（192.168.x.x 这类局域网地址不算本地，
 * 因为它同样可能把截图往外发）。宁可漏判（玩家多填一次 key）也不要误判（把画面发到
 * 不认识的机器）。
 *
 * <p>判定只看 URL 的 host，不看路径，所以 {@code /v1}、{@code /v1/chat/completions}
 * 以及玩家顺手写歪的地址都不会漏。
 */
public final class LocalProvider {

    /** 本机没有 key 时临时塞进请求头的占位值。本地服务不会校验它。 */
    public static final String DUMMY_KEY = "local";

    private LocalProvider() {}

    /** 接口地址是否指向本机上的推理服务。null / 空白一律按「不是」处理。 */
    public static boolean isLocal(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        String base = apiUrl.trim();
        int schemeEnd = base.indexOf("://");
        if (schemeEnd <= 0) {
            return false;
        }
        // 只认 http：ws:// 之类的不可能出现，也不该被当成本地推理服务
        String scheme = base.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http")) {
            return false;
        }

        String rest = base.substring(schemeEnd + 3);
        int pathStart = rest.indexOf('/');
        if (pathStart >= 0) {
            rest = rest.substring(0, pathStart);
        }
        if (rest.endsWith(":")) {
            rest = rest.substring(0, rest.length() - 1);
        }

        String host;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close <= 0) {
                return false;
            }
            host = rest.substring(1, close);
        } else {
            int colon = rest.indexOf(':');
            host = colon >= 0 ? rest.substring(0, colon) : rest;
        }
        return isLoopbackHost(host);
    }

    /** host 是否为本机回环地址。 */
    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1");
    }

    /**
     * 当前配置是否需要用户提供 API Key。
     * 本地服务不需要；其他所有情况（包括空白地址）一律需要，交给原有的错误提示处理。
     */
    public static boolean requiresApiKey(String apiUrl, String apiKey) {
        if (isLocal(apiUrl)) {
            return false;
        }
        return apiKey == null || apiKey.isBlank();
    }

    /**
     * 要放进 {@code Authorization: Bearer <...>} 的 key 值。
     * 本地服务拿不到 key 时用 {@link #DUMMY_KEY} 顶一下，
     * 避免有些服务在完全没有鉴权头时行为不一致。
     */
    public static String effectiveApiKey(String apiUrl, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return DUMMY_KEY;
    }
}
