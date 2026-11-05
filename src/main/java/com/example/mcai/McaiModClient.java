package com.example.mcai;

import com.example.mcai.client.AiCommand;
import com.example.mcai.client.AvatarWidget;
import com.example.mcai.client.ChatHandler;
import com.example.mcai.client.DangerWatch;
import com.example.mcai.client.DeathRecap;
import com.example.mcai.client.ItemSwitchHandler;
import com.example.mcai.client.RequestCancelKey;
import com.example.mcai.client.StartupGuide;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.client.WorldScope;
import com.example.mcai.vision.VisionHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * 客户端模块装配（Fabric 26.1 / Minecraft 26.1.2）。
 *
 * <p>Fabric 的入口点是 {@code ClientModInitializer}，只在逻辑客户端执行 ——
 * 专用服务端不会加载这个类（和 Forge 版在 {@code FMLEnvironment.dist == CLIENT}
 * 里判断是同一个目的）。
 *
 * <p>事件都是 Fabric API 的静态回调，没有"总线"概念：
 * <ul>
 *   <li>tick：{@code ClientTickEvents.END_CLIENT_TICK}</li>
 *   <li>指令：{@code ClientCommandRegistrationCallback.EVENT}</li>
 *   <li>聊天拦截：{@code ClientSendMessageEvents.ALLOW_CHAT}（返回 false = 吃掉）</li>
 *   <li>进/出服：{@code ClientPlayConnectionEvents.JOIN / DISCONNECT}</li>
 *   <li>右键物品：{@code ItemEvents.USE}</li>
 *   <li>HUD 挂件：{@code HudElementRegistry}（26.1 里 {@code HudRenderCallback}
 *       没了，换成分层绘制 {@code HudElement#extractRenderState(...)}）</li>
 * </ul>
 */
public class McaiModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        McaiMod.LOGGER.info("mcAI client modules: registering event listeners");

        // ---- 快捷键：KeyMappingHelper 一步到位（create + register）----
        VisionHandler.registerKeyMappings();
        AvatarWidget.registerKeyMappings();

        // ---- HUD 挂件：插在原版聊天层之后（和 Forge 版 CHAT_OVERLAY 之后等价）----
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "avatar_widget"),
                (graphics, deltaTracker) ->
                        AvatarWidget.onRenderGui(graphics, deltaTracker.getGameTimeDeltaPartialTick(true)));

        // ---- 每 tick：唯一的客户端分发器 ----
        ClientTickEvents.END_CLIENT_TICK.register(McaiModClient::onClientTick);

        // ---- /ai 客户端指令（纯客户端，任意服务器可用）----
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                AiCommand.onRegisterCommands(dispatcher));

        // ---- 聊天拦截：!ai 前缀的消息在客户端吃掉，不发给服务器 ----
        // ALLOW_CHAT 返回 false 表示"不允许发送"（Forge 那边是返回值 true 表示取消）。
        ClientSendMessageEvents.ALLOW_CHAT.register(message ->
                ChatHandler.getInstance().onChatMessage(message));

        // ---- 进/出世界 ----
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            McaiMod.LOGGER.info("mcAI: joined a world, scope = {}", WorldScope.current());
            WorldScope.onJoin();
            StartupGuide.onJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WorldScope.onDisconnect());

        // ---- 右键切换器物品 ----
        ItemEvents.USE.register(ItemSwitchHandler::onUse);

        McaiMod.LOGGER.info("mcAI client modules: listeners registered "
                + "(tick, commands, hud-layer, chat, join, leave, item-use)");
    }

    /** 唯一的客户端 tick 分发器（每个 tick 跑一次）。 */
    private static void onClientTick(Minecraft client) {
        // 顺序有讲究：先让"待打开的界面"生效，再跑可能触发新请求的逻辑
        AiCommand.onClientTick(client);
        StartupGuide.onClientTick(client);
        ThinkingIndicator.onClientTick(client);
        DeathRecap.onTick(client);
        DangerWatch.tick(client);
        RequestCancelKey.tick(client);
        VisionHandler.onClientTick(client);
        AvatarWidget.onClientTick(client);
    }
}
