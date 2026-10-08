package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.VisionResolution;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * 进入世界后，在聊天栏打印一份「本模组所有触发方式 + 功能」清单。
 *
 * <p>实现细节：{@code ClientPlayConnectionEvents.JOIN} 触发的瞬间聊天栏可能还没准备好，
 * 直接发消息会被丢掉。所以这里只记一个"待打印"标记，等到下一个客户端 tick
 * 且 {@code player / inGameHud} 都就绪时再真正输出，保证一定看得见。
 *
 * <p>每次启动游戏只打印一次，避免反复进出世界刷屏。
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

        String model = config == null ? "未知" : config.model;
        String mode = config == null ? "未知" : config.mode;
        String resolution = config == null
                ? VisionResolution.DEFAULT
                : VisionResolution.display(config.visionResolution);

        ClientChat.sendLiteral("§b§l[mcAI] §r§f功能与指令一览");
        ClientChat.sendLiteral("§e !ai §7<问题> §8→ §7在聊天栏向 AI 提问§8（§7冷却 8 秒§8）");
        ClientChat.sendLiteral("§e 右键「模型切换器」 §8→ §7循环切换 AI 模型");
        ClientChat.sendLiteral("§e 右键「模式切换器」 §8→ §7循环切换 聊天 / 视觉 模式");
        ClientChat.sendLiteral("§e H §8→ §7截取当前画面交给 AI 识别§8（§7冷却 3 秒§8）");
        ClientChat.sendLiteral("§e /ai status §8→ §7查看当前模型、模式、分辨率、今日消耗");
        ClientChat.sendLiteral("§e /ai token §8→ §7查看今日 token 消耗 + 最近调用明细");
        ClientChat.sendLiteral("§e /ai resolution §7<分辨率> §8→ §7调整截图清晰度§8（Tab 可补全）");
        ClientChat.sendLiteral("§e /ai help §8→ §7重新显示这份清单");
        ClientChat.sendLiteral("§8  · 当前模型：§f" + ModelCatalog.describe(model));
        ClientChat.sendLiteral("§8  · 当前模式：§f" + ModeCatalog.describe(mode));
        ClientChat.sendLiteral("§8  · 截图分辨率：§f" + resolution
                + " §8（可在 mcai.json 里改 vision_resolution）");
    }
}
