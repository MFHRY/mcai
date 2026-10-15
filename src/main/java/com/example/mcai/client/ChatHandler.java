package com.example.mcai.client;

import com.example.mcai.ConfigManager;
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
    private static final String DEFAULT_PREFIX = "!ai";
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

    /** 当前配置的触发词前缀；配置缺失或为空时回退到默认值 !ai。 */
    private static String prefix() {
        ConfigManager.ConfigData c = ConfigManager.getInstance().get();
        String p = c == null ? null : c.chatPrefix;
        return (p == null || p.isBlank()) ? DEFAULT_PREFIX : p;
    }

    private static boolean isAiMessage(String message) {
        if (message == null) return false;
        String prefix = prefix();
        if (message.length() < prefix.length()) return false;
        return message.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT));
    }

    private void handleAiMessage(String message) {
        String question = message.substring(prefix().length()).trim();
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

        // 所有校验都放在「消耗冷却」之前。
        // 以前 nextAllowedTime 是在下面这一段校验之前就写掉的，于是玩家只要 key 还没填好，
        // 就会先被扣掉 8 秒冷却，改完配置还得干等——看起来就像"改了不起作用"。
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

        // ---- 1.24 相同问题本地缓存：命中就直接回答 ----
        // 刻意放在"消耗冷却"和"预算检查"之前：命中缓存不花一分钱、不占用冷却，
        // 如果先扣冷却再查缓存，玩家会觉得"我问了个一样的问题还被罚站 8 秒"。
        String cached = com.example.mcai.util.ResponseCache.lookup(question, model, config.persona);
        if (cached != null) {
            sendLocalMessage(mc, Lang.tr("mcai.cache.hit"));
            replyToPlayer(mc, cached, 0L);
            if (onReply != null) {
                try {
                    onReply.accept(cached);
                } catch (Throwable ignored) {
                    // 回调失败不影响显示
                }
            }
            return;
        }

        if (blockedByBudget(mc)) {
            return;
        }
        // 1.24 预算预警 + 请求前花费预估
        warnIfNearBudget(mc, config);
        sendEstimate(mc, config, question);

        // ---- 校验全部通过，正式开跑，此时才消耗冷却 ----
        INSTANCE.nextAllowedTime.set(now + COOLDOWN_MILLIS);

        // 多轮上下文(本地内存存储),<=0 表示关闭
        HistoryStore.setMaxTurns(config.historyTurns);
        // 1.24：上下文按世界/服务器分桶，避免换存档后 AI 还记得上一个世界的事
        HistoryStore.setScope(WorldScope.current());
        // 本地服务不需要 key；云端走多 key 轮换，并记住这一轮用的是哪一把，
        // 好让响应回来时能精确拉黑失败的那一把（见 ApiKeyManager.PickedKey）。
        ApiKeyManager.PickedKey picked = LocalProvider.isLocal(config.apiUrl)
                ? new ApiKeyManager.PickedKey(LocalProvider.effectiveApiKey(config.apiUrl, config.apiKey), null)
                : ApiKeyManager.pick(config.apiKey, config.backupApiKeys);
        String apiKey = picked.key();
        // #8 流式输出：开了就走 SSE，边生成边在 Action Bar 上显示
        if (config.streaming) {
            INSTANCE.sendStreamingRequest(mc, picked, config.apiUrl, model, question, onReply);
            return;
        }

        INSTANCE.sendRequest(mc, picked, config.apiUrl, model, question, onReply);
    }

    /** 请求真正发出前检查今日预算;超了就拦下并提示。 */
    private static boolean blockedByBudget(MinecraftClient mc) {
        if (!TokenStats.isOverDailyBudget()) {
            return false;
        }
        sendLocalMessage(mc, Lang.tr("mcai.cmd.budget_blocked"));
        return true;
    }

    // ---------------------------------------------- 1.24 预算预警与花费预估

    /** 今天是否已经就"快超预算"提醒过一次。同一天只提醒一次，避免每问一句都唠叨。 */
    private static volatile java.time.LocalDate budgetWarnedDate = null;

    /**
     * 今日花费达到预算的 {@code budget_warn_ratio}（默认 80%）时提醒一次。
     *
     * <p>原来只有"花完了直接拦下"这一道，中途完全没有预警——对按量计费的服务来说，
     * 等你发现的时候钱已经花完了。现在提前提醒，还来得及换个便宜模型或收手。
     */
    private static void warnIfNearBudget(MinecraftClient mc, ConfigManager.ConfigData config) {
        if (config == null || config.dailyBudgetYuan <= 0.0) {
            return;
        }
        double ratio = config.budgetWarnRatio;
        // <=0 或 >=1 视为关闭：>=1 的时候拦截逻辑本身就会生效，不需要重复提醒
        if (ratio <= 0.0 || ratio >= 1.0) {
            return;
        }
        double spent = TokenStats.todayCost();
        if (spent < config.dailyBudgetYuan * ratio) {
            return;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (today.equals(budgetWarnedDate)) {
            return;
        }
        budgetWarnedDate = today;
        sendLocalMessage(mc, Lang.tr("mcai.cmd.budget_warn",
                formatYuan(spent), formatYuan(config.dailyBudgetYuan),
                String.valueOf(Math.round(ratio * 100))));
    }

    /** 发请求前把这句问题的大致花费写一行暗色提示，让玩家在烧钱之前心里有数。 */
    private static void sendEstimate(MinecraftClient mc, ConfigManager.ConfigData config, String question) {
        double yuan = estimateCostYuan(config, question);
        if (yuan < 0.0) {
            // 这个模型没有单价表，明确说"无法估算"而不是瞎猜一个数字
            return;
        }
        sendLocalMessage(mc, Lang.tr("mcai.chat.estimate", formatYuan(yuan)));
    }

    /**
     * 粗略估算这次请求的花费。
     *
     * <p>只是量级参考：输入按"问题长度/3 + 系统提示词和上下文的固定开销"估，
     * 输出按中等长度回答（400 token）估。真实数字以服务商账单为准 ——
     * 这里刻意不做任何"看起来精确"的伪装。
     *
     * @return 元；模型没有单价表时返回 -1
     */
    private static double estimateCostYuan(ConfigManager.ConfigData config, String question) {
        if (config == null) {
            return -1.0;
        }
        com.example.mcai.util.PricingCatalog.Quote quote =
                com.example.mcai.util.PricingCatalog.find(config.model);
        if (quote == null) {
            return -1.0;
        }
        int questionTokens = question == null ? 0 : question.length() / 3;
        int contextTokens = Math.max(0, config.historyTurns) * 60 + 220;
        int inputTokens = Math.max(1, questionTokens + contextTokens);
        int outputTokens = 400;
        return quote.estimate(inputTokens, outputTokens);
    }

    private static String formatYuan(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", Math.max(0.0, value));
    }

    // ---------------------------------------------- 1.24 回答展示（可点击 + 朗读）

    /**
     * 统一的回答展示入口：把坐标和「物品名」变成可点击文本，并按配置朗读。
     *
     * <p>聊天和截图两条链路都走这里，保证观感一致。
     *
     * @param elapsedMillis 耗时；<=0 表示不显示耗时（缓存命中时用）
     */
    private static void replyToPlayer(MinecraftClient mc, String reply, long elapsedMillis) {
        String flat = reply == null ? "" : reply.replace("\n", " ").trim();
        String suffix = elapsedMillis > 0 ? " " + ThinkingIndicator.formatElapsed(elapsedMillis) : "";
        Text text = com.example.mcai.util.ClickableText.build("§f[AI] " + flat + suffix);
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(text);
            }
        });
        // TTS 默认关闭；开了就顺带念一遍（无障碍用途）
        com.example.mcai.util.Speech.speakIfEnabled(flat);
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
    private void sendStreamingRequest(MinecraftClient mc, ApiKeyManager.PickedKey picked, String apiUrl, String model,
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
                    .header("Authorization", "Bearer " + picked.key())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            sendLocalMessage(mc, "§c" + question + " (" + Lang.tr("mcai.error.code0") + ")");
            return;
        }

        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        // 1.24 可取消：流式请求没有单一的 future 可以 cancel，
        // 靠下面循环里的 isCurrent 检查跳出读取（取消时 HTTP 连接会随流关闭而断开）
        final long requestId = com.example.mcai.util.RequestController.begin();
        com.example.mcai.util.RequestController.setCancelHook(thinking::finish);

        STREAM_EXECUTOR.execute(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            String usageChunk = null;
            try {
                HttpResponse<java.util.stream.Stream<String>> response =
                        HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());

                int statusCode = response.statusCode();
                ApiKeyManager.recordResult(picked, statusCode);
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
                        // 玩家取消了 → 立刻停止读取，不再把后续分片渲染到 Action Bar
                        if (!com.example.mcai.util.RequestController.isCurrent(requestId)) {
                            break;
                        }
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

                // 被取消被顶掉 → 丢掉已经收到的那半截回答
                if (!com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    return;
                }

                finishStreaming(mc, question, content.toString(), reasoning.toString(),
                        usageChunk, elapsedMillis, onReply);
            } catch (Throwable t) {
                thinking.finish();
                StreamingHud.clear();
                if (com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    sendLocalMessage(mc, "§c" + question
                            + " (" + Lang.tr("mcai.error.network", rootMessage(t)) + ")");
                }
            } finally {
                com.example.mcai.util.RequestController.finish(requestId);
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
        storeInCache(question, reply);
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不影响显示
            }
        }

        replyToPlayer(mc, reply, elapsedMillis);
    }

    /** 1.24：把成功的问答写进本地缓存，下次问同样的问题就不再花钱。 */
    private static void storeInCache(String question, String reply) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return;
        }
        com.example.mcai.util.ResponseCache.store(question, config.model, config.persona, reply);
    }

    private void sendRequest(MinecraftClient mc, ApiKeyManager.PickedKey picked, String apiUrl, String model,
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
                    .header("Authorization", "Bearer " + picked.key())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            sendLocalMessage(mc, "§c" + question + " (" + Lang.tr("mcai.error.code0") + ")");
            return;
        }

        // 请求已合法构建，从这里开始计时并启动"思考中"提示
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        // 1.24 可取消请求：领一个 id，登记"取消时立刻停掉思考动画"，并把 future 登记进去
        final long requestId = com.example.mcai.util.RequestController.begin();
        com.example.mcai.util.RequestController.setCancelHook(thinking::finish);

        CompletableFuture<HttpResponse<String>> future =
                HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        com.example.mcai.util.RequestController.track(future);

        future.whenCompleteAsync((response, throwable) -> {
            // 无论成功、HTTP 错误还是抛异常，都必须先停掉动画
            long elapsedMillis = thinking.finish();
            try {
                // 玩家按 ESC 取消了（或被新请求顶掉）→ 静默收场，绝不弹出"幽灵回答"
                if (!com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    return;
                }
                if (throwable != null) {
                    sendLocalMessage(mc, "§c" + question
                            + " (" + Lang.tr("mcai.error.network", rootMessage(throwable)) + ")");
                    return;
                }
                int statusCode = response.statusCode();
                ApiKeyManager.recordResult(picked, statusCode);
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
                com.example.mcai.util.RequestController.finish(requestId);
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
        // 1.24：系统提示词统一由 SystemPrompt 拼装 —— 基础提示词 + 人格 + 长期记忆 + 环境上下文。
        // 以前这里只拼了人格，而截图链路连人格都没有，两条链路行为不一致。
        String system = com.example.mcai.util.SystemPrompt.build();

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
        // 1.24：顺便进本地缓存
        storeInCache(question, reply);

        // 调用方的回调（#19 用它把回答存成任务）
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不能影响正常显示
            }
        }

        replyToPlayer(mc, reply, elapsedMillis);
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
