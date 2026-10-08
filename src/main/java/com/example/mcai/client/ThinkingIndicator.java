package com.example.mcai.client;

import com.example.mcai.util.ClientChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求进行中的提示：聊天栏一行文字 + 头顶 Action Bar 的旋转加载符号。
 *
 * <p>两个关键设计：
 * <ol>
 *   <li><b>引用计数</b>：同时可能有多个在途请求（聊天 + 截图），动画只在
 *       "至少还有一个请求没结束"时显示，最后一个结束才停。</li>
 *   <li><b>幂等的 {@link Request#finish()}</b>：网络超时、HTTP 错误、JSON 解析炸了、
 *       拦截异常……任何路径都必须能停掉动画。用 CAS 保证不管调用几次都只真正结束一次，
 *       这样即使代码里在 finally 里再兜一次也不会把计数减坏，导致加载符号永远转下去。</li>
 * </ol>
 *
 * <p>清空 Action Bar 用的是 {@code setOverlayMessage(null, false)}：查过字节码，
 * {@code renderOverlayMessage} 开头有 {@code ifnull} 提前返回，传 null 是安全的，不会 NPE。
 */
public final class ThinkingIndicator {

    /** 旋转符号：-\|/ （Java 里反斜杠要写成 \\） */
    private static final String[] FRAMES = { "-", "\\", "|", "/" };

    /** 每 3 个 tick 换一帧，约 6.7 fps，观感接近原版加载图标。 */
    private static final int TICKS_PER_FRAME = 3;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static int frameIndex = 0;
    private static int tickCounter = 0;

    private ThinkingIndicator() {}

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ACTIVE.get() <= 0) {
                return;
            }
            if (client.player == null || client.inGameHud == null) {
                return;
            }

            tickCounter++;
            if (tickCounter % TICKS_PER_FRAME == 0) {
                frameIndex = (frameIndex + 1) % FRAMES.length;
            }
            client.inGameHud.setOverlayMessage(
                    Text.literal("§b" + FRAMES[frameIndex] + " §7AI 正在思考中..."), false);
        });
    }

    /** 开始一次请求。 */
    public static Request begin() {
        return new Request();
    }

    private static void beginRequest() {
        if (ACTIVE.incrementAndGet() == 1) {
            ClientChat.sendLiteral("§7[⏳] AI 正在思考中...");
        }
    }

    private static long endRequest(long startNanos) {
        if (ACTIVE.decrementAndGet() <= 0) {
            ACTIVE.set(0);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.inGameHud != null) {
                        client.inGameHud.setOverlayMessage(null, false);
                    }
                });
            }
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 当前在途请求数。主要用于诊断与测试。 */
    public static int activeCount() {
        return Math.max(0, ACTIVE.get());
    }

    /** 格式化成 {@code §7(思考：3.2秒)}。 */
    public static String formatElapsed(long millis) {
        long safe = Math.max(0L, millis);
        return "§7(思考：" + String.format(Locale.ROOT, "%.1f", safe / 1000.0) + "秒)";
    }

    /** 一次请求的生命周期。 */
    public static final class Request {

        private final long startNanos = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile long elapsedMillis = -1L;

        private Request() {
            beginRequest();
        }

        /** 结束请求并返回耗时毫秒。<b>幂等</b>：重复调用返回同一个值，不会重复扣计数。 */
        public long finish() {
            if (finished.compareAndSet(false, true)) {
                elapsedMillis = endRequest(startNanos);
            }
            return elapsedMillis;
        }
    }
}
