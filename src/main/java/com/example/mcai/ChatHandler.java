package com.example.mcai;

import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HttpErrorCatalog;
import com.example.mcai.util.Lang;
import com.example.mcai.util.TokenStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatHandler {
    private static final String PREFIX = "!ai";
    private static final long COOLDOWN_MILLIS = 8000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mcai-http-worker");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicLong nextAllowedTime = new AtomicLong(0L);

    private ChatHandler() {}

    public static ChatHandler getInstance() { return INSTANCE; }
    private static final ChatHandler INSTANCE = new ChatHandler();

    public void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!isAiMessage(message)) return true;
            handleAiMessage(message);
            return false;
        });
    }

    private static boolean isAiMessage(String message) {
        if (message == null || message.length() < PREFIX.length()) return false;
        return message.toLowerCase(java.util.Locale.ROOT).startsWith(PREFIX);
    }

    private void handleAiMessage(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String question = message.substring(PREFIX.length()).trim();
        if (question.startsWith(":") || question.startsWith("：")) {
            question = question.substring(1).trim();
        }
        if (question.isEmpty()) {
            sendLocalMessage(mc, Lang.tr("mcai.chat.empty"));
            return;
        }

        long now = System.currentTimeMillis();
        long allowedAt = nextAllowedTime.get();
        if (now < allowedAt) {
            long remainSeconds = (allowedAt - now + 999L) / 1000L;
            sendLocalMessage(mc, Lang.tr("mcai.chat.cooldown", remainSeconds));
            return;
        }
        nextAllowedTime.set(now + COOLDOWN_MILLIS);

        ConfigManager configManager = ConfigManager.getInstance();
        ConfigManager.ConfigData config = configManager.get();

        if (config == null) {
            sendLocalMessage(mc, Lang.tr("mcai.error.config_load"));
            return;
        }
        if (config.apiKey == null || config.apiKey.isBlank()) {
            sendLocalMessage(mc, Lang.tr("mcai.error.no_apikey"));
            return;
        }
        if (config.apiUrl == null || config.apiUrl.isBlank()) {
            sendLocalMessage(mc, Lang.tr("mcai.error.no_apiurl"));
            return;
        }

        String model = (config.model == null || config.model.isBlank()) ? "gpt-4o-mini" : config.model;
        sendRequest(mc, config.apiKey, config.apiUrl, model, question);
    }

    private void sendRequest(MinecraftClient mc, String apiKey, String apiUrl, String model, String question) {
        String endpoint = buildEndpoint(apiUrl);
        String requestBody = buildRequestBody(model, question);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            sendLocalMessage(mc, "§c" + question + " (" + Lang.tr("mcai.error.code0") + ")");
            return;
        }

        // 请求已合法构建，从这里开始计时并启动"思考中"提示
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        CompletableFuture<HttpResponse<String>> future =
                HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        future.whenCompleteAsync((response, throwable) -> {
            // 无论成功、HTTP 错误还是抛异常，都必须先停掉动画
            long elapsedMillis = thinking.finish();
            try {
                if (throwable != null) {
                    sendLocalMessage(mc, "§c" + question
                            + " (" + Lang.tr("mcai.error.network", rootMessage(throwable)) + ")");
                    return;
                }
                int statusCode = response.statusCode();
                if (statusCode == 200) {
                    handleSuccess(mc, response.body(), elapsedMillis);
                } else {
                    handleHttpError(mc, question, statusCode);
                }
            } catch (Throwable t) {
                sendLocalMessage(mc, "§c" + question
                        + " (" + Lang.tr("mcai.error.response", rootMessage(t)) + ")");
            } finally {
                // 幂等兜底：即使上面某条路径漏了，也保证动画一定会停
                thinking.finish();
            }
        }, HTTP_EXECUTOR);
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

    private static String buildRequestBody(String model, String question) {
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        // 每次请求时才取：玩家中途切换游戏语言也能立刻生效
        systemMessage.addProperty("content", Lang.tr("mcai.prompt.system"));

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);

        JsonArray messages = new JsonArray();
        messages.add(systemMessage);
        messages.add(userMessage);

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("stream", false);

        return root.toString();
    }

    private void handleSuccess(MinecraftClient mc, String responseBody, long elapsedMillis) {
        JsonObject root = AiResponseParser.parseObject(responseBody);
        if (root == null) {
            sendLocalMessage(mc, Lang.tr("mcai.error.unparsable"));
            return;
        }

        // Token 统计：与截图识别共用同一套跨天清零逻辑，并写一条使用明细
        TokenStats.recordFrom(root, "chat");

        // 先输出思维链、再输出答案：这样答案永远落在聊天栏最底部，最靠近输入框
        ClientChat.sendReasoning(root);

        String reply = AiResponseParser.extractContent(root);
        if (reply == null || reply.isBlank()) {
            reply = Lang.tr("mcai.reply.empty");
        }
        sendLocalMessage(mc, "§f[AI] " + reply.replace("\n", " ").trim()
                + " " + ThinkingIndicator.formatElapsed(elapsedMillis));
    }

    private void handleHttpError(MinecraftClient mc, String question, int statusCode) {
        sendLocalMessage(mc, "§c" + question + " (" + statusCode + ": "
                + HttpErrorCatalog.describe(statusCode) + ")");
    }

    private static void sendLocalMessage(MinecraftClient mc, String text) {
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(Text.literal(text));
            }
        });
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
