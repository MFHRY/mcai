package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.DiscordWebhook;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.TokenStats;
import com.example.mcai.util.WorldContext;
import com.example.mcai.vision.ScreenshotCapture;
import com.example.mcai.vision.VisionHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 死亡复盘（#1）与 Discord 播报（#15）。
 *
 * <p><b>触发流程：</b>客户端 tick 里盯着"玩家刚刚死亡"这个瞬间——
 * 死亡后<b>等约 0.7 秒</b>再截图，这样画面里已经盖上了死亡界面，
 * 而死亡界面本身写着死亡原因（"你被僵尸杀死了"之类），
 * 模型直接读画面就能知道死因，省得去翻聊天记录（那要碰 {@code ChatHud} 的私有字段，不稳）。
 *
 * <p><b>两道开关，默认都关：</b>
 * <ul>
 *   <li>{@code death_recap} —— 是否把死亡画面交给 AI 复盘（会消耗 token）</li>
 *   <li>{@code discord_webhook} —— 填了才往 Discord 播报（不消耗 token）</li>
 * </ul>
 * 两个都关着时，这个类什么都不做，连截图都不抓——自动化往外发东西必须默认关闭。
 */
public final class DeathRecap {

    private DeathRecap() {}

    /** 等死亡界面画出来再截图（毫秒）。 */
    private static final long CAPTURE_DELAY_MS = 700L;

    private static boolean wasDead = false;
    private static long captureAt = 0L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-death");
        thread.setDaemon(true);
        return thread;
    });

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DeathRecap::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            wasDead = false;
            captureAt = 0L;
            return;
        }

        boolean dead = client.player.isDead() || client.player.getHealth() <= 0.0f;
        long now = System.currentTimeMillis();

        if (!dead) {
            wasDead = false;
            captureAt = 0L;
            return;
        }

        if (!wasDead) {
            // 刚死的这一 tick：只是记个时间，等界面出来
            wasDead = true;
            captureAt = now + CAPTURE_DELAY_MS;
            return;
        }

        if (captureAt > 0L && now >= captureAt) {
            captureAt = 0L;
            recap(client);
        }
    }

    private static void recap(MinecraftClient client) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return;
        }

        boolean wantDiscord = config.discordWebhook != null && !config.discordWebhook.isBlank();
        boolean wantAi = config.deathRecap
                && !com.example.mcai.util.LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)
                && ModelCatalog.lookup(config.model).supportsVision()
                && !TokenStats.isOverDailyBudget();

        // 两个开关都没开：什么都不做（不抓图、不发请求）
        if (!wantDiscord && !wantAi) {
            return;
        }

        NativeImage image;
        try {
            // 渲染线程上抓图
            image = ScreenshotCapture.grab();
        } catch (Throwable t) {
            return;
        }
        if (image == null) {
            return;
        }

        final boolean sendAi = wantAi;
        final String playerName = client.player == null ? "?" : client.player.getName().getString();
        // 世界环境在渲染线程上取好，避免后台线程访问客户端状态
        final String context = WorldContext.snapshot();

        WORKER.execute(() -> {
            try {
                ScreenshotCapture.EncodedImage encoded =
                        ScreenshotCapture.encode(image, config.visionResolution, null);

                if (wantDiscord) {
                    DiscordWebhook.sendAsync(config.discordWebhook,
                            Lang.tr("mcai.death.broadcast", playerName),
                            encoded.jpeg(), "death.jpg");
                }

                if (sendAi) {
                    String prompt = Lang.tr("mcai.death.prompt")
                            + (context == null ? "" : "\n\n" + context);
                    // 复用截图识别那条链路：token 统计、超时、错误提示都是现成的
                    VisionHandler.sendEncoded(config, encoded.base64(), prompt, prompt);
                }
            } catch (Throwable t) {
                System.err.println("[mcAI] Death recap failed: " + t.getMessage());
            }
        });
    }
}
