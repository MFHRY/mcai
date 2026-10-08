package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.VisionResolution;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;

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

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!alreadyShown) {
                pending = true;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pending && client.player != null && client.inGameHud != null) {
                pending = false;
                alreadyShown = true;
                show();
            }
        });
    }

    /** 打印功能清单。进世界时自动调用一次，{@code /ai help} 也可以随时手动调出。 */
    public static void show() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();

        String unknown = Lang.tr("mcai.value.unknown");
        String model = config == null ? unknown : config.model;
        String mode = config == null ? unknown : config.mode;
        String resolution = config == null
                ? VisionResolution.DEFAULT
                : VisionResolution.label(config.visionResolution);

        ClientChat.sendLiteral(Lang.tr("mcai.guide.title"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.config"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.ask"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.model_switcher"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.mode_switcher"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.vision"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.status"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.token"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.resolution"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.help"));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_model", ModelCatalog.describe(model)));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_mode", ModeCatalog.describe(mode)));
        ClientChat.sendLiteral(Lang.tr("mcai.guide.current_resolution", resolution));

        // 还没填 Key 时给一条醒目、可点击的提示
        if (config != null && (config.apiKey == null || config.apiKey.isBlank())) {
            ClientChat.send(Text.literal(Lang.tr("mcai.guide.no_key"))
                    .styled(style -> style.withClickEvent(new ClickEvent(
                            ClickEvent.Action.SUGGEST_COMMAND, "/ai config"))));
        }
    }
}
