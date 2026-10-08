package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 玩家进服时发放两个切换器。
 *
 * <p>防重复发：先扫一遍背包（含副手，PlayerInventory.contains 覆盖 41 格），
 * 已经有就跳过。这样反复上下线也不会越拿越多。
 *
 * <p>注意：这是服务端事件。单人游戏里客户端模组会同时被整合服务端加载，所以正常工作；
 * 但如果是**多人服务器**，这个模组必须也装在服务端上才会发物品。
 */
public final class McaiItemGiving {

    private McaiItemGiving() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            if (player == null) {
                return;
            }
            giveIfMissing(player, McaiItems.AI_MODEL_SWITCHER);
            giveIfMissing(player, McaiItems.AI_MODE_SWITCHER);
        });
    }

    private static void giveIfMissing(ServerPlayerEntity player, Item item) {
        if (player.getInventory().contains(stack -> stack.isOf(item))) {
            return;
        }
        player.giveItemStack(new ItemStack(item));
        McaiMod.LOGGER.info("mcAI gave the switcher items to {}",
                player.getName() == null ? "?" : player.getName().getString());
    }
}
