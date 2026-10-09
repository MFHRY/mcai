package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.ChatHandler;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.PersonaCatalog;
import com.example.mcai.util.PricingCatalog;
import com.example.mcai.util.RecipeHelper;
import com.example.mcai.util.TextReader;
import com.example.mcai.util.TokenStats;
import com.example.mcai.util.UsageLog;
import com.example.mcai.util.VisionResolution;
import com.example.mcai.util.WorldContext;
import com.example.mcai.vision.VisionHandler;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.time.LocalDate;

/**
 * {@code /ai} 客户端指令。
 *
 * <p><b>1.21.1 的坑：</b>{@code StringArgumentType.word()} 只接受 ASCII 字母数字。
 * 所以分辨率的<b>标准值</b>本身就是 ASCII（{@code 360p / 720p / 1080p / original}），
 * 把它们注册成 brigadier 的 <b>literal 节点</b>，既自带 Tab 补全，也不用去碰
 * {@code word()} 的字符限制。中文写法 {@code 原始} 作为历史别名额外注册一个节点，
 * 用「不缩放」而不用中文，是为了让 {@code mcai.json} 里存的值和语言无关。
 *
 * <p>指令是纯客户端的，改完配置由 ConfigManager 异步落盘，不会卡主线程。
 * 所有回显文案走语言文件。
 */
public final class AiCommand {

    /** brigadier 的自定义高度参数名。保持 ASCII 且与语言无关，理由见 buildResolutionNode。 */
    private static final String HEIGHT_ARG = "height";

    /**
     * 待打开的界面。
     *
     * <p><b>为什么要延迟到下一个 tick：</b>客户端指令是<b>在聊天界面里</b>执行的，
     * 而聊天界面执行完指令之后才会自己调用 {@code setScreen(null)} 关闭。
     * 如果我们在指令里直接 {@code setScreen(新界面)}，那一句会被紧随其后的关闭动作覆盖，
     * 玩家看到的就是"输入 /ai config 之后毫无反应"。
     * 所以这里只登记一个意图，等下一个 tick（聊天界面已经关掉了）再去真正打开。
     */
    private static Runnable pendingScreen = null;

    private AiCommand() {}

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        // 每个 tick 检查一次"有没有界面要打开"
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Runnable action = pendingScreen;
            if (action != null) {
                pendingScreen = null;
                action.run();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root =
                    ClientCommandManager.literal("ai");

            root.then(ClientCommandManager.literal("status")
                    .executes(context -> status(context.getSource())));

            root.then(ClientCommandManager.literal("token")
                    .executes(context -> token(context.getSource())));

            // #11 花费明细:今日花了多少钱 + 是否超预算
            root.then(ClientCommandManager.literal("cost")
                    .executes(context -> cost(context.getSource())));

            // #7 清空本地内存里的多轮上下文
            root.then(ClientCommandManager.literal("clear")
                    .executes(context -> {
                        HistoryStore.clear();
                        context.getSource().sendFeedback(
                                Text.literal(Lang.tr("mcai.cmd.clear_done")));
                        return 1;
                    }));

            // #9 人格预设
            root.then(buildPersonaNode());

            // #17 最近 7 天用量曲线
            root.then(ClientCommandManager.literal("chart")
                    .executes(context -> chart(context.getSource())));

            // #3 地理顾问:把当前坐标/群系/光照/维度发给 AI
            root.then(ClientCommandManager.literal("where")
                    .executes(context -> where(context.getSource(), null))
                    .then(ClientCommandManager.argument("question", StringArgumentType.greedyString())
                            .executes(context -> where(context.getSource(),
                                    StringArgumentType.getString(context, "question")))));

            // #5 翻译眼前的告示牌 / 手中的成书(纯文本,不烧图片 token)
            root.then(ClientCommandManager.literal("read")
                    .executes(context -> read(context.getSource())));

            // #16 无障碍:把屏幕上有什么念给玩家听
            root.then(ClientCommandManager.literal("describe")
                    .executes(context -> {
                        VisionHandler.trigger(Lang.tr("mcai.vision.prompt.describe"));
                        return 1;
                    }));

            // #10 配方：不带参数=列出你现在能做的；带名字=查那样东西怎么做
            root.then(ClientCommandManager.literal("craft")
                    .executes(context -> craft(context.getSource(), null))
                    .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                            .executes(context -> craft(context.getSource(),
                                    StringArgumentType.getString(context, "item")))));

            // #19 AI 任务:按背包内容生成任务 / 查看 / 清除
            root.then(ClientCommandManager.literal("task")
                    .executes(context -> taskGenerate(context.getSource()))
                    .then(ClientCommandManager.literal("show")
                            .executes(context -> taskShow(context.getSource())))
                    .then(ClientCommandManager.literal("clear")
                            .executes(context -> taskClear(context.getSource()))));

            root.then(ClientCommandManager.literal("help")
                    .executes(context -> {
                        StartupGuide.show();
                        return 1;
                    }));

            // 打开游戏内设置窗口（填写 API Key 等）
            root.then(ClientCommandManager.literal("config")
                    .executes(context -> {
                        // 不能在这里直接 setScreen：聊天界面稍后会把自己关掉并覆盖掉它。
                        // 登记到下一个 tick 再打开，见 pendingScreen 的说明。
                        pendingScreen = () -> MinecraftClient.getInstance().setScreen(new ConfigScreen(null));
                        return 1;
                    }));

            // ---- 1.20 开关：让新功能不用手改 mcai.json 也能开 ----
            root.then(ClientCommandManager.literal("death")
                    .then(ClientCommandManager.literal("on").executes(c -> setFlag(c.getSource(), "death", true)))
                    .then(ClientCommandManager.literal("off").executes(c -> setFlag(c.getSource(), "death", false))));

            root.then(ClientCommandManager.literal("streaming")
                    .then(ClientCommandManager.literal("on").executes(c -> setFlag(c.getSource(), "streaming", true)))
                    .then(ClientCommandManager.literal("off").executes(c -> setFlag(c.getSource(), "streaming", false))));

            root.then(ClientCommandManager.literal("recipe")
                    .then(ClientCommandManager.literal("on").executes(c -> setFlag(c.getSource(), "recipe", true)))
                    .then(ClientCommandManager.literal("off").executes(c -> setFlag(c.getSource(), "recipe", false))));

            root.then(ClientCommandManager.literal("budget")
                    .then(ClientCommandManager.argument("yuan",
                                    com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0))
                            .executes(c -> setBudget(c.getSource(),
                                    com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "yuan")))));

            root.then(ClientCommandManager.literal("history")
                    .then(ClientCommandManager.argument("turns",
                                    com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 20))
                            .executes(c -> setHistory(c.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "turns")))));

            root.then(ClientCommandManager.literal("discord")
                    .then(ClientCommandManager.literal("clear")
                            .executes(c -> setDiscord(c.getSource(), "")))
                    .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                            .executes(c -> setDiscord(c.getSource(),
                                    StringArgumentType.getString(c, "url")))));

            root.then(buildResolutionNode());

            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildResolutionNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> node =
                ClientCommandManager.literal("resolution");

        // 四个标准值做成 literal，自带 Tab 补全
        for (String option : VisionResolution.OPTIONS) {
            node.then(ClientCommandManager.literal(option)
                    .executes(context -> setResolution(context.getSource(), option)));
        }

        // 旧版中文写法（"原始" / "原图"）继续接受，落到同一个处理逻辑
        for (String legacy : VisionResolution.LEGACY_ORIGINAL) {
            node.then(ClientCommandManager.literal(legacy)
                    .executes(context -> setResolution(context.getSource(), legacy)));
        }

        // 自定义高度（ASCII），例如 /ai resolution 480p
        //
        // 参数名故意用 ASCII 常量而不是 Lang.tr(...)：brigadier 的参数名在**注册时**
        // 就固定下来了，而 Lang.tr 取的是**执行时**的语言；两者一旦不一致（玩家中途
        // 切换语言，或者客户端初始化时语言资源还没加载）就会让 getString 找不到参数
        // 而抛 IllegalArgumentException。参数名只出现在 brigadier 的报错里，不值得冒这个险。
        node.then(ClientCommandManager.argument(HEIGHT_ARG, StringArgumentType.word())
                .executes(context -> setResolution(context.getSource(),
                        StringArgumentType.getString(context, HEIGHT_ARG))));

        return node;
    }

    // ------------------------------------------------------------------ status

    private static int status(FabricClientCommandSource source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            source.sendError(Text.literal(Lang.tr("mcai.cmd.status_not_ready")));
            return 0;
        }

        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_title")));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_model",
                ModelCatalog.describe(config.model)
                        + (ModelCatalog.lookup(config.model).supportsVision()
                        ? Lang.tr("mcai.cmd.vision_ok")
                        : Lang.tr("mcai.cmd.vision_no")))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_mode",
                ModeCatalog.describe(config.mode))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_resolution",
                VisionResolution.label(config.visionResolution),
                describeTarget(config.visionResolution))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_tokens",
                TokenStats.todayTotal(), LocalDate.now())));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_reasoning",
                Lang.tr(config.showReasoning ? "mcai.cmd.reasoning_on" : "mcai.cmd.reasoning_off"),
                config.reasoningMaxChars == 0
                        ? Lang.tr("mcai.cmd.reasoning_all")
                        : Lang.tr("mcai.cmd.reasoning_chars", config.reasoningMaxChars))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_url", config.apiUrl)));
        if (LocalProvider.isLocal(config.apiUrl)) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_local")));
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_key", maskKey(config.apiKey))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_cost",
                formatYuan(TokenStats.todayCost()), budgetLabel(config))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_persona",
                PersonaCatalog.describe(config.persona))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_history",
                Math.max(0, config.historyTurns))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_hint")));
        return 1;
    }

    /** 今日预算的说明文字(未启用/剩余多少)。 */
    private static String budgetLabel(ConfigManager.ConfigData config) {
        if (config.dailyBudgetYuan <= 0) {
            return Lang.tr("mcai.cmd.budget_off");
        }
        double remain = Math.max(0.0, config.dailyBudgetYuan - TokenStats.todayCost());
        return Lang.tr("mcai.cmd.budget_remain", formatYuan(remain));
    }

    /** 元,保留 2 位小数。 */
    private static String formatYuan(double yuan) {
        return String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.0, yuan));
    }

    // ------------------------------------------------------------------- token

    private static int token(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.token_title")));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.token_today",
                LocalDate.now(), TokenStats.todayTotal())));

        // 明细从 CSV 异步读，读完再补发到聊天栏
        UsageLog.readRecentAsync(8).whenComplete((entries, error) -> {
            if (error != null) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_read_failed", error.getMessage()));
                return;
            }
            if (entries == null || entries.isEmpty()) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_empty", UsageLog.displayPath()));
                return;
            }

            ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_recent", entries.size()));
            for (UsageLog.Entry entry : entries) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_entry",
                        entry.displayTime(), entry.tokens(),
                        sourceLabel(entry.source()), entry.model()));
            }
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_full", UsageLog.displayPath()));
        });
        return 1;
    }

    // ------------------------------------------------------------------- cost

    private static int cost(FabricClientCommandSource source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_title")));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_today",
                LocalDate.now(), formatYuan(TokenStats.todayCost()))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_tokens",
                TokenStats.todayTotal())));

        PricingCatalog.Quote quote = PricingCatalog.find(config == null ? null : config.model);
        if (quote == null) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_unknown",
                    config == null ? "?" : config.model)));
        } else {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_rate",
                    formatYuan(quote.inputPerMillion()), formatYuan(quote.outputPerMillion()),
                    Lang.tr(PricingCatalog.isPeakHour()
                            ? "mcai.cmd.rate_peak" : "mcai.cmd.rate_off"))));
        }

        if (config != null && config.dailyBudgetYuan > 0) {
            if (TokenStats.isOverDailyBudget()) {
                source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.budget_over",
                        formatYuan(config.dailyBudgetYuan))));
            } else {
                source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.budget_remain",
                        formatYuan(config.dailyBudgetYuan - TokenStats.todayCost()))));
            }
        } else {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.budget_off")));
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.cost_note")));
        return 1;
    }

    // ---------------------------------------------------------------- persona

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildPersonaNode() {
        LiteralArgumentBuilder<FabricClientCommandSource> node =
                ClientCommandManager.literal("persona");
        for (String id : PersonaCatalog.ids()) {
            node.then(ClientCommandManager.literal(id)
                    .executes(context -> setPersona(context.getSource(), id)));
        }
        return node;
    }

    private static int setPersona(FabricClientCommandSource source, String id) {
        ConfigManager.getInstance().update(config -> {
            config.persona = id;
            java.util.List<String> list = new java.util.ArrayList<>(
                    config.availablePersonas == null ? java.util.List.of() : config.availablePersonas);
            if (!list.contains(id)) {
                list.add(id);
            }
            config.availablePersonas = java.util.List.copyOf(list);
        });
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.persona_set",
                PersonaCatalog.describe(id))));
        return 1;
    }

    // ----------------------------------------------------------------- chart

    /** #17 最近 7 天用量柱状图（纯文字，聊天栏直接显示）。 */
    private static int chart(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.chart_title")));
        UsageLog.dailyTotalsAsync(7).whenComplete((days, error) -> {
            if (error != null || days == null || days.isEmpty()) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.token_read_failed",
                        error == null ? "?" : String.valueOf(error.getMessage())));
                return;
            }
            int max = 0;
            for (UsageLog.DayTotal d : days) {
                max = Math.max(max, d.tokens());
            }
            if (max <= 0) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.chart_empty"));
                return;
            }
            for (UsageLog.DayTotal d : days) {
                int bars = (int) Math.round(d.tokens() * 20.0 / max);
                if (d.tokens() > 0 && bars < 1) {
                    bars = 1; // 有记录就至少给一根，别显示成空
                }
                String bar = "§a" + "|".repeat(bars);
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.chart_row",
                        d.date().substring(5), bar, d.tokens(), formatYuan(d.cost())));
            }
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.chart_note", formatYuan(TokenStats.todayCost())));
        });
        return 1;
    }

    // ------------------------------------------------------------------ where

    /** #3 地理顾问。 */
    private static int where(FabricClientCommandSource source, String question) {
        String prompt = WorldContext.asPrompt(question);
        if (WorldContext.snapshot() == null) {
            source.sendError(Text.literal(Lang.tr("mcai.ctx.not_in_world")));
            return 0;
        }
        ChatHandler.ask(prompt);
        return 1;
    }

    // ------------------------------------------------------------------- read

    /** #5 读取眼前的告示牌或手中的成书并请 AI 翻译。 */
    private static int read(FabricClientCommandSource source) {
        TextReader.Found found = TextReader.read();
        if (found == null) {
            source.sendError(Text.literal(Lang.tr("mcai.read.not_found")));
            return 0;
        }
        // 文字原样附在提问后面,让模型翻译。纯文本请求,比截图便宜得多。
        String prompt = Lang.tr("mcai.read.ask", found.source()) + "\n\n" + found.text();
        ChatHandler.ask(prompt);
        return 1;
    }

    // ------------------------------------------------------------------ craft

    /**
     * #10 配方查询。纯本地计算，不花 token。
     *
     * @param query null 表示列出"现在材料够做的"；否则按名字查那样东西的配方
     */
    private static int craft(FabricClientCommandSource source, String query) {
        if (query != null && !query.isBlank()) {
            return craftLookup(source, query.trim());
        }

        java.util.List<RecipeHelper.Craftable> list = RecipeHelper.craftableNow();
        // 打进日志：万一结果不对，从日志就能看出是扫描失败还是配方确实不匹配
        com.example.mcai.McaiMod.LOGGER.info("mcAI /ai craft: {} craftable recipe(s) found", list.size());
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_title")));
        if (list.isEmpty()) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_empty")));
            return 1;
        }
        int shown = 0;
        for (RecipeHelper.Craftable item : list) {
            if (shown >= 12) {
                source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_more",
                        list.size() - shown)));
                break;
            }
            String ingredients = item.ingredients().isEmpty()
                    ? Lang.tr("mcai.craft.unknown_ingredients")
                    : String.join(Lang.tr("mcai.craft.separator"), item.ingredients());
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_entry",
                    item.count() > 1 ? item.output() + " x" + item.count() : item.output(),
                    ingredients)));
            shown++;
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_note")));
        return 1;
    }

    /** 按名字查配方（不需要背包里有材料）。 */
    private static int craftLookup(FabricClientCommandSource source, String query) {
        java.util.List<net.minecraft.recipe.RecipeEntry<?>> found = RecipeHelper.findByName(query);
        com.example.mcai.McaiMod.LOGGER.info("mcAI /ai craft {}: {} recipe(s) matched",
                query, found.size());
        if (found.isEmpty()) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_lookup_none", query)));
            return 1;
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_lookup_title", query)));
        for (net.minecraft.recipe.RecipeEntry<?> entry : found) {
            source.sendFeedback(Text.literal("§7 " + RecipeHelper.describe(entry)));
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.craft_note")));
        return 1;
    }

    // ------------------------------------------------------------------- task

    /** #19 按背包内容生成任务。 */
    private static int taskGenerate(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.task_generating")));
        // 回答回来后存成任务；ask 内部会处理冷却、预算、错误提示
        ChatHandler.ask(TaskTracker.buildTaskPrompt(), reply -> {
            TaskTracker.set(reply);
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.task_saved"));
        });
        return 1;
    }

    private static int taskShow(FabricClientCommandSource source) {
        String task = TaskTracker.current();
        if (task == null) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.task_none")));
            return 1;
        }
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.task_title")));
        for (String line : task.split("\n")) {
            if (!line.isBlank()) {
                source.sendFeedback(Text.literal("§7 " + line.trim()));
            }
        }
        return 1;
    }

    private static int taskClear(FabricClientCommandSource source) {
        TaskTracker.clear();
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.task_cleared")));
        return 1;
    }

    // ------------------------------------------------------- 1.20 开关指令

    /**
     * 切换一个布尔设置。用一个字符串 id 分派，避免为每个开关重复写一遍指令节点。
     */
    private static int setFlag(FabricClientCommandSource source, String id, boolean value) {
        String label;
        ConfigManager.getInstance().update(config -> {
            switch (id) {
                case "death" -> config.deathRecap = value;
                case "streaming" -> config.streaming = value;
                case "recipe" -> config.recipeCache = value;
                default -> { }
            }
        });
        label = Lang.tr("mcai.cmd.flag_" + id);
        source.sendFeedback(Text.literal(Lang.tr(value
                ? "mcai.cmd.flag_on" : "mcai.cmd.flag_off", label)));
        return 1;
    }

    /** 设置每日预算（元）；0 表示不限制。 */
    private static int setBudget(FabricClientCommandSource source, double yuan) {
        double safe = Math.max(0.0, yuan);
        ConfigManager.getInstance().update(config -> config.dailyBudgetYuan = safe);
        if (safe <= 0.0) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.budget_cleared")));
        } else {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.budget_set", formatYuan(safe))));
        }
        return 1;
    }

    /** 设置保留几轮上下文；0 表示关闭多轮。 */
    private static int setHistory(FabricClientCommandSource source, int turns) {
        int safe = Math.max(0, Math.min(20, turns));
        ConfigManager.getInstance().update(config -> config.historyTurns = safe);
        HistoryStore.setMaxTurns(safe);
        if (safe == 0) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.history_off")));
        } else {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.history_set", safe)));
        }
        return 1;
    }

    /** 设置/清除 Discord webhook。 */
    private static int setDiscord(FabricClientCommandSource source, String url) {
        String value = url == null ? "" : url.trim();
        if (!value.isEmpty() && !value.startsWith("https://")) {
            source.sendError(Text.literal(Lang.tr("mcai.cmd.discord_invalid")));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.discordWebhook = value);
        if (value.isEmpty()) {
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.discord_cleared")));
        } else {
            // 不把完整 URL 打在聊天栏里，避免直播/截图泄露（和 API Key 一样的处理）
            String masked = value.length() <= 40 ? value : value.substring(0, 40) + "…";
            source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.discord_set", masked)));
        }
        return 1;
    }

    // -------------------------------------------------------------- resolution

    private static int setResolution(FabricClientCommandSource source, String raw) {
        if (!VisionResolution.isUsable(raw)) {            source.sendError(Text.literal(Lang.tr("mcai.cmd.resolution_invalid",
                    raw, String.join(" / ", VisionResolution.OPTIONS))));
            return 0;
        }

        String normalized = VisionResolution.normalize(raw);
        ConfigManager.getInstance().update(config -> config.visionResolution = normalized);

        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.resolution_set",
                VisionResolution.label(normalized), describeTarget(normalized))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.resolution_saved")));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    /** 把分辨率配置翻译成一句人话。 */
    private static String describeTarget(String resolution) {
        int height = VisionResolution.targetHeight(resolution);
        if (height <= 0) {
            return Lang.tr("mcai.cmd.target_original");
        }
        return Lang.tr("mcai.cmd.target_height", height);
    }

    /** CSV 里存的是 ASCII 的 chat / vision，展示时翻成当前语言。 */
    private static String sourceLabel(String source) {
        if (source == null) {
            return Lang.tr("mcai.value.unknown");
        }
        return switch (source.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "chat" -> Lang.tr("mcai.source.chat");
            case "vision" -> Lang.tr("mcai.source.vision");
            default -> source;
        };
    }

    /** 聊天栏里不要把完整 key 打出来，避免直播 / 截图泄露。 */
    private static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Lang.tr("mcai.cmd.key_missing");
        }
        if (apiKey.length() <= 10) {
            return "****";
        }
        return apiKey.substring(0, 7) + "…" + apiKey.substring(apiKey.length() - 4);
    }
}
