package com.example.mcai;

import com.example.mcai.item.McaiItemGiving;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;

/**
 * Fabric 主入口（Minecraft 26.1.2）。
 *
 * <p>26.1 的游戏包已经<b>不再混淆</b>，Loom 直接给你带官方（Mojang）名称的
 * 原版 jar —— 没有 Yarn、没有 intermediary。所以这一版的源码和 Forge 26.1 版
 * 共用同一套原版 API 名称，只有"模组装配层"（入口点、事件、注册、网络）是
 * 加载器自己的那套。
 *
 * <p>Fabric 的 {@code main} 入口点在逻辑客户端和专用服务端都会执行，
 * 所以这里把双端都需要的模块放进来，和 Forge 版 {@code @Mod} 构造器等价。
 */
public class McaiMod implements ModInitializer {

    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing mcAI (Fabric 26.1)...");

        // ---- 模块 1：双重切换器（物品）----
        ConfigManager.getInstance();
        McaiItems.register();        // Registry.register(Registries.ITEM, ...)
        McaiNetworking.initCommon(); // PayloadTypeRegistry + 服务端接收器
        McaiItemGiving.register();   // 玩家进服时发放切换器（防重复）
    }
}
