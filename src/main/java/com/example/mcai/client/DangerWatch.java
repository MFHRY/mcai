package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.Lang;
import com.example.mcai.vision.VisionHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.Box;

/**
 * 危险预警（1.24 新增）：血量偏低且附近有敌对生物时，自动截图交给 AI 判断该跑还是该打。
 *
 * <p>这是"死亡复盘"的<b>前置</b>版本——死亡复盘是在你死了之后分析死因，
 * 这个是在你可能要死之前介入。
 *
 * <p><b>三个防刷屏设计（这类自动触发的功能最容易做烦人）：</b>
 * <ol>
 *   <li><b>只在跨过阈值那一刻触发一次</b>：用 {@code armed} 标志。
 *       血量掉到阈值以下触发后立刻解除武装，必须等血量<b>回升到阈值以上</b>才重新武装，
 *       否则站在怪旁边血量在阈值上来回抖就会疯狂触发。</li>
 *   <li><b>触发后强制冷却</b>（{@code danger_cooldown_seconds}，默认 60 秒）。</li>
 *   <li><b>默认整个功能是关闭的</b>（{@code danger_alert = false}），要玩家自己去开。</li>
 * </ol>
 *
 * <p>截图与请求完全复用 {@link VisionHandler#trigger(String)}，所以冷却、
 * 预算拦截、模型是否支持读图这些校验全都自动生效，不需要在这里重写一遍。
 */
public final class DangerWatch {

    private DangerWatch() {}

    private static long nextAllowedAt = 0L;
    private static boolean armed = true;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DangerWatch::tick);
    }

    private static void tick(MinecraftClient client) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.dangerAlert) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }

        float health = player.getHealth() + player.getAbsorptionAmount();
        int threshold = Math.max(1, config.dangerHealth);

        // 血量回到阈值以上 → 重新武装，等下一次"掉下去"
        if (health > threshold) {
            armed = true;
            return;
        }
        if (!armed) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAllowedAt) {
            return;
        }

        if (!hasNearbyHostile(client, player, config.dangerRadius)) {
            return;
        }

        armed = false;
        nextAllowedAt = now + Math.max(0, config.dangerCooldownSeconds) * 1000L;
        VisionHandler.trigger(Lang.tr("mcai.danger.prompt"));
    }

    private static boolean hasNearbyHostile(MinecraftClient client, ClientPlayerEntity player, double radius) {
        try {
            Box box = player.getBoundingBox().expand(Math.max(1.0, radius));
            return !client.world.getEntitiesByClass(HostileEntity.class, box, entity -> entity.isAlive()).isEmpty();
        } catch (Throwable t) {
            // 任何异常都当作"没有危险"，宁可漏报也不要因为报错把游戏搞崩
            return false;
        }
    }
}
