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
        if (root == null || !root.has("usage") || !root.get("usage").isJsonObject()) {
            return -1;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        if (!usage.has("total_tokens") || usage.get("total_tokens").isJsonNull()) {
            return -1;
        }
        try {
            return usage.get("total_tokens").getAsInt();
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
