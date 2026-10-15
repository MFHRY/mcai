package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.Lang;
import com.example.mcai.util.VisionResolution;
import com.example.mcai.vision.VisionHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 游戏内设置窗口：一个可搜索、可折叠、可滚动的配置面板。
 *
 * <p>用 {@code /ai config} 打开（Mod Menu 里的按钮也走这里）。
 *
 * <p><b>设计要点：</b>
 * <ul>
 *   <li><b>行驱动</b>：每一项配置都是一行（{@link Row} 的子类），自己负责创建控件、
 *       渲染、以及把值写回 {@link ConfigManager.ConfigData}。加一项配置只要
 *       {@code buildGroups} 里多一行，布局 / 搜索 / 滚动全部自动适配。</li>
 *   <li><b>写盘只走 {@code ConfigManager.update()}</b>：界面里改的只是行内的草稿值，
 *       点「保存」时才在 copy-on-write 里一次性写回，绝不会去改 {@code get()} 返回的对象。</li>
 *   <li><b>按键不进 mcai.json</b>：四个快捷键直接改原版 {@link KeyMapping} 并
 *       {@code options.write()} —— 原版 options.txt 是唯一真相，另存一份必然打架。</li>
 * </ul>
 *
 * <p><b>1.21.1 的界面 API 陷阱（全部已核实）：</b>
 * <ul>
 *   <li>{@code EditBox} 是 6 参构造：{@code (Font, x, y, w, h, Component)}。</li>
 *   <li>按钮不是 {@code new Button(...)}，而是
 *       {@code Button.builder(Component, pressAction).dimensions(x,y,w,h).build()}。</li>
 *   <li>{@code AbstractWidget} 没有 {@code setPosition}，只有 {@code setX/setY}。</li>
 *   <li>滚轮是 4 参 {@code mouseScrolled(double,double,double,double)}。</li>
 *   <li>绘制多行 tooltip 要用带 {@code Optional<TooltipData>} 的那个重载。</li>
 *   <li>{@code ChatFormatting} 在 {@code net.minecraft.util}，不在 {@code net.minecraft.text}。</li>
 * </ul>
 *
 * <p>所有界面文字走语言文件（{@link Lang}），代码里不出现硬编码文案。
 */
public class ConfigScreen extends Screen {

    // ------------------------------------------------------------ 布局常量

    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_HEIGHT = 20;
    private static final int GROUP_HEADER_HEIGHT = 22;
    private static final int CONTROL_WIDTH = 200;
    private static final int CONTROL_GAP = 6;
    private static final int REVEAL_WIDTH = 54;
    private static final int REBIND_WIDTH = 110;
    private static final int TOGGLE_WIDTH = 64;
    private static final int HEADER_HEIGHT = 48;
    private static final int FOOTER_HEIGHT = 48;
    private static final int MIN_LABEL_WIDTH = 70;
    private static final int PARKED = -10000;

    /** 搜索框里是否有内容时，ESC 优先清空搜索而不是关窗口。 */
    private static final Component HELP_LINK =
            Component.translatable("mcai.screen.help_link").formatted(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);

    private final Screen parent;

    private final List<Group> groups = new ArrayList<>();
    private boolean built;

    private EditBox searchField;
    private Button saveButton;
    private Button cancelButton;

    private String query = "";
    private int scroll;
    private int maxScroll;
    private int contentHeight;

    private int contentLeft;
    private int contentRight;
    private int contentTop;
    private int contentBottom;
    private int footerTop;

    /** 正在等待玩家按键的那一行；null 表示没有处于捕获态。 */
    private KeyRow capturing;

    private boolean revealKey;

    private int helpX;
    private int helpY;
    private int helpWidth;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("mcai.screen.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void init() {
        ConfigManager.ConfigData config = safeConfig();
        if (!built) {
            buildGroups(config);
            built = true;
        }

        searchField = new EditBox(textRenderer, 0, 0, 200, WIDGET_HEIGHT, Lang.text("mcai.config.search"));
        searchField.setMaxLength(64);
        searchField.setText(query);
        searchField.setPlaceholder(Lang.text("mcai.config.search_hint").formatted(ChatFormatting.DARK_GRAY));
        searchField.setChangedListener(text -> {
            String next = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            if (!next.equals(query)) {
                query = next;
                relayout();
            }
        });
        // 先注册搜索框与底部按钮：ParentElement 的点击命中是「第一个命中的子元素优先」，
        // 这样滚到边界上的行绝不会抢走搜索框 / 保存按钮的点击。
        addSelectableChild(searchField);

        saveButton = Button.builder(Lang.text("mcai.screen.save"), b -> save())
                .dimensions(0, 0, 100, WIDGET_HEIGHT).build();
        addSelectableChild(saveButton);

        cancelButton = Button.builder(Lang.text("mcai.screen.cancel"), b -> close())
                .dimensions(0, 0, 100, WIDGET_HEIGHT).build();
        addSelectableChild(cancelButton);

        for (Group group : groups) {
            for (Row row : group.rows) {
                row.ensureWidgets();
                for (AbstractWidget widget : row.controlWidgets()) {
                    addSelectableChild(widget);
                }
            }
        }

        relayout();
    }

    @Override
    public void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    // ------------------------------------------------------------ 交互

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        if (maxScroll <= 0 || mouseY < contentTop || mouseY > contentBottom) {
            return false;
        }
        int next = (int) Math.round(scroll - verticalAmount * ROW_HEIGHT);
        next = Math.max(0, Math.min(maxScroll, next));
        if (next == scroll) {
            return true;
        }
        scroll = next;
        // 滚走的输入框不要再抢键盘
        if (getFocused() instanceof EditBox) {
            setFocused(null);
        }
        relayout();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturing != null) {
            // 点到别处就放弃改键，避免一直停在「等待按键」
            capturing.cancelCapture();
        }
        if (button == 0 && isInContent(mouseX, mouseY)) {
            int y = contentTop - scroll;
            for (Group group : groups) {
                if (!group.isVisible(query)) {
                    continue;
                }
                if (mouseY >= y && mouseY < y + GROUP_HEADER_HEIGHT) {
                    // 搜索时视觉上强制展开，但折叠状态照样记着，清空搜索后生效
                    group.expanded = !group.expanded;
                    relayout();
                    return true;
                }
                y += GROUP_HEADER_HEIGHT;
                if (group.isExpanded(query)) {
                    y += group.shownRows(query).size() * ROW_HEIGHT;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturing != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                capturing.cancelCapture();
            } else {
                capturing.applyKeyCode(keyCode);
            }
            return true;
        }
        // 搜索框里的 ESC：清空搜索而不是关窗口
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && searchField != null && searchField.isFocused() && !query.isEmpty()) {
            setFocused(null);
            searchField.setText("");
            query = "";
            relayout();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isInContent(double mouseX, double mouseY) {
        return mouseX >= contentLeft && mouseX <= contentRight
                && mouseY >= contentTop && mouseY <= contentBottom;
    }

    private boolean isHoveringHelp(int mouseX, int mouseY) {
        return mouseX >= helpX && mouseX <= helpX + helpWidth
                && mouseY >= helpY && mouseY <= helpY + 9;
    }

    // ------------------------------------------------------------ 布局

    private void relayout() {
        int contentWidth = Math.min(this.width - 40, 580);
        if (contentWidth < 200) {
            contentWidth = Math.max(120, this.width - 12);
        }
        this.contentLeft = (this.width - contentWidth) / 2;
        this.contentRight = this.contentLeft + contentWidth;
        this.contentTop = HEADER_HEIGHT;
        this.footerTop = this.height - FOOTER_HEIGHT;
        this.contentBottom = Math.max(contentTop + ROW_HEIGHT, this.footerTop - 4);

        for (Group group : groups) {
            group.refreshMatches(query);
        }

        int total = 0;
        for (Group group : groups) {
            if (!group.isVisible(query)) {
                continue;
            }
            total += GROUP_HEADER_HEIGHT;
            if (group.isExpanded(query)) {
                total += group.shownRows(query).size() * ROW_HEIGHT;
            }
        }
        this.contentHeight = total;
        int viewport = Math.max(1, contentBottom - contentTop);
        this.maxScroll = Math.max(0, total - viewport);
        this.scroll = Math.max(0, Math.min(scroll, maxScroll));

        // 先全部收起（移出屏幕 + 隐藏），再摆出真正要显示的行。
        // 这样任何一行都不会在内容区之外留下可点击的热区。
        for (Group group : groups) {
            for (Row row : group.rows) {
                row.park();
            }
            group.headerVisible = false;
        }

        int y = contentTop - scroll;
        for (Group group : groups) {
            if (!group.isVisible(query)) {
                continue;
            }
            group.headerY = y;
            group.headerVisible = true;
            y += GROUP_HEADER_HEIGHT;
            if (!group.isExpanded(query)) {
                continue;
            }
            for (Row row : group.shownRows(query)) {
                boolean fullyVisible = y >= contentTop && y + ROW_HEIGHT <= contentBottom;
                if (fullyVisible) {
                    row.layout(contentLeft, y, contentWidth);
                }
                y += ROW_HEIGHT;
            }
        }

        // 搜索框与底部按钮
        int searchWidth = Math.max(120, contentWidth - 8);
        searchField.setX(contentLeft + 4);
        searchField.setY(24);
        searchField.setWidth(searchWidth);

        int buttonWidth = 100;
        int gap = 10;
        int buttonsX = contentRight - buttonWidth * 2 - gap;
        int buttonsY = footerTop + 18;
        saveButton.setX(buttonsX);
        saveButton.setY(buttonsY);
        cancelButton.setX(buttonsX + buttonWidth + gap);
        cancelButton.setY(buttonsY);
    }

    // ------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, this.title, this.width / 2, 6, 0xFFFFFF);

        if (contentBottom > contentTop) {
            context.fill(contentLeft - 6, contentTop - 2, contentRight + 6, contentBottom + 2, 0x55000000);
            context.enableScissor(contentLeft - 6, contentTop, contentRight + 6, contentBottom);
            renderGroupHeaders(context, mouseX, mouseY);
            for (Group group : groups) {
                for (Row row : group.rows) {
                    if (row.visible) {
                        row.render(context, mouseX, mouseY, delta);
                    }
                }
            }
            if (contentHeight == 0) {
                context.drawCenteredTextWithShadow(textRenderer,
                        Lang.text("mcai.config.no_result").formatted(ChatFormatting.GRAY),
                        (contentLeft + contentRight) / 2, (contentTop + contentBottom) / 2 - 4, 0xFFFFFF);
            }
            context.disableScissor();
        }

        renderScrollbar(context);

        // ---- 底部：提示 + 帮助链接 + 保存/取消 ----
        context.drawCenteredTextWithShadow(textRenderer,
                Lang.text("mcai.screen.hint").formatted(ChatFormatting.DARK_GRAY),
                this.width / 2, footerTop + 2, 0xFFFFFF);
        this.helpWidth = textRenderer.getWidth(HELP_LINK);
        this.helpX = contentLeft;
        this.helpY = footerTop + 18;
        context.drawString(textRenderer, HELP_LINK, helpX, helpY, 0xFFFFFF);

        searchField.render(context, mouseX, mouseY, delta);
        saveButton.render(context, mouseX, mouseY, delta);
        cancelButton.render(context, mouseX, mouseY, delta);

        if (isHoveringHelp(mouseX, mouseY)) {
            context.drawTooltip(textRenderer, helpTooltip(), Optional.empty(), mouseX, mouseY);
        }
    }

    private void renderGroupHeaders(GuiGraphics context, int mouseX, int mouseY) {
        for (Group group : groups) {
            if (!group.headerVisible) {
                continue;
            }
            int y = group.headerY;
            if (y + GROUP_HEADER_HEIGHT < contentTop || y > contentBottom) {
                continue;
            }
            boolean hovered = mouseX >= contentLeft && mouseX <= contentRight
                    && mouseY >= y && mouseY < y + GROUP_HEADER_HEIGHT;
            context.fill(contentLeft, y, contentRight, y + GROUP_HEADER_HEIGHT - 2,
                    hovered ? 0x44FFFFFF : 0x33000000);
            String arrow = group.isExpanded(query) ? "\u25bc " : "\u25b6 ";
            context.drawString(textRenderer, Component.literal(arrow).formatted(ChatFormatting.AQUA),
                    contentLeft + 6, y + 6, 0xFFFFFF);
            context.drawString(textRenderer,
                    Lang.text(group.titleKey).formatted(ChatFormatting.BOLD),
                    contentLeft + 6 + textRenderer.getWidth(arrow), y + 6, 0xFFFFFF);
        }
    }

    private void renderScrollbar(GuiGraphics context) {
        if (maxScroll <= 0 || contentBottom <= contentTop) {
            return;
        }
        int trackTop = contentTop;
        int trackHeight = contentBottom - contentTop;
        int barX = contentRight + 8;
        context.fill(barX, trackTop, barX + 3, trackTop + trackHeight, 0x33FFFFFF);
        int viewport = Math.max(1, trackHeight);
        int thumbHeight = Math.max(12, trackHeight * viewport / Math.max(1, contentHeight));
        thumbHeight = Math.min(thumbHeight, trackHeight);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        context.fill(barX, thumbY, barX + 3, thumbY + thumbHeight, 0xAAFFFFFF);
    }

    /** 悬停提示的正文。网址与 {@code sk-} 前缀保持原样，其余跟随游戏语言。 */
    private static List<Component> helpTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Lang.text("mcai.help.title").formatted(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(Lang.text("mcai.help.body1").formatted(ChatFormatting.GRAY));
        lines.add(Lang.text("mcai.help.body2").formatted(ChatFormatting.GRAY));
        lines.add(Component.empty());
        lines.add(Lang.text("mcai.help.where").formatted(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(Lang.text("mcai.help.step1").formatted(ChatFormatting.GRAY)
                .append(Component.literal("platform.deepseek.com").formatted(ChatFormatting.WHITE)));
        lines.add(Lang.text("mcai.help.step2").formatted(ChatFormatting.GRAY));
        lines.add(Lang.text("mcai.help.step3a").formatted(ChatFormatting.GRAY)
                .append(Component.literal("sk-").formatted(ChatFormatting.WHITE))
                .append(Lang.text("mcai.help.step3b").formatted(ChatFormatting.GRAY)));
        lines.add(Lang.text("mcai.help.step4").formatted(ChatFormatting.GRAY));
        lines.add(Component.empty());
        lines.add(Lang.text("mcai.help.notes").formatted(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(Lang.text("mcai.help.note1").formatted(ChatFormatting.GRAY));
        lines.add(Lang.text("mcai.help.note2a").formatted(ChatFormatting.GRAY)
                .append(Lang.text("mcai.help.note2b").formatted(ChatFormatting.RED)));
        return lines;
    }

    // ------------------------------------------------------------ 保存

    private void save() {
        if (capturing != null) {
            capturing.cancelCapture();
        }
        ConfigManager manager = ConfigManager.getInstance();
        manager.update(config -> {
            for (Group group : groups) {
                for (Row row : group.rows) {
                    row.apply(config);
                }
            }
            // 手填的模型顺手加进切换器列表，否则右键「模型切换器」会跳过它
            String model = config.model == null ? "" : config.model.trim();
            if (!model.isEmpty()) {
                List<String> models = new ArrayList<>(
                        config.availableModels == null ? List.of() : config.availableModels);
                if (!models.contains(model)) {
                    models.add(model);
                }
                config.availableModels = List.copyOf(models);
            }
        });

        ConfigManager.ConfigData saved = manager.get();
        String key = saved == null ? "" : nullToEmpty(saved.apiKey).trim();
        // 让多轮上下文立刻生效，不用重启
        HistoryStore.setMaxTurns(saved == null ? 4 : saved.historyTurns);

        if (key.isEmpty()) {
            ClientChat.sendLiteral(Lang.tr("mcai.screen.saved_empty"));
        } else {
            ClientChat.sendLiteral(Lang.tr("mcai.screen.saved", mask(key)));
        }
        ClientChat.sendLiteral(Lang.tr("mcai.screen.saved_hint"));
        close();
    }

    // ------------------------------------------------------------ 分组定义

    /**
     * 把所有配置项按主题分组。{@code ConfigData} 里的每一个字段都在这里出现，
     * 一个都不会漏。
     */
    private void buildGroups(ConfigManager.ConfigData config) {
        groups.clear();

        // ---- 连接与密钥 ----
        Group connection = group("mcai.config.group.connection", true);
        connection.add(new SecretRow("api_key", "mcai.screen.api_key", 512, "sk-...",
                c -> c.apiKey, (c, v) -> c.apiKey = v));
        connection.add(new TextRow("api_url", "mcai.screen.api_url", 512, "https://api.deepseek.com/v1",
                c -> c.apiUrl, (c, v) -> {
                    if (!v.isBlank()) {
                        c.apiUrl = v;
                    }
                }));
        connection.add(new CycleRow("api_provider", "mcai.config.api_provider", "mcai.config.provider.",
                () -> List.of("auto", "openai", "gemini", "claude"),
                c -> c.apiProvider, (c, v) -> c.apiProvider = v));
        connection.add(new ListRow("backup_api_keys", "mcai.config.backup_keys", 1024, ";",
                c -> c.backupApiKeys, (c, v) -> c.backupApiKeys = v));
        connection.add(new IntRow("retry_max", "mcai.config.retry_max", 0, 10,
                c -> c.retryMax, (c, v) -> c.retryMax = v));

        // ---- 模型与模式 ----
        Group model = group("mcai.config.group.model", true);
        model.add(new TextRow("model", "mcai.screen.model", 128, "deepseek-flash",
                c -> c.model, (c, v) -> {
                    if (!v.isBlank()) {
                        c.model = v;
                    }
                }));
        model.add(new ListRow("available_models", "mcai.config.available_models", 1024, ";",
                c -> c.availableModels, (c, v) -> c.availableModels = v));
        model.add(new CycleRow("mode", "mcai.config.mode", "mcai.mode.",
                () -> {
                    ConfigManager.ConfigData current = safeConfig();
                    List<String> modes = current.availableModes;
                    return modes == null || modes.isEmpty() ? List.of("chat", "vision") : modes;
                },
                c -> c.mode, (c, v) -> c.mode = v));
        model.add(new ListRow("available_modes", "mcai.config.available_modes", 512, ";",
                c -> c.availableModes, (c, v) -> c.availableModes = v));
        model.add(new CycleRow("vision_resolution", "mcai.config.vision_resolution", "mcai.resolution.",
                () -> VisionResolution.OPTIONS,
                c -> c.visionResolution, (c, v) -> c.visionResolution = VisionResolution.normalize(v)));
        model.add(new CycleRow("persona", "mcai.config.persona", "mcai.persona.",
                () -> {
                    ConfigManager.ConfigData current = safeConfig();
                    List<String> personas = current.availablePersonas;
                    return personas == null || personas.isEmpty() ? List.of("default") : personas;
                },
                c -> c.persona, (c, v) -> c.persona = v));
        model.add(new ListRow("available_personas", "mcai.config.available_personas", 512, ";",
                c -> c.availablePersonas, (c, v) -> c.availablePersonas = v));
        model.add(new ToggleRow("show_reasoning", "mcai.config.show_reasoning",
                c -> c.showReasoning, (c, v) -> c.showReasoning = v));
        model.add(new IntRow("reasoning_max_chars", "mcai.config.reasoning_max", 0, Integer.MAX_VALUE,
                c -> c.reasoningMaxChars, (c, v) -> c.reasoningMaxChars = v));
        model.add(new ToggleRow("streaming", "mcai.screen.toggle_streaming",
                c -> c.streaming, (c, v) -> c.streaming = v));
        model.add(new TextRow("chat_prefix", "mcai.config.chat_prefix", 32, "!ai",
                c -> c.chatPrefix, (c, v) -> {
                    if (!v.isBlank()) {
                        c.chatPrefix = v;
                    }
                }));
        model.add(new TextRow("translate_lang", "mcai.config.translate_lang", 64, "中文",
                c -> c.translateLang, (c, v) -> {
                    if (!v.isBlank()) {
                        c.translateLang = v;
                    }
                }));

        // ---- 花费与预算 ----
        Group cost = group("mcai.config.group.cost", false);
        cost.add(new DoubleRow("daily_budget_yuan", "mcai.screen.budget", 0.0, Double.MAX_VALUE,
                c -> c.dailyBudgetYuan, (c, v) -> c.dailyBudgetYuan = v));
        cost.add(new DoubleRow("budget_warn_ratio", "mcai.config.budget_warn_ratio",
                Double.NEGATIVE_INFINITY, Double.MAX_VALUE,
                c -> c.budgetWarnRatio, (c, v) -> c.budgetWarnRatio = v));
        cost.add(new ToggleRow("show_request_cost", "mcai.config.show_request_cost",
                c -> c.showRequestCost, (c, v) -> c.showRequestCost = v));
        cost.add(new DoubleRow("daily_cost_yuan", "mcai.config.daily_cost", 0.0, Double.MAX_VALUE,
                c -> c.dailyCostYuan, (c, v) -> c.dailyCostYuan = v));
        cost.add(new IntRow("daily_tokens", "mcai.config.daily_tokens", 0, Integer.MAX_VALUE,
                c -> c.dailyTokens, (c, v) -> c.dailyTokens = v));
        cost.add(new TextRow("token_date", "mcai.config.token_date", 10, "2024-01-01",
                c -> c.tokenDate == null ? "" : c.tokenDate.toString(),
                (c, v) -> {
                    if (!v.isBlank()) {
                        try {
                            c.tokenDate = java.time.LocalDate.parse(v.trim());
                        } catch (RuntimeException ignored) {
                            // 日期写错就保持原值，不要让整次保存失败
                        }
                    }
                }));

        // ---- 多轮上下文与记忆 ----
        Group context = group("mcai.config.group.context", false);
        context.add(new IntRow("history_turns", "mcai.screen.history", 0, 20,
                c -> c.historyTurns, (c, v) -> c.historyTurns = v));
        context.add(new ToggleRow("response_cache", "mcai.cmd.flag_cache",
                c -> c.responseCache, (c, v) -> c.responseCache = v));
        context.add(new IntRow("response_cache_size", "mcai.config.cache_size", 0, Integer.MAX_VALUE,
                c -> c.responseCacheSize, (c, v) -> c.responseCacheSize = v));
        context.add(new ToggleRow("env_context", "mcai.cmd.flag_env",
                c -> c.envContext, (c, v) -> c.envContext = v));
        context.add(new IntRow("env_context_max_mods", "mcai.config.env_max_mods", 0, Integer.MAX_VALUE,
                c -> c.envContextMaxMods, (c, v) -> c.envContextMaxMods = v));
        context.add(new ListRow("memories", "mcai.config.memories", 2048, ";",
                c -> c.memories, (c, v) -> c.memories = v));

        // ---- 截图与视觉 ----
        Group vision = group("mcai.config.group.vision", false);
        vision.add(new ToggleRow("vision_preview", "mcai.cmd.flag_preview",
                c -> c.visionPreview, (c, v) -> c.visionPreview = v));

        // ---- 隐私与安全 ----
        Group privacy = group("mcai.config.group.privacy", false);
        privacy.add(new ToggleRow("privacy_mask", "mcai.cmd.flag_mask",
                c -> c.privacyMask, (c, v) -> c.privacyMask = v));
        privacy.add(new ListRow("privacy_mask_regions", "mcai.config.mask_regions", 512, ";",
                c -> c.privacyMaskRegions, (c, v) -> c.privacyMaskRegions = v));
        privacy.add(new ToggleRow("danger_alert", "mcai.cmd.flag_danger",
                c -> c.dangerAlert, (c, v) -> c.dangerAlert = v));
        privacy.add(new IntRow("danger_health", "mcai.config.danger_health", 1, Integer.MAX_VALUE,
                c -> c.dangerHealth, (c, v) -> c.dangerHealth = v));
        privacy.add(new DoubleRow("danger_radius", "mcai.config.danger_radius", 0.0, Double.MAX_VALUE,
                c -> c.dangerRadius, (c, v) -> c.dangerRadius = v));
        privacy.add(new IntRow("danger_cooldown_seconds", "mcai.config.danger_cooldown", 0, Integer.MAX_VALUE,
                c -> c.dangerCooldownSeconds, (c, v) -> c.dangerCooldownSeconds = v));

        // ---- 声音与朗读 ----
        Group sound = group("mcai.config.group.sound", false);
        sound.add(new ToggleRow("tts", "mcai.cmd.flag_tts",
                c -> c.tts, (c, v) -> c.tts = v));
        sound.add(new IntRow("tts_max_chars", "mcai.config.tts_max_chars", 0, Integer.MAX_VALUE,
                c -> c.ttsMaxChars, (c, v) -> c.ttsMaxChars = v));

        // ---- 通知与集成 ----
        Group notify = group("mcai.config.group.notify", false);
        notify.add(new ToggleRow("death_recap", "mcai.screen.toggle_death",
                c -> c.deathRecap, (c, v) -> c.deathRecap = v));
        notify.add(new TextRow("discord_webhook", "mcai.config.discord_webhook", 512, "https://discord.com/api/webhooks/...",
                c -> c.discordWebhook, (c, v) -> c.discordWebhook = v.trim()));

        // ---- 挂件 ----
        Group avatar = group("mcai.config.group.avatar", false);
        avatar.add(new ToggleRow("avatar_visible", "mcai.config.avatar_visible",
                c -> c.avatarVisible, (c, v) -> c.avatarVisible = v));
        avatar.add(new CycleRow("avatar_corner", "mcai.config.avatar_corner", "mcai.config.corner.",
                () -> List.of("bottom_left", "bottom_right", "top_left", "top_right"),
                c -> c.avatarCorner, (c, v) -> c.avatarCorner = v));
        avatar.add(new IntRow("avatar_offset_x", "mcai.config.avatar_offset_x",
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                c -> c.avatarOffsetX, (c, v) -> c.avatarOffsetX = v));
        avatar.add(new IntRow("avatar_offset_y", "mcai.config.avatar_offset_y",
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                c -> c.avatarOffsetY, (c, v) -> c.avatarOffsetY = v));
        avatar.add(new DoubleRow("avatar_scale", "mcai.config.avatar_scale", 0.1, 8.0,
                c -> c.avatarScale, (c, v) -> c.avatarScale = v));
        avatar.add(new TextRow("avatar_image", "mcai.config.avatar_image", 512, "C:\\path\\to\\avatar.png",
                c -> c.avatarImage, (c, v) -> c.avatarImage = v.trim()));
        avatar.add(new ToggleRow("avatar_sound", "mcai.config.avatar_sound",
                c -> c.avatarSound, (c, v) -> c.avatarSound = v));
        avatar.add(new ListRow("avatar_bubble_items", "mcai.config.avatar_bubble", 256, ";",
                c -> c.avatarBubbleItems, (c, v) -> c.avatarBubbleItems = v));

        // ---- 其它 ----
        Group misc = group("mcai.config.group.misc", false);
        misc.add(new ToggleRow("recipe_cache", "mcai.screen.toggle_recipe",
                c -> c.recipeCache, (c, v) -> c.recipeCache = v));

        // ---- 快捷键（不进 mcai.json，直接改原版 KeyMapping）----
        Group keys = group("mcai.config.group.keys", false);
        keys.add(new KeyRow("key_vision", "key.mcai.vision", VisionHandler::visionKeyBinding));
        keys.add(new KeyRow("key_build", "key.mcai.build", VisionHandler::buildKeyBinding));
        keys.add(new KeyRow("key_pick", "key.mcai.pick", VisionHandler::pickKeyBinding));
        keys.add(new KeyRow("key_what", "key.mcai.what", VisionHandler::whatKeyBinding));
    }

    private Group group(String titleKey, boolean expanded) {
        Group group = new Group(titleKey, expanded);
        groups.add(group);
        return group;
    }

    // ------------------------------------------------------------ 行模型

    private static final class Control {
        final AbstractWidget widget;
        final int width;

        Control(AbstractWidget widget, int width) {
            this.widget = widget;
            this.width = width;
        }
    }

    /** 一组可折叠的配置行。 */
    private final class Group {
        final String titleKey;
        final List<Row> rows = new ArrayList<>();
        final List<Row> matching = new ArrayList<>();
        boolean expanded;
        int headerY;
        boolean headerVisible;

        Group(String titleKey, boolean expanded) {
            this.titleKey = titleKey;
            this.expanded = expanded;
        }

        void add(Row row) {
            rows.add(row);
        }

        void refreshMatches(String q) {
            matching.clear();
            for (Row row : rows) {
                if (row.matches(q)) {
                    matching.add(row);
                }
            }
        }

        boolean isVisible(String q) {
            return q.isEmpty() || !matching.isEmpty();
        }

        List<Row> shownRows(String q) {
            return q.isEmpty() ? rows : matching;
        }

        /** 搜索时强制展开，保证匹配到的行一定看得见；折叠状态本身不改。 */
        boolean isExpanded(String q) {
            return q.isEmpty() ? expanded : true;
        }
    }

    /** 一行配置：左边标签，右边一到多个控件。 */
    private abstract class Row {
        final String id;
        final String labelKey;
        private final List<Control> controls = new ArrayList<>();
        private boolean widgetsBuilt;
        boolean visible;
        int labelX;
        int labelY;
        int labelMaxWidth;
        int controlLeft;

        Row(String id, String labelKey) {
            this.id = id;
            this.labelKey = labelKey;
        }

        Component label() {
            return Lang.text(labelKey);
        }

        final void addControl(AbstractWidget widget, int width) {
            controls.add(new Control(widget, width));
        }

        final List<AbstractWidget> controlWidgets() {
            List<AbstractWidget> widgets = new ArrayList<>(controls.size());
            for (Control control : controls) {
                widgets.add(control.widget);
            }
            return widgets;
        }

        /** 控件只建一次；窗口大小变化时复用同一批对象，草稿值不会丢。 */
        final void ensureWidgets() {
            if (widgetsBuilt) {
                return;
            }
            widgetsBuilt = true;
            buildControls();
        }

        protected abstract void buildControls();

        /** 把这一行的值写进配置快照（不落盘，落盘由 ConfigManager 防抖处理）。 */
        void apply(ConfigManager.ConfigData config) {}

        /** 搜索匹配：标签（当前语言）+ 配置字段名。 */
        final boolean matches(String q) {
            if (q.isEmpty()) {
                return true;
            }
            String haystack = (Lang.tr(labelKey) + " " + id + " " + labelKey).toLowerCase(Locale.ROOT);
            return haystack.contains(q);
        }

        void renderLabel(GuiGraphics context, int mouseX, int mouseY, float delta) {
            drawTruncated(context, Lang.tr(labelKey), labelX, labelY, labelMaxWidth, 0xA0A0A0);
        }

        final void layout(int x, int y, int width) {
            int available = Math.max(40, width - 16 - MIN_LABEL_WIDTH);
            int gaps = Math.max(0, controls.size() - 1) * CONTROL_GAP;
            int sum = 0;
            for (Control control : controls) {
                sum += control.width;
            }
            double scale = 1.0;
            if (sum + gaps > available && sum > 0) {
                scale = Math.max(0.25, (double) Math.max(20, available - gaps) / (double) sum);
            }
            int right = x + width - 8;
            for (int i = controls.size() - 1; i >= 0; i--) {
                Control control = controls.get(i);
                int widgetWidth = Math.max(20, (int) Math.round(control.width * scale));
                right -= widgetWidth;
                control.widget.setX(right);
                control.widget.setY(y + (ROW_HEIGHT - WIDGET_HEIGHT) / 2);
                control.widget.setWidth(widgetWidth);
                right -= CONTROL_GAP;
            }
            this.controlLeft = right + CONTROL_GAP;
            this.labelX = x + 8;
            this.labelY = y + (ROW_HEIGHT - 9) / 2;
            this.labelMaxWidth = Math.max(20, controlLeft - labelX - 8);
            this.visible = true;
            setWidgetsVisible(true);
        }

        /** 折叠 / 未匹配 / 滚出视野的行：移出屏幕并隐藏，杜绝幽灵热区。 */
        final void park() {
            this.visible = false;
            for (Control control : controls) {
                control.widget.setX(PARKED);
                control.widget.setY(PARKED);
            }
            setWidgetsVisible(false);
        }

        private void setWidgetsVisible(boolean value) {
            for (Control control : controls) {
                control.widget.visible = value;
            }
        }

        final void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
            renderLabel(context, mouseX, mouseY, delta);
            for (Control control : controls) {
                control.widget.render(context, mouseX, mouseY, delta);
            }
        }
    }

    /** 普通文本框。 */
    private class TextRow extends Row {
        private final int maxLength;
        private final String placeholder;
        private final Function<ConfigManager.ConfigData, String> getter;
        private final BiConsumer<ConfigManager.ConfigData, String> setter;
        protected EditBox field;
        protected String draft;

        TextRow(String id, String labelKey, int maxLength, String placeholder,
                Function<ConfigManager.ConfigData, String> getter,
                BiConsumer<ConfigManager.ConfigData, String> setter) {
            super(id, labelKey);
            this.maxLength = maxLength;
            this.placeholder = placeholder;
            this.getter = getter;
            this.setter = setter;
        }

        protected final EditBox ensureField() {
            if (field == null) {
                if (draft == null) {
                    draft = nullToEmpty(getter.apply(safeConfig()));
                }
                field = new EditBox(textRenderer, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT, label());
                field.setMaxLength(maxLength);
                if (placeholder != null) {
                    field.setPlaceholder(Component.literal(placeholder).formatted(ChatFormatting.DARK_GRAY));
                }
                field.setText(draft);
                field.setChangedListener(text -> draft = text);
            }
            return field;
        }

        @Override
        protected void buildControls() {
            addControl(ensureField(), CONTROL_WIDTH);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            setter.accept(config, draft == null ? "" : draft.trim());
        }
    }

    /** API Key：默认打码，右边一个「显示 / 隐藏」。 */
    private class SecretRow extends TextRow {
        private Button revealButton;

        SecretRow(String id, String labelKey, int maxLength, String placeholder,
                  Function<ConfigManager.ConfigData, String> getter,
                  BiConsumer<ConfigManager.ConfigData, String> setter) {
            super(id, labelKey, maxLength, placeholder, getter, setter);
        }

        @Override
        protected void buildControls() {
            EditBox textField = ensureField();
            applyMask();
            revealButton = Button.builder(revealLabel(), button -> {
                revealKey = !revealKey;
                applyMask();
                button.setMessage(revealLabel());
            }).dimensions(0, 0, REVEAL_WIDTH, WIDGET_HEIGHT).build();
            addControl(textField, CONTROL_WIDTH - REVEAL_WIDTH - 4);
            addControl(revealButton, REVEAL_WIDTH);
        }

        /** API Key 默认打码，避免直播 / 截图时泄露。 */
        private void applyMask() {
            field.setRenderTextProvider((text, firstCharacterIndex) -> {
                String shown = revealKey ? text : "*".repeat(text.length());
                return FormattedCharSequence.styledForwardsVisitedString(shown, Style.EMPTY);
            });
        }

        private Component revealLabel() {
            return Lang.text(revealKey ? "mcai.screen.hide" : "mcai.screen.show");
        }
    }

    /** 开 / 关。 */
    private class ToggleRow extends Row {
        private final Predicate<ConfigManager.ConfigData> getter;
        private final BiConsumer<ConfigManager.ConfigData, Boolean> setter;
        private boolean value;
        private Button button;

        ToggleRow(String id, String labelKey,
                  Predicate<ConfigManager.ConfigData> getter,
                  BiConsumer<ConfigManager.ConfigData, Boolean> setter) {
            super(id, labelKey);
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void buildControls() {
            value = getter.test(safeConfig());
            button = Button.builder(stateLabel(), b -> {
                value = !value;
                b.setMessage(stateLabel());
            }).dimensions(0, 0, TOGGLE_WIDTH, WIDGET_HEIGHT).build();
            addControl(button, TOGGLE_WIDTH);
        }

        private Component stateLabel() {
            return Lang.text(value ? "mcai.screen.on" : "mcai.screen.off")
                    .formatted(value ? ChatFormatting.GREEN : ChatFormatting.RED);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            setter.accept(config, value);
        }
    }

    /** 整数输入；解析失败就保持原值，不把玩家手滑写成 0。 */
    private class IntRow extends Row {
        private final int min;
        private final int max;
        private final Function<ConfigManager.ConfigData, Integer> getter;
        private final BiConsumer<ConfigManager.ConfigData, Integer> setter;
        private EditBox field;
        private String draft;

        IntRow(String id, String labelKey, int min, int max,
               Function<ConfigManager.ConfigData, Integer> getter,
               BiConsumer<ConfigManager.ConfigData, Integer> setter) {
            super(id, labelKey);
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void buildControls() {
            Integer initial = getter.apply(safeConfig());
            draft = String.valueOf(initial == null ? 0 : initial);
            field = new EditBox(textRenderer, 0, 0, 120, WIDGET_HEIGHT, label());
            field.setMaxLength(12);
            field.setText(draft);
            field.setChangedListener(text -> draft = text);
            addControl(field, 120);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            Integer parsed = parseIntOrNull(draft);
            if (parsed == null) {
                return;
            }
            int clamped = Math.max(min, Math.min(max, parsed));
            setter.accept(config, clamped);
        }
    }

    /** 小数输入；同样容错。 */
    private class DoubleRow extends Row {
        private final double min;
        private final double max;
        private final Function<ConfigManager.ConfigData, Double> getter;
        private final BiConsumer<ConfigManager.ConfigData, Double> setter;
        private EditBox field;
        private String draft;

        DoubleRow(String id, String labelKey, double min, double max,
                  Function<ConfigManager.ConfigData, Double> getter,
                  BiConsumer<ConfigManager.ConfigData, Double> setter) {
            super(id, labelKey);
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void buildControls() {
            Double initial = getter.apply(safeConfig());
            draft = formatNumber(initial == null ? 0.0 : initial);
            field = new EditBox(textRenderer, 0, 0, 120, WIDGET_HEIGHT, label());
            field.setMaxLength(20);
            field.setText(draft);
            field.setChangedListener(text -> draft = text);
            addControl(field, 120);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            Double parsed = parseDoubleOrNull(draft);
            if (parsed == null) {
                return;
            }
            double clamped = Math.max(min, Math.min(max, parsed));
            setter.accept(config, clamped);
        }
    }

    /** 字符串列表；显示与解析都用 {@code ;} 分隔，所以条目本身可以带逗号。 */
    private class ListRow extends Row {
        private final String separator;
        private final int maxLength;
        private final Function<ConfigManager.ConfigData, List<String>> getter;
        private final BiConsumer<ConfigManager.ConfigData, List<String>> setter;
        private EditBox field;
        private String draft;

        ListRow(String id, String labelKey, int maxLength, String separator,
                Function<ConfigManager.ConfigData, List<String>> getter,
                BiConsumer<ConfigManager.ConfigData, List<String>> setter) {
            super(id, labelKey);
            this.maxLength = maxLength;
            this.separator = separator;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void buildControls() {
            List<String> initial = getter.apply(safeConfig());
            draft = join(initial, separator);
            field = new EditBox(textRenderer, 0, 0, CONTROL_WIDTH, WIDGET_HEIGHT, label());
            field.setMaxLength(maxLength);
            field.setText(draft);
            field.setChangedListener(text -> draft = text);
            addControl(field, CONTROL_WIDTH);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            List<String> values = new ArrayList<>();
            if (draft != null) {
                for (String piece : draft.split(java.util.regex.Pattern.quote(separator))) {
                    String trimmed = piece.trim();
                    if (!trimmed.isEmpty()) {
                        values.add(trimmed);
                    }
                }
            }
            setter.accept(config, List.copyOf(values));
        }
    }

    /** 枚举值：点一下切到下一个。 */
    private class CycleRow extends Row {
        private final String labelPrefix;
        private final Supplier<List<String>> optionsSupplier;
        private final Function<ConfigManager.ConfigData, String> getter;
        private final BiConsumer<ConfigManager.ConfigData, String> setter;
        private String value;
        private Button button;

        CycleRow(String id, String labelKey, String labelPrefix, Supplier<List<String>> optionsSupplier,
                 Function<ConfigManager.ConfigData, String> getter,
                 BiConsumer<ConfigManager.ConfigData, String> setter) {
            super(id, labelKey);
            this.labelPrefix = labelPrefix;
            this.optionsSupplier = optionsSupplier;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        protected void buildControls() {
            value = getter.apply(safeConfig());
            button = Button.builder(valueLabel(), b -> {
                value = nextValue();
                b.setMessage(valueLabel());
            }).dimensions(0, 0, CONTROL_WIDTH, WIDGET_HEIGHT).build();
            addControl(button, CONTROL_WIDTH);
        }

        private List<String> options() {
            List<String> options = optionsSupplier.get();
            return options == null || options.isEmpty() ? List.of() : options;
        }

        private String nextValue() {
            List<String> options = options();
            if (options.isEmpty()) {
                return value;
            }
            int index = options.indexOf(value);
            if (index < 0) {
                return options.get(0);
            }
            return options.get((index + 1) % options.size());
        }

        private Component valueLabel() {
            if (value == null || value.isBlank()) {
                return Lang.text("mcai.value.unknown");
            }
            if (labelPrefix != null) {
                String key = labelPrefix + value;
                if (Lang.has(key)) {
                    return Lang.text(key);
                }
            }
            return Component.literal(value);
        }

        @Override
        void apply(ConfigManager.ConfigData config) {
            if (value != null && !value.isBlank()) {
                setter.accept(config, value);
            }
        }
    }

    /**
     * 快捷键行：右边一个「改键」按钮，点击后进入捕获态，
     * 下一个按下的键成为新绑定，ESC 取消。
     *
     * <p>新按键<b>只写原版 {@code options.txt}</b>（{@code options.write()}），
     * 绝不会存进 mcai.json —— 两份状态必然打架。
     */
    private class KeyRow extends Row {
        private final Supplier<KeyMapping> bindingSupplier;
        private Button button;

        KeyRow(String id, String labelKey, Supplier<KeyMapping> bindingSupplier) {
            super(id, labelKey);
            this.bindingSupplier = bindingSupplier;
        }

        @Override
        protected void buildControls() {
            button = Button.builder(rebindLabel(), b -> beginCapture())
                    .dimensions(0, 0, REBIND_WIDTH, WIDGET_HEIGHT).build();
            addControl(button, REBIND_WIDTH);
        }

        @Override
        void renderLabel(GuiGraphics context, int mouseX, int mouseY, float delta) {
            super.renderLabel(context, mouseX, mouseY, delta);
            String keyName = currentKeyName();
            int width = textRenderer.getWidth(keyName);
            int x = controlLeft - 8 - width;
            if (x > labelX + 30) {
                context.drawString(textRenderer,
                        Component.literal(keyName).formatted(ChatFormatting.YELLOW), x, labelY, 0xFFFFFF);
            }
        }

        private String currentKeyName() {
            KeyMapping binding = bindingSupplier.get();
            if (binding == null || binding.isUnbound()) {
                return Lang.tr("mcai.config.key_unbound");
            }
            return binding.getBoundKeyLocalizedText().getString();
        }

        private Component rebindLabel() {
            return capturing == this ? Lang.text("mcai.config.rebind_waiting") : Lang.text("mcai.config.rebind");
        }

        private void beginCapture() {
            if (capturing != null && capturing != this) {
                capturing.cancelCapture();
            }
            capturing = this;
            setFocused(null);
            button.setMessage(rebindLabel());
        }

        void cancelCapture() {
            if (capturing == this) {
                capturing = null;
            }
            if (button != null) {
                button.setMessage(rebindLabel());
            }
        }

        void applyKeyCode(int keyCode) {
            KeyMapping binding = bindingSupplier.get();
            if (binding != null) {
                binding.setBoundKey(InputConstants.Type.KEYSYM.createFromCode(keyCode));
                Minecraft.getInstance().options.write();
            }
            capturing = null;
            button.setMessage(rebindLabel());
        }
    }

    // ------------------------------------------------------------ 小工具

    private void drawTruncated(GuiGraphics context, String text, int x, int y, int maxWidth, int color) {
        String shown = maxWidth <= 0 ? text : textRenderer.trimToWidth(text, maxWidth);
        context.drawString(textRenderer, Component.literal(shown), x, y, color);
    }

    private static ConfigManager.ConfigData safeConfig() {
        ConfigManager.ConfigData data = ConfigManager.getInstance().get();
        return data == null ? new ConfigManager.ConfigData() : data;
    }

    private static String join(List<String> values, String separator) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ').append(separator).append(' ');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    /** 0 显示成 "0" 而不是 "0.00"。 */
    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Double parseDoubleOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String mask(String key) {
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 7) + "\u2026" + key.substring(key.length() - 4);
    }
}
