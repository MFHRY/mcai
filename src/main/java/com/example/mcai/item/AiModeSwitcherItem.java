package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModeCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 模式切换器：右键循环切换 {@code config.availableModes} 里的模式（chat / vision）。
 *
 * <p><b>26.1 的签名：</b>
 * {@code appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay,
 * Consumer<Component>, TooltipFlag)} —— 提示行通过 Consumer 逐条吐出，不再是往 List 里 add。
 */
public class AiModeSwitcherItem extends Item {

    public AiModeSwitcherItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String mode = (config == null) ? null : config.mode;

        tooltip.accept(Component.translatable("tooltip.mcai.mode_switcher.action").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.mcai.current", ModeCatalog.describe(mode)).withStyle(ChatFormatting.AQUA));
    }
}
