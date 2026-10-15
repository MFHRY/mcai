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
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 客户端模块装配（Forge 版）。
 *
 * <h2>与 Fabric 版最大的结构差异</h2>
 *
 * <p>Fabric 那边每个模块自己在 {@code register()} 里挂一个监听器，于是有
 * <b>8 个独立的</b>{@code END_CLIENT_TICK} 回调。Forge 这边改成
 * <b>一个</b> {@link TickEvent.ClientTickEvent} 监听器里顺序分发——
 * 回调数量少了一个数量级，执行顺序也变成显式可读的，而不是取决于注册先后。
 *
 * <h2>两条总线的区别（最容易搞错的地方）</h2>
 *
 * <ul>
 *   <li><b>mod 事件总线</b>（{@code modBus}）：只在初始化阶段触发一次的事件，
 *       例如 {@link RegisterKeyMappingsEvent}。用 {@code modBus.addListener}。</li>
 *   <li><b>Forge 总线</b>（{@link MinecraftForge#EVENT_BUS}）：运行时事件，
 *       例如 tick、渲染、聊天、进服。用 {@code EVENT_BUS.addListener}。</li>
 * </ul>
 *
 * <p>按键注册挂错总线是最常见的错误：挂在 Forge 总线上会静默不生效
 * （按键永远不出现在设置里），而且没有任何报错。
 */
public final class McaiModClient {

    private McaiModClient() {}

    /** 由 {@code McaiMod} 经 {@code DistExecutor} 调用，只在物理客户端上执行。 */
    public static void register(IEventBus modBus) {
        // ---- mod 总线：初始化期一次性事件 ----
        modBus.addListener(McaiModClient::onRegisterKeyMappings);

        // ---- Forge 总线：运行时事件 ----
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onRegisterClientCommands);
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onRenderGuiPost);
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onClientChat);
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(McaiModClient::onLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(ItemSwitchHandler::onRightClickItem);

        // ---- 不需要额外接线的模块 ----
        // ChatHandler / StartupGuide / WorldScope / AvatarWidget / VisionHandler /
        // ItemSwitchHandler / AiCommand 的状态都在上面的回调里驱动，这里不再各自注册。
    }

    // ---------------------------------------------------------------- 初始化期

    /**
     * 注册快捷键。
     *
     * <p><b>必须挂 mod 总线。</b>Forge 只在初始化期间触发一次这个事件，
     * 而 {@code KeyMapping} 必须在这时通过 {@code event.register(...)} 登记，
     * 否则不会出现在「选项 → 控制」里，玩家也就无法改键。
     */
    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        VisionHandler.registerKeyMappings(event);
        AvatarWidget.registerKeyMappings(event);
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 唯一的客户端 tick 分发器。
     *
     * <p>只处理 {@code Phase.END}：Forge 每个 tick 会触发 START 和 END 两次，
     * 不判断就会把所有逻辑跑两遍（冷却减半、动画速度翻倍，且很难看出原因）。
     */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();

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

    // -------------------------------------------------------------- 运行期事件

    /**
     * 注册 {@code /ai} 客户端指令。
     *
     * <p>Forge 1.20.1 原生带 {@code RegisterClientCommandsEvent}，所以这套指令
     * <b>不需要 mixin</b>，也就保住了"纯客户端、任意服务器可用"这一点。
     */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        AiCommand.onRegisterCommands(event.getDispatcher());
    }

    /** HUD 渲染（鲸鱼娘挂件）。 */
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        AvatarWidget.onRenderGui(event.getGuiGraphics(), event.getPartialTick());
    }

    /**
     * 聊天拦截：以 {@code !ai} 开头的消息在客户端就被吃掉，永远不发给服务器。
     *
     * <p>Fabric 那边是"返回 false 表示拦截"，Forge 这边是"取消事件"，
     * 所以 {@link ChatHandler#onChatMessage} 的返回值语义是"是否允许放行"。
     */
    private static void onClientChat(net.minecraftforge.client.event.ClientChatEvent event) {
        if (!ChatHandler.getInstance().onChatMessage(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    /** 进入世界：切上下文作用域 + 准备打印进服清单。 */
    private static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        WorldScope.onJoin();
        StartupGuide.onJoin();
    }

    /** 离开世界：清掉所有上下文分桶。 */
    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WorldScope.onDisconnect();
    }
}
