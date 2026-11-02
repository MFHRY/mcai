package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册表。在 {@code McaiMod} 构造器里调用 {@link #register(BusGroup)}。
 *
 * <p><b>与 1.20.1 的差异：</b>{@code DeferredRegister.register(...)} 现在要的是
 * {@link BusGroup}（EB7 的总线类型），不是 {@code IEventBus}。
 *
 * <p><b>1.21.2+ 多出来的一步：</b>{@code Item.Properties} 必须自带 id
 * （{@code setId(ResourceKey<Item>)}）。1.20.1 时注册表会兜底填 id，现在不会 ——
 * 漏了就抛 "Item id not set"，而且是在注册表冻结时才炸，离出错的那一行很远。
 * 这里的 id 必须和 {@code ITEMS.register("名字", ...)} 的名字一致，否则物品在
 * 客户端和服务端会认成两个不同的东西。
 */
public final class McaiItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, McaiMod.MOD_ID);

    private static ResourceKey<Item> key(String name) {
        return ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, name));
    }

    /** 模型切换器 */
    public static final RegistryObject<Item> AI_MODEL_SWITCHER =
            ITEMS.register("ai_model_switcher",
                    () -> new AiModelSwitcherItem(
                            new Item.Properties().stacksTo(1).setId(key("ai_model_switcher"))));

    /** 模式切换器 */
    public static final RegistryObject<Item> AI_MODE_SWITCHER =
            ITEMS.register("ai_mode_switcher",
                    () -> new AiModeSwitcherItem(
                            new Item.Properties().stacksTo(1).setId(key("ai_mode_switcher"))));

    private McaiItems() {}

    public static void register(BusGroup modBus) {
        ITEMS.register(modBus);
        McaiMod.LOGGER.info("mcAI registering items: ai_model_switcher, ai_mode_switcher");
    }
}
