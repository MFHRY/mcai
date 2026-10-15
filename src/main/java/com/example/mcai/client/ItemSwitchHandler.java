package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiSwitchPayload;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.ModeCatalog;
import com.example.mcai.util.ModelCatalog;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionResultHolder;

import java.util.List;

/**
 * 客户端右键逻辑：循环切换模型 / 模式。
 *
 * <p>1.21.1 的坑：{@code UseItemCallback.interact} 返回的是
 * {@code InteractionResultHolder<ItemStack>}，不是 {@code ActionResult}
 * （ActionResult 在 1.21.1 只是个普通 enum，没有泛型）。所以要用
 * {@code InteractionResultHolder.pass(stack)} / {@code InteractionResultHolder.success(stack)}。
 */
public final class ItemSwitchHandler {

    private ItemSwitchHandler() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            boolean isModelSwitcher = stack.is(McaiItems.AI_MODEL_SWITCHER);
            boolean isModeSwitcher = stack.is(McaiItems.AI_MODE_SWITCHER);

            if (!isModelSwitcher && !isModeSwitcher) {
                return InteractionResultHolder.pass(stack);
            }

            if (!world.isClientSide()) {
                // 同一次右键的服务端侧（单人游戏）：接受但不动手，切换完全由客户端驱动，
                // 结果再通过 McaiSwitchPayload 同步回来，避免双端各切一次。
                return InteractionResultHolder.success(stack);
            }

            return switchOnClient(stack, isModelSwitcher);
        });
    }

    private static InteractionResultHolder<ItemStack> switchOnClient(ItemStack stack, boolean isModelSwitcher) {
        ConfigManager manager = ConfigManager.getInstance();
        ConfigManager.ConfigData config = manager.get();

        if (config == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.switch.config_not_ready"));
            return InteractionResultHolder.success(stack);
        }

        if (isModelSwitcher) {
            String next = nextInCycle(config.availableModels, config.model);
            if (next == null) {
                ClientChat.sendLiteral(Lang.tr("mcai.switch.no_models"));
                return InteractionResultHolder.success(stack);
            }
            manager.update(c -> c.model = next);
            // 服务端没装本模组时不会注册这个通道，直接 send 会抛异常/记警告。
            // 切换模型/模式本来就是纯客户端的（config 已改好），拿不到服务器回执也能用，
            // 所以这里先探测再发，发给原版服务器也不会报错。
            if (ClientPlayNetworking.canSend(McaiSwitchPayload.ID)) {
                ClientPlayNetworking.send(new McaiSwitchPayload(McaiSwitchPayload.KIND_MODEL, next));
            }
            ClientChat.sendLiteral(Lang.tr("mcai.switch.model_switched", ModelCatalog.describe(next)));
        } else {
            String next = nextInCycle(config.availableModes, config.mode);
            if (next == null) {
                ClientChat.sendLiteral(Lang.tr("mcai.switch.no_modes"));
                return InteractionResultHolder.success(stack);
            }
            manager.update(c -> c.mode = next);
            if (ClientPlayNetworking.canSend(McaiSwitchPayload.ID)) {
                ClientPlayNetworking.send(new McaiSwitchPayload(McaiSwitchPayload.KIND_MODE, next));
            }
            ClientChat.sendLiteral(Lang.tr("mcai.switch.mode_switched", ModeCatalog.describe(next)));
        }

        return InteractionResultHolder.success(stack);
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
