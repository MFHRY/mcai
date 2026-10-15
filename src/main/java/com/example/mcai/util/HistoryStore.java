package com.example.mcai.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮上下文（#7）的内存存储。
 *
 * <p><b>只存在内存里、不落盘</b>:用户如果要隐私,出去这个会话就没了;
 * 数据不会泄漏到配置文件。重启游戏即清空。
 *
 * <p>有界窗口:最多记住 {@code maxTurns} 轮(chat + vision 各一条消息算一轮,
 * 这里简化成"每次请求塞进去的那条 user 消息及其 assistant 回复")。
 * 超出上限丢最旧的。因为对话里可能夹着很长的图片 base64(截图),这里还做了一个
 * <b>token 预算上限</b>(按字符/4 粗估)兜底,避免一次历史把请求体撑爆。
 *
 * <p><b>1.24 起按"世界"分桶</b>（见 {@link #setScope}）:原来这里是<b>一个全局队列</b>,
 * 只有手动 {@code /ai clear} 才清空。后果是从单人存档 A 退出去、进服务器 B 之后,
 * AI 依然记得 A 里聊过的坐标和建筑,回答会莫名张冠李戴。
 * 现在每个世界/服务器一个独立队列,离开世界时整体丢弃。
 */
public final class HistoryStore {

    private HistoryStore() {}

    private static final class Turn {
        final String user;
        final String assistant;
        Turn(String user, String assistant) {
            this.user = user;
            this.assistant = assistant;
        }
    }

    /**
     * 每个作用域一个队列。用访问序 LinkedHashMap 并限制桶数量,
     * 避免玩家在服务器列表里来回点导致桶无限增长。
     */
    private static final int MAX_SCOPES = 8;
    private static final Map<String, Deque<Turn>> BUCKETS =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Turn>> eldest) {
                    return size() > MAX_SCOPES;
                }
            };

    private static final String DEFAULT_SCOPE = "unknown";
    private static String activeScope = DEFAULT_SCOPE;

    private static int maxTurns = 4;      // 默认记住 4 轮
    private static final int MAX_CHARS = 8_000; // 全部历史含文本总量上限

    /**
     * 切换当前作用域（1.24）。
     *
     * @param scope 由 {@code WorldScope} 算出的世界标识；空值表示退化成不区分
     */
    public static synchronized void setScope(String scope) {
        activeScope = (scope == null || scope.isBlank()) ? DEFAULT_SCOPE : scope;
        // 触碰一下，让它进入 LRU 的"最近使用"位置
        buckets().size();
    }

    private static Deque<Turn> buckets() {
        return BUCKETS.computeIfAbsent(activeScope, key -> new ArrayDeque<>());
    }

    /** 设置要记住几轮(本地,只影响本次运行)。非正数=完全关闭多轮。 */
    public static synchronized void setMaxTurns(int turns) {
        maxTurns = Math.max(0, turns);
        trim();
    }

    /** 清空<b>当前作用域</b>的历史（{@code /ai clear}）。 */
    public static synchronized void clear() {
        buckets().clear();
    }

    /** 丢掉<b>所有</b>作用域的历史（离开世界时调用）。 */
    public static synchronized void clearAll() {
        BUCKETS.clear();
        activeScope = DEFAULT_SCOPE;
    }

    /** 当前作用域里有多少轮（{@code /ai status} 展示用）。 */
    public static synchronized int currentTurns() {
        return buckets().size();
    }

    /** 记住一轮对话。assistant 为空(比如调用出错)便不记录。 */
    public static synchronized void remember(String user, String assistant) {
        if (maxTurns <= 0) {
            return;
        }
        String a = (assistant == null || assistant.isBlank()) ? ". . ." : assistant.trim();
        buckets().addLast(new Turn(truncateUser(user), a));
        trim();
    }

    /** 组装成聊天历史消息数组,供 ChatHandler 一起发给模型。 */
    public static synchronized List<String[]> snapshot() {
        List<String[]> out = new ArrayList<>();
        int budget = MAX_CHARS;
        // 从新到旧收集,直到预算用尽
        List<Turn> recent = new ArrayList<>(buckets());
        java.util.Collections.reverse(recent);
        for (Turn t : recent) {
            int cost = t.user.length() + t.assistant.length() + 8;
            if (budget - cost < 0) break;
            budget -= cost;
            out.add(new String[]{t.user, t.assistant});
        }
        java.util.Collections.reverse(out); // 回到从旧到新,方便追加
        return out;
    }

    private static String truncateUser(String u) {
        if (u == null || u.length() <= 300) return u == null ? "" : u;
        return u.substring(0, 300) + "…";
    }

    private static void trim() {
        Deque<Turn> bucket = buckets();
        while (bucket.size() > maxTurns && maxTurns > 0) {
            bucket.pollFirst();
        }
    }
}
