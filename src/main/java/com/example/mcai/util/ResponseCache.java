package com.example.mcai.util;

import com.example.mcai.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 相同问题的本地回答缓存（1.24 新增）—— 同一个问题问第二遍不再发请求，零 token。
 *
 * <p><b>只存在内存里，不落盘。</b>重启游戏即失效。这样做的原因有两个：
 * 一是问句里可能含玩家自己写的私密内容，不该被持久化；二是避免再加一套独立的文件 I/O
 * 和损坏恢复逻辑。
 *
 * <p><b>缓存键</b>由「归一化后的问题 + 模型 + 人格 + 长期记忆」组成。注意多轮上下文
 * （{@link HistoryStore}）<b>不参与键</b>——也就是说开了上下文之后，同一个问题在不同
 * 对话脉络下会命中同一条回答。这是刻意的取舍：把上下文也塞进键会让命中率接近于零，
 * 缓存就失去意义了。
 *
 * <p>用访问序 LRU（{@link LinkedHashMap} 的 accessOrder 模式）维护容量上限，
 * 所有操作都加了同步，允许从任意线程调用（请求回调在后台线程）。
 */
public final class ResponseCache {

    private ResponseCache() {}

    private static final Map<String, String> CACHE = new LinkedHashMap<>(32, 0.75f, true);

    /** 是否启用缓存（配置里关掉、或容量为 0 时一律不缓存）。 */
    public static boolean enabled() {
        ConfigManager.ConfigData c = ConfigManager.getInstance().get();
        return c != null && c.responseCache && c.responseCacheSize > 0;
    }

    private static int capacity() {
        ConfigManager.ConfigData c = ConfigManager.getInstance().get();
        int n = c == null ? 64 : c.responseCacheSize;
        return Math.max(0, n);
    }

    /** 命中就返回上次的回答；未命中返回 null。 */
    public static String lookup(String question, String model, String persona) {
        if (!enabled()) {
            return null;
        }
        String key = key(question, model, persona);
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    /** 记录一条问答。空回答不记（错误路径产生的占位文案不该被缓存）。 */
    public static void store(String question, String model, String persona, String reply) {
        if (!enabled() || reply == null || reply.isBlank()) {
            return;
        }
        int cap = capacity();
        if (cap <= 0) {
            return;
        }
        String key = key(question, model, persona);
        synchronized (CACHE) {
            CACHE.put(key, reply);
            // LinkedHashMap 不会自己淘汰，手动按访问序丢最旧的一条
            while (CACHE.size() > cap) {
                java.util.Iterator<String> it = CACHE.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
    }

    /** 当前缓存条数（{@code /ai status} 展示用）。 */
    public static int size() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    /** 清空缓存（{@code /ai cache clear}）。 */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static String key(String question, String model, String persona) {
        return normalize(question) + "\u0000" + nz(model) + "\u0000" + nz(persona)
                + "\u0000" + memoriesFingerprint();
    }

    /** 归一化：去掉首尾空白、把连续空白压成一个空格、统一小写，让「怎么合成钻石镐？」和「怎么合成钻石镐? 」算同一条。 */
    private static String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 长期记忆会进系统提示词，所以它一变、缓存就必须失效——用它的内容做一个指纹。 */
    private static String memoriesFingerprint() {
        ConfigManager.ConfigData c = ConfigManager.getInstance().get();
        if (c == null || c.memories == null || c.memories.isEmpty()) {
            return "-";
        }
        return Integer.toHexString(String.join("\u0001", c.memories).hashCode());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
