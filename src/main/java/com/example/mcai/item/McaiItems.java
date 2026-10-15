package com.example.mcai.item;

import com.example.mcai.McaiMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品注册表。在 {@code McaiMod} 构造器里调用 {@link #register(IEventBus)}。
 *
 * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 那边是"new 出实例 + 立刻
 * {@code Registry.register(...)}"。Forge 用 {@link DeferredRegister}：先声明、
 * 稍后由注册事件统一登记。这不只是写法不同——Forge 在注册表冻结之后<b>不允许</b>
 * 再往 {@code ITEMS} 里塞东西，直接 register 会在启动期抛异常。
 *
 * <p>代价是取用时要多一个 {@code .get()}：{@code McaiItems.AI_MODEL_SWITCHER.get()}。
 * 所以这里存的是 {@link RegistryObject}，而不是裸的 {@code Item}。
 */
public final class McaiItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, McaiMod.MOD_ID);

    /** 模型切换器 */
    public static final RegistryObject<Item> AI_MODEL_SWITCHER =
            ITEMS.register("ai_model_switcher",
                    () -> new AiModelSwitcherItem(new Item.Properties().stacksTo(1)));

    /** 模式切换器 */
    public static final RegistryObject<Item> AI_MODE_SWITCHER =
            ITEMS.register("ai_mode_switcher",
                    () -> new AiModeSwitcherItem(new Item.Properties().stacksTo(1)));

    private McaiItems() {}

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        McaiMod.LOGGER.info("mcAI registering items: ai_model_switcher, ai_mode_switcher");
    }
}
