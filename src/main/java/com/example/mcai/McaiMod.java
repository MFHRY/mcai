package com.example.mcai;

import com.example.mcai.item.McaiItemGiving;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Forge 主入口（26.1 / EventBus 7）。
 *
 * <p><b>与 1.20.1 Forge 版的结构差异</b>（每一条都是照着 26.1 的 MDK 与事件总线实测改的）：
 *
 * <ul>
 *   <li>构造器<b>注入</b> {@link FMLJavaModLoadingContext}，不再有
 *       {@code FMLJavaModLoadingContext.get().getModEventBus()} —— 那套 API 在 EB7 里没了。
 *       总线的类型也从 {@code IEventBus} 变成 {@link BusGroup}。</li>
 *   <li>{@code @Mod} 只剩 {@code value()} 一个属性，没有 {@code dist}。客户端专属装配
 *       因此不能靠注解分端，只能在 {@link FMLEnvironment#dist} 里判断。</li>
 *   <li>{@code DistExecutor} 已被移除。这里用最直接的方式：客户端那一行代码只在
 *       逻辑客户端上执行，专用服务端不会碰到 {@code McaiModClient} 这个类。</li>
 * </ul>
 */
@Mod(McaiMod.MOD_ID)
public class McaiMod {

    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McaiMod(FMLJavaModLoadingContext context) {
        BusGroup modBus = context.getModBusGroup();

        LOGGER.info("Initializing mcAI (Forge 26.1)...");
        ConfigManager.getInstance();

        // ---- 模块 1：双重切换器（物品）----
        McaiItems.register(modBus);  // DeferredRegister 要的是 BusGroup
        McaiNetworking.initCommon(); // SimpleChannel + 服务端接收器
        McaiItemGiving.register();   // 玩家进服时发放切换器（防重复）

        // ---- 客户端专属模块 ----
        // 只在这个分支里引用 McaiModClient：专用服务端上判断为 false，
        // 那个类永远不会被加载，也就不会碰到只存在于客户端的类型。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            McaiModClient.register(modBus);
        }
    }
}
