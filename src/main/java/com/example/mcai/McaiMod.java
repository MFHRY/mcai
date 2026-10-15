package com.example.mcai;

import com.example.mcai.item.McaiItemGiving;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

/**
 * Forge 主入口。
 *
 * <p><b>与 Fabric 版的结构差异：</b>Fabric 有两个入口点（{@code ModInitializer} 和
 * {@code ClientModInitializer}），由 {@code fabric.mod.json} 分别声明。Forge 只有
 * <b>一个</b> {@code @Mod} 类，而且它在<b>双端</b>都会被加载 —— 包括专用服务端。
 *
 * <p>所以客户端代码必须走 {@link DistExecutor#unsafeRunWhenOn} 延迟加载：
 * 直接在 {@code if (dist == CLIENT)} 里引用 {@code McaiModClient} 是不够的，
 * 方法校验阶段就会去加载那个类，专用服务端上会直接崩。
 */
@Mod(McaiMod.MOD_ID)
public class McaiMod {

    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McaiMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        LOGGER.info("Initializing mcAI (Forge)...");
        ConfigManager.getInstance();

        // ---- 模块 1：双重切换器（物品）----
        McaiItems.register(modBus);  // 改成 DeferredRegister，要拿 mod 总线
        McaiNetworking.initCommon(); // SimpleChannel + 服务端接收器
        McaiItemGiving.register();   // 玩家进服时发放切换器（防重复）

        // ---- 客户端专属模块 ----
        // 必须用 DistExecutor：这个 lambda 在专用服务端上永远不会被执行，
        // 因此 McaiModClient 也不会被类加载器碰到。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> McaiModClient.register(modBus));
    }
}
