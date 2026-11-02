package com.example.mcai.network;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/**
 * 网络注册（双端都要跑，所以在 {@code @Mod} 构造器里调用）。
 *
 * <p><b>26.1 与 1.20.1 的差异：</b>1.20.1 用
 * {@code NetworkRegistry.newSimpleChannel(...)} 加 {@code channel.registerMessage(序号, ...)}；
 * 26.1 改成建造器：{@code ChannelBuilder.named(...).networkProtocolVersion(n).acceptedVersions(...)}
 * 拿到通道，再 {@code messageBuilder(类).encoder().decoder().consumerMainThread().add()}，
 * 最后 {@code build()}。序号不再手写，由建造器按加入顺序分配 —— 所以<b>消息顺序一旦改变，
 * 协议版本号必须跟着改</b>，否则两端会在错误的序号上解包。
 *
 * <p>发送侧也不再是 {@code sendToServer(...)}：通道只认
 * {@code send(消息, Connection)} 或 {@code send(消息, PacketTarget)}，
 * 发给服务端就是 {@code PacketDistributor.SERVER.noArg()}。
 */
public final class McaiNetworking {

    /** 协议版本。改消息布局/顺序时必须 +1。 */
    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "switch"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            // 只接受同版本：两边包布局不同时，宁可握手失败，
            // 也不要让它们在错误的字节位置上各自解包。
            .acceptedVersions(Channel.VersionTest.exact(PROTOCOL_VERSION))
            .simpleChannel()
            .messageBuilder(McaiSwitchPayload.class)
                .encoder(McaiSwitchPayload::encode)
                .decoder(McaiSwitchPayload::decode)
                .consumerMainThread(McaiNetworking::handle)
                .add()
            .build();

    private static boolean initialized = false;

    private McaiNetworking() {}

    public static void initCommon() {
        if (initialized) {
            return;
        }
        initialized = true;
        // 通道在静态初始化时就已注册，这里只留一条可核对的日志：
        // 网络层"到底装没装"不应该靠猜。
        McaiMod.LOGGER.info("mcAI network channel registered: {} (protocol {})",
                CHANNEL.getName(), CHANNEL.getProtocolVersion());
    }

    /**
     * 给服务端发一次。**故意不先判断"对面有没有这个通道"**。
     *
     * <p>这个模组必须能在**原版服务器**上正常运行（核心卖点之一）。接原版服务器时
     * 这一步必然失败（对面没注册这个通道），而切换模型/模式本来就已经在客户端本地改好了，
     * 拿不到服务端确认也完全可用。
     *
     * <p>所以：发不出去不影响任何功能，能发出去只是让整合服务端也同步一份。
     */
    public static void sendSwitchToServer(String kind, String value) {
        try {
            CHANNEL.send(new McaiSwitchPayload(kind, value), PacketDistributor.SERVER.noArg());
        } catch (Throwable ignored) {
            // 原版服务器没有注册这个通道 —— 预期之内，不是错误
        }
    }

    /**
     * 服务端收包。
     *
     * <p>{@code consumerMainThread} 已经负责回到服务端主线程，所以这里不需要
     * 自己 {@code enqueueWork}；在 Netty 线程里碰配置对象才是要避免的事。
     */
    private static void handle(McaiSwitchPayload payload, CustomPayloadEvent.Context context) {
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            applyOnServer(payload);
        }
        context.setPacketHandled(true);
    }

    private static void applyOnServer(McaiSwitchPayload payload) {
        ConfigManager.getInstance().update(config -> {
            if (McaiSwitchPayload.KIND_MODEL.equals(payload.kind())) {
                config.model = payload.value();
            } else if (McaiSwitchPayload.KIND_MODE.equals(payload.kind())) {
                config.mode = payload.value();
            }
        });
    }
}
