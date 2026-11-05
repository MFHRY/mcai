package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家进服时发放两个切换器（Fabric 26.1）。
 *
 * <p>防重复发：先扫一遍背包（{@code Inventory.getContainerSize} 覆盖主背包 + 护甲 + 副手），
 * 已经有就跳过。这样反复上下线也不会越拿越多。
 *
 * <p>Fabric 的事件是 {@code ServerPlayConnectionEvents.JOIN}，回调解锁
 * {@code (handler, sender, server)}，玩家从 {@code handler.getPlayer()} 拿。
 * 这个回调只在逻辑服务端触发，所以不用像 Forge 版那样再判 {@code instanceof ServerPlayer}。
 */
public final class McaiItemGiving {

    private McaiItemGiving() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            giveIfMissing(player, McaiItems.AI_MODEL_SWITCHER);
            giveIfMissing(player, McaiItems.AI_MODE_SWITCHER);
        });
    }

    private static void giveIfMissing(ServerPlayer player, Item item) {
        // Inventory 没有"传个谓词"的重载，所以这里自己扫一遍
        // （覆盖主背包 + 护甲 + 副手）。
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            // 1.21.2+ 没有 is(Item) 了；直接比物品实例最直白。
            if (inventory.getItem(slot).getItem() == item) {
                return;
            }
        }
        inventory.add(new ItemStack(item));
        McaiMod.LOGGER.info("mcAI gave the switcher items to {}",
                player.getName() == null ? "?" : player.getName().getString());
    }
}
