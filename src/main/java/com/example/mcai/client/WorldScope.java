package com.example.mcai.client;

import com.example.mcai.util.HistoryStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * 多轮上下文的「作用域」（1.24 新增）——修掉"换了存档/服务器，AI 还记得上一个世界"的问题。
 *
 * <p><b>原来的行为：</b>{@link HistoryStore} 是一个全局静态队列，只有手动
 * {@code /ai clear} 才会清空。于是你从单人存档 A 退出去、进服务器 B，
 * AI 依然记得 A 里聊过的坐标和建筑，回答会莫名其妙地张冠李戴。
 *
 * <p><b>现在的行为：</b>按"哪个世界"分桶存放，并在断开连接时整体清空：
 * <ul>
 *   <li>多人游戏 —— 用服务器地址（{@code server:play.example.com}）</li>
 *   <li>单人游戏 —— 用集成服务端对象的身份哈希（{@code local:1234567}）</li>
 * </ul>
 * 单人这一支用身份哈希而不是存档名，是因为上下文本来就<b>只存在内存里</b>：
 * 只需要在一次游戏进程内区分开不同世界就够了，用不着跨重启稳定。
 * 这样也避免去碰 {@code IntegratedServer} 上那些容易随版本变化的存档相关 API。
 */
public final class WorldScope {

    private WorldScope() {}

    /** 进入世界：把历史切到这个世界的桶。由 {@code McaiModClient} 的登入事件调用。 */
    public static void onJoin() {
        HistoryStore.setScope(current());
    }

    /** 离开世界就把所有桶丢掉：既修掉了串味，也顺手把内存还回去。 */
    public static void onDisconnect() {
        HistoryStore.clearAll();
    }

    /** 当前世界的标识；取不到时返回 {@code unknown}（所有这种情况共用一个桶）。 */
    public static String current() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "unknown";
        }
        try {
            ServerData server = client.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isBlank()) {
                return "server:" + server.ip.toLowerCase(Locale.ROOT);
            }
            if (client.getSingleplayerServer() != null) {
                return "local:" + System.identityHashCode(client.getSingleplayerServer());
            }
        } catch (Throwable ignored) {
            // 拿不到就不区分，退化成原来的全局行为，不影响可用性
        }
        return "unknown";
    }
}
