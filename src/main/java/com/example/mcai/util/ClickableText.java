package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 AI 回答里的坐标和物品名变成可点击文本（1.24 新增）。
 *
 * <p>两类可点击内容：
 * <ul>
 *   <li><b>坐标</b>（{@code X=120 Y=64 Z=-340} 或 {@code 120, 64, -340}）——
 *       点击复制到剪贴板，方便直接粘进传送指令或聊天。</li>
 *   <li><b>「物品名」</b>（mcAI 自己产出的形式，例如 {@code 「钻石镐」的配方}）——
 *       点击把 {@code <前缀> 钻石镐} 填进聊天栏，你补一句就能问。</li>
 * </ul>
 *
 * <p>用 {@link ClickEvent.Action#COPY_TO_CLIPBOARD} 和
 * {@link ClickEvent.Action#SUGGEST_COMMAND}：前者无副作用，后者只填入不执行，
 * 所以<b>误点不会触发任何操作</b>——这一点很重要，回答里难免有被误认成坐标的数字串。
 *
 * <p>1.21.1 的 API 是老的「构造函数 + Action 枚举」形式
 * （{@code new ClickEvent(Action, String)}），不是 1.20.5 之后重构出来的那些 record 子类。
 */
public final class ClickableText {

    private ClickableText() {}

    /** 形如 X=120 Y=64 Z=-340（mcAI 自己的 /ai where 与 ctx 输出）。 */
    private static final String XYZ = "X=(-?\\d{1,7})\\s+Y=(-?\\d{1,7})\\s+Z=(-?\\d{1,7})";
    /** 形如 120, 64, -340（半角或全角逗号）。 */
    private static final String CSV = "(-?\\d{1,7})\\s*[,，]\\s*(-?\\d{1,7})\\s*[,，]\\s*(-?\\d{1,7})";
    /** mcAI 产出的「物品名」。 */
    private static final String BRACKET = "「([^」\\n]{1,40})」";

    private static final Pattern PATTERN = Pattern.compile(XYZ + "|" + CSV + "|" + BRACKET);

    private static final int COORD_COLOR = 0x55FFFF;   // 青色
    private static final int ITEM_COLOR = 0xFFE066;    // 暖黄

    /**
     * 构造可点击文本。没有命中任何模式时返回等价的纯文本。
     */
    public static Component build(String raw) {
        return build(raw, null);
    }

    /**
     * 构造可点击文本，并给<b>整段</b>挂一个"点击复制"动作（1.25 新增）。
     *
     * <p>实现方式是把复制动作挂在根节点上，而不是给每个普通片段各挂一份。理由在
     * 1.21.1 的 {@code MutableComponent#append}：它会把父节点的样式当作"底"传给子节点
     * （{@code child.getStyle().withParent(parentStyle)}），子节点自己非空的字段覆盖父节点。
     * 于是"坐标段自己的 COPY_TO_CLIPBOARD""物品段自己的 SUGGEST_COMMAND"照旧生效，
     * 只有点在既不是坐标也不是物品的文字上时，才轮到根节点那份"复制整段回答"。
     *
     * @param clipboard 点普通文字时要复制的全文；null 或空表示不加这个动作
     */
    public static Component build(String raw, String clipboard) {
        if (raw == null || raw.isEmpty()) {
            return Component.literal("");
        }
        MutableComponent out = Component.literal("");
        if (clipboard != null && !clipboard.isEmpty()) {
            out.withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboard))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Lang.text("mcai.chat.click_copy_reply"))));
        }
        Matcher matcher = PATTERN.matcher(raw);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                out.append(Component.literal(raw.substring(last, matcher.start())));
            }
            out.append(segment(matcher));
            last = matcher.end();
        }
        if (last < raw.length()) {
            out.append(Component.literal(raw.substring(last)));
        }
        return out;
    }

    private static MutableComponent segment(Matcher matcher) {
        String shown = matcher.group();
        // 坐标分支（XYZ 优先，其次 CSV）
        String x = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
        if (x != null) {
            String y = matcher.group(1) != null ? matcher.group(2) : matcher.group(5);
            String z = matcher.group(1) != null ? matcher.group(3) : matcher.group(6);
            String clipboard = x + " " + y + " " + z;
            return Component.literal(shown).withStyle(style ->
                    style.withColor(COORD_COLOR)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboard))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Lang.text("mcai.chat.click_copy_coords", clipboard))));
        }

        // 「物品名」分支
        String item = matcher.group(7);
        if (item != null) {
            String prefix = chatPrefix();
            String suggested = prefix + " " + item;
            return Component.literal(shown).withStyle(style ->
                    style.withColor(ITEM_COLOR)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggested))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Lang.text("mcai.chat.click_ask_item", suggested))));
        }

        return Component.literal(shown);
    }

    private static String chatPrefix() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String p = config == null ? null : config.chatPrefix;
        return (p == null || p.isBlank()) ? "!ai" : p;
    }
}
