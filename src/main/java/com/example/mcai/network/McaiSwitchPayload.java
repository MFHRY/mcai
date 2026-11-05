package com.example.mcai.network;

import com.example.mcai.McaiMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端切换完，把**结果值**同步给服务端（C2S，Fabric 26.1）。
 *
 * <p>Fabric 版走 1.20.5+ 的 {@code CustomPayload} 体系：record + {@code TYPE} +
 * {@code StreamCodec}，在 {@link McaiNetworking#initCommon()} 里注册进
 * {@code PayloadTypeRegistry.serverboundPlay()}。
 *
 * <p>关键设计没变：包里传的是**绝对的新值**（例如 "deepseek-v4-pro"），不是"切换一次"
 * 这种指令。因为单人游戏里客户端和整合服务端共用同一个 ConfigManager 单例，
 * 如果传"切换指令"就会被执行两次、连跳两个模型。传绝对值天然幂等。
 */
public record McaiSwitchPayload(String kind, String value) implements CustomPacketPayload {

    public static final String KIND_MODEL = "model";
    public static final String KIND_MODE = "mode";

    public static final CustomPacketPayload.Type<McaiSwitchPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "switch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, McaiSwitchPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, McaiSwitchPayload::kind,
                    ByteBufCodecs.STRING_UTF8, McaiSwitchPayload::value,
                    McaiSwitchPayload::new);

    public McaiSwitchPayload {
        // 防御：两个字段都不能是 null，否则 writeUtf 会炸
        kind = kind == null ? "" : kind;
        value = value == null ? "" : value;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
