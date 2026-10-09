package com.example.mcai.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 流式输出的实时显示（#8）。
 *
 * <p><b>为什么要单独做这个：</b>Minecraft 的聊天栏是<b>只能追加</b>的，
 * 没法"改写上一行"，所以流式输出不能直接往聊天栏里刷。
 * 这里改用头顶 Action Bar（{@code setOverlayMessage}）显示正在生成的内容：
 * 它每帧重绘、天然支持覆盖，正是"进度条式"更新的正确载体。
 *
 * <p>两个约束：
 * <ul>
 *   <li><b>节流</b>：模型可能每几十毫秒就吐一小段，如果每次都去碰 UI 会拖慢主线程。
 *       这里限制最短更新间隔，超出的更新直接丢掉（丢内容不影响最终结果，
 *       最终完整回答仍然会整条写进聊天栏）。</li>
 *   <li><b>只显示尾部</b>：Action Bar 只有一行，显示最新的一小段比显示开头更有用
 *       （能看到"正在长出来"的感觉）。</li>
 * </ul>
 */
public final class StreamingHud {

    private StreamingHud() {}

    /** 最短更新间隔（毫秒）。 */
    private static final long MIN_INTERVAL_MS = 120L;

    /** Action Bar 一行大约能放这么多字符，超了就只留尾部。 */
    private static final int MAX_CHARS = 110;

    private static volatile long lastUpdate = 0L;
    private static volatile boolean active = false;

    /** 是否正在显示流式正文。ThinkingIndicator 靠它决定要不要让出 Action Bar。 */
    public static boolean isActive() {
        return active;
    }

    /** 更新正在生成的内容。 */
    public static void update(String partial) {
        if (partial == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUpdate < MIN_INTERVAL_MS) {
            return;
        }
        lastUpdate = now;
        active = true;

        String tail = tailOf(partial.replace('\n', ' ').trim());
        String color = Lang_Color();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.setOverlayMessage(Text.literal(color + tail), false);
            }
        });
    }

    /** 结束时清掉 Action Bar。 */
    public static void clear() {
        if (!active) {
            return;
        }
        active = false;
        lastUpdate = 0L;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.setOverlayMessage(null, false);
            }
        });
    }

    private static String tailOf(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return "…" + text.substring(text.length() - MAX_CHARS);
    }

    /** 前缀色：跟"思考中"的样式保持一致。 */
    private static String Lang_Color() {
        return "§b" + com.example.mcai.util.Lang.tr("mcai.stream.prefix") + " §f";
    }
}
