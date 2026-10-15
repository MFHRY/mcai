package com.example.mcai.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端切换完，把**结果值**同步给服务端（C2S）。
 *
 * <p><b>Forge 版与 Fabric 版的结构差异：</b>Fabric 那边这是一个实现
 * {@code CustomPayload} 的 record，走 1.20.5+ 的 {@code PayloadTypeRegistry} /
 * {@code PacketCodec} 体系。Forge 1.20.1 没有那套东西，走的是传统的
 * {@link net.minecraftforge.network.SimpleChannel}：普通类 + 一对
 * {@code encode} / {@code decode} 静态方法。
 *
 * <p>关键设计没变：包里传的是**绝对的新值**（例如 "deepseek-v4-pro"），不是"切换一次"
 * 这种指令。因为单人游戏里客户端和整合服务端共用同一个 ConfigManager 单例，
 * 如果传"切换指令"就会被执行两次、连跳两个模型。传绝对值天然幂等。
 */
public final class McaiSwitchPayload {

    public static final String KIND_MODEL = "model";
    public static final String KIND_MODE = "mode";

    private final String kind;
    private final String value;

    public McaiSwitchPayload(String kind, String value) {
        this.kind = kind == null ? "" : kind;
        this.value = value == null ? "" : value;
    }

    public String kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public static void encode(McaiSwitchPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.kind);
        buf.writeUtf(payload.value);
    }

    public static McaiSwitchPayload decode(FriendlyByteBuf buf) {
        return new McaiSwitchPayload(buf.readUtf(), buf.readUtf());
    }
}
