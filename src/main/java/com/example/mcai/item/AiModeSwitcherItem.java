package com.example.mcai.item;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ModeCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 模式切换器：右键循环切换 {@code config.availableModes} 里的模式（chat / vision）。
 *
 * <p><b>1.20.1 的签名：</b>{@code appendHoverText(ItemStack, Level, List<Component>, TooltipFlag)}。
 * 1.21.1 那边叫 {@code appendTooltip}，而且第二个参数是 {@code Item.TooltipContext}
 * —— 那个类型在 1.20.1 里根本不存在，照抄必然编译不过。
 */
public class AiModeSwitcherItem extends Item {

    public AiModeSwitcherItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String mode = (config == null) ? null : config.mode;

        tooltip.add(Component.translatable("tooltip.mcai.mode_switcher.action").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.mcai.current", ModeCatalog.describe(mode)).withStyle(ChatFormatting.AQUA));
    }
}
