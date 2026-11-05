package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import com.example.mcai.network.McaiSwitchPayload;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 客户端右键逻辑：循环切换模型 / 模式。
 *
 * <p><b>Forge 版与 Fabric 版的差异：</b>
 * <ol>
 *   <li>Fabric 的 {@code ItemEvents.USE} 直接返回
 *       {@link InteractionResult}（PASS = 没处理，SUCCESS = 吃掉这次右键）；
 *       Forge 的 {@code RightClickItem} 没有返回值，靠
 *       {@code setCanceled(true)} / {@code setCancellationResult(...)}。</li>
 *   <li>物品是普通常量，不需要 {@code .get()}。</li>
 *   <li>发包走 {@link McaiNetworking#sendSwitchToServer}（内部已经吞掉了
 *       "服务端没装模组"的失败）。</li>
 * </ol>
 *
 * <p>为什么要在服务端那一侧"接受但不动手"：切换完全由客户端驱动，服务端只是把
 * 结果<b>回写</b>配置。如果两侧都切一次，就会连跳两格。
 */
public final class ItemSwitchHandler {

    private ItemSwitchHandler() {}

    /**
     * 右键监听器（{@code ItemEvents.USE}）。
     *
     * @return PASS = 不是切换器，交给原版；SUCCESS = 已吃掉这次右键
     */
    public static InteractionResult onUse(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 1.21.2+ 没有 is(Item)，直接比物品实例
        boolean isModelSwitcher = stack.getItem() == McaiItems.AI_MODEL_SWITCHER;
        boolean isModeSwitcher = stack.getItem() == McaiItems.AI_MODE_SWITCHER;

        if (!isModelSwitcher && !isModeSwitcher) {
            return InteractionResult.PASS;
        }

        if (!level.isClientSide()) {
            // 同一次右键的服务端侧（单人游戏）：接受但不动手，避免双端各切一次
            return InteractionResult.SUCCESS;
        }

        switchOnClient(stack, isModelSwitcher);

        // 客户端处理完就吃掉这个事件：不要让原版的"使用物品"再跑一遍
        return InteractionResult.SUCCESS;
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
