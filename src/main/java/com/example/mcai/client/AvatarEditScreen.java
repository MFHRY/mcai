package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.Lang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「调整挂件位置」界面（1.25 新增）。
 *
 * <h2>为什么需要这个界面</h2>
 *
 * <p>HUD 挂件本身<b>点不到</b>。游戏进行中光标被锁进窗口中心，而光标自由时必然有别的
 * 界面开着；没有 mixin 就无法消费鼠标事件，所以在 {@code InGameHud} 上做"点击/拖动"
 * 这条路在原版里走不通。与其留一个永远不触发的交互，不如把它放到一个真正能用的地方：
 * 本界面是一个普通的 {@link Screen}，鼠标本来就在手上。
 *
 * <p>于是交互被拆成两半，各自走能走通的路：
 * <ul>
 *   <li><b>看气泡</b> → {@code key.mcai.avatar}（默认 B）快捷键，游戏进行中随时可用；</li>
 *   <li><b>调位置</b> → 本界面，{@code /ai avatar edit} 打开。</li>
 * </ul>
 *
 * <h2>两个刻意的做法</h2>
 *
 * <p><b>1. 背景不涂黑。</b>调位置的意义就是"别挡住血条、快捷栏、聊天栏"，
 * 把背景涂黑就看不见要避开的东西了。所以 {@link #renderBackground} 是空的。
 *
 * <p><b>2. 和 HUD 用同一套绘制代码</b>（{@link AvatarWidget#renderWidget}）。
 * 各画各的话迟早会出现"在这里摆好了，实际显示却偏几像素"这种很难排查的问题。
 */
public final class AvatarEditScreen extends Screen {

    /** 位移小于这个值算「点击」（切换气泡），不算「拖动」。与 HUD 那套保持一致。 */
    private static final float CLICK_SLOP = 4.0F;

    private final Screen parent;

    private boolean placed = false;
    private int x;
    private int y;
    private int w = 1;
    private int h = 1;

    private boolean dragging = false;
    private double grabDx;
    private double grabDy;
    private double pressX;
    private double pressY;

    public AvatarEditScreen(Screen parent) {
        super(Lang.text("mcai.avatar.edit_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Lang.text("mcai.avatar.edit_done"), button -> onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20)
                .build());
    }

    /** 故意留空：要能看见游戏画面，才判断得出挂件有没有挡住该避开的 HUD 元素。 */
    @Override
    public void renderBackground(GuiGraphics context) {
        // no-op
    }

    /** 不暂停游戏：这是调 UI 位置，不是弹窗，暂停反而会让画面（含 HUD）停在半截状态。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        // HUD 渲染回调在界面开着时不会跑，纹理不会自己加载，必须在这里主动催一下
        AvatarWidget.prepare(minecraft, config);

        int[] size = AvatarWidget.widgetSize(config);
        w = Math.max(1, size[0]);
        h = Math.max(1, size[1]);
        if (!placed) {
            int[] pos = AvatarWidget.positionFor(config, w, h, width, height);
            x = pos[0];
            y = pos[1];
            placed = true;
        }

        // 拖动时实时跟随，不等松手才动——那样没有手感
        if (dragging) {
            x = Mth.clamp((int) Math.round(mouseX - grabDx), 0, Math.max(0, width - w));
            y = Mth.clamp((int) Math.round(mouseY - grabDy), 0, Math.max(0, height - h));
        }

        context.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        context.drawCenteredString(font, Lang.text("mcai.avatar.edit_hint"),
                width / 2, 24, 0xFFAAAAAA);

        if (!AvatarWidget.isTextureReady()) {
            context.drawCenteredString(font, Lang.text("mcai.avatar.edit_failed"),
                    width / 2, height / 2, 0xFFFF5555);
        } else {
            // 选中框暗示"这块可以拖"；拖动中换成暖色，给一点反馈
            context.renderOutline(x - 1, y - 1, w + 2, h + 2, dragging ? 0xFFFFD040 : 0x8055FFFF);
            AvatarWidget.renderWidget(context, font, x, y, w, h, width, height);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && placed && AvatarWidget.isTextureReady()
                && mouseX >= x && mouseX < x + (double) w
                && mouseY >= y && mouseY < y + (double) h) {
            dragging = true;
            grabDx = mouseX - x;
            grabDy = mouseY - y;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 位置在 {@link #render} 里跟鼠标走，这里只需要把事件吃掉，别让它传到下面的控件。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            // 位移很小 → 玩家其实是想点一下看看气泡，不是在摆位置
            double moved = Math.hypot(mouseX - pressX, mouseY - pressY);
            if (moved < CLICK_SLOP) {
                AvatarWidget.toggleBubble();
            } else {
                AvatarWidget.savePosition(x, y, w, h, width, height);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        // 拖到一半直接按 ESC 也要落盘，否则玩家以为摆好了、其实位置没存
        if (placed) {
            AvatarWidget.savePosition(x, y, w, h, width, height);
        }
        minecraft.setScreen(parent);
    }
}
