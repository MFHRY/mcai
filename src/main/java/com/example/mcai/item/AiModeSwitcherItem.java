package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModeCatalog;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.List;

/**
 * 模式切换器：右键循环切换 {@code config.availableModes} 里的模式（chat / vision）。
 */
public class AiModeSwitcherItem extends Item {

    public AiModeSwitcherItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag type) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String mode = (config == null) ? null : config.mode;

        tooltip.add(Component.translatable("tooltip.mcai.mode_switcher.action").formatted(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.mcai.current", ModeCatalog.describe(mode)).formatted(ChatFormatting.AQUA));
    }
}
