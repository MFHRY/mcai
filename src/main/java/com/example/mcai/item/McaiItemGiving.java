package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 玩家进服时发放两个切换器。
 *
 * <p>防重复发：先扫一遍背包（{@code Inventory.contains} 覆盖主背包 + 护甲 + 副手），
 * 已经有就跳过。这样反复上下线也不会越拿越多。
 *
 * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 用
 * {@code ServerPlayConnectionEvents.JOIN}（带 handler，能直接拿到 player）；
 * Forge 用 {@link PlayerEvent.PlayerLoggedInEvent}，玩家从 {@code event.getEntity()} 取，
 * 需要自己往下转型成 {@link ServerPlayer}。
 *
 * <p>注意：这是服务端事件。单人游戏里客户端模组会同时被整合服务端加载，所以正常工作；
 * 但如果是<b>多人服务器</b>，这个模组必须也装在服务端上才会发物品。
 */
public final class McaiItemGiving {

    private McaiItemGiving() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(McaiItemGiving::onPlayerLoggedIn);
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 只在逻辑服务端发；客户端那一侧的这个事件没有意义（也没权限改背包）
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        giveIfMissing(player, McaiItems.AI_MODEL_SWITCHER.get());
        giveIfMissing(player, McaiItems.AI_MODE_SWITCHER.get());
    }

    private static void giveIfMissing(ServerPlayer player, Item item) {
        // 1.20.1 的 Inventory.contains 只接受 ItemStack 或 TagKey，没有 Yarn 那种
        // 「传个谓词」的重载，所以这里自己扫一遍（覆盖主背包 + 护甲 + 副手）。
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return;
            }
        }
        inventory.add(new ItemStack(item));
        McaiMod.LOGGER.info("mcAI gave the switcher items to {}",
                player.getName() == null ? "?" : player.getName().getString());
    }
}
