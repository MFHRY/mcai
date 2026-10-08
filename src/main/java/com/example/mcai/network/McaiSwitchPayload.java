package com.example.mcai.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 1.21.1 标准的 CustomPayload（C2S）：客户端切换完，把**结果值**同步给服务端。
 *
 * <p>关键设计：包里传的是**绝对的新值**（例如 "deepseek-v4-pro"），不是"切换一次"这种指令。
 * 因为单人游戏里客户端和整合服务端共用同一个 ConfigManager 单例，如果传"切换指令"
 * 就会被执行两次、连跳两个模型。传绝对值就天然幂等。
 *
 * <p>1.21.1 的坑：{@code PacketCodecs.STRING} 的类型是 {@code PacketCodec<ByteBuf, String>}，
 * 而 {@code PacketByteBuf extends ByteBuf}，所以能直接喂给 {@code PacketCodec.tuple}。
 */
public record McaiSwitchPayload(String kind, String value) implements CustomPayload {

    public static final String KIND_MODEL = "model";
    public static final String KIND_MODE = "mode";

    public static final CustomPayload.Id<McaiSwitchPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcai", "switch"));

    public static final PacketCodec<PacketByteBuf, McaiSwitchPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, McaiSwitchPayload::kind,
            PacketCodecs.STRING, McaiSwitchPayload::value,
            McaiSwitchPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
