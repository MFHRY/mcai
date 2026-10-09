package com.example.mcai;

import com.example.mcai.client.StreamingHud;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ApiKeyManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HttpErrorCatalog;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.RecipeHelper;
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
        String question = message.substring(PREFIX.length()).trim();
        if (question.startsWith(":") || question.startsWith("：")) {
            question = question.substring(1).trim();
        }
        if (question.isEmpty()) {
            sendLocalMessage(MinecraftClient.getInstance(), Lang.tr("mcai.chat.empty"));
            return;
        }

        // #10 离线配方库：如果这是"怎么做 X"而且本地配方表能答，就直接答，不花钱。
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config != null && config.recipeCache) {
            String local = RecipeHelper.answerCraftQuestion(question);
            if (local != null) {
                sendLocalMessage(MinecraftClient.getInstance(), local);
                return;
            }
        }

        ask(question);
    }

    /**
     * 公开的提问入口：{@code !ai} 聊天、{@code /ai where}、{@code /ai read} 等命令共用。
     *
     * <p>负责冷却、配置校验、预算拦截、多轮上下文开关和 API Key 轮换，
     * 保证所有入口的行为完全一致。
     */
    public static void ask(String question) {
        ask(question, null);
    }

    /**
     * 带回答回调的提问入口（#19 AI 任务要用它把回答存下来）。
     *
     * @param onReply 收到回答时回调（在后台线程执行）；null 表示不需要
     */
    public static void ask(String question, java.util.function.Consumer<String> onReply) {
        MinecraftClient mc = MinecraftClient.getInstance();

        long now = System.currentTimeMillis();
        long allowedAt = INSTANCE.nextAllowedTime.get();
        if (now < allowedAt) {
            long remainSeconds = (allowedAt - now + 999L) / 1000L;
            sendLocalMessage(mc, Lang.tr("mcai.chat.cooldown", remainSeconds));
            return;
        }
        INSTANCE.nextAllowedTime.set(now + COOLDOWN_MILLIS);

        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            sendLocalMessage(mc, Lang.tr("mcai.error.config_load"));
            return;
        }
        // 本地推理服务（Ollama / LM Studio）不需要 API Key，不要因为没有 key 把它拦掉
        if (LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)) {
            sendLocalMessage(mc, Lang.tr("mcai.error.no_apikey"));
            return;
        }
        if (config.apiUrl == null || config.apiUrl.isBlank()) {
            sendLocalMessage(mc, Lang.tr("mcai.error.no_apiurl"));
            return;
        }

        String model = (config.model == null || config.model.isBlank()) ? "gpt-4o-mini" : config.model;
        if (blockedByBudget(mc)) {
            return;
        }
        // 多轮上下文(本地内存存储),<=0 表示关闭
        HistoryStore.setMaxTurns(config.historyTurns);
        String apiKey = LocalProvider.isLocal(config.apiUrl)
                ? LocalProvider.effectiveApiKey(config.apiUrl, config.apiKey)
                : ApiKeyManager.pick(config.apiKey, config.backupApiKeys);
        // #8 流式输出：开了就走 SSE，边生成边在 Action Bar 上显示
        if (config.streaming) {
            INSTANCE.sendStreamingRequest(mc, apiKey, config.apiUrl, model, question, onReply);
            return;
        }

        INSTANCE.sendRequest(mc, apiKey, config.apiUrl, model, question, onReply);
    }

    /** 请求真正发出前检查今日预算;超了就拦下并提示。 */
    private static boolean blockedByBudget(MinecraftClient mc) {
        if (!TokenStats.isOverDailyBudget()) {
            return false;
        }
        sendLocalMessage(mc, Lang.tr("mcai.cmd.budget_blocked"));
        return true;
    }

    private void sendRequest(MinecraftClient mc, String apiKey, String apiUrl, String model, String question) {
        sendRequest(mc, apiKey, apiUrl, model, question, null);
    }

    // ------------------------------------------------------- #8 流式输出

    /** 流式请求专用线程池：SSE 读取是阻塞的，不能占用聊天那条的线程池。 */
    private static final ExecutorService STREAM_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcai-stream-worker");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 流式（SSE）提问：边收边把内容显示在 Action Bar 上，收完再整条写进聊天栏。
     *
     * <p>几个必须处理的点：
     * <ul>
     *   <li>请求体要多带 {@code stream:true} 和 {@code stream_options.include_usage}，
     *       否则流式响应里<b>根本不会有 usage 字段</b>，token 统计就全丢了。</li>
     *   <li>HTTP 错误时响应体是普通 JSON 而不是 SSE，这里把它读完只为日志，
     *       错误提示仍走统一的 {@link HttpErrorCatalog}。</li>
     *   <li>{@code [DONE]} 是流结束标志，收到就停。</li>
     * </ul>
     */
    private void sendStreamingRequest(MinecraftClient mc, String apiKey, String apiUrl, String model,
                                      String question, java.util.function.Consumer<String> onReply) {
        String endpoint = buildEndpoint(apiUrl);
        String requestBody = buildRequestBody(model, question, true);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            sendLocalMessage(mc, "§c" + question + " (" + Lang.tr("mcai.error.code0") + ")");
            return;
        }

        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        STREAM_EXECUTOR.execute(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            String usageChunk = null;
            try {
                HttpResponse<java.util.stream.Stream<String>> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());

                int statusCode = response.statusCode();
                ApiKeyManager.recordResult(statusCode);
                if (statusCode != 200) {
                    // 错误响应是普通 JSON，读掉它免得连接挂起，然后走统一错误提示
                    try (var body = response.body()) {
                        body.forEach(line -> { /* 丢弃 */ });
                    }
                    thinking.finish();
                    StreamingHud.clear();
                    handleHttpError(mc, question, statusCode);
                    return;
                }

                try (var lines = response.body()) {
                    for (String line : (Iterable<String>) lines::iterator) {
                        if (line == null) {
                            continue;
                        }
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("data:")) {
                            continue;
                        }
                        String payload = trimmed.substring(5).trim();
                        if (payload.isEmpty()) {
                            continue;
                        }
                        if (payload.equals("[DONE]")) {
                            break;
                        }
                        JsonObject chunk = AiResponseParser.parseObject(payload);
                        if (chunk == null) {
                            continue;
                        }
                        String delta = AiResponseParser.extractDeltaContent(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            content.append(delta);
                            StreamingHud.update(content.toString());
                        }
                        // 思维链也是分片到达的，先攒起来，收完再按和非流式一样的规则显示
                        String reasonDelta = AiResponseParser.extractDeltaReasoning(chunk);
                        if (reasonDelta != null && !reasonDelta.isEmpty()) {
                            reasoning.append(reasonDelta);
                        }
                        // 带 usage 的那个分片要留着，收完再一起记统计
                        if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                            usageChunk = chunk.toString();
                        }
                    }
                }

                long elapsedMillis = thinking.finish();
                StreamingHud.clear();
                finishStreaming(mc, question, content.toString(), reasoning.toString(),
                        usageChunk, elapsedMillis, onReply);
            } catch (Throwable t) {
                thinking.finish();
                StreamingHud.clear();
                sendLocalMessage(mc, "§c" + question
                        + " (" + Lang.tr("mcai.error.network", rootMessage(t)) + ")");
            }
        });
    }

    /** 流式收完后：记统计、显示思维链、写聊天栏、记历史、回调。 */
    private void finishStreaming(MinecraftClient mc, String question, String content, String reasoning,
                                 String usageChunk, long elapsedMillis,
                                 java.util.function.Consumer<String> onReply) {
        if (usageChunk != null) {
            JsonObject usageRoot = AiResponseParser.parseObject(usageChunk);
            if (usageRoot != null) {
                TokenStats.recordFrom(usageRoot, "chat");
            }
        }

        String reply = content == null ? "" : content.trim();
        if (reply.isEmpty()) {
            reply = Lang.tr("mcai.reply.empty");
        }

        // 先补上思维链，再发答案——和非流式链路的顺序保持一致
        ClientChat.sendReasoningText(reasoning);

        HistoryStore.remember(question, reply);
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不影响显示
            }
        }

        sendLocalMessage(mc, "§f[AI] " + reply.replace("\n", " ").trim()
                + " " + ThinkingIndicator.formatElapsed(elapsedMillis));
    }

    private void sendRequest(MinecraftClient mc, String apiKey, String apiUrl, String model,
                             String question, java.util.function.Consumer<String> onReply) {
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
                ApiKeyManager.recordResult(statusCode);
                if (statusCode == 200) {
                    handleSuccess(mc, response.body(), question, elapsedMillis, onReply);
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
        return buildRequestBody(model, question, false);
    }

    private static String buildRequestBody(String model, String question, boolean stream) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String system = Lang.tr("mcai.prompt.system");

        // #9 人格:如果当前 persona 有额外的系统提示词后缀,追加到 system 里
        String personaSuffix = config == null ? null
                : com.example.mcai.util.PersonaCatalog.systemSuffix(config.persona);
        if (personaSuffix != null && !personaSuffix.isBlank()) {
            system = system + " " + personaSuffix;
        }

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", system);
        messages.add(systemMessage);

        // #7 多轮上下文:系统提示词之后、当前问题之前,把内存里的旧对话插进去
        for (String[] turn : HistoryStore.snapshot()) {
            JsonObject u = new JsonObject();
            u.addProperty("role", "user");
            u.addProperty("content", turn[0]);
            messages.add(u);
            JsonObject a = new JsonObject();
            a.addProperty("role", "assistant");
            a.addProperty("content", turn[1]);
            messages.add(a);
        }

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", question);
        messages.add(userMessage);

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("stream", stream);

        if (stream) {
            // 开了流式就必须显式要 usage，否则响应里没有 token 统计
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }

        return root.toString();
    }

    private void handleSuccess(MinecraftClient mc, String responseBody, String question,
                               long elapsedMillis, java.util.function.Consumer<String> onReply) {
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

        // 多轮上下文：这次问答写进内存历史（本地存储，不上传）
        HistoryStore.remember(question, reply);

        // 调用方的回调（#19 用它把回答存成任务）
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不能影响正常显示
            }
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
