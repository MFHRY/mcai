package com.example.mcai.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 集成（1.24 新增）：让玩家能从「Mod Menu → mcAI → 设置」打开配置界面。
 *
 * <p><b>为什么值得做：</b>之前打开设置只有两条路 —— 在聊天栏敲 {@code /ai config}，
 * 或者手动编辑 {@code config/mcai.json}。而 Fabric 玩家的默认习惯是去 Mod Menu 里找齿轮图标，
 * 找不到就以为这个模组"没有配置项"。
 *
 * <p><b>加载安全：</b>这个类实现了 Mod Menu 的接口，没装 Mod Menu 时类里的符号是解析不了的。
 * 所以它<b>不能</b>出现在 {@code main} / {@code client} 入口点里（那会在没装 Mod Menu 的
 * 环境直接 NoClassDefFoundError）。它只挂在 {@code fabric.mod.json} 的
 * <b>{@code modmenu} 入口点</b>下 —— 这个入口点只有 Mod Menu 自己会去读，
 * 没装就永远不会被加载。同时 {@code fabric.mod.json} 里用 {@code suggests} 声明它是可选前置。
 *
 * <p>顺便一提：设置界面本身（{@link ConfigScreen}）不依赖 Mod Menu，
 * 所以点这里进去和 {@code /ai config} 进去是<b>同一个界面</b>，不存在两套配置 UI 要维护。
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // parent 就是 Mod Menu 的模组列表界面，ConfigScreen 会在"取消"时回到它
        return parent -> new ConfigScreen(parent);
    }
}
