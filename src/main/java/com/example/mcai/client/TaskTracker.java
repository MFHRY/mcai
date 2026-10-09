package com.example.mcai.client;

import com.example.mcai.util.Lang;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 任务（#19）的本地存储。
 *
 * <p>只存当前这一条任务文本，落在 {@code config/mcai-task.txt}，
 * <b>不参与多轮上下文、也不会被上传</b>（除了生成它的那一次提问本身）。
 *
 * <p>读写都是同步小文件，由命令线程直接调用；文件很小（几百字节），
 * 不会造成可感知的卡顿。读写失败一律静默降级成"没有任务"。
 */
public final class TaskTracker {

    private TaskTracker() {}

    private static final String FILE_NAME = "mcai-task.txt";

    private static String cached = null;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 当前任务文本；没有则返回 null。 */
    public static synchronized String current() {
        if (cached != null) {
            return cached.isBlank() ? null : cached;
        }
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return null;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            cached = text;
            return text.isBlank() ? null : text;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 保存一条新任务。 */
    public static synchronized void set(String task) {
        cached = task == null ? "" : task;
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, cached, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("[mcAI] Failed to save task: " + t.getMessage());
        }
    }

    /** 清空任务。 */
    public static synchronized void clear() {
        cached = "";
        try {
            Files.deleteIfExists(file());
        } catch (Throwable ignored) {
            // 删不掉就只清内存，下次读取仍会看到旧内容——不影响主要功能
        }
    }

    /**
     * 组装"让 AI 出任务"的提示词：把背包里有什么告诉模型，
     * 这样它给的任务是"用现有材料能做出来的"，而不是空谈。
     */
    public static String buildTaskPrompt() {
        String inventory = inventorySummary();
        String base = Lang.tr("mcai.task.prompt");
        return inventory.isEmpty() ? base : base + "\n\n" + inventory;
    }

    /** 把背包内容列成 "物品 x数量" 的清单。 */
    private static String inventorySummary() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        net.minecraft.client.network.ClientPlayerEntity player = client.player;
        if (player == null) {
            return "";
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                net.minecraft.item.ItemStack stack = inventory.getStack(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                counts.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
            }
        } catch (Throwable t) {
            return "";
        }
        if (counts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Lang.tr("mcai.task.inventory_header")).append('\n');
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" x").append(e.getValue()).append('\n');
        }
        return sb.toString().trim();
    }
}
