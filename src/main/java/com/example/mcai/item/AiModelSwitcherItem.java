package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModelCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 模型切换器：右键循环切换 {@code config.availableModels} 里的模型。
 *
 * <p><b>1.20.1 的签名：</b>{@code appendHoverText(ItemStack, Level, List<Component>, TooltipFlag)}。
 * 1.21.1 那边叫 {@code appendTooltip}，第二个参数是 {@code Item.TooltipContext}
 * —— 1.20.1 没有那个类型。
 */
public class AiModelSwitcherItem extends Item {

    public AiModelSwitcherItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String model = (config == null) ? null : config.model;

        tooltip.add(Component.translatable("tooltip.mcai.model_switcher.action").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.mcai.current", ModelCatalog.describe(model)).withStyle(ChatFormatting.AQUA));
    }
}
