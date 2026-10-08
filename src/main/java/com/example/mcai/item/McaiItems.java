package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 物品注册表。必须在 {@code McaiMod.onInitialize()} 里调用 {@link #register()}。
 *
 * <p>{@code McaiMod.MOD_ID} 是编译期常量，javac 会内联，所以这里不存在静态初始化顺序问题。
 */
public final class McaiItems {

    /** 模型切换器 */
    public static final Item AI_MODEL_SWITCHER =
            new AiModelSwitcherItem(new Item.Settings().maxCount(1));

    /** 模式切换器 */
    public static final Item AI_MODE_SWITCHER =
            new AiModeSwitcherItem(new Item.Settings().maxCount(1));

    private McaiItems() {}

    public static void register() {
        Registry.register(Registries.ITEM,
                Identifier.of(McaiMod.MOD_ID, "ai_model_switcher"), AI_MODEL_SWITCHER);
        Registry.register(Registries.ITEM,
                Identifier.of(McaiMod.MOD_ID, "ai_mode_switcher"), AI_MODE_SWITCHER);
        McaiMod.LOGGER.info("mcAI 已注册物品: ai_model_switcher, ai_mode_switcher");
    }
}
