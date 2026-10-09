package com.example.mcai.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI 兼容响应的公共解析器。
 *
 * <p>聊天（纯文本）和截图识别（多模态）返回的是同一套结构，所以解析逻辑只留一份，
 * 避免两条链路对同一个接口的解析逐渐跑偏。
 */
public final class AiResponseParser {

    private AiResponseParser() {}

    /** 把响应体解析成 JsonObject；不是合法 JSON 对象则返回 null。 */
    public static JsonObject parseObject(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(responseBody);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取出 {@code choices[0].message.content}。
     *
     * <p>兼容三种形态：普通字符串、内容分片数组，以及接口直接用 {@code error.message} 报错的情况。
     * 取不到返回 null。
     */
    public static String extractContent(JsonObject root) {
        if (root == null) {
            return null;
        }

        if (!root.has("choices") || !root.get("choices").isJsonArray()
                || root.getAsJsonArray("choices").isEmpty()) {
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message") && !error.get("message").isJsonNull()) {
                    return error.get("message").getAsString();
                }
            }
            return null;
        }

        JsonElement firstChoice = root.getAsJsonArray("choices").get(0);
        if (!firstChoice.isJsonObject()) {
            return null;
        }
        JsonObject choice = firstChoice.getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return null;
        }
        JsonObject message = choice.getAsJsonObject("message");
        if (!message.has("content") || message.get("content").isJsonNull()) {
            return null;
        }

        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject()) {
                    JsonObject partObject = part.getAsJsonObject();
                    if (partObject.has("text") && !partObject.get("text").isJsonNull()) {
                        builder.append(partObject.get("text").getAsString());
                    }
                } else if (part.isJsonPrimitive()) {
                    builder.append(part.getAsString());
                }
            }
            return builder.toString();
        }
        return null;
    }

    /** 取 {@code usage.total_tokens}；没有该字段返回 {@code -1}。 */
    public static int extractTotalTokens(JsonObject root) {
        return extractToken(root, "total_tokens");
    }

    /** 取 {@code usage.prompt_tokens}；没有返回 -1。用于估算输入费。 */
    public static int extractPromptTokens(JsonObject root) {
        return extractToken(root, "prompt_tokens");
    }

    /** 取 {@code usage.completion_tokens}；没有返回 -1。用于估算输出费。
     *  优先读 {@code completion_tokens}，其次回退到 {@code completion_tokens_details} 里的总和。 */
    public static int extractCompletionTokens(JsonObject root) {
        return extractToken(root, "completion_tokens");
    }

    private static int extractToken(JsonObject root, String field) {
        if (root == null || !root.has("usage") || !root.get("usage").isJsonObject()) {
            return -1;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        if (!usage.has(field) || usage.get(field).isJsonNull()) {
            return -1;
        }
        try {
            return usage.get(field).getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 取推理模型的思维链 {@code choices[0].message.reasoning_content}。
     *
     * <p>实测 DeepSeek 两个模型都会返回这个字段（同一问题 flash 约 4100 字、pro 约 6600 字），
     * 非推理模型则完全没有该字段，此时返回 null。
     * 另外兼容部分供应商用的 {@code reasoning} 字段名。
     */
    public static String extractReasoning(JsonObject root) {
        JsonObject message = firstMessage(root);
        if (message == null) {
            return null;
        }
        String value = readString(message, "reasoning_content");
        if (value == null) {
            value = readString(message, "reasoning");
        }
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * 取流式响应里这一小段新增的正文（#8）。
     *
     * <p>流式分片的形状是 {@code choices[0].delta.content}，跟非流式的
     * {@code message.content} 不同。首个分片和结尾的 usage 分片都可能没有 content，
     * 这时返回 null（调用方直接跳过即可）。
     */
    public static String extractDeltaContent(JsonObject root) {
        return deltaField(root, "content");
    }

    /** 取流式分片里的思维链增量（{@code delta.reasoning_content}）。 */
    public static String extractDeltaReasoning(JsonObject root) {
        String value = deltaField(root, "reasoning_content");
        return value != null ? value : deltaField(root, "reasoning");
    }

    private static String deltaField(JsonObject root, String field) {
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray()
                || root.getAsJsonArray("choices").isEmpty()) {
            return null;
        }
        JsonElement first = root.getAsJsonArray("choices").get(0);
        if (!first.isJsonObject()) {
            return null;
        }
        JsonObject choice = first.getAsJsonObject();
        if (!choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return null;
        }
        return readString(choice.getAsJsonObject("delta"), field);
    }

    private static JsonObject firstMessage(JsonObject root) {
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray()
                || root.getAsJsonArray("choices").isEmpty()) {
            return null;
        }
        JsonElement firstChoice = root.getAsJsonArray("choices").get(0);
        if (!firstChoice.isJsonObject()) {
            return null;
        }
        JsonObject choice = firstChoice.getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            return null;
        }
        return choice.getAsJsonObject("message");
    }

    private static String readString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
