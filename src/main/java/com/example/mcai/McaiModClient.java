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
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/**
 * 客户端模块装配（Forge 26.1 / EventBus 7）。
 *
 * <h2>与 1.20.1 Forge 版最大的结构差异：没有"两条总线"了</h2>
 *
 * <p>1.20.1 要区分 mod 总线和 Forge 主总线，挂错了会静默失效。EB7 把这件事
 * 变成了类型系统：<b>每个事件类自带 BUS</b>，监听器直接挂到那个字段上，
 * 挂错编译不过。所以这里的每个注册都是 {@code XxxEvent.BUS.addListener(...)}。
 *
 * <p>第二处差异：{@code TickEvent} 从"一个事件 + phase 字段"拆成了
 * {@code ClientTickEvent.Pre} / {@code .Post} 两个 <b>record</b>。
 * 原来那句 {@code if (event.phase != END) return;} 没有了——
 * 挂在 {@code Post.BUS} 上，就只会收到 END。
 *
 * <p>第三处：HUD 不再有 {@code RenderGuiEvent}（1.20.5 就删了，改走原版的
 * 分层绘制）。26.1 的入口是 {@link AddGuiOverlayLayersEvent}，
 * 图层接口是 {@code ForgeLayer#extract(GuiGraphicsExtractor, DeltaTracker)}。
 */
public final class McaiModClient {

    private McaiModClient() {}

    /** 由 {@code McaiMod} 调用，只在物理客户端上执行。 */
    public static void register(BusGroup modBus) {
        McaiMod.LOGGER.info("mcAI client modules: registering event listeners");

        // ---- 初始化期一次性事件（SelfDestructing / IModBusEvent）----
        RegisterKeyMappingsEvent.BUS.addListener(McaiModClient::onRegisterKeyMappings);
        AddGuiOverlayLayersEvent.BUS.addListener(McaiModClient::onAddGuiOverlayLayers);

        // ---- 运行期事件 ----
        TickEvent.ClientTickEvent.Post.BUS.addListener(McaiModClient::onClientTick);
        RegisterClientCommandsEvent.BUS.addListener(McaiModClient::onRegisterClientCommands);
        ClientChatEvent.BUS.addListener(McaiModClient::onClientChat);
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(McaiModClient::onLoggedIn);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(McaiModClient::onLoggedOut);
        PlayerInteractEvent.RightClickItem.BUS.addListener(ItemSwitchHandler::onRightClickItem);

        McaiMod.LOGGER.info("mcAI client modules: 8 listeners registered "
                + "(tick, commands, hud-layer, chat, join, leave, item-use)");
    }

    // ---------------------------------------------------------------- 初始化期

    /**
     * 注册快捷键。
     *
     * <p>{@code RegisterKeyMappingsEvent} 只在初始化期间触发一次，
     * {@code KeyMapping} 必须在这时通过 {@code event.register(...)} 登记，
     * 否则不会出现在「选项 → 控制」里，玩家也就无法改键。
     */
    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        VisionHandler.registerKeyMappings(event);
        AvatarWidget.registerKeyMappings(event);
        McaiMod.LOGGER.info("mcAI key mappings registered: H, G, R, V (screenshots) and B (widget bubble)");
    }

    /**
     * 把鲸鱼娘挂件插进 HUD 分层绘制。
     *
     * <p>插在 {@code CHAT_OVERLAY} 之后：原版的聊天栏是最容易被玩家看到的，
     * 挂件压在它上面才不会被聊天顶掉——1.20.1 那边是"Post 事件最后画"，
     * 语义等价。
     */
    private static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().add(
                Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "avatar_widget"),
                (graphics, deltaTracker) ->
                        AvatarWidget.onRenderGui(graphics, deltaTracker.getGameTimeDeltaPartialTick(true)));
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 唯一的客户端 tick 分发器。
     *
     * <p>挂在 {@code ClientTickEvent.Post} 上，所以每个 tick 只会跑一次
     * （1.20.1 需要自己判断 phase，不判断就会把所有逻辑跑两遍）。
     */
    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
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

    /** 注册 {@code /ai} 客户端指令（纯客户端，任意服务器可用）。 */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        AiCommand.onRegisterCommands(event.getDispatcher());
        McaiMod.LOGGER.info("mcAI registered the /ai client command tree");
    }

    /**
     * 聊天拦截：以 {@code !ai} 开头的消息在客户端就被吃掉，永远不发给服务器。
     *
     * <p><b>EB7 的取消语义：</b>可取消总线的监听器本身就是一个
     * {@code Predicate}——返回 {@code true} 表示"取消"。Forge 那边收到取消后
     * 会把消息置空、不发出去（{@code ForgeHooksClient#onClientSendMessage}）。
     * 所以这里的返回值是"是否拦截"，和 1.20.1 的 {@code setCanceled(true)} 等价。
     */
    private static boolean onClientChat(ClientChatEvent event) {
        return !ChatHandler.getInstance().onChatMessage(event.getMessage());
    }

    /** 进入世界：切上下文作用域 + 准备打印进服清单。 */
    private static void onLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        McaiMod.LOGGER.info("mcAI: joined a world, scope = {}", WorldScope.current());
        WorldScope.onJoin();
        StartupGuide.onJoin();
    }

    /** 离开世界：清掉所有上下文分桶。 */
    private static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WorldScope.onDisconnect();
    }
}
