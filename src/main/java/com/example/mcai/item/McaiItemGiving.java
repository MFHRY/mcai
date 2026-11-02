package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 玩家进服时发放两个切换器。
 *
 * <p>防重复发：先扫一遍背包（{@code Inventory.getContainerSize} 覆盖主背包 + 护甲 + 副手），
 * 已经有就跳过。这样反复上下线也不会越拿越多。
 *
 * <p><b>26.1 与 1.20.1 的差异：</b>EB7 没有全局 EVENT_BUS 了，监听器挂在
 * <b>事件自己的</b> BUS 上：{@code PlayerEvent.PlayerLoggedInEvent.BUS.addListener(...)}。
 * 事件本身也从"带 getter 的类"变成 record，取玩家写作 {@code event.getEntity()}（record 分量名）。
 */
public final class McaiItemGiving {

    private McaiItemGiving() {}

    public static void register() {
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(McaiItemGiving::onPlayerLoggedIn);
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
        // Inventory 没有"传个谓词"的重载，所以这里自己扫一遍
        // （覆盖主背包 + 护甲 + 副手）。
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            // 1.21.2+ 没有 is(Item) 了，只剩 is(Predicate<Holder<Item>>)；
            // 直接比物品实例最直白，也不依赖 Holder 的相等语义。
            if (inventory.getItem(slot).getItem() == item) {
                return;
            }
        }
        inventory.add(new ItemStack(item));
        McaiMod.LOGGER.info("mcAI gave the switcher items to {}",
                player.getName() == null ? "?" : player.getName().getString());
    }
}
