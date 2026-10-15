package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import com.example.mcai.network.McaiSwitchPayload;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * 客户端右键逻辑：循环切换模型 / 模式。
 *
 * <p><b>Forge 版与 Fabric 版的差异（三点）：</b>
 * <ol>
 *   <li>Fabric 的 {@code UseItemCallback.interact} 返回
 *       {@code InteractionResultHolder<ItemStack>}；Forge 的
 *       {@link PlayerInteractEvent.RightClickItem} 没有返回值，而是在事件上
 *       调 {@code setCanceled(true)} / {@code setCancellationResult(...)}。</li>
 *   <li>物品是 {@code RegistryObject}，取值要多一个 {@code .get()}。</li>
 *   <li>发包走 {@link McaiNetworking#sendSwitchToServer}（内部已经吞掉了
 *       "服务端没装模组"的失败）。</li>
 * </ol>
 *
 * <p>为什么要在服务端那一侧"接受但不动手"：切换完全由客户端驱动，服务端只是把
 * 结果<b>回写</b>配置。如果两侧都切一次，就会连跳两格。
 */
public final class ItemSwitchHandler {

    private ItemSwitchHandler() {}

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        boolean isModelSwitcher = stack.is(McaiItems.AI_MODEL_SWITCHER.get());
        boolean isModeSwitcher = stack.is(McaiItems.AI_MODE_SWITCHER.get());

        if (!isModelSwitcher && !isModeSwitcher) {
            return;
        }

        if (!event.getLevel().isClientSide()) {
            // 同一次右键的服务端侧（单人游戏）：接受但不动手，避免双端各切一次
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }

        switchOnClient(stack, isModelSwitcher);

        // 客户端处理完就吃掉这个事件：不要让原版的"使用物品"再跑一遍
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static void switchOnClient(ItemStack stack, boolean isModelSwitcher) {
        ConfigManager manager = ConfigManager.getInstance();
        ConfigManager.ConfigData config = manager.get();

        if (config == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.switch.config_not_ready"));
            return;
        }

        if (isModelSwitcher) {
            String next = nextInCycle(config.availableModels, config.model);
            if (next == null) {
                ClientChat.sendLiteral(Lang.tr("mcai.switch.no_models"));
                return;
            }
            manager.update(c -> c.model = next);
            McaiNetworking.sendSwitchToServer(McaiSwitchPayload.KIND_MODEL, next);
            ClientChat.sendLiteral(Lang.tr("mcai.switch.model_switched", ModelCatalog.describe(next)));
        } else {
            String next = nextInCycle(config.availableModes, config.mode);
            if (next == null) {
                ClientChat.sendLiteral(Lang.tr("mcai.switch.no_modes"));
                return;
            }
            manager.update(c -> c.mode = next);
            McaiNetworking.sendSwitchToServer(McaiSwitchPayload.KIND_MODE, next);
            ClientChat.sendLiteral(Lang.tr("mcai.switch.mode_switched", ModeCatalog.describe(next)));
        }
    }

    /** 在列表里往前循环一格；当前值不在列表里就回到第一个。 */
    private static String nextInCycle(List<String> options, String current) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        int index = options.indexOf(current);
        if (index < 0) {
            return options.get(0);
        }
        return options.get((index + 1) % options.size());
    }
}
