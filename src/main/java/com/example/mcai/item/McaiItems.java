package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * 物品注册表（Fabric 26.1）。
 *
 * <p>Forge 版走 {@code DeferredRegister}；Fabric 就是 {@code Registry.register(...)}。
 * 物品对象是静态常量，注册动作放在 {@link #register()}（{@code main} 入口点调用）。
 *
 * <p><b>26.1 的两点：</b>
 * <ul>
 *   <li>{@code Registry.register} 的第一个参数是注册表对象
 *       （{@link BuiltInRegistries#ITEM}）；{@link Registries#ITEM} 现在只是
 *       注册表的 {@code ResourceKey}，不能再当注册表用。</li>
 *   <li>{@code Item.Properties} 必须自带 id（{@code setId(ResourceKey<Item>)}），
 *       漏了就在注册表冻结时抛 "Item id not set"。id 必须和注册用的
 *       {@link Identifier} 一致，否则客户端和服务端会把它当成两个不同的物品。</li>
 * </ul>
 */
public final class McaiItems {

    /** 模型切换器 */
    public static final Item AI_MODEL_SWITCHER = new AiModelSwitcherItem(
            new Item.Properties().stacksTo(1).setId(key("ai_model_switcher")));

    /** 模式切换器 */
    public static final Item AI_MODE_SWITCHER = new AiModeSwitcherItem(
            new Item.Properties().stacksTo(1).setId(key("ai_mode_switcher")));

    private McaiItems() {}

    private static ResourceKey<Item> key(String name) {
        return ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, name));
    }

    /** 必须在 {@code main} 入口点调用（客户端/服务端都会注册）。 */
    public static void register() {
        // 26.1 的 Registry.register 第一个参数要的是注册表对象本身
        // （BuiltInRegistries.ITEM），不再是 Registries.ITEM（那只是注册表的 ResourceKey）。
        Registry.register(BuiltInRegistries.ITEM, key("ai_model_switcher"), AI_MODEL_SWITCHER);
        Registry.register(BuiltInRegistries.ITEM, key("ai_mode_switcher"), AI_MODE_SWITCHER);
        McaiMod.LOGGER.info("mcAI registering items: ai_model_switcher, ai_mode_switcher");
    }
}
