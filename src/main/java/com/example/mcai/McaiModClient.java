package com.example.mcai;

import com.example.mcai.client.AiCommand;
import com.example.mcai.client.DeathRecap;
import com.example.mcai.client.ItemSwitchHandler;
import com.example.mcai.client.StartupGuide;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.vision.VisionHandler;
import net.fabricmc.api.ClientModInitializer;

public class McaiModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ChatHandler.getInstance().register();

        // ---- 模块 1：双重切换器（客户端右键逻辑）----
        ItemSwitchHandler.register();

        // ---- 模块 2：截图与多模态识别（快捷键 H / G）----
        VisionHandler.register();

        // ---- 模块 3：/ai 客户端指令与统计 ----
        AiCommand.register();

        // ---- 模块 4：思考中动画 + 耗时统计 ----
        ThinkingIndicator.register();

        // ---- 模块 5：死亡复盘（#1）+ Discord 播报（#15），默认关闭 ----
        DeathRecap.register();

        // ---- 进世界后打印功能清单 ----
        StartupGuide.register();
    }
}
