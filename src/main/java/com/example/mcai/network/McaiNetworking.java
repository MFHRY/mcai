package com.example.mcai.network;

import com.example.mcai.ConfigManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * 网络注册（双端都要跑，所以放在 main 入口）。
 *
 * <p>1.21.1 的坑：{@code ServerPlayConnectionEvents} 和 {@code PayloadTypeRegistry}
 * 由 {@code fabric-networking-api-v1} 提供，包名仍然是
 * {@code net.fabricmc.fabric.api.networking.v1}（不在 fabric-lifecycle-events 里）。
 */
public final class McaiNetworking {

    private static boolean initialized = false;

    private McaiNetworking() {}

    public static void initCommon() {
        if (initialized) {
            return;
        }
        initialized = true;

        PayloadTypeRegistry.playC2S().register(McaiSwitchPayload.ID, McaiSwitchPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(McaiSwitchPayload.ID, (payload, context) ->
                // 回到服务端主线程再改配置，避免在 Netty 线程里碰配置对象
                context.server().execute(() -> applyOnServer(payload)));
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
