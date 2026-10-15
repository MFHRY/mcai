package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.VisionResolution;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * 进入世界后，在聊天栏打印一份「本模组所有触发方式 + 功能」清单。
 *
 * <p>实现细节：{@code ClientPlayConnectionEvents.JOIN} 触发的瞬间聊天栏可能还没准备好，
 * 直接发消息会被丢掉。所以这里只记一个"待打印"标记，等到下一个客户端 tick
 * 且 {@code player / inGameHud} 都就绪时再真正输出，保证一定看得见。
 *
 * <p>每次启动游戏只打印一次，避免反复进出世界刷屏。
 *
 * <p>整份清单都走语言文件，所以中文玩家看到中文、英文玩家看到英文。
 */
public final class StartupGuide {

    private static boolean pending = false;
    private static boolean alreadyShown = false;

    private StartupGuide() {}

    /** 进入世界时由 {@code McaiModClient} 调用：只登记意图，真正打印推迟到下一个 tick。 */
    public static void onJoin() {
        if (!alreadyShown) {
            pending = true;
        }
    }

    /**
     * 由 {@code McaiModClient} 的统一 tick 分发器调用。
     *
     * <p>JOURNAL 触发的瞬间聊天栏可能还没准备好，所以这里等到 player / gui 都就绪才打印。
     */
    public static void onClientTick(Minecraft client) {
        if (pending && client.player != null && client.gui != null) {
            pending = false;
            alreadyShown = true;
            // 进世界只打精简版；完整版随时可以用 /ai help 调出来
            show(false);
        }
    }

    /** 完整清单：{@code /ai help} 用。 */
    public static void show() {
        show(true);
    }

    /**
     * 打印功能清单。
     *
     * <p><b>为什么分两个版本：</b>1.25 的指令总数已经到 35 条，每次进世界都刷 35 行
     * 聊天栏实在过分。所以进世界时用 {@code show(false)} 打一份精简版（常用操作 +
     * 一行"看全部指令"的指引），{@code /ai help} 用 {@code show(true)} 打完整版。
     *
     * <p>精简版里那行指引是关键：它保证完整版一定被发现，否则又会退回 1.23/1.24
     * 那个"新功能没人知道"的老问题——那正是这次要修的。
     */
    public static void show(boolean full) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();

        String unknown = Lang.tr("mcai.value.unknown");
        String model = config == null ? unknown : config.model;
        String mode = config == null ? unknown : config.mode;
        String resolution = config == null
                ? VisionResolution.DEFAULT
                : VisionResolution.label(config.visionResolution);

        // 这份清单要跟 AiCommand.register() 里注册的节点保持一致：每加一个指令，
        // 就补一个 mcai.guide.* 键（四份语言文件都要补）并在这里插一行。
        // 顺序按"玩家最可能先用到"排，不按字母序。
        //
        // 带 if (full) 的是"进阶项"：精简版里省掉，但完整版一条不少。
        ClientChat.sendLiteral(Lang.tr("mcai.guide.title"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.config"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.ask"));
        if (full) {
            ClientChat.sendLiteral(Lang.tr("mcai.guide.prefix"));
        }
        ClientChat.sendLiteral(Lang.tr("mcai.guide.model_switcher"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.mode_switcher"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.vision"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.pick"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.build"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.what"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.avatar"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.where"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.read"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.craft"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.task"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.inv"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.describe"));
        if (full) {
            ClientChat.sendLiteral(Lang.tr("mcai.guide.translate"));
        }
        ClientChat.sendLiteral(Lang.tr("mcai.guide.cost"));
        if (full) {
            ClientChat.sendLiteral(Lang.tr("mcai.guide.chart"));
        }
        ClientChat.sendLiteral(Lang.tr("mcai.guide.persona"));
        if (full) {
            ClientChat.sendLiteral(Lang.tr("mcai.guide.clear"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.toggles"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.toggles_124"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.toggles_125"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.memory"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.cancel"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.speak"));
        }
        ClientChat.sendLiteral(Lang.tr("mcai.guide.status"));
        if (full) {
            ClientChat.sendLiteral(Lang.tr("mcai.guide.token"));
            ClientChat.sendLiteral(Lang.tr("mcai.guide.resolution"));
        }
        // 精简版用"看全部指令"那一行收尾，完整版用它自己那一行收尾
        ClientChat.sendLiteral(Lang.tr(full ? "mcai.guide.help" : "mcai.guide.more"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_model", ModelCatalog.describe(model)));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_mode", ModeCatalog.describe(mode)));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_resolution", resolution));

        // 还没填 Key 时给一条醒目、可点击的提示。
        // 指向本机推理服务（Ollama / LM Studio）时不需要 key，别把人吓着去办 key。
        if (config != null && LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)) {
            ClientChat.send(Component.literal(Lang.tr("mcai.guide.no_key"))
                    .styled(style -> style.withClickEvent(new ClickEvent(
                            ClickEvent.Action.SUGGEST_COMMAND, "/ai config"))));
        }
    }
}
