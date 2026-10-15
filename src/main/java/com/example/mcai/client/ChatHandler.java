package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ApiClient;
import com.example.mcai.util.ApiKeyManager;
import com.example.mcai.util.ApiProvider;
import com.example.mcai.util.ApiRequest;
import com.example.mcai.util.ClickableText;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.PricingCatalog;
import com.example.mcai.util.RecipeHelper;
import com.example.mcai.util.ResponseCache;
import com.example.mcai.util.Speech;
import com.example.mcai.util.SystemPrompt;
import com.example.mcai.util.TokenStats;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ChatHandler {
    private static final String DEFAULT_PREFIX = "!ai";
    private static final long COOLDOWN_MILLIS = 8000L;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mcai-http-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicLong nextAllowedTime = new AtomicLong(0L);

    private ChatHandler() {}

    public static ChatHandler getInstance() { return INSTANCE; }
    private static final ChatHandler INSTANCE = new ChatHandler();

    /**
     * 聊天拦截回调，由 {@code McaiModClient} 的 {@code ClientChatEvent} 监听器调用。
     *
     * <p><b>返回值语义是"是否允许放行"</b>，而不是 Fabric 那边的注册式回调。
     * 返回 {@code false} 时调用方会取消事件，这条消息就<b>不会发给服务器</b> ——
     * 这正是"在任意服务器上都能用"的关键：以 {@code !ai} 开头的提问不会出现在别人眼里。
     */
    public boolean onChatMessage(String message) {
        if (!isAiMessage(message)) {
            return true;
        }
        handleAiMessage(message);
        return false;
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
            sendLocalMessage(Minecraft.getInstance(), Lang.tr("mcai.chat.empty"));
            return;
        }

        // #10 离线配方库：如果这是"怎么做 X"而且本地配方表能答，就直接答，不花钱。
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config != null && config.recipeCache) {
            String local = RecipeHelper.answerCraftQuestion(question);
            if (local != null) {
                sendLocalMessage(Minecraft.getInstance(), local);
                return;
            }
        }

        ask(question);
    }

    /**
     * 公开的提问入口：{@code !ai} 聊天、{@code /ai where}、{@code /ai read}、
     * {@code /ai inv}、{@code /ai what} 等命令共用。
     *
     * <p>负责冷却、配置校验、预算拦截、多轮上下文开关和 API Key 轮换，
     * 保证所有入口的行为完全一致。
     */
    public static void ask(String question) {
        ask(question, question, null);
    }

    /**
     * 带回答回调的提问入口（#19 AI 任务、1.25 的准星查询要用它把回答存下来）。
     *
     * @param onReply 收到回答时回调（在后台线程执行）；null 表示不需要
     */
    public static void ask(String question, Consumer<String> onReply) {
        ask(question, question, onReply);
    }

    /**
     * 核心入口：把「发给模型的问题」和「记进多轮上下文的内容」分开。
     *
     * <p><b>为什么要分开：</b>{@code /ai inv} 的"问题"是一整份自动生成的背包清单（几十行）。
     * 如果原样记进多轮上下文，之后<b>每一轮</b>提问都会重新带上那段清单，
     * token 消耗会莫名其妙地涨上去，而玩家完全看不出原因。
     * 所以这类调用传一个短标签当 {@code historyNote}。
     *
     * @param historyNote 记进上下文的内容；null 或空白表示用 {@code question} 本身
     */
    public static void ask(String question, String historyNote, Consumer<String> onReply) {
        Minecraft mc = Minecraft.getInstance();

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
        String cached = ResponseCache.lookup(question, model, config.persona);
        if (cached != null) {
            sendLocalMessage(mc, Lang.tr("mcai.cache.hit"));
            replyToPlayer(mc, cached, 0L, 0.0);
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
        String note = (historyNote == null || historyNote.isBlank()) ? question : historyNote;
        // #8 流式输出：开了就走 SSE，边生成边在 Action Bar 上显示
        if (config.streaming) {
            INSTANCE.sendStreamingRequest(mc, picked, config, model, question, note, onReply);
            return;
        }

        INSTANCE.sendRequest(mc, picked, config, model, question, note, onReply);
    }

    /** 请求真正发出前检查今日预算;超了就拦下并提示。 */
    private static boolean blockedByBudget(Minecraft mc) {
        if (!TokenStats.isOverDailyBudget()) {
            return false;
        }
        sendLocalMessage(mc, Lang.tr("mcai.cmd.budget_blocked"));
        return true;
    }

    // ---------------------------------------------- 1.24 预算预警与花费预估

    /** 今天是否已经就"快超预算"提醒过一次。同一天只提醒一次，避免每问一句都唠叨。 */
    private static volatile LocalDate budgetWarnedDate = null;

    /**
     * 今日花费达到预算的 {@code budget_warn_ratio}（默认 80%）时提醒一次。
     *
     * <p>原来只有"花完了直接拦下"这一道，中途完全没有预警——对按量计费的服务来说，
     * 等你发现的时候钱已经花完了。现在提前提醒，还来得及换个便宜模型或收手。
     */
    private static void warnIfNearBudget(Minecraft mc, ConfigManager.ConfigData config) {
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
        LocalDate today = LocalDate.now();
        if (today.equals(budgetWarnedDate)) {
            return;
        }
        budgetWarnedDate = today;
        sendLocalMessage(mc, Lang.tr("mcai.cmd.budget_warn",
                formatYuan(spent), formatYuan(config.dailyBudgetYuan),
                String.valueOf(Math.round(ratio * 100))));
    }

    /** 发请求前把这句问题的大致花费写一行暗色提示，让玩家在烧钱之前心里有数。 */
    private static void sendEstimate(Minecraft mc, ConfigManager.ConfigData config, String question) {
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
     * <p>1.25 起回答末尾会附上<b>按真实 usage 结算</b>的数字，所以这里的预估值
     * 只承担"发出去之前先拦一下"的作用。
     *
     * @return 元；模型没有单价表时返回 -1
     */
    private static double estimateCostYuan(ConfigManager.ConfigData config, String question) {
        if (config == null) {
            return -1.0;
        }
        PricingCatalog.Quote quote = PricingCatalog.find(config.model);
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

    // ---------------------------------------------- 1.24/1.25 回答展示

    /**
     * 统一的回答展示入口：坐标和「物品名」可变可点击，整段回答本身也可以点一下复制，
     * 末尾附上耗时和这一次的真实花费，最后按配置朗读。
     *
     * <p>聊天和截图两条链路都走这里，保证观感一致。
     *
     * <p><b>点击优先级：</b>整段复制是加在根节点上的样式，坐标/物品段各自带自己的
     * clickEvent。Minecraft 的 {@code MutableComponent} 在 append 时会把父样式作为"底"传给子样式
     * （子节点非空的字段覆盖父节点），所以点坐标仍然复制坐标、点物品名仍然填指令，
     * 只有点在普通文字上才是复制整段回答——不会出现"想复制坐标结果复制了一整篇"。
     *
     * @param elapsedMillis 耗时；&lt;=0 表示不显示耗时（缓存命中时用）
     * @param costYuan      这一次调用按真实 usage 算出的花费；&lt;=0 表示不显示（缓存命中/无单价表）
     */
    private static void replyToPlayer(Minecraft mc, String reply, long elapsedMillis, double costYuan) {
        String flat = reply == null ? "" : reply.replace("\n", " ").trim();

        StringBuilder suffix = new StringBuilder();
        if (elapsedMillis > 0) {
            suffix.append(' ').append(ThinkingIndicator.formatElapsed(elapsedMillis));
        }
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config != null && config.showRequestCost && costYuan > 0.0) {
            suffix.append(' ').append(Lang.tr("mcai.chat.cost", formatYuan(costYuan)));
        }

        Component text = ClickableText.build("§f[AI] " + flat + suffix, flat);
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.getChat().addMessage(text);
            }
        });
        // TTS 默认关闭；开了就顺带念一遍（无障碍用途）
        Speech.speakIfEnabled(flat);
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
     * <p>1.25 起 SSE 的读取循环搬到了 {@link ApiClient#stream}，因为 Gemini 和 Claude 的
     * 分片结构与 OpenAI 不同，而那部分细节属于"协议差异"，应该跟请求体构造放在一起。
     * 这里只负责界面：Action Bar 实时刷新、收完写聊天栏、出错提示。
     */
    private void sendStreamingRequest(Minecraft mc, ApiKeyManager.PickedKey picked,
                                      ConfigManager.ConfigData config, String model, String question,
                                      String historyNote, Consumer<String> onReply) {
        ApiRequest apiRequest = ApiRequest.text(model, SystemPrompt.build(), question,
                HistoryStore.snapshot(), true);

        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();
        final long requestId = com.example.mcai.util.RequestController.begin();
        com.example.mcai.util.RequestController.setCancelHook(thinking::finish);

        STREAM_EXECUTOR.execute(() -> {
            try {
                ApiProvider provider = ApiProvider.detect(config.apiProvider, config.apiUrl);
                ApiClient.Options options = ApiClient.Options.of(
                        config.apiUrl, picked.key(), config.retryMax, REQUEST_TIMEOUT);
                ApiClient.StreamResult result = ApiClient.stream(provider, options, apiRequest,
                        () -> com.example.mcai.util.RequestController.isCurrent(requestId),
                        com.example.mcai.client.StreamingHud::update);

                long elapsedMillis = thinking.finish();
                com.example.mcai.client.StreamingHud.clear();

                // 被取消或被新请求顶掉 → 丢掉已经收到的那半截回答
                if (!com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    return;
                }
                ApiKeyManager.recordResult(picked, 200);
                finishStreaming(mc, question, historyNote, result, elapsedMillis, onReply);
            } catch (ApiClient.ApiException e) {
                thinking.finish();
                com.example.mcai.client.StreamingHud.clear();
                if (com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    reportFailure(mc, picked, question, e);
                }
            } catch (Throwable t) {
                thinking.finish();
                com.example.mcai.client.StreamingHud.clear();
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
    private void finishStreaming(Minecraft mc, String question, String historyNote,
                                 ApiClient.StreamResult result,
                                 long elapsedMillis, Consumer<String> onReply) {
        double cost = 0.0;
        if (result.usage() != null) {
            // 把归一化后的 usage 重新包成 OpenAI 形状的根，好复用同一套统计逻辑
            JsonObject usageRoot = new JsonObject();
            usageRoot.add("usage", result.usage());
            cost = TokenStats.recordFrom(usageRoot, "chat");
        }

        String reply = result.content() == null ? "" : result.content().trim();
        if (reply.isEmpty()) {
            reply = Lang.tr("mcai.reply.empty");
        }

        // 先补上思维链，再发答案——和非流式链路的顺序保持一致
        ClientChat.sendReasoningText(result.reasoning());

        // 记进上下文的是 historyNote（可能是短标签），不是那份可能很长的原始问题
        HistoryStore.remember(historyNote, reply);
        storeInCache(question, reply);
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不影响显示
            }
        }

        replyToPlayer(mc, reply, elapsedMillis, cost);
    }

    /** 1.24：把成功的问答写进本地缓存，下次问同样的问题就不再花钱。 */
    private static void storeInCache(String question, String reply) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return;
        }
        ResponseCache.store(question, config.model, config.persona, reply);
    }

    // ------------------------------------------------------------ 非流式

    private void sendRequest(Minecraft mc, ApiKeyManager.PickedKey picked,
                             ConfigManager.ConfigData config, String model, String question,
                             String historyNote, Consumer<String> onReply) {
        ApiRequest apiRequest = ApiRequest.text(model, SystemPrompt.build(), question,
                HistoryStore.snapshot(), false);

        // 请求已合法构建，从这里开始计时并启动"思考中"提示
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        // 1.24 可取消请求：领一个 id，登记"取消时立刻停掉思考动画"
        final long requestId = com.example.mcai.util.RequestController.begin();
        com.example.mcai.util.RequestController.setCancelHook(thinking::finish);

        HTTP_EXECUTOR.execute(() -> {
            try {
                ApiProvider provider = ApiProvider.detect(config.apiProvider, config.apiUrl);
                ApiClient.Options options = ApiClient.Options.of(
                        config.apiUrl, picked.key(), config.retryMax, REQUEST_TIMEOUT);
                JsonObject root = ApiClient.send(provider, options, apiRequest,
                        () -> com.example.mcai.util.RequestController.isCurrent(requestId));

                long elapsedMillis = thinking.finish();

                // 玩家按 ESC 取消了（或被新请求顶掉）→ 静默收场，绝不弹出"幽灵回答"
                if (!com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    return;
                }
                ApiKeyManager.recordResult(picked, 200);
                handleSuccess(mc, root, question, historyNote, elapsedMillis, onReply);
            } catch (ApiClient.ApiException e) {
                thinking.finish();
                if (com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    reportFailure(mc, picked, question, e);
                }
            } catch (Throwable t) {
                thinking.finish();
                if (com.example.mcai.util.RequestController.isCurrent(requestId)) {
                    sendLocalMessage(mc, "§c" + question
                            + " (" + Lang.tr("mcai.error.response", rootMessage(t)) + ")");
                }
            } finally {
                // 幂等兜底：即使上面某条路径漏了，也保证动画一定会停
                thinking.finish();
                com.example.mcai.util.RequestController.finish(requestId);
            }
        });
    }

    /**
     * 把 {@link ApiClient.ApiException} 翻译成给玩家看的提示。
     *
     * <p>三种情况分开说，因为玩家的下一步动作完全不同：
     * 服务端返回错误码 → 看错误说明；地址非法 → 去改配置；网络层失败 → 多半是网络问题。
     */
    private static void reportFailure(Minecraft mc, ApiKeyManager.PickedKey picked,
                                      String question, ApiClient.ApiException error) {
        if (error.cancelled) {
            // 玩家自己取消的，不要弹"错误"
            return;
        }
        if (error.status > 0) {
            ApiKeyManager.recordResult(picked, error.status);
            handleHttpError(mc, question, error.status);
        } else if (error.status == 0) {
            sendLocalMessage(mc, "§c" + question + " (" + Lang.tr("mcai.error.code0") + ")");
        } else {
            sendLocalMessage(mc, "§c" + question
                    + " (" + Lang.tr("mcai.error.network", error.getMessage()) + ")");
        }
    }

    private void handleSuccess(Minecraft mc, JsonObject root, String question,
                               String historyNote, long elapsedMillis, Consumer<String> onReply) {
        if (root == null) {
            sendLocalMessage(mc, Lang.tr("mcai.error.unparsable"));
            return;
        }

        // Token 统计：与截图识别共用同一套跨天清零逻辑，并写一条使用明细。
        // 1.25：返回值是这一次按真实 token 算出的花费，直接显示在回答末尾。
        double cost = TokenStats.recordFrom(root, "chat");

        // 先输出思维链、再输出答案：这样答案永远落在聊天栏最底部，最靠近输入框
        ClientChat.sendReasoning(root);

        String reply = AiResponseParser.extractContent(root);
        if (reply == null || reply.isBlank()) {
            reply = Lang.tr("mcai.reply.empty");
        }

        // 多轮上下文：这次问答写进内存历史（本地存储，不上传）。
        // 写的 historyNote 而非 question —— 见 ask(...) 的说明。
        HistoryStore.remember(historyNote, reply);
        // 1.24：顺便进本地缓存（缓存键仍然用完整问题，因为背包内容变了答案也该变）
        storeInCache(question, reply);

        // 调用方的回调（#19 用它把回答存成任务）
        if (onReply != null) {
            try {
                onReply.accept(reply);
            } catch (Throwable ignored) {
                // 回调失败不能影响正常显示
            }
        }

        replyToPlayer(mc, reply, elapsedMillis, cost);
    }

    private static void handleHttpError(Minecraft mc, String question, int statusCode) {
        sendLocalMessage(mc, "§c" + question + " (" + statusCode + ": "
                + com.example.mcai.util.HttpErrorCatalog.describe(statusCode) + ")");
    }

    private static void sendLocalMessage(Minecraft mc, String text) {
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.getChat().addMessage(Component.literal(text));
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
