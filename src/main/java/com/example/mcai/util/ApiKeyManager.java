package com.example.mcai.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 API Key 轮换与降级（#13）。
 *
 * <p>把主 key + 备用 key 排成一个环,每次调用取"游标指到的那一个";若该 key 返回
 * 401/402/429,被临时记进黑名单移出循环,游标前进,让下一个 key 顶上。
 *
 * <p>本地服务不需要 key(由 {@link LocalProvider} 处理),本类只涉及云端 key;
 * 调用方应先问过 {@code LocalProvider.isLocal(apiUrl)} 再决定要不要用这里。
 *
 * <h2>为什么 {@link #pick} 返回一个句柄而不是 String</h2>
 * 早期版本把"最近一次取出去的 key"存在一个静态字段里,响应回来时靠它判断该拉黑谁。
 * 但聊天链路和截图链路是<b>并发</b>的:两条请求先后 pick,后一次会把前一次的记录覆盖掉,
 * 于是 A 请求收到 401 时,被拉黑的可能是正在正常工作的 B 的那把 key —— 越换越坏。
 * 现在改成 {@link PickedKey} 句柄随请求一起传递,归因永远精确,也顺带去掉了那个共享可变状态。
 */
public final class ApiKeyManager {

    private ApiKeyManager() {}

    private static final AtomicInteger cursor = new AtomicInteger(0);

    /** 已被判定失效的 key。两个链路并发读写,所以用并发集合。 */
    private static final Set<String> dead = ConcurrentHashMap.newKeySet();

    /**
     * 一次 {@link #pick} 的结果。
     *
     * @param key  放进 {@code Authorization: Bearer} 的值
     * @param slot 非 {@code null} 表示这把 key 归本类管理、失败时可以被拉黑；
     *             {@code null} 表示"不归本类管"（本地服务、或环为空时的兜底 key），
     *             这类请求的结果不会被用来拉黑任何 key
     */
    public record PickedKey(String key, Integer slot) {}

    /** 清空黑名单与游标（配置变化或玩家手动重置时用）。 */
    public static void reset() {
        dead.clear();
        cursor.set(0);
    }

    /**
     * 按当前配置重建 key 环（已拉黑的会被剔除）。
     *
     * <p>注意这个列表每次调用都会重新算一遍，所以它的<b>下标在两次调用之间并不稳定</b>
     * （剔除失效 key 后后面的元素会前移）。因此 {@link PickedKey#slot()} 只用来区分
     * "这把 key 归本类管吗"，真正的拉黑判定用的是 key 的值。
     */
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

    /** 取当前该用的 key(thread-safe)。返回值必须原样传给 {@link #recordResult}。 */
    public static PickedKey pick(String primaryKey, List<String> backups) {
        List<String> ring = ring(primaryKey, backups);
        if (ring.isEmpty()) {
            return new PickedKey(LocalProvider.DUMMY_KEY, null);
        }
        int i = (cursor.getAndIncrement() & Integer.MAX_VALUE) % ring.size();
        return new PickedKey(ring.get(i), i);
    }

    /**
     * 上报调用结果。
     *
     * @param picked     发起请求时 {@link #pick} 返回的那个句柄
     * @param statusCode HTTP 状态码;0 表示网络层异常(不适合拉黑 key)
     */
    public static void recordResult(PickedKey picked, int statusCode) {
        if (statusCode != 401 && statusCode != 402 && statusCode != 429) {
            return;
        }
        if (picked == null || picked.slot() == null) {
            // 本地服务 / 兜底 key：没有可拉黑的对象
            return;
        }
        String used = picked.key();
        if (used != null && !used.equals(LocalProvider.DUMMY_KEY)) {
            dead.add(used);
        }
        cursor.incrementAndGet();
    }

    /** 是否有健康备用 key 可用(供界面提示"已切到备用 key")。 */
    public static boolean hasAnyHealthy(String primaryKey, List<String> backups) {
        return !ring(primaryKey, backups).isEmpty();
    }
}
