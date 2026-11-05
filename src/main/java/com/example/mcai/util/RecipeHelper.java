package com.example.mcai.util;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 离线配方查询（#10「背包懒汉包」）。
 *
 * <h2>26.1 为什么整个重写了</h2>
 *
 * <p>1.20.1 这里遍历 {@code client.level.getRecipeManager().getRecipes()}，直接读
 * {@code Recipe.getIngredients()} 自己算"材料够不够"。<b>26.1 这条路不存在了</b>：
 *
 * <ul>
 *   <li>{@code Level.getRecipeManager()} 没了，客户端只剩 {@code recipeAccess()}，
 *       而它只暴露"某物品能不能烧"这类<b>属性表</b>（{@code RecipePropertySet}）与切石机配方；</li>
 *   <li>{@code Recipe} 接口本身也瘦身了：没有 {@code getIngredients()} / {@code getResultItem()} /
 *       {@code getToastSymbol()}，配方内容改成通过 {@code display()} 返回的
 *       <b>RecipeDisplay</b> 表达，服务端只把这份"展示数据"同步给客户端。</li>
 * </ul>
 *
 * <p>所以现在正确的数据源是 <b>{@link ClientRecipeBook}</b>（玩家已解锁配方的显示条目）：
 * <ul>
 *   <li>能不能做 → {@code RecipeDisplayEntry.canCraft(StackedItemContents)}，
 *       而 {@code StackedItemContents} 由 {@code Inventory.fillStackedContents} 直接从背包灌进去。
 *       这一步顺带解决了 1.20.1 里我们自己写的"二分图最大匹配"问题——原版这套本来就处理了
 *       材料互相替代（任意木板/任意羊毛）。</li>
 *   <li>产物是什么 → {@code entry.display().result()}（{@link SlotDisplay}），
 *       这里按槽位类型解析成物品栈，不需要再传 {@code ContextMap}。</li>
 *   <li>材料是什么 → 优先用 {@code entry.craftingRequirements()}（服务端同步的 Ingredient 列表），
 *       没有时退回从 display 的 ingredients 推断。</li>
 * </ul>
 *
 * <p><b>代价（如实说明）：</b>客户端只拿得到"玩家已经解锁/见过的配方"，
 * 所以列出来的是<b>配方书里有的</b>东西。这与原版配方书的行为一致；
 * 1.20.1 那种"服务器全量配方表"在 26.1 的同步模型下不可能再拿到。
 *
 * <p>仍然<b>完全不花钱</b>：零 token、不用联网。
 */
public final class RecipeHelper {

    private RecipeHelper() {}

    /** 一条"能合成的东西"：产物 + 需要的材料。 */
    public record Craftable(String output, int count, List<String> ingredients) {}

    /** 本地匹配到的一条配方（26.1 起不再有客户端可用的 {@code Recipe} 对象，用它代替）。 */
    public record Match(ItemStack result, List<String> ingredients) {}

    /**
     * 列出玩家背包里当前材料就能合成的东西。
     * 不在世界里返回空列表。
     */
    public static List<Craftable> craftableNow() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        List<Craftable> out = new ArrayList<>();
        if (player == null || client.level == null) {
            return out;
        }

        try {
            ClientRecipeBook book = player.getRecipeBook();
            // 背包 → 可被原版判定逻辑消费的"堆叠内容"
            StackedItemContents contents = new StackedItemContents();
            player.getInventory().fillStackedContents(contents);

            // 用产物的显示名去重，避免同一个东西因为多个配方（比如不同木材）刷屏
            Map<String, Craftable> unique = new LinkedHashMap<>();
            int scanned = 0;
            int craftable = 0;
            for (RecipeCollection collection : book.getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    scanned++;
                    if (!entry.canCraft(contents)) {
                        continue;
                    }
                    craftable++;
                    ItemStack result = resultOf(entry.display());
                    if (result.isEmpty()) {
                        continue;
                    }
                    String name = result.getHoverName().getString();
                    unique.putIfAbsent(name, new Craftable(
                            name, result.getCount(), describeIngredients(entry)));
                }
            }
            // 诊断：扫了多少、其中可合成多少、最后命中多少。
            // 结果为空时靠这条日志就能区分"配方书是空的"和"材料确实不够"。
            com.example.mcai.McaiMod.LOGGER.info(
                    "mcAI recipe scan: {} known, {} craftable now, {} unique results",
                    scanned, craftable, unique.size());
            out.addAll(unique.values());
        } catch (Throwable t) {
            com.example.mcai.McaiMod.LOGGER.warn("mcAI recipe scan failed: {}", t.toString());
            System.err.println("[mcAI] Recipe scan failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * 按名字找配方（给 {@code !ai 怎么做钻石镐} 这类提问用）。
     * 匹配产物显示名包含查询词的配方；找不到返回空列表。
     */
    public static List<Match> findByName(String query) {
        List<Match> out = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || query == null || query.isBlank()) {
            return out;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) {
            return out;
        }
        try {
            for (RecipeDisplayEntry entry : allEntries(player)) {
                ItemStack result = resultOf(entry.display());
                if (result.isEmpty()) {
                    continue;
                }
                String name = result.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (name.contains(needle) || needle.contains(name)) {
                    out.add(new Match(result, describeIngredients(entry)));
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
    public static String describe(Match entry) {
        if (entry == null) {
            return Lang.tr("mcai.value.unknown");
        }
        String output = entry.result() == null || entry.result().isEmpty()
                ? Lang.tr("mcai.value.unknown")
                : entry.result().getHoverName().getString();
        List<String> lines = entry.ingredients();
        String ingredients = (lines == null || lines.isEmpty())
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
     * 所以拿配方书里每条配方的产物名去问句里找子串即可，无需分词。
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

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return null;
        }

        Match best = null;
        int bestLength = 1; // 至少 2 个字才算匹配，避免单字误命中
        try {
            for (RecipeDisplayEntry entry : allEntries(player)) {
                ItemStack result = resultOf(entry.display());
                if (result.isEmpty()) {
                    continue;
                }
                String name = result.getHoverName().getString();
                if (name.length() > bestLength && lower.contains(name.toLowerCase(Locale.ROOT))) {
                    best = new Match(result, describeIngredients(entry));
                    bestLength = name.length();
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

    // ------------------------------------------------------------------ 内部

    /** 配方书里所有显示条目（已按类别分好组的那些，平铺开）。 */
    private static List<RecipeDisplayEntry> allEntries(LocalPlayer player) {
        List<RecipeDisplayEntry> all = new ArrayList<>();
        ClientRecipeBook book = player.getRecipeBook();
        for (RecipeCollection collection : book.getCollections()) {
            all.addAll(collection.getRecipes());
        }
        return all;
    }

    /** 从 display 里取产物物品栈。 */
    private static ItemStack resultOf(RecipeDisplay display) {
        if (display == null) {
            return ItemStack.EMPTY;
        }
        return resolveStack(display.result());
    }

    /**
     * 把 {@link SlotDisplay} 解析成一个具体物品栈。
     *
     * <p>原版的 {@code resolveForStacks(ContextMap)} 需要一整套上下文（等级、附魔表……），
     * 而我们只要"这东西叫什么名字、几个"。所以这里对常见槽位类型做最小解析：
     * 单个物品、固定物品栈、标签（取第一个成员即可用于显示）、以及它们的包装类型。
     * 解析不出来就返回空栈，上层会跳过它而不是崩掉。
     */
    private static ItemStack resolveStack(SlotDisplay slot) {
        if (slot == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (slot instanceof SlotDisplay.ItemSlotDisplay s) {
                return s.item().value().getDefaultInstance();
            }
            if (slot instanceof SlotDisplay.ItemStackSlotDisplay s) {
                return s.stack().create();
            }
            if (slot instanceof SlotDisplay.TagSlotDisplay s) {
                return firstOfTag(s.tag());
            }
            if (slot instanceof SlotDisplay.Composite s) {
                for (SlotDisplay inner : s.contents()) {
                    ItemStack stack = resolveStack(inner);
                    if (!stack.isEmpty()) {
                        return stack;
                    }
                }
                return ItemStack.EMPTY;
            }
            if (slot instanceof SlotDisplay.WithRemainder s) {
                return resolveStack(s.input());
            }
            if (slot instanceof SlotDisplay.OnlyWithComponent s) {
                return resolveStack(s.source());
            }
            if (slot instanceof SlotDisplay.WithAnyPotion s) {
                return resolveStack(s.display());
            }
        } catch (Throwable ignored) {
            // 解析失败当作"没有产物"，不影响其它配方
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack firstOfTag(net.minecraft.tags.TagKey<Item> tag) {
        return BuiltInRegistries.ITEM.get(tag)
                .flatMap(set -> set.stream().findFirst())
                .map(holder -> holder.value().getDefaultInstance())
                .orElse(ItemStack.EMPTY);
    }

    /** 槽位 → 显示名（用于材料清单）。 */
    private static String slotName(SlotDisplay slot) {
        ItemStack stack = resolveStack(slot);
        if (stack.isEmpty()) {
            return null;
        }
        return stack.getHoverName().getString();
    }

    /** 把配方需要的材料列成文字。 */
    private static List<String> describeIngredients(RecipeDisplayEntry entry) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            // 首选：服务端随配方同步下来的 Ingredient 列表（合成类配方才有）
            List<Ingredient> required = entry.craftingRequirements().orElse(List.of());
            if (!required.isEmpty()) {
                for (Ingredient ingredient : required) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    String name = ingredientName(ingredient);
                    if (name != null) {
                        counts.merge(name, 1, Integer::sum);
                    }
                }
            } else {
                // 熔炉/切石机这类没有 craftingRequirements，从 display 的槽位里读
                for (SlotDisplay slot : displayIngredients(entry.display())) {
                    String name = slotName(slot);
                    if (name != null) {
                        counts.merge(name, 1, Integer::sum);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 拿不到材料就不显示材料，产物仍然有用
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            lines.add(e.getValue() > 1 ? e.getKey() + " x" + e.getValue() : e.getKey());
        }
        return lines;
    }

    private static String ingredientName(Ingredient ingredient) {
        try {
            Holder<Item> first = (Holder<Item>) ingredient.items().findFirst().orElse(null);
            if (first == null) {
                return null;
            }
            return first.value().getDefaultInstance().getHoverName().getString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<SlotDisplay> displayIngredients(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay d) {
            return d.ingredients();
        }
        if (display instanceof ShapelessCraftingRecipeDisplay d) {
            return d.ingredients();
        }
        if (display instanceof FurnaceRecipeDisplay d) {
            return List.of(d.ingredient());
        }
        return List.of();
    }
}
