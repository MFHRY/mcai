package com.example.mcai.vision;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HttpErrorCatalog;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.ApiKeyManager;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.TokenStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快捷键 H：截取当前画面 → 本地压缩 → 发给多模态模型 → 结果回聊天栏。
 *
 * <p><b>1.21.1 的坑（已核实）：</b>
 * <ul>
 *   <li>快捷键类叫 {@code net.minecraft.client.option.KeyBinding}，<b>不叫 KeyMapping</b>；
 *       而且 1.21.1 <b>没有 KeyMapping.Category</b> 这种东西，分类参数就是一个普通的
 *       String 翻译键（这里用 "category.mcai"）。</li>
 *   <li>{@code KeyBindingHelper.registerKeyBinding} 必须在客户端初始化阶段调用。</li>
 * </ul>
 *
 * <p><b>模型差异（实测）：</b> {@code deepseek-flash} 能真正读图（实测准确读出隐藏文字），
 * 而 {@code deepseek-v4-pro} <b>不支持图片输入</b>，它不会报错，只会礼貌地回一句
 * "我无法读取这张图片" —— 既浪费 token 又让人困惑。所以这里在发请求前先拦一道。
 *
 * <p>所有面向玩家的文案都走语言文件，中英文共用一个 jar。
 */
public final class VisionHandler {

    private static final String KEY_TRANSLATION = "key.mcai.vision";
    private static final String KEY_BUILD_TRANSLATION = "key.mcai.build";
    private static final String KEY_CATEGORY = "category.mcai";

    private static final long COOLDOWN_MILLIS = 3000L;
    private static final long MIN_INTERVAL_MS = 300L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static KeyBinding visionKey;
    private static KeyBinding buildKey;

    private static final AtomicLong nextAllowedTime = new AtomicLong(0L);
    private static volatile long lastTriggerAt = 0L;

    private static final ExecutorService VISION_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-vision-worker");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private VisionHandler() {}

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        // 1.21.1：分类是普通 String，不是 KeyMapping.Category
        visionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_TRANSLATION, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, KEY_CATEGORY));

        // #2 红石 / 建筑读图：另开一个键，走同一条截图链路但换提示词
        buildKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_BUILD_TRANSLATION, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (visionKey.wasPressed()) {
                trigger(null);
            }
            while (buildKey.wasPressed()) {
                trigger(Lang.tr("mcai.vision.prompt.build"));
            }
        });
    }

    private static void onKeyPressed(MinecraftClient client) {
        trigger(null);
    }

    /**
     * 可复用的截图识别入口（#2 红石读图 / #4 GUI 感知 / #16 无障碍描述 共用）。
     *
     * @param promptOverride 自定提示词；null 表示用默认的 {@code mcai.vision.prompt}
     */
    public static void trigger(String promptOverride) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        // 键盘 repeat / 同一 tick 多次触发时兜底，避免连点
        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < MIN_INTERVAL_MS) {
            return;
        }

        long allowedAt = nextAllowedTime.get();
        if (now < allowedAt) {
            long remainSeconds = (allowedAt - now + 999L) / 1000L;
            ClientChat.sendLiteral(Lang.tr("mcai.vision.cooldown", remainSeconds));
            return;
        }

        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.config_load"));
            return;
        }
        // 本地推理服务（Ollama / LM Studio）不需要 API Key，不要因为没有 key 把它拦掉
        if (LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)) {
            ClientChat.sendLiteral(Lang.tr(LocalProvider.isLocal(config.apiUrl)
                    ? "mcai.vision.local_hint"
                    : "mcai.error.no_apikey"));
            return;
        }
        if (config.apiUrl == null || config.apiUrl.isBlank()) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.no_apiurl"));
            return;
        }
        if (config.model == null || config.model.isBlank()) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.no_model"));
            return;
        }

        // 实测 deepseek-v4-pro 不支持图片输入，且它不会报错、只会敷衍一句，
        // 所以这里直接拦下来并告诉玩家怎么办，省得白烧 token。
        if (!ModelCatalog.lookup(config.model).supportsVision()) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.no_vision", config.model));
            return;
        }

        // 今日预算超支时拦下,别继续烧钱
        if (TokenStats.isOverDailyBudget()) {
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.budget_blocked"));
            return;
        }

        // ---- 校验全部通过，正式开跑，此时才消耗冷却 ----
        lastTriggerAt = now;
        nextAllowedTime.set(now + COOLDOWN_MILLIS);

        // 从按下快捷键的这一刻开始计时（截图 + 压缩 + 网络全部算玩家感知的等待）
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        // #4 GUI 感知：如果此刻开着背包/箱子这类容器界面，只截界面那一块。
        // 图片 token 大致与像素数成正比，裁到界面能把开销降到约四分之一，
        // 同时模型也更容易看清格子内容。必须在渲染线程、截图之前取。
        com.example.mcai.util.ScreenCrop.Region crop =
                com.example.mcai.util.ScreenCrop.forCurrentScreen();

        NativeImage image;
        try {
            // 必须在渲染线程抓取（END_CLIENT_TICK 就在渲染线程上）
            image = ScreenshotCapture.grab();
        } catch (Throwable t) {
            thinking.finish();
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_failed", rootMessage(t)));
            return;
        }
        if (image == null) {
            thinking.finish();
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_null"));
            return;
        }

        processAsync(config, image, thinking, promptOverride, crop);
    }

    /** 像素转换 / 裁剪 / 缩放 / JPEG / HTTP 全部在后台线程完成，绝不占用渲染线程。 */
    private static void processAsync(ConfigManager.ConfigData config, NativeImage image,
                                     ThinkingIndicator.Request thinking, String promptOverride,
                                     com.example.mcai.util.ScreenCrop.Region crop) {
        // encode() 接管 image 所有权，无论成功失败都会 close，不会泄漏堆外内存
        VISION_EXECUTOR.execute(() -> {
            try {
                ScreenshotCapture.EncodedImage encoded =
                        ScreenshotCapture.encode(image, config.visionResolution, crop);

                McaiMod.LOGGER.info("mcAI screenshot encoded: {}x{}, JPEG {} KB, vision_resolution={}, cropped={}",
                        encoded.width(), encoded.height(),
                        encoded.jpegBytes() / 1024, config.visionResolution, crop != null);

                sendRequest(config, encoded.base64(), promptOverride, thinking, null);
            } catch (Throwable t) {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.encode_failed", rootMessage(t)));
            } finally {
                // 幂等兜底：任何漏网的路径都不会让加载符号永远转下去
                thinking.finish();
            }
        });
    }

    /**
     * 直接用一份已经编码好的截图发请求（供死亡复盘 #1 复用）。
     *
     * <p>调用方负责做好前置校验（key、预算、模型是否支持读图），并保证在后台线程调用。
     */
    public static void sendEncoded(ConfigManager.ConfigData config, String base64,
                                   String prompt, String historyNote) {
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();
        try {
            sendRequest(config, base64, prompt, thinking, historyNote);
        } finally {
            // 幂等兜底
            thinking.finish();
        }
    }

    private static void sendRequest(ConfigManager.ConfigData config, String base64,
                                    ThinkingIndicator.Request thinking) {
        sendRequest(config, base64, Lang.tr("mcai.vision.prompt"), thinking, null);
    }

    /**
     * 可复用的视觉请求（#2 红石读图 / #4 GUI 感知 / #5 翻译 / #16 无障碍 / #1 死亡复盘 共用）。
     *
     * @param promptOverride 自定提示词；null 表示用默认的 {@code mcai.vision.prompt}
     * @param historyNote    非 null 时，把这次问答也记进多轮上下文（用文字摘要，不放图片 base64）
     */
    public static void sendRequest(ConfigManager.ConfigData config, String base64,
                                   String promptOverride, ThinkingIndicator.Request thinking,
                                   String historyNote) {
        String endpoint = buildEndpoint(config.apiUrl);
        String body = buildRequestBody(config.model, base64, promptOverride);
        String bearer = LocalProvider.isLocal(config.apiUrl)
                ? LocalProvider.effectiveApiKey(config.apiUrl, config.apiKey)
                : ApiKeyManager.pick(config.apiKey, config.backupApiKeys);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + bearer)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.failed", Lang.tr("mcai.error.code0")));
            return;
        }

        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int statusCode = response.statusCode();
            ApiKeyManager.recordResult(statusCode);
            if (statusCode == 200) {
                String reply = handleSuccess(response.body(), thinking.finish());
                if (historyNote != null) {
                    HistoryStore.remember(historyNote, reply);
                }
            } else {
                // 401 / 402 / 429 等按统一字典给出解释（跟随游戏语言）
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed",
                        statusCode + ": " + HttpErrorCatalog.describe(statusCode)));
            }
        } catch (Throwable t) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.failed_network", rootMessage(t)));
        }
    }

    /** @return 这次识别得到的回答文本（供调用方记进多轮上下文）。 */
    private static String handleSuccess(String responseBody, long elapsedMillis) {
        JsonObject root = AiResponseParser.parseObject(responseBody);
        if (root == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.unparsable"));
            return null;
        }

        String reply = AiResponseParser.extractContent(root);
        if (reply == null || reply.isBlank()) {
            reply = Lang.tr("mcai.reply.empty");
        }

        // Token 统计与聊天共用同一套跨天清零逻辑，并写一条使用明细
        TokenStats.recordFrom(root, "vision");

        // 先输出思维链、再输出答案（与聊天链路行为一致）
        ClientChat.sendReasoning(root);

        ClientChat.sendLiteral("§f[AI] " + reply.replace("\n", " ").trim()
                + " " + ThinkingIndicator.formatElapsed(elapsedMillis));
        return reply;
    }

    private static String buildEndpoint(String apiUrl) {
        String base = apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    /** 组装 OpenAI 兼容的多模态请求体：content 是 [{type:text}, {type:image_url}] 数组。 */
    private static String buildRequestBody(String model, String base64, String promptOverride) {
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        // 每次请求时才取：语言文件里的提示词就是"让 AI 用哪种语言回答"的开关
        systemMessage.addProperty("content", Lang.tr("mcai.prompt.system"));

        JsonArray content = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", promptOverride == null ? Lang.tr("mcai.vision.prompt") : promptOverride);
        content.add(textPart);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + base64);

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("stream", false);

        return root.toString();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }
}
