package com.example.mcai.client;

import com.example.mcai.util.Lang;
import com.example.mcai.util.ScreenCrop;
import com.example.mcai.vision.VisionHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * R 键框选截图（1.24 新增）：把一个可拖拽的取景框盖在画面上，只把框住的部分发给 AI。
 *
 * <p><b>为什么值得做：</b>视觉 token 大致与像素数成正比。原来 {@code H} 发的是整屏，
 * 而玩家真正想问的往往只是一小块（一个熔炉界面、一个红石结构、一个告示牌）。
 * 框到 40% 面积就等于把这次识别的图<br>片开销降到 40%，而且模型不用再被无关的
 * 背景干扰，回答也更准。
 *
 * <p><b>关键实现选择——先截图、再框选：</b>
 * 按下 R 的那一刻就先把画面抓下来（{@link com.example.mcai.vision.ScreenshotCapture#grab()}，
 * 必须在渲染线程），然后才弹这个界面。这样做有两个好处：
 * <ul>
 *   <li>框选时参考的<b>就是你即将发送的那一帧</b>。若反过来（先框选再截图），
 *       帧缓冲里会残留这个界面自己画的遮罩，等于把遮罩一起发出去了。</li>
 *   <li>不需要把截图上传成纹理来显示，本界面只是"在实时画面上盖一层暗色 + 一个亮框"，
 *       代码量和风险都小得多。</li>
 * </ul>
 *
 * <p>选框用<b>比例坐标</b>（0~1）而不是像素，所以屏幕分辨率、GUI 缩放怎么变都成立，
 * 也能直接喂给 {@link ScreenCrop.Region} 与 {@code ScreenshotCapture.encode}。
 *
 * <p>操作：拖拽画框 → <b>回车</b>确认，<b>ESC</b>取消。默认已经预选中间 60%，
 * 所以"直接回车"也是一条可用路径。
 */
public class VisionCaptureScreen extends Screen {

    private final NativeImage image;
    private final String promptOverride;

    /** 拖拽起点（比例）。 */
    private double anchorX;
    private double anchorY;

    private double x0 = 0.20;
    private double y0 = 0.20;
    private double x1 = 0.80;
    private double y1 = 0.80;

    private boolean dragging = false;

    /** 所有权是否已经移交出去（移交后 close() 不能再释放这张图）。 */
    private boolean handedOff = false;

    private static final int DIM = 0xB4000000;
    private static final int BORDER = 0xFF55FFFF;

    public VisionCaptureScreen(NativeImage image, String promptOverride) {
        super(Component.translatable("mcai.pick.title"));
        this.image = image;
        this.promptOverride = promptOverride;
    }

    /** 不要把世界暂停：多人游戏里框选时世界应该继续跑。 */
    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        int left = (int) Math.round(x0 * this.width);
        int top = (int) Math.round(y0 * this.height);
        int right = (int) Math.round(x1 * this.width);
        int bottom = (int) Math.round(y1 * this.height);

        // 选框之外涂暗、选框之内保持原样 —— 于是玩家能直接看到自己框住了画面里的哪一块
        context.fill(0, 0, this.width, top, DIM);
        context.fill(0, bottom, this.width, this.height, DIM);
        context.fill(0, top, left, bottom, DIM);
        context.fill(right, top, this.width, bottom, DIM);

        context.renderOutline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), BORDER);

        context.drawString(this.font, Lang.text("mcai.pick.hint"), 8, 8, 0xFFFFFF);
        int percent = (int) Math.round(100.0 * (x1 - x0) * (y1 - y0));
        context.drawString(this.font,
                Lang.text("mcai.pick.size", Math.max(1, right - left), Math.max(1, bottom - top), percent),
                8, 20, 0xFFAAAAAA);

        // 不调用 super.render：父类会先铺一层全屏暗色，把世界压暗就没法看清框住的内容了。
        // 本界面没有子控件，所以跳过父类渲染是安全的。
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            anchorX = clamp(mouseX / this.width);
            anchorY = clamp(mouseY / this.height);
            x0 = x1 = anchorX;
            y0 = y1 = anchorY;
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == 0) {
            double currentX = clamp(mouseX / this.width);
            double currentY = clamp(mouseY / this.height);
            // 用「起点 + 当前点」取包围盒，这样往任意方向拖都成立，不会像直接交换端点那样越拖越乱
            x0 = Math.min(anchorX, currentX);
            x1 = Math.max(anchorX, currentX);
            y0 = Math.min(anchorY, currentY);
            y1 = Math.max(anchorY, currentY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            // 只是点了一下没拖（或拖得太小）→ 退回默认的中间 60%，别让玩家卡在一个选不中的状态
            if (x1 - x0 < 0.03 || y1 - y0 < 0.03) {
                x0 = 0.20;
                y0 = 0.20;
                x1 = 0.80;
                y1 = 0.80;
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            // 再按一次 R 等同于确认，和 H 的操作习惯一致
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void confirm() {
        ScreenCrop.Region region = new ScreenCrop.Region(x0, y0, x1, y1);
        NativeImage picked = this.image;
        this.handedOff = true;
        // super.close() 让 Screen 自己关掉界面；截图所有权已经移交给 VisionHandler
        super.close();
        VisionHandler.onRegionPicked(picked, region, promptOverride);
    }

    @Override
    public void close() {
        if (!handedOff) {
            // 取消：这张截图是堆外内存，必须自己释放，否则真的会泄漏
            try {
                image.close();
            } catch (Throwable ignored) {
                // 已经关过就算了
            }
        }
        super.close();
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
