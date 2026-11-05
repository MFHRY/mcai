package com.example.mcai.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Locale;

/**
 * 各家 API 的协议差异全部收在这一个枚举里。
 *
 * <p><b>核心设计：归一化。</b>Gemini 和 Claude 的请求体、响应结构、token 字段名都跟
 * OpenAI 不一样。与其让 {@link com.example.mcai.client.ChatHandler} 和
 * {@code VisionHandler} 里到处 {@code if (gemini) ... else if (claude) ...}，
 * 不如在本类里把它们<b>翻译成 OpenAI 形状</b>：
 *
 * <pre>
 * {"choices":[{"message":{"content":"...","reasoning_content":"..."}}],
 *  "usage":{"prompt_tokens":P,"completion_tokens":C,"total_tokens":T}}
 * </pre>
 *
 * <p>这样一来 {@link AiResponseParser}、{@link TokenStats}、
 * {@code ClientChat.sendReasoning} 的既有逻辑<b>一行都不用改</b>就能支持三家，
 * 也就不会出现"加了新厂商之后 token 统计悄悄失效"这种问题。
 *
 * <p>请求方向同理：调用方只准备 {@link ApiRequest}（厂商无关），
 * 由本类翻译成各家的 JSON。
 */
public enum ApiProvider {

    /** OpenAI 兼容（DeepSeek、Ollama、LM Studio、以及绝大多数中转站都走这条）。 */
    OPENAI,

    /** Google Gemini（{@code generativelanguage.googleapis.com}）。 */
    GEMINI,

    /** Anthropic Claude（{@code api.anthropic.com}）。 */
    CLAUDE;

    /** Claude 的 {@code max_tokens} 是<b>必填</b>，没有默认值，不给就 400。 */
    private static final int CLAUDE_MAX_TOKENS = 4096;
    private static final String CLAUDE_VERSION = "2023-06-01";

    // ------------------------------------------------------------ 厂商判定

    /**
     * 决定用哪套协议。
     *
     * @param configured 配置里的 {@code api_provider}；{@code auto} 或缺省表示按 URL 猜
     * @param apiUrl     配置里的 {@code api_url}
     */
    public static ApiProvider detect(String configured, String apiUrl) {
        if (configured != null && !configured.isBlank()) {
            String value = configured.trim().toLowerCase(Locale.ROOT);
            if (!value.equals("auto")) {
                for (ApiProvider provider : values()) {
                    if (provider.name().toLowerCase(Locale.ROOT).equals(value)) {
                        return provider;
                    }
                }
            }
        }
        String url = apiUrl == null ? "" : apiUrl.toLowerCase(Locale.ROOT);
        if (url.contains("googleapis.com")) {
            return GEMINI;
        }
        if (url.contains("anthropic.com") || url.contains("/v1/messages")) {
            return CLAUDE;
        }
        return OPENAI;
    }

    // ------------------------------------------------------------ 端点

    /** 拼出这次调用真正要 POST 的地址。 */
    public String endpoint(String baseUrl, String model, boolean stream) {
        String base = stripTrailingSlash(baseUrl);
        String id = (model == null || model.isBlank()) ? "" : model.trim();

        switch (this) {
            case GEMINI -> {
                // Gemini 把模型名写在路径里，而且 base 可能已经被填成 .../v1beta
                if (base.contains("/v1beta")) {
                    base = base.substring(0, base.indexOf("/v1beta"));
                } else if (base.contains("/v1")) {
                    base = base.substring(0, base.indexOf("/v1"));
                }
                String method = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
                return base + "/v1beta/models/" + id + method;
            }
            case CLAUDE -> {
                if (base.endsWith("/v1/messages")) {
                    return base;
                }
                if (base.endsWith("/v1")) {
                    return base + "/messages";
                }
                return base + "/v1/messages";
            }
            default -> {
                if (base.endsWith("/chat/completions")) {
                    return base;
                }
                return base + "/chat/completions";
            }
        }
    }

    private static String stripTrailingSlash(String url) {
        String base = url == null ? "" : url.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // ------------------------------------------------------------ 鉴权

    /**
     * 按厂商的规矩挂上鉴权头。
     *
     * <p>Gemini 和 Claude <b>都不吃</b> {@code Authorization: Bearer}；Gemini 用
     * {@code x-goog-api-key}，Claude 用 {@code x-api-key} 外加一个必需的版本头。
     * 三家的头都写在这里，调用方不需要知道。
     */
    public void applyAuth(HttpRequest.Builder builder, String apiKey) {
        String key = apiKey == null ? "" : apiKey;
        switch (this) {
            case GEMINI -> builder.header("x-goog-api-key", key);
            case CLAUDE -> {
                builder.header("x-api-key", key);
                builder.header("anthropic-version", CLAUDE_VERSION);
            }
            default -> builder.header("Authorization", "Bearer " + key);
        }
    }

    /** 本地推理服务（Ollama 等）不需要鉴权头时可以完全跳过。 */
    public boolean needsAuthHeader() {
        return true;
    }

    // ------------------------------------------------------------ 请求体

    /** 把厂商无关的 {@link ApiRequest} 翻译成这一家的 JSON。 */
    public String buildBody(ApiRequest request) {
        JsonObject root = switch (this) {
            case GEMINI -> geminiBody(request);
            case CLAUDE -> claudeBody(request);
            default -> openAiBody(request);
        };
        return root.toString();
    }

    private static JsonObject openAiBody(ApiRequest request) {
        JsonArray messages = new JsonArray();

        if (notBlank(request.system())) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", request.system());
            messages.add(system);
        }
        appendOpenAiHistory(messages, request.history());

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        List<String> images = request.imagesBase64();
        if (images == null || images.isEmpty()) {
            user.addProperty("content", nullToEmpty(request.userText()));
        } else {
            JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", nullToEmpty(request.userText()));
            parts.add(text);
            for (String base64 : images) {
                JsonObject part = new JsonObject();
                part.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:" + request.mime() + ";base64," + base64);
                part.add("image_url", url);
                parts.add(part);
            }
            user.add("content", parts);
        }
        messages.add(user);

        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        root.add("messages", messages);
        root.addProperty("stream", request.stream());
        if (request.stream()) {
            // 开了流式就必须显式要 usage，否则响应里根本没有 token 统计
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }
        return root;
    }

    private static void appendOpenAiHistory(JsonArray messages, List<String[]> history) {
        if (history == null) {
            return;
        }
        for (String[] turn : history) {
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", turn[0]);
            messages.add(user);
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.addProperty("content", turn[1]);
            messages.add(assistant);
        }
    }

    private static JsonObject geminiBody(ApiRequest request) {
        JsonObject root = new JsonObject();

        if (notBlank(request.system())) {
            JsonObject instruction = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("text", request.system());
            parts.add(text);
            instruction.add("parts", parts);
            root.add("systemInstruction", instruction);
        }

        JsonArray contents = new JsonArray();
        if (request.history() != null) {
            for (String[] turn : request.history()) {
                // 注意：Gemini 里助手这一侧的角色名是 "model"，不是 "assistant"
                contents.add(geminiContent("user", turn[0], null, request.mime()));
                contents.add(geminiContent("model", turn[1], null, request.mime()));
            }
        }
        contents.add(geminiContent("user", request.userText(), request.imagesBase64(), request.mime()));
        root.add("contents", contents);
        // 流式与非流式的区别只体现在 URL 上（:streamGenerateContent），请求体不带 stream 字段
        return root;
    }

    private static JsonObject geminiContent(String role, String text, List<String> images, String mimeType) {
        JsonObject content = new JsonObject();
        content.addProperty("role", role);
        JsonArray parts = new JsonArray();
        if (text != null && !text.isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("text", text);
            parts.add(part);
        }
        if (images != null) {
            for (String base64 : images) {
                JsonObject part = new JsonObject();
                JsonObject inline = new JsonObject();
                inline.addProperty("mime_type", mimeType);
                inline.addProperty("data", base64);
                part.add("inline_data", inline);
                parts.add(part);
            }
        }
        content.add("parts", parts);
        return content;
    }

    private static JsonObject claudeBody(ApiRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());
        root.addProperty("max_tokens", CLAUDE_MAX_TOKENS);
        if (notBlank(request.system())) {
            root.addProperty("system", request.system());
        }

        JsonArray messages = new JsonArray();
        if (request.history() != null) {
            for (String[] turn : request.history()) {
                messages.add(claudeMessage("user", turn[0], null, request.mime()));
                messages.add(claudeMessage("assistant", turn[1], null, request.mime()));
            }
        }
        messages.add(claudeMessage("user", request.userText(), request.imagesBase64(), request.mime()));
        root.add("messages", messages);
        if (request.stream()) {
            root.addProperty("stream", true);
        }
        return root;
    }

    private static JsonObject claudeMessage(String role, String text, List<String> images, String mimeType) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        JsonArray content = new JsonArray();
        if (text != null && !text.isEmpty()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            content.add(block);
        }
        if (images != null) {
            for (String base64 : images) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "image");
                JsonObject source = new JsonObject();
                source.addProperty("type", "base64");
                source.addProperty("media_type", mimeType);
                source.addProperty("data", base64);
                block.add("source", source);
                content.add(block);
            }
        }
        message.add("content", content);
        return message;
    }

    // ------------------------------------------------------------ 响应归一化

    /**
     * 把一次<b>非流式</b>响应翻译成 OpenAI 形状。
     *
     * <p>出错时原样返回：三家的错误体都带 {@code error.message}
     * （Gemini 和 Claude 恰好也是这个结构），交给 {@link AiResponseParser#extractContent}
     * 统一取出来显示。
     */
    public JsonObject normalizeResponse(JsonObject raw) {
        if (raw == null) {
            return null;
        }
        if (raw.has("error")) {
            return raw;
        }
        return switch (this) {
            case GEMINI -> normalizeGemini(raw);
            case CLAUDE -> normalizeClaude(raw);
            default -> raw;
        };
    }

    private static JsonObject normalizeGemini(JsonObject raw) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        collectGeminiParts(raw, content, reasoning);

        // 被安全策略拦下时 candidates 是空的，这在 Gemini 上很常见，要说清楚而不是显示空回答
        if (content.length() == 0 && raw.has("promptFeedback")) {
            JsonObject feedback = raw.getAsJsonObject("promptFeedback");
            String block = optString(feedback, "blockReason");
            if (block != null) {
                content.append("[Gemini] ").append(block);
            }
        }

        int prompt = usageInt(raw, "promptTokenCount");
        int completion = usageInt(raw, "candidatesTokenCount");
        return openAiShape(content.toString(), reasoning.length() == 0 ? null : reasoning.toString(),
                prompt, completion);
    }

    private static void collectGeminiParts(JsonObject raw, StringBuilder content, StringBuilder reasoning) {
        JsonObject candidate = firstElement(raw, "candidates");
        if (candidate == null) {
            return;
        }
        JsonObject contentObject = candidate.has("content") && candidate.get("content").isJsonObject()
                ? candidate.getAsJsonObject("content") : null;
        if (contentObject == null || !contentObject.has("parts")
                || !contentObject.get("parts").isJsonArray()) {
            return;
        }
        for (JsonElement element : contentObject.getAsJsonArray("parts")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            String text = optString(part, "text");
            if (text == null) {
                continue;
            }
            // Gemini 2.5 把思考过程也放在 parts 里，用 thought:true 标记
            if (part.has("thought") && part.get("thought").isJsonPrimitive()
                    && part.get("thought").getAsBoolean()) {
                reasoning.append(text);
            } else {
                content.append(text);
            }
        }
    }

    private static JsonObject normalizeClaude(JsonObject raw) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();

        if (raw.has("content") && raw.get("content").isJsonArray()) {
            for (JsonElement element : raw.getAsJsonArray("content")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                String type = optString(block, "type");
                if ("text".equals(type)) {
                    String text = optString(block, "text");
                    if (text != null) {
                        content.append(text);
                    }
                } else if ("thinking".equals(type)) {
                    String thinking = optString(block, "thinking");
                    if (thinking != null) {
                        reasoning.append(thinking);
                    }
                }
            }
        }

        int prompt = nestedInt(raw, "usage", "input_tokens");
        int completion = nestedInt(raw, "usage", "output_tokens");
        return openAiShape(content.toString(), reasoning.length() == 0 ? null : reasoning.toString(),
                prompt, completion);
    }

    /**
     * 把<b>流式</b>的一个分片翻译成 OpenAI 形状的 {@code {choices:[{delta:{...}}], usage:{...}}}。
     */
    public JsonObject normalizeDelta(JsonObject chunk) {
        if (chunk == null) {
            return null;
        }
        return switch (this) {
            case GEMINI -> normalizeGeminiDelta(chunk);
            case CLAUDE -> normalizeClaudeDelta(chunk);
            default -> chunk;
        };
    }

    private static JsonObject normalizeGeminiDelta(JsonObject chunk) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        collectGeminiParts(chunk, content, reasoning);
        return deltaShape(content.toString(), reasoning.length() == 0 ? null : reasoning.toString(),
                chunk.has("usageMetadata") ? usageObject(chunk) : null);
    }

    private static JsonObject normalizeClaudeDelta(JsonObject chunk) {
        String type = optString(chunk, "type");
        String content = null;
        String reasoning = null;
        JsonObject usage = null;

        if ("content_block_delta".equals(type) && chunk.has("delta") && chunk.get("delta").isJsonObject()) {
            JsonObject delta = chunk.getAsJsonObject("delta");
            String deltaType = optString(delta, "type");
            if ("thinking_delta".equals(deltaType)) {
                reasoning = optString(delta, "thinking");
            } else {
                content = optString(delta, "text");
            }
        } else if ("message_start".equals(type) && chunk.has("message")
                && chunk.get("message").isJsonObject()) {
            usage = usageObject(chunk.getAsJsonObject("message"));
        } else if ("message_delta".equals(type)) {
            usage = usageObject(chunk);
        }
        return deltaShape(content, reasoning, usage);
    }

    // ------------------------------------------------------------ 构造工具

    /** 造一个 OpenAI 形状的完整响应。token 数为负表示"这一家没给这个数"，字段直接省略。 */
    private static JsonObject openAiShape(String content, String reasoning, int prompt, int completion) {
        JsonObject root = new JsonObject();
        JsonArray choices = new JsonArray();
        JsonObject choice = new JsonObject();
        JsonObject message = new JsonObject();
        message.addProperty("content", content == null ? "" : content);
        if (reasoning != null && !reasoning.isEmpty()) {
            message.addProperty("reasoning_content", reasoning);
        }
        choice.add("message", message);
        choices.add(choice);
        root.add("choices", choices);

        JsonObject usage = new JsonObject();
        if (prompt >= 0) {
            usage.addProperty("prompt_tokens", prompt);
        }
        if (completion >= 0) {
            usage.addProperty("completion_tokens", completion);
        }
        if (prompt >= 0 || completion >= 0) {
            usage.addProperty("total_tokens", Math.max(0, prompt) + Math.max(0, completion));
        }
        if (usage.size() != 0) {
            root.add("usage", usage);
        }
        return root;
    }

    private static JsonObject deltaShape(String content, String reasoning, JsonObject usage) {
        JsonObject root = new JsonObject();
        JsonObject delta = new JsonObject();
        if (content != null && !content.isEmpty()) {
            delta.addProperty("content", content);
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            delta.addProperty("reasoning_content", reasoning);
        }
        if (delta.size() != 0) {
            JsonArray choices = new JsonArray();
            JsonObject choice = new JsonObject();
            choice.add("delta", delta);
            choices.add(choice);
            root.add("choices", choices);
        }
        if (usage != null && usage.size() != 0) {
            root.add("usage", usage);
        }
        return root;
    }

    /**
     * 把这一家的 usage 字段重新命名成 OpenAI 的名字，好让 {@link TokenStats} 直接复用。
     *
     * <p>兼容 Gemini 的 {@code usageMetadata.{promptTokenCount,candidatesTokenCount,totalTokenCount}}
     * 和 Claude 的 {@code usage.{input_tokens,output_tokens}}。
     */
    private static JsonObject usageObject(JsonObject raw) {
        if (raw == null) {
            return null;
        }
        JsonObject source = null;
        if (raw.has("usageMetadata") && raw.get("usageMetadata").isJsonObject()) {
            source = raw.getAsJsonObject("usageMetadata");
        } else if (raw.has("usage") && raw.get("usage").isJsonObject()) {
            source = raw.getAsJsonObject("usage");
        }
        if (source == null) {
            return null;
        }
        int prompt = pickInt(source, "promptTokenCount", "input_tokens");
        int completion = pickInt(source, "candidatesTokenCount", "output_tokens");
        int total = pickInt(source, "totalTokenCount", "total_tokens");
        JsonObject usage = new JsonObject();
        if (prompt >= 0) {
            usage.addProperty("prompt_tokens", prompt);
        }
        if (completion >= 0) {
            usage.addProperty("completion_tokens", completion);
        }
        if (total >= 0 || prompt >= 0 || completion >= 0) {
            usage.addProperty("total_tokens",
                    total >= 0 ? total : Math.max(0, prompt) + Math.max(0, completion));
        }
        return usage.size() == 0 ? null : usage;
    }

    private static int pickInt(JsonObject object, String first, String second) {
        int value = nestedInt(object, null, first);
        return value >= 0 ? value : nestedInt(object, null, second);
    }

    /** 读取嵌套整数；容器不存在、字段不存在或类型不对都返回 -1。 */
    private static int nestedInt(JsonObject root, String container, String field) {
        if (root == null) {
            return -1;
        }
        JsonObject target = root;
        if (container != null) {
            if (!root.has(container) || !root.get(container).isJsonObject()) {
                return -1;
            }
            target = root.getAsJsonObject(container);
        }
        if (!target.has(field) || target.get(field).isJsonNull()) {
            return -1;
        }
        try {
            return target.get(field).getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int usageInt(JsonObject root, String field) {
        return nestedInt(root, "usageMetadata", field);
    }

    private static JsonObject firstElement(JsonObject root, String arrayField) {
        if (root == null || !root.has(arrayField) || !root.get(arrayField).isJsonArray()) {
            return null;
        }
        JsonArray array = root.getAsJsonArray(arrayField);
        if (array.isEmpty() || !array.get(0).isJsonObject()) {
            return null;
        }
        return array.get(0).getAsJsonObject();
    }

    private static String optString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(field);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
