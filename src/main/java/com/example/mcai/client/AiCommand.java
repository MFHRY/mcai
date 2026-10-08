package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.TokenStats;
import com.example.mcai.util.UsageLog;
import com.example.mcai.util.VisionResolution;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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

    private AiCommand() {}

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root =
                    ClientCommandManager.literal("ai");

            root.then(ClientCommandManager.literal("status")
                    .executes(context -> status(context.getSource())));

            root.then(ClientCommandManager.literal("token")
                    .executes(context -> token(context.getSource())));

            root.then(ClientCommandManager.literal("help")
                    .executes(context -> {
                        StartupGuide.show();
                        return 1;
                    }));

            // 打开游戏内设置窗口（填写 API Key 等）
            root.then(ClientCommandManager.literal("config")
                    .executes(context -> {
                        MinecraftClient.getInstance().setScreen(new ConfigScreen(null));
                        return 1;
                    }));

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
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_key", maskKey(config.apiKey))));
        source.sendFeedback(Text.literal(Lang.tr("mcai.cmd.status_hint")));
        return 1;
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

    // -------------------------------------------------------------- resolution

    private static int setResolution(FabricClientCommandSource source, String raw) {
        if (!VisionResolution.isUsable(raw)) {
            source.sendError(Text.literal(Lang.tr("mcai.cmd.resolution_invalid",
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
