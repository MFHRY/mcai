package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModelCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 模型切换器：右键循环切换 {@code config.availableModels} 里的模型。
 *
 * <p><b>26.1 的签名：</b>
 * {@code appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay,
 * Consumer<Component>, TooltipFlag)}。
 *
 * <p>和 1.20.1 的区别不只是名字：1.20.1 是"往 List 里 add"，现在是把每行
 * <b>accept 给一个 Consumer</b>。所以这里仍然一行一行 accept，但没有任何
 * "要不要清空列表"的假设——列表从来就不归我们。
 */
public class AiModelSwitcherItem extends Item {

    public AiModelSwitcherItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String model = (config == null) ? null : config.model;

        tooltip.accept(Component.translatable("tooltip.mcai.model_switcher.action").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.mcai.current", ModelCatalog.describe(model)).withStyle(ChatFormatting.AQUA));
    }
}
