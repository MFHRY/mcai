package com.example.mcai.util;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * 本地化入口：所有面向玩家的文案都必须经过这里，不在代码里写死中文或英文。
 *
 * <p>语言文件在 {@code assets/mcai/lang/zh_cn.json} 与 {@code en_us.json}，
 * 游戏语言是哪个就显示哪个——同一个 jar 中英文都能用。
 *
 * <p><b>为什么用 {@link Text#translatable} 而不是
 * {@code net.minecraft.client.resource.language.I18n}：</b>
 * {@code I18n} 在客户端专属包里（{@code net.minecraft.client.*}），而这个 mod 允许装在
 * 专用服务端（发放切换器物品需要服务端侧代码）。一旦服务端加载到引用了 {@code I18n}
 * 的类就会 {@code NoClassDefFoundError}。{@code Text} 和 {@code Language} 都在 common
 * 包里，两端都安全。
 *
 * <p>{@code TranslatableTextContent.getString()} 查过字节码：它会走
 * {@code Language.getInstance().get(key)} 再做 {@code %s} 占位符替换，
 * 所以能直接拿到当前语言下的成品字符串。
 */
public final class Lang {

    private Lang() {}

    /**
     * 取一条当前游戏语言下的文案。
     *
     * <p>注意：Minecraft 的占位符是 {@code %s}（按顺序），字面量百分号要写成 {@code %%}。
     *
     * @param key  语言文件里的键
     * @param args 依次替换 {@code %s} 的参数
     * @return 成品字符串；键不存在时原样返回键名（不会抛异常）
     */
    public static String tr(String key, Object... args) {
        if (args.length == 0) {
            return Text.translatable(key).getString();
        }
        return Text.translatable(key, args).getString();
    }

    /**
     * 需要 {@link Text} 对象时用这个（保留样式，交给渲染器处理）。
     * 主要用于 tooltip、界面控件这类本来就在用 {@code Text} 的地方。
     */
    public static MutableText text(String key, Object... args) {
        if (args.length == 0) {
            return Text.translatable(key);
        }
        return Text.translatable(key, args);
    }

    /** 当前语言里是否真的有这条键。诊断用。 */
    public static boolean has(String key) {
        return net.minecraft.util.Language.getInstance().hasTranslation(key);
    }
}
