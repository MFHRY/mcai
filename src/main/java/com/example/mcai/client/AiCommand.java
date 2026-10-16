package com.example.mcai.client;

import com.example.mcai.ConfigManager;
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
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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

    /**
     * 由 {@code McaiModClient} 的统一 tick 分发器调用：把"待打开的界面"落地。
     *
     * <p>为什么不能在指令里直接 {@code setScreen}：客户端指令<b>是在聊天界面里</b>
     * 执行的，聊天界面执行完还会自己 {@code setScreen(null)}，会把我们刚开的界面覆盖掉。
     * 所以这里只登记意图，等下一个 tick 再真正打开。
     */
    public static void onClientTick(Minecraft client) {
        Runnable action = pendingScreen;
        if (action != null) {
            pendingScreen = null;
            action.run();
        }
    }

    /**
     * 注册 {@code /ai} 全部子指令。
     *
     * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 通过
     * {@code ClientCommandRegistrationCallback} 把 dispatcher 交给我们；Forge 的
     * {@code RegisterClientCommandsEvent} 直接给 dispatcher，签名就是下面这样。
     * 两边都是<b>原生客户端指令</b>，不需要 mixin，也不会要求服务端装模组。
     */
    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("ai");

        root.then(Commands.literal("status")
                .executes(context -> status(context.getSource())));

        root.then(Commands.literal("token")
                .executes(context -> token(context.getSource())));

        // #11 花费明细:今日花了多少钱 + 是否超预算
        root.then(Commands.literal("cost")
                .executes(context -> cost(context.getSource())));

        // #7 清空本地内存里的多轮上下文
        root.then(Commands.literal("clear")
                .executes(context -> {
                    HistoryStore.clear();
                    feedback(context.getSource(), 
                            Component.literal(Lang.tr("mcai.cmd.clear_done")));
                    return 1;
                }));

        // #9 人格预设
        root.then(buildPersonaNode());

        // #17 最近 7 天用量曲线
        root.then(Commands.literal("chart")
                .executes(context -> chart(context.getSource())));

        // #3 地理顾问:把当前坐标/群系/光照/维度发给 AI
        root.then(Commands.literal("where")
                .executes(context -> where(context.getSource(), null))
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(context -> where(context.getSource(),
                                StringArgumentType.getString(context, "question")))));

        // #5 翻译眼前的告示牌 / 手中的成书(纯文本,不烧图片 token)
        root.then(Commands.literal("read")
                .executes(context -> read(context.getSource())));

        // #16 无障碍:把屏幕上有什么念给玩家听
        root.then(Commands.literal("describe")
                .executes(context -> {
                    VisionHandler.trigger(Lang.tr("mcai.vision.prompt.describe"));
                    return 1;
                }));

        // #10 配方：不带参数=列出你现在能做的；带名字=查那样东西怎么做
        root.then(Commands.literal("craft")
                .executes(context -> craft(context.getSource(), null))
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .executes(context -> craft(context.getSource(),
                                StringArgumentType.getString(context, "item")))));

        // #19 AI 任务:按背包内容生成任务 / 查看 / 清除
        root.then(Commands.literal("task")
                .executes(context -> taskGenerate(context.getSource()))
                .then(Commands.literal("show")
                        .executes(context -> taskShow(context.getSource())))
                .then(Commands.literal("clear")
                        .executes(context -> taskClear(context.getSource()))));

        root.then(Commands.literal("help")
                .executes(context -> {
                    StartupGuide.show();
                    return 1;
                }));

        // 打开游戏内设置窗口（填写 API Key 等）
        root.then(Commands.literal("config")
                .executes(context -> {
                    // 不能在这里直接 setScreen：聊天界面稍后会把自己关掉并覆盖掉它。
                    // 登记到下一个 tick 再打开，见 pendingScreen 的说明。
                    pendingScreen = () -> Minecraft.getInstance().setScreen(new ConfigScreen(null));
                    return 1;
                }));

        // ---- 1.20 开关：让新功能不用手改 mcai.json 也能开 ----
        root.then(Commands.literal("death")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "death", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "death", false))));

        root.then(Commands.literal("streaming")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "streaming", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "streaming", false))));

        root.then(Commands.literal("recipe")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "recipe", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "recipe", false))));

        // ---- 1.24 新增开关 ----
        // 发送前预览（默认关闭：多一步确认会打断连续操作）
        root.then(Commands.literal("preview")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "preview", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "preview", false))));

        // 隐私遮罩（发图前把聊天栏那块涂黑）
        root.then(Commands.literal("mask")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "mask", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "mask", false))));

        // 系统语音朗读
        root.then(Commands.literal("tts")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "tts", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "tts", false))));

        // 危险预警（低血量 + 附近有怪时自动求助）
        root.then(Commands.literal("danger")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "danger", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "danger", false))));

        // 环境感知（把游戏版本和模组列表告诉 AI）
        root.then(Commands.literal("env")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "env", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "env", false))));

        // 相同问题本地缓存
        root.then(Commands.literal("cache")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "cache", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "cache", false)))
                .then(Commands.literal("clear")
                        .executes(c -> clearCache(c.getSource()))));

        // 中止正在进行的请求（键盘上的 ESC 是同一个功能）
        root.then(Commands.literal("cancel")
                .executes(c -> cancelRequest(c.getSource())));

        // 让系统语音念一段文字
        root.then(Commands.literal("speak")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> speak(c.getSource(),
                                StringArgumentType.getString(c, "text")))));

        // 长期记忆：让 AI 一直记住某些事实
        root.then(Commands.literal("remember")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> remember(c.getSource(),
                                StringArgumentType.getString(c, "text")))));
        root.then(Commands.literal("memories")
                .executes(c -> listMemories(c.getSource())));
        root.then(Commands.literal("forget")
                .then(Commands.literal("all")
                        .executes(c -> forget(c.getSource(), null)))
                .then(Commands.argument("index",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 999))
                        .executes(c -> forget(c.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "index")))));

        // ---- 1.25 新增 ----
        // 物品栏分析：把背包清单整理成文字交给 AI。
        // 故意不发截图：同样信息的文字清单约 60~120 token，截图要几千个视觉 token。
        root.then(Commands.literal("inv")
                .executes(c -> inventory(c.getSource(), null))
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(c -> inventory(c.getSource(),
                                StringArgumentType.getString(c, "question")))));

        // 准星查询：看准一个方块/生物，问"这是什么"（等同于按键 V）
        root.then(Commands.literal("what")
                .executes(c -> crosshair(c.getSource())));

        // 鲸鱼娘挂件开关 + 位置编辑 + 指定头像图（缩放等在 /ai config 里改）
        root.then(Commands.literal("avatar")
                .then(Commands.literal("on").executes(c -> setFlag(c.getSource(), "avatar", true)))
                .then(Commands.literal("off").executes(c -> setFlag(c.getSource(), "avatar", false)))
                .then(Commands.literal("edit").executes(c -> editAvatar(c.getSource())))
                .then(Commands.literal("image")
                        .executes(c -> setAvatarImage(c.getSource(), ""))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(c -> setAvatarImage(c.getSource(),
                                        StringArgumentType.getString(c, "path"))))));

        // API 协议：默认 auto（按 api_url 猜），也可以强制指定
        root.then(Commands.literal("provider")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(PROVIDER_SUGGESTIONS)
                        .executes(c -> setProvider(c.getSource(),
                                StringArgumentType.getString(c, "name")))));

        // 失败自动重试次数（只对网络异常和 429/5xx 生效）
        root.then(Commands.literal("retry")
                .then(Commands.argument("count",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 5))
                        .executes(c -> setRetry(c.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "count")))));

        root.then(Commands.literal("budget")
                .then(Commands.argument("yuan",
                                com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0))
                        .executes(c -> setBudget(c.getSource(),
                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "yuan")))));

        root.then(Commands.literal("history")
                .then(Commands.argument("turns",
                                com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 20))
                        .executes(c -> setHistory(c.getSource(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "turns")))));

        root.then(Commands.literal("discord")
                .then(Commands.literal("clear")
                        .executes(c -> setDiscord(c.getSource(), "")))
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(c -> setDiscord(c.getSource(),
                                StringArgumentType.getString(c, "url")))));

        root.then(buildResolutionNode());

        // 触发词前缀（把 !ai 改成别的，聊天栏用新前缀提问）
        root.then(Commands.literal("prefix")
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(context -> setPrefix(context.getSource(),
                                StringArgumentType.getString(context, "value")))));

        // 翻译：/ai tl <文本>（翻成默认目标语言）或 /ai tl <语言> <文本>
        root.then(buildTranslateNode("tl"));
        root.then(buildTranslateNode("translate"));

        // 默认翻译目标语言
        root.then(Commands.literal("tlang")
                .then(Commands.argument("lang", StringArgumentType.greedyString())
                        .executes(context -> setTranslateLang(context.getSource(),
                                StringArgumentType.getString(context, "lang")))));

        dispatcher.register(root);
    }

    // -------------------------------------------------- 回显助手（Forge 专用）

    /**
     * 发一条普通回显。
     *
     * <p>Fabric 的 {@code CommandSource.sendFeedback(Text)} 在 Forge 里叫
     * {@code sendSuccess(Supplier<Component>, boolean)} —— 多了一层 Supplier，
     * 而且多一个"是否广播给其他管理员"的参数。包成这两个小助手之后，
     * 下面 80 多处调用点只要换前缀，不用去动括号配对。
     */
    private static void feedback(CommandSourceStack source, Component text) {
        source.sendSuccess(() -> text, false);
    }

    /** 发一条错误回显。{@code sendError} 在 Forge 里叫 {@code sendFailure}。 */
    private static void error(CommandSourceStack source, Component text) {
        source.sendFailure(text);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildResolutionNode() {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal("resolution");

        // 四个标准值做成 literal，自带 Tab 补全
        for (String option : VisionResolution.OPTIONS) {
            node.then(Commands.literal(option)
                    .executes(context -> setResolution(context.getSource(), option)));
        }

        // 旧版中文写法（"原始" / "原图"）继续接受，落到同一个处理逻辑
        for (String legacy : VisionResolution.LEGACY_ORIGINAL) {
            node.then(Commands.literal(legacy)
                    .executes(context -> setResolution(context.getSource(), legacy)));
        }

        // 自定义高度（ASCII），例如 /ai resolution 480p
        //
        // 参数名故意用 ASCII 常量而不是 Lang.tr(...)：brigadier 的参数名在**注册时**
        // 就固定下来了，而 Lang.tr 取的是**执行时**的语言；两者一旦不一致（玩家中途
        // 切换语言，或者客户端初始化时语言资源还没加载）就会让 getString 找不到参数
        // 而抛 IllegalArgumentException。参数名只出现在 brigadier 的报错里，不值得冒这个险。
        node.then(Commands.argument(HEIGHT_ARG, StringArgumentType.word())
                .executes(context -> setResolution(context.getSource(),
                        StringArgumentType.getString(context, HEIGHT_ARG))));

        return node;
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandSourceStack source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            error(source, Component.literal(Lang.tr("mcai.cmd.status_not_ready")));
            return 0;
        }

        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_title")));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_model",
                ModelCatalog.describe(config.model)
                        + (ModelCatalog.lookup(config.model).supportsVision()
                        ? Lang.tr("mcai.cmd.vision_ok")
                        : Lang.tr("mcai.cmd.vision_no")))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_mode",
                ModeCatalog.describe(config.mode))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_resolution",
                VisionResolution.label(config.visionResolution),
                describeTarget(config.visionResolution))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_tokens",
                TokenStats.todayTotal(), LocalDate.now())));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_reasoning",
                Lang.tr(config.showReasoning ? "mcai.cmd.reasoning_on" : "mcai.cmd.reasoning_off"),
                config.reasoningMaxChars == 0
                        ? Lang.tr("mcai.cmd.reasoning_all")
                        : Lang.tr("mcai.cmd.reasoning_chars", config.reasoningMaxChars))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_url", config.apiUrl)));
        if (LocalProvider.isLocal(config.apiUrl)) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.status_local")));
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_key", maskKey(config.apiKey))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_cost",
                formatYuan(TokenStats.todayCost()), budgetLabel(config))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_persona",
                PersonaCatalog.describe(config.persona))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_history",
                Math.max(0, config.historyTurns))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_prefix", chatPrefix(config))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.status_hint")));
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

    private static int token(CommandSourceStack source) {
        feedback(source, Component.literal(Lang.tr("mcai.cmd.token_title")));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.token_today",
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

    private static int cost(CommandSourceStack source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_title")));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_today",
                LocalDate.now(), formatYuan(TokenStats.todayCost()))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_tokens",
                TokenStats.todayTotal())));

        PricingCatalog.Quote quote = PricingCatalog.find(config == null ? null : config.model);
        if (quote == null) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_unknown",
                    config == null ? "?" : config.model)));
        } else {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_rate",
                    formatYuan(quote.inputPerMillion()), formatYuan(quote.outputPerMillion()),
                    Lang.tr(PricingCatalog.isPeakHour()
                            ? "mcai.cmd.rate_peak" : "mcai.cmd.rate_off"))));
        }

        if (config != null && config.dailyBudgetYuan > 0) {
            if (TokenStats.isOverDailyBudget()) {
                feedback(source, Component.literal(Lang.tr("mcai.cmd.budget_over",
                        formatYuan(config.dailyBudgetYuan))));
            } else {
                feedback(source, Component.literal(Lang.tr("mcai.cmd.budget_remain",
                        formatYuan(config.dailyBudgetYuan - TokenStats.todayCost()))));
            }
        } else {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.budget_off")));
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.cost_note")));
        return 1;
    }

    // ---------------------------------------------------------------- persona

    private static LiteralArgumentBuilder<CommandSourceStack> buildPersonaNode() {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal("persona");
        for (String id : PersonaCatalog.ids()) {
            node.then(Commands.literal(id)
                    .executes(context -> setPersona(context.getSource(), id)));
        }
        node.then(Commands.literal("list")
                .executes(context -> personaList(context.getSource())));
        node.executes(context -> personaShow(context.getSource()));
        return node;
    }

    private static int setPersona(CommandSourceStack source, String id) {
        ConfigManager.getInstance().update(config -> {
            config.persona = id;
            java.util.List<String> list = new java.util.ArrayList<>(
                    config.availablePersonas == null ? java.util.List.of() : config.availablePersonas);
            if (!list.contains(id)) {
                list.add(id);
            }
            config.availablePersonas = java.util.List.copyOf(list);
        });
        feedback(source, Component.literal(Lang.tr("mcai.cmd.persona_set",
                PersonaCatalog.describe(id))));
        return 1;
    }

    /** 列出所有可用人格预设。 */
    private static int personaList(CommandSourceStack source) {
        feedback(source, Component.literal(Lang.tr("mcai.cmd.persona_list_title")));
        for (String id : PersonaCatalog.ids()) {
            feedback(source, Component.literal("§7 · " + PersonaCatalog.describe(id)));
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.persona_usage")));
        return 1;
    }

    /** /ai persona（不带参数）：查看当前人格。 */
    private static int personaShow(CommandSourceStack source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        feedback(source, Component.literal(Lang.tr("mcai.cmd.persona_current",
                config == null ? PersonaCatalog.describe("default") : PersonaCatalog.describe(config.persona))));
        return 1;
    }

    // ----------------------------------------------------------------- chart

    /** #17 最近 7 天用量柱状图（纯文字，聊天栏直接显示）。 */
    private static int chart(CommandSourceStack source) {
        feedback(source, Component.literal(Lang.tr("mcai.cmd.chart_title")));
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
    private static int where(CommandSourceStack source, String question) {
        String prompt = WorldContext.asPrompt(question);
        if (WorldContext.snapshot() == null) {
            error(source, Component.literal(Lang.tr("mcai.ctx.not_in_world")));
            return 0;
        }
        ChatHandler.ask(prompt);
        return 1;
    }

    // ------------------------------------------------------------------- read

    /** #5 读取眼前的告示牌或手中的成书并请 AI 翻译。 */
    private static int read(CommandSourceStack source) {
        TextReader.Found found = TextReader.read();
        if (found == null) {
            error(source, Component.literal(Lang.tr("mcai.read.not_found")));
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
    private static int craft(CommandSourceStack source, String query) {
        if (query != null && !query.isBlank()) {
            return craftLookup(source, query.trim());
        }

        java.util.List<RecipeHelper.Craftable> list = RecipeHelper.craftableNow();
        // 打进日志：万一结果不对，从日志就能看出是扫描失败还是配方确实不匹配
        com.example.mcai.McaiMod.LOGGER.info("mcAI /ai craft: {} craftable recipe(s) found", list.size());
        feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_title")));
        if (list.isEmpty()) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_empty")));
            return 1;
        }
        int shown = 0;
        for (RecipeHelper.Craftable item : list) {
            if (shown >= 12) {
                feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_more",
                        list.size() - shown)));
                break;
            }
            String ingredients = item.ingredients().isEmpty()
                    ? Lang.tr("mcai.craft.unknown_ingredients")
                    : String.join(Lang.tr("mcai.craft.separator"), item.ingredients());
            feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_entry",
                    item.count() > 1 ? item.output() + " x" + item.count() : item.output(),
                    ingredients)));
            shown++;
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_note")));
        return 1;
    }

    /** 按名字查配方（不需要背包里有材料）。 */
    private static int craftLookup(CommandSourceStack source, String query) {
        java.util.List<net.minecraft.world.item.crafting.Recipe<?>> found = RecipeHelper.findByName(query);
        com.example.mcai.McaiMod.LOGGER.info("mcAI /ai craft {}: {} recipe(s) matched",
                query, found.size());
        if (found.isEmpty()) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_lookup_none", query)));
            return 1;
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_lookup_title", query)));
        for (net.minecraft.world.item.crafting.Recipe<?> entry : found) {
            feedback(source, Component.literal("§7 " + RecipeHelper.describe(entry)));
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.craft_note")));
        return 1;
    }

    // ------------------------------------------------------------------- task

    /** #19 按背包内容生成任务。 */
    private static int taskGenerate(CommandSourceStack source) {
        feedback(source, Component.literal(Lang.tr("mcai.cmd.task_generating")));
        // 回答回来后存成任务；ask 内部会处理冷却、预算、错误提示
        ChatHandler.ask(TaskTracker.buildTaskPrompt(), reply -> {
            TaskTracker.set(reply);
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.task_saved"));
        });
        return 1;
    }

    private static int taskShow(CommandSourceStack source) {
        String task = TaskTracker.current();
        if (task == null) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.task_none")));
            return 1;
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.task_title")));
        for (String line : task.split("\n")) {
            if (!line.isBlank()) {
                feedback(source, Component.literal("§7 " + line.trim()));
            }
        }
        return 1;
    }

    private static int taskClear(CommandSourceStack source) {
        TaskTracker.clear();
        feedback(source, Component.literal(Lang.tr("mcai.cmd.task_cleared")));
        return 1;
    }

    // ------------------------------------------------------- 1.20 开关指令

    /**
     * 切换一个布尔设置。用一个字符串 id 分派，避免为每个开关重复写一遍指令节点。
     */
    private static int setFlag(CommandSourceStack source, String id, boolean value) {
        String label;
        ConfigManager.getInstance().update(config -> {
            switch (id) {
                case "death" -> config.deathRecap = value;
                case "streaming" -> config.streaming = value;
                case "recipe" -> config.recipeCache = value;
                // ---- 1.24 ----
                case "preview" -> config.visionPreview = value;
                case "mask" -> config.privacyMask = value;
                case "tts" -> config.tts = value;
                case "danger" -> config.dangerAlert = value;
                case "cache" -> config.responseCache = value;
                case "env" -> config.envContext = value;
                // ---- 1.25 ----
                case "avatar" -> config.avatarVisible = value;
                default -> { }
            }
        });
        label = Lang.tr("mcai.cmd.flag_" + id);
        feedback(source, Component.literal(Lang.tr(value
                ? "mcai.cmd.flag_on" : "mcai.cmd.flag_off", label)));
        return 1;
    }

    // ---------------------------------------------------------- 1.25 新增指令实现

    /** {@code /ai provider} 的 Tab 补全候选。 */
    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>
            PROVIDER_SUGGESTIONS = (context, builder) -> {
        for (String name : new String[]{"auto", "openai", "gemini", "claude"}) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    /** 物品栏分析：不带参数用默认问题，带了就按玩家的问题问。 */
    private static int inventory(CommandSourceStack source, String question) {
        InventoryQuery.trigger(question);
        return 1;
    }

    /** 准星查询：看准的方块 / 生物是什么。 */
    private static int crosshair(CommandSourceStack source) {
        CrosshairQuery.trigger();
        return 1;
    }

    /**
     * 打开「调整挂件位置」界面。
     *
     * <p>和 {@code /ai config} 走同一个 {@link #pendingScreen} 延迟机制：
     * 客户端指令是在聊天界面里执行的，聊天界面执行完才会自己 {@code setScreen(null)}，
     * 直接在这里开界面会被那一句覆盖掉，看起来就是"敲了没反应"。
     */
    private static int editAvatar(CommandSourceStack source) {
        pendingScreen = () -> Minecraft.getInstance().setScreen(
                new AvatarEditScreen(Minecraft.getInstance().screen));
        return 1;
    }

    /**
     * 指定头像图片。不带参数表示清空（挂件就不显示了）。
     *
     * <p><b>先验证再保存</b>：把一个打不开的路径写进配置，玩家的现象是"挂件不见了"，
     * 根本想不到是路径问题。所以这里先查「文件在不在 + 是不是 PNG」，通过了才落盘。
     */
    private static int setAvatarImage(CommandSourceStack source, String raw) {
        String path = AvatarWidget.normalizeImagePath(raw);
        String problem = AvatarWidget.validateImagePath(path);
        if (problem != null) {
            error(source, Component.literal(problem));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.avatarImage = path);
        if (path.isEmpty()) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.avatar_image_cleared")));
        } else {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.avatar_image_set", path)));
            feedback(source, Component.literal(Lang.tr("mcai.cmd.avatar_image_hint")));
        }
        return 1;
    }

    /**
     * 设置 API 协议。{@code auto} 表示由 {@code api_url} 推断。
     *
     * <p>回显里额外报一次"实际选中的协议"，因为 {@code auto} 猜错了的话，
     * 玩家看到的症状会是一句莫名其妙的 404 或 401，很难联想到是协议没匹配上。
     */
    private static int setProvider(CommandSourceStack source, String name) {
        String value = (name == null || name.isBlank())
                ? "auto" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("auto") && !value.equals("openai")
                && !value.equals("gemini") && !value.equals("claude")) {
            error(source, Component.literal(Lang.tr("mcai.cmd.provider_invalid", name)));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.apiProvider = value);
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        com.example.mcai.util.ApiProvider resolved = com.example.mcai.util.ApiProvider.detect(
                value, config == null ? null : config.apiUrl);
        feedback(source, Component.literal(Lang.tr("mcai.cmd.provider_set", value,
                resolved.name().toLowerCase(java.util.Locale.ROOT))));
        return 1;
    }

    /** 设置失败自动重试次数。 */
    private static int setRetry(CommandSourceStack source, int count) {
        int safe = Math.max(0, Math.min(5, count));
        ConfigManager.getInstance().update(config -> config.retryMax = safe);
        feedback(source, Component.literal(safe == 0
                ? Lang.tr("mcai.cmd.retry_off")
                : Lang.tr("mcai.cmd.retry_set", safe)));
        return 1;
    }

    // ---------------------------------------------------------- 1.24 新增指令实现

    /** 中止正在进行的请求（键盘上的 ESC 是同一个功能）。 */
    private static int cancelRequest(CommandSourceStack source) {
        boolean cancelled = com.example.mcai.util.RequestController.cancel();
        feedback(source, Component.literal(Lang.tr(cancelled
                ? "mcai.cmd.cancel_done" : "mcai.cmd.cancel_none")));
        return 1;
    }

    /** 清空相同问题缓存。 */
    private static int clearCache(CommandSourceStack source) {
        int before = com.example.mcai.util.ResponseCache.size();
        com.example.mcai.util.ResponseCache.clear();
        feedback(source, Component.literal(Lang.tr("mcai.cmd.cache_cleared", before)));
        return 1;
    }

    /** 用系统语音念一段文字（无障碍）。 */
    private static int speak(CommandSourceStack source, String text) {
        if (text == null || text.isBlank()) {
            error(source, Component.literal(Lang.tr("mcai.cmd.speak_empty")));
            return 0;
        }
        com.example.mcai.util.Speech.speakAsync(text);
        feedback(source, Component.literal(Lang.tr("mcai.cmd.speak_started")));
        return 1;
    }

    /** 记一条长期记忆，之后每次提问都会带上。 */
    private static int remember(CommandSourceStack source, String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            error(source, Component.literal(Lang.tr("mcai.cmd.remember_empty")));
            return 0;
        }
        // 单条限长、总量限 20 条：记忆是进系统提示词的，必须有上限，否则提示词会无限膨胀
        final String stored = value.length() > 200 ? value.substring(0, 200) : value;
        ConfigManager.getInstance().update(config -> {
            java.util.List<String> next = new java.util.ArrayList<>(
                    config.memories == null ? java.util.List.<String>of() : config.memories);
            if (next.size() >= 20) {
                next.remove(0);
            }
            next.add(stored);
            config.memories = next;
        });
        feedback(source, Component.literal(Lang.tr("mcai.cmd.remember_added", stored)));
        return 1;
    }

    /** 列出所有长期记忆。 */
    private static int listMemories(CommandSourceStack source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        java.util.List<String> memories = (config == null || config.memories == null)
                ? java.util.List.of() : config.memories;
        if (memories.isEmpty()) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.memories_empty")));
            return 1;
        }
        feedback(source, Component.literal(Lang.tr("mcai.cmd.memories_title")));
        for (int i = 0; i < memories.size(); i++) {
            feedback(source, Component.literal("§8 " + (i + 1) + ". §f" + memories.get(i)));
        }
        return 1;
    }

    /**
     * 忘掉一条长期记忆；{@code index} 为 null 表示全部忘掉。
     */
    private static int forget(CommandSourceStack source, Integer index) {
        ConfigManager.getInstance().update(config -> {
            if (config.memories == null || config.memories.isEmpty()) {
                return;
            }
            java.util.List<String> next = new java.util.ArrayList<>(config.memories);
            if (index == null) {
                next.clear();
            } else {
                int position = index - 1;
                if (position >= 0 && position < next.size()) {
                    next.remove(position);
                }
            }
            config.memories = next;
        });
        feedback(source, Component.literal(Lang.tr("mcai.cmd.forget_done")));
        return 1;
    }

    /** 设置每日预算（元）；0 表示不限制。 */
    private static int setBudget(CommandSourceStack source, double yuan) {
        double safe = Math.max(0.0, yuan);
        ConfigManager.getInstance().update(config -> config.dailyBudgetYuan = safe);
        if (safe <= 0.0) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.budget_cleared")));
        } else {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.budget_set", formatYuan(safe))));
        }
        return 1;
    }

    /** 设置保留几轮上下文；0 表示关闭多轮。 */
    private static int setHistory(CommandSourceStack source, int turns) {
        int safe = Math.max(0, Math.min(20, turns));
        ConfigManager.getInstance().update(config -> config.historyTurns = safe);
        HistoryStore.setMaxTurns(safe);
        if (safe == 0) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.history_off")));
        } else {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.history_set", safe)));
        }
        return 1;
    }

    /** 设置/清除 Discord webhook。 */
    private static int setDiscord(CommandSourceStack source, String url) {
        String value = url == null ? "" : url.trim();
        if (!value.isEmpty() && !value.startsWith("https://")) {
            error(source, Component.literal(Lang.tr("mcai.cmd.discord_invalid")));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.discordWebhook = value);
        if (value.isEmpty()) {
            feedback(source, Component.literal(Lang.tr("mcai.cmd.discord_cleared")));
        } else {
            // 不把完整 URL 打在聊天栏里，避免直播/截图泄露（和 API Key 一样的处理）
            String masked = value.length() <= 40 ? value : value.substring(0, 40) + "…";
            feedback(source, Component.literal(Lang.tr("mcai.cmd.discord_set", masked)));
        }
        return 1;
    }

    // -------------------------------------------------------------- resolution

    private static int setResolution(CommandSourceStack source, String raw) {
        if (!VisionResolution.isUsable(raw)) {            error(source, Component.literal(Lang.tr("mcai.cmd.resolution_invalid",
                    raw, String.join(" / ", VisionResolution.OPTIONS))));
            return 0;
        }

        String normalized = VisionResolution.normalize(raw);
        ConfigManager.getInstance().update(config -> config.visionResolution = normalized);

        feedback(source, Component.literal(Lang.tr("mcai.cmd.resolution_set",
                VisionResolution.label(normalized), describeTarget(normalized))));
        feedback(source, Component.literal(Lang.tr("mcai.cmd.resolution_saved")));
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

    // ------------------------------------------------------ prefix & translate

    /** 当前配置的触发词前缀（配置缺失或空白时用默认值 !ai）。 */
    private static String chatPrefix(ConfigManager.ConfigData config) {
        if (config == null || config.chatPrefix == null || config.chatPrefix.isBlank()) {
            return "!ai";
        }
        return config.chatPrefix;
    }

    private static int setPrefix(CommandSourceStack source, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            error(source, Component.literal(Lang.tr("mcai.cmd.prefix_invalid")));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.chatPrefix = value);
        feedback(source, Component.literal(Lang.tr("mcai.cmd.prefix_set", value)));
        return 1;
    }

    private static int setTranslateLang(CommandSourceStack source, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            error(source, Component.literal(Lang.tr("mcai.cmd.tlang_invalid")));
            return 0;
        }
        ConfigManager.getInstance().update(config -> config.translateLang = value);
        feedback(source, Component.literal(Lang.tr("mcai.cmd.tlang_set", value)));
        return 1;
    }

    /**
     * 翻译指令的节点构建：
     *   /&lt;literal&gt; &lt;lang&gt; &lt;text&gt;   （指定目标语言，lang 是单个 ASCII 词，如 en / zh / ja）
     *   /&lt;literal&gt; &lt;text&gt;            （用配置的默认目标语言）
     * 两者是不同参数形态的两个子节点，brigadier 会给 <lang> 优先，给不出就落到纯文本那条。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildTranslateNode(String literal) {
        LiteralArgumentBuilder<CommandSourceStack> node =
                Commands.literal(literal);
        node.then(Commands.argument("lang", StringArgumentType.word())
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(context -> translate(context.getSource(),
                                StringArgumentType.getString(context, "lang"),
                                StringArgumentType.getString(context, "text")))));
        node.then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(context -> translate(context.getSource(), null,
                        StringArgumentType.getString(context, "text"))));
        return node;
    }

    private static int translate(CommandSourceStack source, String lang, String text) {
        if (text == null || text.isBlank()) {
            error(source, Component.literal(Lang.tr("mcai.cmd.tl_empty")));
            return 0;
        }
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String target = (lang == null || lang.isBlank())
                ? (config == null ? "中文" : (config.translateLang == null || config.translateLang.isBlank()
                    ? "中文" : config.translateLang))
                : lang.trim();
        String prompt = Lang.tr("mcai.cmd.tl_ask", target) + "\n\n" + text.trim();
        ChatHandler.ask(prompt);
        return 1;
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
