package com.example.mcai.util;

import com.example.mcai.ConfigManager;

/**
 * 统一拼装系统提示词（1.24 新增）。
 *
 * <p>在 1.24 之前，系统提示词只在聊天链路里拼过一次，截图链路用的是"裸"的
 * {@code mcai.prompt.system} —— 于是同一个模组里，聊天知道你在用哪个人格、
 * 截图却不知道。现在两条链路都走这里，行为一定一致。
 *
 * <p>拼装顺序（越靠后越"具体"，也越贴近模型的注意力）：
 * <ol>
 *   <li>语言文件里的基础提示词 —— 同时决定 AI 用哪种语言回答</li>
 *   <li>人格后缀（{@link PersonaCatalog}）</li>
 *   <li>长期记忆（{@code /ai remember} 记下的内容）</li>
 *   <li>环境上下文（游戏版本 + 已加载模组列表，见 {@link EnvironmentContext}）</li>
 * </ol>
 */
public final class SystemPrompt {

    private SystemPrompt() {}

    /** 组装完整系统提示词；任何一段取不到就跳过，绝不返回 null。 */
    public static String build() {
        StringBuilder sb = new StringBuilder(Lang.tr("mcai.prompt.system"));
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();

        if (config != null) {
            // #9 人格：当前 persona 的额外提示词后缀
            String personaSuffix = PersonaCatalog.systemSuffix(config.persona);
            if (personaSuffix != null && !personaSuffix.isBlank()) {
                sb.append(' ').append(personaSuffix);
            }

            // 1.24 长期记忆：玩家明确要求 AI 一直记住的事实
            if (config.memories != null && !config.memories.isEmpty()) {
                StringBuilder memories = new StringBuilder();
                for (String memory : config.memories) {
                    if (memory == null || memory.isBlank()) {
                        continue;
                    }
                    memories.append("\n- ").append(memory.trim());
                }
                if (memories.length() > 0) {
                    sb.append(' ').append(Lang.tr("mcai.prompt.memories")).append(memories);
                }
            }
        }

        // 1.24 环境感知：游戏版本 + 模组列表
        String environment = EnvironmentContext.build();
        if (environment != null && !environment.isBlank()) {
            sb.append(' ').append(environment);
        }

        return sb.toString();
    }
}
