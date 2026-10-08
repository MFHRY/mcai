package com.example.mcai.vision;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HttpErrorCatalog;
import com.example.mcai.util.Lang;
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
    private static final String KEY_CATEGORY = "category.mcai";

    private static final long COOLDOWN_MILLIS = 3000L;
    private static final long MIN_INTERVAL_MS = 300L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static KeyBinding visionKey;

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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (visionKey.wasPressed()) {
                onKeyPressed(client);
            }
        });
    }

    private static void onKeyPressed(MinecraftClient client) {
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
        if (config.apiKey == null || config.apiKey.isBlank()) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.no_apikey"));
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

        // ---- 校验全部通过，正式开跑，此时才消耗冷却 ----
        lastTriggerAt = now;
        nextAllowedTime.set(now + COOLDOWN_MILLIS);

        // 从按下 H 的这一刻开始计时（截图 + 压缩 + 网络全部算玩家感知的等待）
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

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

        processAsync(config, image, thinking);
    }

    /** 像素转换 / 缩放 / JPEG / HTTP 全部在后台线程完成，绝不占用渲染线程。 */
    private static void processAsync(ConfigManager.ConfigData config, NativeImage image,
                                     ThinkingIndicator.Request thinking) {
        // encode() 接管 image 所有权，无论成功失败都会 close，不会泄漏堆外内存
        VISION_EXECUTOR.execute(() -> {
            try {
                ScreenshotCapture.EncodedImage encoded =
                        ScreenshotCapture.encode(image, config.visionResolution);

                McaiMod.LOGGER.info("mcAI screenshot encoded: {}x{}, JPEG {} KB, vision_resolution={}",
                        encoded.width(), encoded.height(),
                        encoded.jpegBytes() / 1024, config.visionResolution);

                sendRequest(config, encoded.base64(), thinking);
            } catch (Throwable t) {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.encode_failed", rootMessage(t)));
            } finally {
                // 幂等兜底：任何漏网的路径都不会让加载符号永远转下去
                thinking.finish();
            }
        });
    }

    private static void sendRequest(ConfigManager.ConfigData config, String base64,
                                    ThinkingIndicator.Request thinking) {
        String endpoint = buildEndpoint(config.apiUrl);
        String body = buildRequestBody(config.model, base64);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey)
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
            if (statusCode == 200) {
                handleSuccess(response.body(), thinking.finish());
            } else {
                // 401 / 402 / 429 等按统一字典给出解释（跟随游戏语言）
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed",
                        statusCode + ": " + HttpErrorCatalog.describe(statusCode)));
            }
        } catch (Throwable t) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.failed_network", rootMessage(t)));
        }
    }

    private static void handleSuccess(String responseBody, long elapsedMillis) {
        JsonObject root = AiResponseParser.parseObject(responseBody);
        if (root == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.unparsable"));
            return;
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
    private static String buildRequestBody(String model, String base64) {
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        // 每次请求时才取：语言文件里的提示词就是"让 AI 用哪种语言回答"的开关
        systemMessage.addProperty("content", Lang.tr("mcai.prompt.system"));

        JsonArray content = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", Lang.tr("mcai.vision.prompt"));
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
