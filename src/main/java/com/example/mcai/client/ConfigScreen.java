package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 游戏内设置窗口：填写 API Key / 接口地址 / 模型。
 *
 * <p>用 {@code /ai config} 打开。
 *
 * <p><b>1.21.1 的界面 API 陷阱（全部已核实）：</b>
 * <ul>
 *   <li>{@code TextFieldWidget} 是 6 参构造：{@code (TextRenderer, x, y, w, h, Text)}，
 *       老教程里的 4 参版本早已不存在。</li>
 *   <li>按钮不是 {@code new ButtonWidget(...)}，而是
 *       {@code ButtonWidget.builder(Text, pressAction).dimensions(x,y,w,h).build()}。</li>
 *   <li>{@code ClickEvent} 是<b>普通类</b>（{@code new ClickEvent(Action, String)}），
 *       不是新版的 record。</li>
 *   <li>绘制多行 tooltip 要用带 {@code Optional<TooltipData>} 的那个重载。</li>
 * </ul>
 *
 * <p>所有界面文字走语言文件。蓝色提示链接与悬停说明在两份语言文件里是各自成句写的
 * （不是逐字翻译），所以中英文读起来都自然。
 */
public class ConfigScreen extends Screen {

    private static final int FIELD_WIDTH = 260;
    private static final int FIELD_HEIGHT = 20;
    private static final int REVEAL_WIDTH = 54;

    /**
     * 下方的蓝色提示文字。用 {@code Text.translatable} 而不是 {@code Text.literal}：
     * 翻译是<b>渲染时</b>才解析的，所以即使游戏中途切换语言也会跟着变。
     */
    private static final Text HELP_LINK =
            Text.translatable("mcai.screen.help_link").formatted(Formatting.AQUA, Formatting.UNDERLINE);

    private final Screen parent;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget apiUrlField;
    private TextFieldWidget modelField;
    private ButtonWidget revealButton;

    private boolean revealKey = false;

    /** 蓝色提示文字的位置，用于鼠标悬停判定。 */
    private int helpX;
    private int helpY;
    private int helpWidth;

    private int startY;

    public ConfigScreen(Screen parent) {
        super(Text.translatable("mcai.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();

        int centerX = this.width / 2;
        int fieldX = centerX - FIELD_WIDTH / 2;
        this.startY = Math.max(18, (this.height - 190) / 2);

        // ---------------- API Key ----------------
        int keyFieldWidth = FIELD_WIDTH - REVEAL_WIDTH - 4;
        apiKeyField = new TextFieldWidget(textRenderer, fieldX, startY + 26,
                keyFieldWidth, FIELD_HEIGHT, Lang.text("mcai.screen.api_key"));
        apiKeyField.setMaxLength(512);
        apiKeyField.setText(config == null ? "" : nullToEmpty(config.apiKey));
        apiKeyField.setPlaceholder(Text.literal("sk-...").formatted(Formatting.DARK_GRAY));
        applyMask();
        addDrawableChild(apiKeyField);

        revealButton = ButtonWidget.builder(revealLabel(), button -> {
            revealKey = !revealKey;
            applyMask();
            button.setMessage(revealLabel());
        }).dimensions(fieldX + keyFieldWidth + 4, startY + 26, REVEAL_WIDTH, FIELD_HEIGHT).build();
        addDrawableChild(revealButton);

        // 蓝色提示文字：记录位置用于悬停判定
        this.helpWidth = textRenderer.getWidth(HELP_LINK);
        this.helpX = centerX - helpWidth / 2;
        this.helpY = startY + 52;

        // ---------------- 接口地址 ----------------
        apiUrlField = new TextFieldWidget(textRenderer, fieldX, startY + 88,
                FIELD_WIDTH, FIELD_HEIGHT, Lang.text("mcai.screen.api_url"));
        apiUrlField.setMaxLength(512);
        apiUrlField.setText(config == null ? "" : nullToEmpty(config.apiUrl));
        apiUrlField.setPlaceholder(Text.literal("https://api.deepseek.com/v1").formatted(Formatting.DARK_GRAY));
        addDrawableChild(apiUrlField);

        // ---------------- 模型 ----------------
        modelField = new TextFieldWidget(textRenderer, fieldX, startY + 130,
                FIELD_WIDTH, FIELD_HEIGHT, Lang.text("mcai.screen.model"));
        modelField.setMaxLength(128);
        modelField.setText(config == null ? "" : nullToEmpty(config.model));
        modelField.setPlaceholder(Text.literal("deepseek-flash").formatted(Formatting.DARK_GRAY));
        addDrawableChild(modelField);

        // ---------------- 按钮 ----------------
        int buttonWidth = 100;
        int gap = 12;
        int buttonsX = centerX - (buttonWidth * 2 + gap) / 2;
        int buttonY = startY + 164;

        addDrawableChild(ButtonWidget.builder(Lang.text("mcai.screen.save"), button -> save())
                .dimensions(buttonsX, buttonY, buttonWidth, FIELD_HEIGHT).build());
        addDrawableChild(ButtonWidget.builder(Lang.text("mcai.screen.cancel"), button -> close())
                .dimensions(buttonsX + buttonWidth + gap, buttonY, buttonWidth, FIELD_HEIGHT).build());
    }

    private Text revealLabel() {
        return Lang.text(revealKey ? "mcai.screen.hide" : "mcai.screen.show");
    }

    /** API Key 默认打码，避免直播 / 截图时泄露。 */
    private void applyMask() {
        apiKeyField.setRenderTextProvider((text, firstCharacterIndex) -> {
            String shown = revealKey ? text : "*".repeat(text.length());
            return OrderedText.styledForwardsVisitedString(shown, Style.EMPTY);
        });
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        context.drawCenteredTextWithShadow(textRenderer, this.title, centerX, startY, 0xFFFFFF);

        context.drawTextWithShadow(textRenderer, Lang.text("mcai.screen.api_key"), helpX, startY + 14, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, HELP_LINK, helpX, helpY, 0xFFFFFF);

        context.drawTextWithShadow(textRenderer, Lang.text("mcai.screen.api_url"), helpX, startY + 76, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, Lang.text("mcai.screen.model"), helpX, startY + 118, 0xA0A0A0);

        context.drawCenteredTextWithShadow(textRenderer,
                Lang.text("mcai.screen.compat").formatted(Formatting.DARK_GRAY),
                centerX, startY + 156, 0xFFFFFF);

        // 鼠标悬停在蓝色文字上时解释什么是 API
        if (isHoveringHelp(mouseX, mouseY)) {
            context.drawTooltip(textRenderer, helpTooltip(), Optional.empty(), mouseX, mouseY);
        }
    }

    private boolean isHoveringHelp(int mouseX, int mouseY) {
        return mouseX >= helpX && mouseX <= helpX + helpWidth
                && mouseY >= helpY && mouseY <= helpY + 9;
    }

    /** 悬停提示的正文。网址与 {@code sk-} 前缀保持原样，其余跟随游戏语言。 */
    private static List<Text> helpTooltip() {
        List<Text> lines = new ArrayList<>();
        lines.add(Lang.text("mcai.help.title").formatted(Formatting.AQUA, Formatting.BOLD));
        lines.add(Lang.text("mcai.help.body1").formatted(Formatting.GRAY));
        lines.add(Lang.text("mcai.help.body2").formatted(Formatting.GRAY));
        lines.add(Text.empty());
        lines.add(Lang.text("mcai.help.where").formatted(Formatting.AQUA, Formatting.BOLD));
        lines.add(Lang.text("mcai.help.step1").formatted(Formatting.GRAY)
                .append(Text.literal("platform.deepseek.com").formatted(Formatting.WHITE)));
        lines.add(Lang.text("mcai.help.step2").formatted(Formatting.GRAY));
        lines.add(Lang.text("mcai.help.step3a").formatted(Formatting.GRAY)
                .append(Text.literal("sk-").formatted(Formatting.WHITE))
                .append(Lang.text("mcai.help.step3b").formatted(Formatting.GRAY)));
        lines.add(Lang.text("mcai.help.step4").formatted(Formatting.GRAY));
        lines.add(Text.empty());
        lines.add(Lang.text("mcai.help.notes").formatted(Formatting.AQUA, Formatting.BOLD));
        lines.add(Lang.text("mcai.help.note1").formatted(Formatting.GRAY));
        lines.add(Lang.text("mcai.help.note2a").formatted(Formatting.GRAY)
                .append(Lang.text("mcai.help.note2b").formatted(Formatting.RED)));
        return lines;
    }

    private void save() {
        String key = apiKeyField.getText().trim();
        String url = apiUrlField.getText().trim();
        String model = modelField.getText().trim();

        ConfigManager manager = ConfigManager.getInstance();
        manager.update(config -> {
            config.apiKey = key;
            if (!url.isEmpty()) {
                config.apiUrl = url;
            }
            if (!model.isEmpty()) {
                config.model = model;
                // 顺手把它加进切换器列表，否则右键「模型切换器」会跳过这个模型
                List<String> models = new ArrayList<>(
                        config.availableModels == null ? List.of() : config.availableModels);
                if (!models.contains(model)) {
                    models.add(model);
                }
                config.availableModels = List.copyOf(models);
            }
        });

        if (key.isEmpty()) {
            ClientChat.sendLiteral(Lang.tr("mcai.screen.saved_empty"));
        } else {
            ClientChat.sendLiteral(Lang.tr("mcai.screen.saved", mask(key)));
            ClientChat.sendLiteral(Lang.tr("mcai.screen.saved_hint"));
        }
        close();
    }

    private static String mask(String key) {
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 7) + "…" + key.substring(key.length() - 4);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }
}
