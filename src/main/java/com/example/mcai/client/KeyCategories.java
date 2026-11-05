package com.example.mcai.client;

import com.example.mcai.McaiMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * mcAI 的快捷键分类。
 *
 * <p>26.1 起 {@code KeyMapping} 的分类不再是字符串，而是 {@link KeyMapping.Category}
 * （一个 record）。它必须在<b>任何 {@code KeyMapping} 构造之前</b>
 * 通过 {@code Category.register(...)} 登记一次，否则「选项 → 控制」里
 * 不会出现 mcAI 这一栏，玩家的键位也就无处可改。
 *
 * <p>放成单独一个类，是因为快捷键分散在两处（{@link com.example.mcai.vision.VisionHandler}
 * 的 H/G/R/V 和 {@link AvatarWidget} 的 B）。分开各登记一次会得到两个不同的分类对象，
 * 界面上就会出现两栏一模一样的 "mcAI"——所以这里是唯一的那一份。
 *
 * <p>用静态常量而不是方法：类初始化就是登记动作，天然只发生一次，
 * 也不会有人把它误当成可以在运行期任意时刻调用的东西。
 */
public final class KeyCategories {

    public static final KeyMapping.Category MCAI =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(McaiMod.MOD_ID, "keys"));

    private KeyCategories() {}
}
