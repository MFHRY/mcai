package com.example.mcai.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模式字典：把配置里的 {@code mode} 英文值映射成中文，用于聊天栏提示。
 * 查不到时显示 {@code <原值> (未知模式)}。
 */
public final class ModeCatalog {

    private static final Map<String, String> MODES = new LinkedHashMap<>();

    private ModeCatalog() {}

    static {
        MODES.put("chat", "聊天");
        MODES.put("vision", "视觉识别");
    }

    /** 渲染成 {@code <原英文模式ID> (<中文名>)}。 */
    public static String describe(String mode) {
        String id = (mode == null || mode.isBlank()) ? "未知" : mode.trim();
        String chinese = MODES.get(id.toLowerCase(Locale.ROOT));
        return chinese == null ? id + " (未知模式)" : id + " (" + chinese + ")";
    }
}
