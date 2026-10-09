package com.example.mcai.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

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

    private static final Deque<Turn> history = new ArrayDeque<>();
    private static int maxTurns = 4;      // 默认记住 4 轮
    private static final int MAX_CHARS = 8_000; // 全部历史含文本总量上限

    /** 设置要记住几轮(本地,只影响本次运行)。非正数=完全关闭多轮。 */
    public static void setMaxTurns(int turns) {
        maxTurns = Math.max(0, turns);
        trim();
    }

    /** 退出时清空。 */
    public static void clear() {
        history.clear();
    }

    /** 记住一轮对话。assistant 为空(比如调用出错)便不记录。 */
    public static void remember(String user, String assistant) {
        if (maxTurns <= 0) {
            return;
        }
        String a = (assistant == null || assistant.isBlank()) ? ". . ." : assistant.trim();
        history.addLast(new Turn(truncateUser(user), a));
        trim();
    }

    /** 组装成聊天历史消息数组,供 ChatHandler 一起发给模型。 */
    public static java.util.List<String[]> snapshot() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        int budget = MAX_CHARS;
        // 从新到旧收集,直到预算用尽
        java.util.List<Turn> recent = new java.util.ArrayList<>(history);
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
        while (history.size() > maxTurns && maxTurns > 0) {
            history.pollFirst();
        }
    }
}