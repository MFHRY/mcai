package com.example.mcai.client;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

/**
 * 1.21.11 起按键分类从字符串变成了 {@link KeyBinding.Category} 实例，
 * 而 {@code Category.create(...)} <b>会注册这个分类</b>，同一个 id 第二次调用直接抛
 * "Category 'mcai:hud' is already registered"。
 *
 * <p>所以整个模组只在这里建一次，其它地方引用同一个常量。
 */
public final class McaiKeys {

    /** 所有 mcAI 快捷键共用的分类（显示名来自 lang 的 key.categories.mcai.hud）。 */
    public static final KeyBinding.Category MCAI_CATEGORY =
            KeyBinding.Category.create(Identifier.of("mcai", "hud"));

    private McaiKeys() {}
}
