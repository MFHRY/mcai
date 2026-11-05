package com.example.mcai.network;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * 网络注册（双端都要跑，所以在 {@code main} 入口点里调用；Fabric 26.1）。
 *
 * <p>注册：{@code PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC)}
 * 声明包类型；服务端接收用 {@code ServerPlayNetworking.registerGlobalReceiver(...)}。
 * 客户端发送用 {@code ClientPlayNetworking.send(payload)}。
 */
public final class McaiNetworking {

    private static boolean initialized = false;

    private McaiNetworking() {}

    public static void initCommon() {
        if (initialized) {
            return;
        }
        initialized = true;

        // 声明 C2S 包类型（serverboundPlay 是"客户端→服务端"一侧）
        PayloadTypeRegistry.serverboundPlay().register(McaiSwitchPayload.TYPE, McaiSwitchPayload.CODEC);

        // 服务端收包。context.player() 就是发包的玩家。
        ServerPlayNetworking.registerGlobalReceiver(McaiSwitchPayload.TYPE, (payload, context) -> {
            if (context.player() != null) {
                applyOnServer(payload);
            }
        });

        McaiMod.LOGGER.info("mcAI network channel registered: mcai:switch");
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
            ClientPlayNetworking.send(new McaiSwitchPayload(kind, value));
        } catch (Throwable ignored) {
            // 原版服务器没有注册这个通道 —— 预期之内，不是错误
        }
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
