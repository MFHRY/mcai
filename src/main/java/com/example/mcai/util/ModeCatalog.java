package com.example.mcai.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模式字典：把配置里的 {@code mode} 英文值映射成人话，用于聊天栏提示。
 * 名称走语言文件（{@code mcai.mode.*}），查不到时显示 {@code <原值> (未知模式)}。
 */
public final class ModeCatalog {

    private static final Map<String, String> MODES = new LinkedHashMap<>();

    private ModeCatalog() {}

    static {
        MODES.put("chat", "mcai.mode.chat");
        MODES.put("vision", "mcai.mode.vision");
    }

    /** 渲染成 {@code <原英文模式ID> (<模式名>)}，模式名按当前游戏语言显示。 */
    public static String describe(String mode) {
        String id = (mode == null || mode.isBlank())
                ? Lang.tr("mcai.value.unknown")
                : mode.trim();
        String key = MODES.get(id.toLowerCase(Locale.ROOT));
        return key == null
                ? id + " (" + Lang.tr("mcai.mode.unknown") + ")"
                : id + " (" + Lang.tr(key) + ")";
    }
}
