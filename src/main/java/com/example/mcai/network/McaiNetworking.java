package com.example.mcai.network;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 网络注册（双端都要跑，所以在 {@code @Mod} 构造器里调用）。
 *
 * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 用的是 1.20.5+ 的
 * {@code PayloadTypeRegistry} + {@code ServerPlayNetworking}；Forge 1.20.1 用的是传统的
 * {@link SimpleChannel}（{@code NetworkRegistry.newSimpleChannel}），消息按序号注册。
 *
 * <p>协议版本故意留成常量 {@code "1"}：它只用于 Forge 的通道握手。
 */
public final class McaiNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(McaiMod.MOD_ID, "switch"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static boolean initialized = false;

    private McaiNetworking() {}

    public static void initCommon() {
        if (initialized) {
            return;
        }
        initialized = true;

        CHANNEL.registerMessage(0, McaiSwitchPayload.class,
                McaiSwitchPayload::encode,
                McaiSwitchPayload::decode,
                McaiNetworking::handle);
    }

    /**
     * 给服务端发一次。**故意不先判断"对面有没有这个通道"**。
     *
     * <p>判断通道是否存在的 API（{@code isRemotePresent} 之类）在 Forge 各版本间
     * 名称并不稳定，而这个模组又必须能在**原版服务器**上正常运行（核心卖点之一）。
     * 所以这里改成直接发、失败就吞掉：接原版服务器时这一步必然失败，而切换模型/模式
     * 本来就已经在客户端本地改好了（配置是同源的），拿不到服务端确认也完全可用。
     *
     * <p>换句话说：发不出去不影响任何功能，能发出去只是让整合服务端也同步一份。
     */
    public static void sendSwitchToServer(String kind, String value) {
        try {
            CHANNEL.sendToServer(new McaiSwitchPayload(kind, value));
        } catch (Throwable ignored) {
            // 原版服务器没有注册这个通道 —— 预期之内，不是错误
        }
    }

    /** 服务端收包。注意这是在 Netty 线程上被调用的。 */
    private static void handle(McaiSwitchPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 回到服务端主线程再改配置，避免在 Netty 线程里碰配置对象
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return; // 纯客户端环境（没装服务端）不该走到这里
            }
            applyOnServer(payload);
        });
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
