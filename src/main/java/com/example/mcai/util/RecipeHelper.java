package com.example.mcai.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.util.context.ContextParameterMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线配方查询（#10「背包懒汉包」）。
 *
 * <p><b>为什么做成"我现在能做什么"而不是"怎么做某样东西"：</b>
 * Minecraft 客户端拿到的是<b>服务器同步过来的配方表</b>，可以正向判定
 * 「这份材料能不能满足这个配方」，但反向"从一句自然语言找出配方"
 * 需要自己做一整套名字索引与模糊匹配，又慢又容易误判。
 * 而"背包里这些东西现在能合成什么"只要遍历配方 + 数材料，
 * 既准确又<b>完全不花钱</b>（零 token、不用联网）。
 *
 * <p><b>1.21.11 的取数方式：</b>这一版把配方表从 {@code world.getRecipeManager()}
 * 挪到了客户端的 {@link ClientRecipeBook}（{@code player.getRecipeBook()}）。
 * 配方本身以 {@link RecipeDisplayEntry} 的形式下发，材料在
 * {@code craftingRequirements()} 里，产物用 {@code getStacks(参数表)} 取。
 * 这样单人/多人服都能用，也不依赖服务端。
 */
public final class RecipeHelper {

    private RecipeHelper() {}

    /** 一条"能合成的东西"：产物 + 需要的材料。 */
    public record Craftable(String output, int count, List<String> ingredients) {}

    /** 一条按名字找到的配方（产物 + 材料），给 {@code ?craft} 类提问用。 */
    public record Match(String output, List<String> ingredients) {}

    /**
     * 列出玩家背包里当前材料就能合成的东西（只算工作台那类合成配方）。
     * 不在世界里返回空列表。
     */
    public static List<Craftable> craftableNow() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        List<Craftable> out = new ArrayList<>();
        if (player == null || world == null) {
            return out;
        }

        try {
            // 用产物的显示名去重，避免同一个东西因为多个配方（比如不同木材）刷屏
            Map<String, Craftable> unique = new LinkedHashMap<>();
            int scanned = 0;
            int crafting = 0;
            for (RecipeResultCollection collection : clientRecipes(player)) {
                for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                    scanned++;
                    List<Ingredient> required = entry.craftingRequirements().orElse(null);
                    if (required == null || required.isEmpty()) {
                        // 熔炉 / 切石机这类没有"合成格材料"，不在本次回答范围内
                        continue;
                    }
                    crafting++;
                    if (!canCraftFromInventory(required, player)) {
                        continue;
                    }
                    ItemStack result = outputOf(entry, world);
                    if (result == null || result.isEmpty()) {
                        continue;
                    }
                    String name = result.getName().getString();
                    unique.putIfAbsent(name, new Craftable(
                            name, result.getCount(), describeIngredients(required)));
                }
            }
            // 诊断：扫了多少、其中合成配方多少、最后命中多少。
            // 结果为空时靠这条日志就能区分"配方表拿不到"和"材料确实不够"。
            com.example.mcai.McaiMod.LOGGER.info(
                    "mcAI recipe scan: {} total, {} crafting, {} craftable now",
                    scanned, crafting, unique.size());
            out.addAll(unique.values());
        } catch (Throwable t) {
            com.example.mcai.McaiMod.LOGGER.warn("mcAI recipe scan failed: {}", t.toString());
            System.err.println("[mcAI] Recipe scan failed: " + t.getMessage());
        }
        return out;
    }

    /** 客户端配方书里的全部配方分组；拿不到就返回空表。 */
    private static List<RecipeResultCollection> clientRecipes(ClientPlayerEntity player) {
        ClientRecipeBook book = player.getRecipeBook();
        if (book == null) {
            return List.of();
        }
        List<RecipeResultCollection> results = book.getOrderedResults();
        return results == null ? List.of() : results;
    }

    /** 取配方的产物（只用于拿名字和数量）。 */
    private static ItemStack outputOf(RecipeDisplayEntry entry, ClientWorld world) {
        try {
            ContextParameterMap params = SlotDisplayContexts.createParameters(world);
            List<ItemStack> stacks = entry.getStacks(params);
            if (stacks == null || stacks.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return stacks.get(0);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 判断背包里的材料够不够做这条配方。
     *
     * <p>玩家平时并没有开着合成界面，背包里也没有格子布局，所以这里不跑
     * {@code matches(...)}，而是直接统计材料数量——对有序/无序配方都成立。
     *
     * <p>材料之间可能互相替代（比如任意木板），这里把「配方槽位」和「背包物品」建成一张
     * 二分图跑最大匹配（Kuhn 算法）：只有当每个槽位都能分到一份不同的背包物品时才判定为
     * 可合成。这也修正了早期贪心匹配的<b>漏报</b>。
     */
    private static boolean canCraftFromInventory(List<Ingredient> required, ClientPlayerEntity player) {
        List<Ingredient> needed = new ArrayList<>();
        for (Ingredient ingredient : required) {
            if (ingredient != null && !ingredient.isEmpty()) {
                needed.add(ingredient);
            }
        }
        if (needed.isEmpty()) {
            return false;
        }

        // 先把背包里的物品数量统计出来
        Map<net.minecraft.item.Item, Integer> available = new LinkedHashMap<>();
        try {
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack != null && !stack.isEmpty()) {
                    available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        } catch (Throwable t) {
            return false;
        }

        return maxMatchingSatisfies(needed, available);
    }

    /**
     * 二分图最大匹配（Kuhn 算法）判断能否给每个必需槽位分配一份（不重复的）背包物品。
     *
     * <p>左部＝配方槽位；右部＝把每种背包物品按数量摊开成的一「份」。
     * 只要存在覆盖全部槽位的匹配，就判定为可合成。n 很小（合成配方最多 9 个槽位），
     * 运行开销可以忽略。
     */
    private static boolean maxMatchingSatisfies(List<Ingredient> required,
                                                Map<net.minecraft.item.Item, Integer> available) {
        int n = required.size();
        // 每个槽位可选哪些物品类型（只保留背包里真实有货的）
        List<java.util.List<net.minecraft.item.Item>> slotItems = new ArrayList<>(n);
        java.util.Set<net.minecraft.item.Item> allItems = new java.util.HashSet<>();
        for (Ingredient ingredient : required) {
            java.util.Set<net.minecraft.item.Item> opts = new java.util.HashSet<>();
            ingredient.getMatchingItems().forEach(entry -> {
                if (entry == null) {
                    return;
                }
                net.minecraft.item.Item item = entry.value();
                if (item != null && available.containsKey(item)) {
                    opts.add(item);
                }
            });
            if (opts.isEmpty()) {
                return false; // 该槽位没有任何一种可用的替代材料
            }
            slotItems.add(new ArrayList<>(opts));
            allItems.addAll(opts);
        }

        // 右部顶点：把每种物品按数量摊开成多份（每份只能被一个槽位独占）
        List<net.minecraft.item.Item> unitItems = new ArrayList<>();
        for (net.minecraft.item.Item item : allItems) {
            int cap = Math.min(available.get(item), n);
            for (int c = 0; c < cap; c++) {
                unitItems.add(item);
            }
        }
        if (unitItems.isEmpty()) {
            return false;
        }

        // 邻接表：槽位 -> 它能接受的「份」的下标
        List<java.util.List<Integer>> adj = new ArrayList<>(n);
        for (java.util.List<net.minecraft.item.Item> opts : slotItems) {
            java.util.List<Integer> list = new ArrayList<>();
            for (int u = 0; u < unitItems.size(); u++) {
                if (opts.contains(unitItems.get(u))) {
                    list.add(u);
                }
            }
            adj.add(list);
        }

        // Kuhn 算法求最大匹配；能盖住全部槽位即为可合成
        int[] matchSlot = new int[unitItems.size()];
        java.util.Arrays.fill(matchSlot, -1);
        int matches = 0;
        for (int slot = 0; slot < n; slot++) {
            if (augment(slot, adj, matchSlot, new boolean[unitItems.size()])) {
                matches++;
            }
        }
        return matches == n;
    }

    /** 为 slot 尝试找一条增广路；找到并更新匹配后返回 true。 */
    private static boolean augment(int slot, List<java.util.List<Integer>> adj,
                                   int[] matchSlot, boolean[] visited) {
        for (int u : adj.get(slot)) {
            if (visited[u]) {
                continue;
            }
            visited[u] = true;
            if (matchSlot[u] == -1 || augment(matchSlot[u], adj, matchSlot, visited)) {
                matchSlot[u] = slot;
                return true;
            }
        }
        return false;
    }

    /** 把配方需要的材料列成文字。 */
    private static List<String> describeIngredients(List<Ingredient> ingredients) {
        List<String> lines = new ArrayList<>();
        try {
            // 空槽位（Ingredient.EMPTY）跳过，否则会列出一堆"空"
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Ingredient ingredient : ingredients) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                String name = firstItemName(ingredient);
                if (name == null) {
                    continue;
                }
                counts.merge(name, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                lines.add(e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey());
            }
        } catch (Throwable ignored) {
            // 拿不到材料就不显示材料，产物仍然有用
        }
        return lines;
    }

    /** 取一个材料槽位里第一种物品的显示名。 */
    private static String firstItemName(Ingredient ingredient) {
        try {
            List<ItemStack> stacks = new ArrayList<>();
            ingredient.getMatchingItems().limit(1).forEach(entry -> {
                if (entry != null && entry.value() != null) {
                    stacks.add(new ItemStack(entry.value()));
                }
            });
            if (stacks.isEmpty()) {
                return null;
            }
            return stacks.get(0).getName().getString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按名字找配方（给 {@code !ai 怎么做钻石镐} 这类提问用）。
     * 匹配产物显示名包含查询词的配方；找不到返回空列表。
     */
    public static List<Match> findByName(String query) {
        List<Match> out = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || query == null || query.isBlank()) {
            return out;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) {
            return out;
        }
        try {
            for (RecipeResultCollection collection : clientRecipes(player)) {
                for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                    List<Ingredient> required = entry.craftingRequirements().orElse(null);
                    if (required == null || required.isEmpty()) {
                        continue;
                    }
                    ItemStack icon = outputOf(entry, world);
                    if (icon == null || icon.isEmpty()) {
                        continue;
                    }
                    String name = icon.getName().getString().toLowerCase(Locale.ROOT);
                    if (name.contains(needle) || needle.contains(name)) {
                        out.add(new Match(icon.getName().getString(), describeIngredients(required)));
                    }
                    if (out.size() >= 3) {
                        break;
                    }
                }
                if (out.size() >= 3) {
                    break;
                }
            }
        } catch (Throwable t) {
            // 查询失败就当作没找到，交给 AI 回答
        }
        return out;
    }

    /** 把一条配方渲染成人话（产物 + 材料）。 */
    public static String describe(Match match) {
        String output = match == null ? Lang.tr("mcai.value.unknown") : match.output();
        List<String> lines = match == null ? List.of() : match.ingredients();
        String ingredients = lines.isEmpty()
                ? Lang.tr("mcai.craft.unknown_ingredients")
                : String.join(Lang.tr("mcai.craft.separator"), lines);
        return Lang.tr("mcai.craft.line", output, ingredients);
    }

    // ------------------------------------------------- 自然语言提问的本地拦截

    /** 出现这些词才认为玩家是在问"怎么做某样东西"（避免抢答无关问题）。 */
    private static final String[] CRAFT_HINTS = {
            "怎么做", "怎么合成", "如何制作", "如何合成", "怎么造", "怎样做", "配方",
            "how to craft", "how do i craft", "how to make", "how do i make",
            "recipe for", "recipe of",
    };

    /**
     * 尝试在本地回答一个"怎么做 X"的问题。<b>成功时不花任何 token</b>。
     *
     * <p>匹配思路：玩家的问句里通常直接包含物品名（例如"怎么做钻石镐"），
     * 所以拿配方表里每条配方的产物名去问句里找子串即可，无需分词。
     * 命中最长的那个名字（最具体）作为答案。
     *
     * @return 回答文本；没匹配到、或不在世界里时返回 null（由调用方继续走 AI）
     */
    public static String answerCraftQuestion(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        boolean craftIntent = false;
        for (String hint : CRAFT_HINTS) {
            if (lower.contains(hint)) {
                craftIntent = true;
                break;
            }
        }
        if (!craftIntent) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return null;
        }

        Match best = null;
        int bestLength = 1; // 至少 2 个字才算匹配，避免单字误命中
        try {
            for (RecipeResultCollection collection : clientRecipes(player)) {
                for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
                    List<Ingredient> required = entry.craftingRequirements().orElse(null);
                    if (required == null || required.isEmpty()) {
                        continue;
                    }
                    ItemStack icon = outputOf(entry, world);
                    if (icon == null || icon.isEmpty()) {
                        continue;
                    }
                    String name = icon.getName().getString();
                    if (name.length() > bestLength && lower.contains(name.toLowerCase(Locale.ROOT))) {
                        best = new Match(name, describeIngredients(required));
                        bestLength = name.length();
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        }

        if (best == null) {
            return null;
        }
        return Lang.tr("mcai.craft.local_answer") + " " + describe(best);
    }
}
