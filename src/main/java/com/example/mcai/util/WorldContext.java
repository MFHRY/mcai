package com.example.mcai.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

/**
 * 世界环境快照（#3 F3 地理顾问）。
 *
 * <p><b>为什么不直接去读 F3 调试屏的文字：</b>那些字符串是在
 * {@code DebugHud} 内部临时拼出来的，既没有公开访问器，排版也随版本变化。
 * 这里改成自己调 API 拼一份"人话版"环境摘要，稳定且可控。
 *
 * <p>全部取值都在客户端主线程上做（读的是 {@code client.player}/{@code client.world}），
 * 拼好的字符串可以直接塞进提示词。
 */
public final class WorldContext {

    private WorldContext() {}

    /** 当前环境的文字摘要；玩家不在世界里时返回 null。 */
    public static String snapshot() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }

        BlockPos pos = player.getBlockPos();
        StringBuilder sb = new StringBuilder();
        sb.append(Lang.tr("mcai.ctx.header")).append('\n');
        sb.append(Lang.tr("mcai.ctx.dimension",
                world.getRegistryKey().getValue().toString())).append('\n');
        sb.append(Lang.tr("mcai.ctx.coords",
                pos.getX(), pos.getY(), pos.getZ())).append('\n');

        // 群系：1.21.1 的 getBiome 返回 RegistryEntry<Biome>
        try {
            RegistryEntry<Biome> biome = world.getBiome(pos);
            String biomeName = biome.getKey()
                    .map(key -> key.getValue().getPath())
                    .orElse(Lang.tr("mcai.value.unknown"));
            sb.append(Lang.tr("mcai.ctx.biome", biomeName)).append('\n');
        } catch (Throwable ignored) {
            // 某些维度/极端情况下取不到群系，跳过这一行而不是让整条链路失败
        }

        sb.append(Lang.tr("mcai.ctx.light",
                world.getLightLevel(pos))).append('\n');
        sb.append(Lang.tr("mcai.ctx.y_level",
                player.getY(), world.getSeaLevel())).append('\n');
        sb.append(Lang.tr("mcai.ctx.time",
                world.getTimeOfDay() % 24000L)).append('\n');
        sb.append(Lang.tr("mcai.ctx.health",
                Math.round(player.getHealth()), Math.round(player.getMaxHealth())));

        return sb.toString();
    }

    /** 把环境摘要包成一句给 AI 的提问。 */
    public static String asPrompt(String question) {
        String ctx = snapshot();
        if (ctx == null) {
            return question;
        }
        if (question == null || question.isBlank()) {
            return Lang.tr("mcai.ctx.ask_default") + "\n\n" + ctx;
        }
        return question + "\n\n" + ctx;
    }
}
