package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * 「环境感知」系统提示词片段（1.24 新增）：把游戏版本和当前加载的模组列表告诉 AI。
 *
 * <p><b>要解决的问题：</b>模型默认按原版知识回答。你说"我的离心机不工作"，
 * 它一本正经地讲原版没有离心机——因为<b>它不知道你装了哪些模组</b>。
 * 拼上这一行之后，回答的针对性会明显变好。
 *
 * <p>列表长度有上限（{@code env_context_max_mods}），太大的整合包也不会把提示词撑爆。
 *
 * <p>文案固定用英文：这是喂给模型的元数据、不是给玩家看的界面文案，模组名本身也全是英文。
 *
 * <p>只依赖 FabricLoader 和 {@code SharedConstants}，两边（客户端/服务端）都能跑，
 * 所以放在 util 包里是安全的。
 */
public final class EnvironmentContext {

    private EnvironmentContext() {}

    private static volatile String cached;
    private static volatile int cachedMax = -1;

    /**
     * 组装提示词片段；功能关闭、或什么都取不到时返回 null（调用方按"不追加"处理）。
     */
    public static String build() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.envContext) {
            return null;
        }
        int max = config.envContextMaxMods;
        String value = cached;
        if (value == null || cachedMax != max) {
            value = compute(max);
            cached = value;
            cachedMax = max;
        }
        return value.isEmpty() ? null : value;
    }

    /** 模组列表不会在运行中变化，所以这里的结果可以一直缓存。 */
    private static String compute(int maxMods) {
        StringBuilder sb = new StringBuilder();
        try {
            String version = SharedConstants.getGameVersion().getName();
            if (version != null && !version.isBlank()) {
                sb.append("Target Minecraft version: ").append(version).append(". ");
            }
        } catch (Throwable ignored) {
            // 版本取不到就不写这一句
        }

        try {
            List<String> names = new ArrayList<>();
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String name = mod.getMetadata().getName();
                if (name == null || name.isBlank()) {
                    name = mod.getMetadata().getId();
                }
                if (name == null || name.isBlank()) {
                    continue;
                }
                // 原版和加载器本身不算"模组"，写进去只会占位置
                if (name.equalsIgnoreCase("Minecraft") || name.equalsIgnoreCase("Fabric Loader")
                        || name.equalsIgnoreCase("Fabric API")) {
                    continue;
                }
                names.add(name);
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);

            int total = names.size();
            if (total > 0) {
                List<String> shown = names;
                boolean truncated = false;
                if (maxMods > 0 && total > maxMods) {
                    shown = names.subList(0, maxMods);
                    truncated = true;
                }
                sb.append("Loaded mods (").append(total).append("): ")
                        .append(String.join(", ", shown));
                if (truncated) {
                    sb.append(", ...");
                }
                sb.append(". Answer with these mods in mind; do not assume vanilla-only behaviour.");
            }
        } catch (Throwable ignored) {
            // 模组列表取不到就只保留版本那一句
        }

        return sb.toString().trim();
    }

    /** 配置改了以后让缓存失效（{@code env_context_max_mods} 变化时 {@link #build()} 会自己重算）。 */
    public static void invalidate() {
        cached = null;
        cachedMax = -1;
    }
}
