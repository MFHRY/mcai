package com.example.mcai;

import com.example.mcai.item.McaiItemGiving;
import com.example.mcai.item.McaiItems;
import com.example.mcai.network.McaiNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McaiMod implements ModInitializer {
    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("正在初始化 mcAI 主入口...");
        ConfigManager.getInstance();

        // ---- 模块 1：双重切换器（物品）----
        McaiItems.register();        // 注册 ai_model_switcher / ai_mode_switcher
        McaiNetworking.initCommon(); // 注册 CustomPayload 类型 + 服务端接收器
        McaiItemGiving.register();   // 玩家进服时发放切换器（防重复）
    }
}
