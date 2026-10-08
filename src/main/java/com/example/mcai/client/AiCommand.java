package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
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
 * <p><b>1.21.1 的坑：</b>{@code StringArgumentType.word()} 只接受 ASCII 字母数字，
 * 中文的 {@code 原始} 根本没法作为参数值解析。所以四个标准分辨率被注册成
 * brigadier 的 <b>literal 节点</b>——既绕开了限制，又顺带白送 Tab 补全。
 * 需要自定义高度时（例如 {@code 480p}）再走 ASCII 的 word 参数。
 *
 * <p>指令是纯客户端的，改完配置由 ConfigManager 异步落盘，不会卡主线程。
 */
public final class AiCommand {

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

        // 四个标准值做成 literal：中文 "原始" 只能这样写，同时自带 Tab 补全
        for (String option : VisionResolution.OPTIONS) {
            node.then(ClientCommandManager.literal(option)
                    .executes(context -> setResolution(context.getSource(), option)));
        }

        // 自定义高度（ASCII），例如 /ai resolution 480p
        node.then(ClientCommandManager.argument("高度", StringArgumentType.word())
                .executes(context -> setResolution(context.getSource(),
                        StringArgumentType.getString(context, "高度"))));

        return node;
    }

    // ------------------------------------------------------------------ status

    private static int status(FabricClientCommandSource source) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            source.sendError(Text.literal("§c[AI] 配置尚未加载完成。"));
            return 0;
        }

        source.sendFeedback(Text.literal("§b§l[mcAI] §r§f当前状态"));
        source.sendFeedback(Text.literal("§7 · 模型：§f" + ModelCatalog.describe(config.model)
                + (ModelCatalog.lookup(config.model).supportsVision() ? " §a可读图" : " §c不支持读图")));
        source.sendFeedback(Text.literal("§7 · 模式：§f" + ModeCatalog.describe(config.mode)));
        source.sendFeedback(Text.literal("§7 · 截图分辨率：§f"
                + VisionResolution.display(config.visionResolution)
                + " §8(" + describeTarget(config.visionResolution) + ")"));
        source.sendFeedback(Text.literal("§7 · 今日消耗：§f" + TokenStats.todayTotal()
                + " §7tokens §8(" + LocalDate.now() + ")"));
        source.sendFeedback(Text.literal("§7 · 思考过程：§f"
                + (config.showReasoning ? "显示" : "隐藏")
                + "§7，最多 §f"
                + (config.reasoningMaxChars == 0 ? "全部" : config.reasoningMaxChars + " 字")));
        source.sendFeedback(Text.literal("§7 · 接口地址：§f" + config.apiUrl));
        source.sendFeedback(Text.literal("§7 · API Key：§f" + maskKey(config.apiKey)));
        source.sendFeedback(Text.literal("§8 /ai token 看明细 · /ai help 看全部功能"));
        return 1;
    }

    // ------------------------------------------------------------------- token

    private static int token(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("§b[mcAI] §fToken 统计"));
        source.sendFeedback(Text.literal("§7 · 今日（§f" + LocalDate.now() + "§7）：§f"
                + TokenStats.todayTotal() + " §7tokens"));

        // 明细从 CSV 异步读，读完再补发到聊天栏
        UsageLog.readRecentAsync(8).whenComplete((entries, error) -> {
            if (error != null) {
                ClientChat.sendLiteral("§c[AI] 读取使用明细失败：" + error.getMessage());
                return;
            }
            if (entries == null || entries.isEmpty()) {
                ClientChat.sendLiteral("§8 暂无历史明细。完成一次 !ai 提问或 H 截图后，"
                        + "记录会写入 " + UsageLog.displayPath() + "");
                return;
            }

            ClientChat.sendLiteral("§7 · 最近 " + entries.size() + " 次调用：");
            for (UsageLog.Entry entry : entries) {
                ClientChat.sendLiteral("§8   · §7" + entry.displayTime()
                        + "  §f" + entry.tokens() + " §7tokens"
                        + "  §8(" + entry.source() + ", " + entry.model() + ")");
            }
            ClientChat.sendLiteral("§8 完整明细（可用 Excel 打开）：" + UsageLog.displayPath());
        });
        return 1;
    }

    // -------------------------------------------------------------- resolution

    private static int setResolution(FabricClientCommandSource source, String raw) {
        if (!VisionResolution.isUsable(raw)) {
            source.sendError(Text.literal("§c[AI] 无法识别的分辨率：§f" + raw
                    + "§c。可用值：§f" + String.join(" / ", VisionResolution.OPTIONS)
                    + "§c，或 §f480p§c 这类自定义高度。"));
            return 0;
        }

        String normalized = VisionResolution.normalize(raw);
        ConfigManager.getInstance().update(config -> config.visionResolution = normalized);

        source.sendFeedback(Text.literal("§b[AI] 截图分辨率已设为 §f" + normalized
                + " §7(" + describeTarget(normalized) + ")"));
        source.sendFeedback(Text.literal("§8 配置已异步保存，下一次按 H 生效。"));
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    /** 把分辨率配置翻译成一句人话。 */
    private static String describeTarget(String resolution) {
        int height = VisionResolution.targetHeight(resolution);
        if (height <= 0) {
            return "不缩放，使用窗口原始尺寸";
        }
        return "最高 " + height + "px，超出则等比缩小，不放大";
    }

    /** 聊天栏里不要把完整 key 打出来，避免直播 / 截图泄露。 */
    private static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "§c未填写";
        }
        if (apiKey.length() <= 10) {
            return "****";
        }
        return apiKey.substring(0, 7) + "…" + apiKey.substring(apiKey.length() - 4);
    }
}
