package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModeCatalog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 模式切换器：右键循环切换 {@code config.availableModes} 里的模式（chat / vision）。
 */
public class AiModeSwitcherItem extends Item {

    public AiModeSwitcherItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String mode = (config == null) ? null : config.mode;

        tooltip.add(Text.translatable("tooltip.mcai.mode_switcher.action").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mcai.current", ModeCatalog.describe(mode)).formatted(Formatting.AQUA));
    }
}
