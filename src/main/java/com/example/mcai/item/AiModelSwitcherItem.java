package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModelCatalog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * 模型切换器：右键循环切换 {@code config.availableModels} 里的模型。
 *
 * <p>注意 1.21.1 的 appendTooltip 签名是
 * {@code (ItemStack, Item.TooltipContext, List<Text>, TooltipType)}，
 * 不是新版本那种 Consumer + TooltipDisplayComponent。
 */
public class AiModelSwitcherItem extends Item {

    public AiModelSwitcherItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String model = (config == null) ? null : config.model;

        tooltip.add(Text.translatable("tooltip.mcai.model_switcher.action").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("tooltip.mcai.current", ModelCatalog.describe(model)).formatted(Formatting.AQUA));
    }
}
