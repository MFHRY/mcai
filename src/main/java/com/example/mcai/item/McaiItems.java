package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/**
 * 物品注册表。必须在 {@code McaiMod.onInitialize()} 里调用 {@link #register()}。
 *
 * <p>{@code McaiMod.MOD_ID} 是编译期常量，javac 会内联，所以这里不存在静态初始化顺序问题。
 */
public final class McaiItems {

    /** 模型切换器 */
    public static final Item AI_MODEL_SWITCHER =
            new AiModelSwitcherItem(new Item.Properties().stacksTo(1));

    /** 模式切换器 */
    public static final Item AI_MODE_SWITCHER =
            new AiModeSwitcherItem(new Item.Properties().stacksTo(1));

    private McaiItems() {}

    public static void register() {
        Registry.register(Registries.ITEM,
                new ResourceLocation(McaiMod.MOD_ID, "ai_model_switcher"), AI_MODEL_SWITCHER);
        Registry.register(Registries.ITEM,
                new ResourceLocation(McaiMod.MOD_ID, "ai_mode_switcher"), AI_MODE_SWITCHER);
        McaiMod.LOGGER.info("mcAI registered items: ai_model_switcher, ai_mode_switcher");
    }
}
