package com.example.mcai.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 API Key 轮换与降级（#13）。
 *
 * <p>把主 key + 备用 key 排成一个环,每次调用取"游标指到的那一个";若该 key 返回
 * 401/402/429,被临时记进黑名单移出循环,游标前进,让下一个 key 顶上。
 *
 * <p>本地服务不需要 key(由 {@link LocalProvider} 处理),本类只涉及云端 key;
 * 调用方应先问过 {@code LocalProvider.isLocal(apiUrl)} 再决定要不要用这里。
 */
public final class ApiKeyManager {

    private ApiKeyManager() {}

    private static final AtomicInteger cursor = new AtomicInteger(0);
    private static final Set<String> dead = new HashSet<>();
    /** 最近一次 pick 出去的 key,recordResult 时靠它拉黑对应那一个。 */
    private static volatile String lastPicked = null;

    public static void reset() {
        dead.clear();
        cursor.set(0);
        lastPicked = null;
    }

    private static List<String> ring(String primaryKey, List<String> backups) {
        List<String> out = new ArrayList<>();
        if (primaryKey != null && !primaryKey.isBlank()) {
            out.add(primaryKey.trim());
        }
        if (backups != null) {
            for (String k : backups) {
                if (k != null) out.add(k.trim());
            }
        }
        out.removeIf(k -> k.isEmpty() || k.equals(LocalProvider.DUMMY_KEY));
        out.removeIf(dead::contains);
        if (out.isEmpty() && primaryKey != null && !primaryKey.isBlank()) {
            // 全部被拉黑:主 key 兜底,让它失败好把错误原样给用户
            out.add(primaryKey.trim());
        }
        return out;
    }

    /** 取当前该用的 key(thread-safe)。将记录为"最近一次命中的 key"。 */
    public static String pick(String primaryKey, List<String> backups) {
        List<String> ring = ring(primaryKey, backups);
        String chosen;
        if (ring.isEmpty()) {
            chosen = LocalProvider.DUMMY_KEY;
        } else {
            int i = (cursor.getAndIncrement() & Integer.MAX_VALUE) % ring.size();
            chosen = ring.get(i);
        }
        lastPicked = chosen;
        return chosen;
    }

    /**
     * 上报调用结果。
     *
     * @param statusCode HTTP 状态码;0 表示网络层异常(不适合拉黑 key,只前进)。
     */
    public static void recordResult(int statusCode) {
        if (statusCode == 401 || statusCode == 402 || statusCode == 429) {
            String used = lastPicked;
            if (used != null && !used.equals(LocalProvider.DUMMY_KEY)) {
                dead.add(used);
            }
            cursor.incrementAndGet();
        }
    }

    /** 是否有健康备用 key 可用(供界面提示"已切到备用 key")。 */
    public static boolean hasAnyHealthy(String primaryKey, List<String> backups) {
        return !ring(primaryKey, backups).isEmpty();
    }
}