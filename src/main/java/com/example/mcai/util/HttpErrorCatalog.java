package com.example.mcai.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 状态码 → 人话解释。
 *
 * <p>从 ChatHandler 里抽出来的公共字典，这样聊天和截图识别两条链路用的是同一份文案，
 * 不会出现「同一个 401 在两处显示不一样」的情况。
 *
 * <p>文案全部走语言文件（{@code mcai.http.*}），中文环境显示中文、英文环境显示英文。
 */
public final class HttpErrorCatalog {

    private static final Map<Integer, String> KEYS;

    static {
        Map<Integer, String> map = new HashMap<>();
        map.put(400, "mcai.http.400");
        map.put(401, "mcai.http.401");
        map.put(402, "mcai.http.402");
        map.put(403, "mcai.http.403");
        map.put(404, "mcai.http.404");
        map.put(405, "mcai.http.405");
        map.put(408, "mcai.http.408");
        map.put(413, "mcai.http.413");
        map.put(415, "mcai.http.415");
        map.put(422, "mcai.http.422");
        map.put(429, "mcai.http.429");
        map.put(500, "mcai.http.500");
        map.put(501, "mcai.http.501");
        map.put(502, "mcai.http.502");
        map.put(503, "mcai.http.503");
        map.put(504, "mcai.http.504");
        KEYS = Collections.unmodifiableMap(map);
    }

    private HttpErrorCatalog() {}

    /** 已知状态码返回精确文案，否则按 4xx / 5xx 归类。 */
    public static String describe(int statusCode) {
        String known = KEYS.get(statusCode);
        if (known != null) {
            return Lang.tr(known);
        }
        if (statusCode >= 500) {
            return Lang.tr("mcai.http.server_error");
        }
        if (statusCode >= 400) {
            return Lang.tr("mcai.http.client_error");
        }
        return Lang.tr("mcai.http.unknown_error");
    }
}
