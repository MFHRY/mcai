package com.example.mcai.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 状态码 → 中文解释。
 *
 * <p>从 ChatHandler 里抽出来的公共字典，这样聊天和截图识别两条链路用的是同一份文案，
 * 不会出现「同一个 401 在两处显示不同中文」的情况。
 */
public final class HttpErrorCatalog {

    private static final Map<Integer, String> MESSAGES;

    static {
        Map<Integer, String> map = new HashMap<>();
        map.put(400, "请求参数错误");
        map.put(401, "密钥无效");
        map.put(402, "余额不足");
        map.put(403, "访问被拒绝");
        map.put(404, "接口地址不存在");
        map.put(405, "请求方法不被允许");
        map.put(408, "请求超时");
        map.put(413, "请求内容过大");
        map.put(415, "不支持的媒体类型");
        map.put(422, "请求内容无法处理");
        map.put(429, "请求过于频繁");
        map.put(500, "服务器错误");
        map.put(501, "服务器不支持该功能");
        map.put(502, "网关错误");
        map.put(503, "服务暂时不可用");
        map.put(504, "网关超时");
        MESSAGES = Collections.unmodifiableMap(map);
    }

    private HttpErrorCatalog() {}

    /** 已知状态码返回精确文案，否则按 4xx / 5xx 归类。 */
    public static String describe(int statusCode) {
        String known = MESSAGES.get(statusCode);
        if (known != null) {
            return known;
        }
        if (statusCode >= 500) {
            return "服务器错误";
        }
        if (statusCode >= 400) {
            return "请求错误";
        }
        return "未知错误";
    }
}
