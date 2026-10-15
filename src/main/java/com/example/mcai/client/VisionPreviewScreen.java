package com.example.mcai.client;

import com.example.mcai.util.Lang;
import com.example.mcai.util.ScreenCrop;
import com.example.mcai.vision.VisionHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * 发送前预览（1.24 新增）：在把图发到第三方之前，先让你亲眼确认一次。
 *
 * <p>显示的是<b>处理之后</b>的图 —— 已经裁掉框外部分、也已经涂上隐私遮罩、并且缩放到
 * 实际要上传的尺寸。也就是说：<b>你看到的就是即将发出去的那张图</b>，而不是原始截图。
 * 这一点很重要，否则"预览"就失去了它唯一的意义。
 *
 * <p>默认关闭（{@code vision_preview = false}），因为多一步确认会打断连续操作。
 * 想用的人自己去 {@code /ai preview on} 打开。
 *
 * <p><b>纹理所有权：</b>{@code NativeImageBackedTexture} 会持有并负责关闭它拿到的那张
 * NativeImage。所以预览图交给纹理，原始截图仍由本界面持有 ——
 * 两者生命周期分开，避免"纹理关掉了、编码时又在读"这种野指针式的崩溃。
 */
public class VisionPreviewScreen extends Screen {

    private static final Identifier TEXTURE_ID = Identifier.of("mcai", "vision_preview");

    private final NativeImage original;
    private final NativeImage preview;
    private final ScreenCrop.Region region;
    private final String promptOverride;
    private final String infoLine;

    private NativeImageBackedTexture texture;
    private boolean handedOff = false;

    public VisionPreviewScreen(NativeImage original, NativeImage preview,
                               ScreenCrop.Region region, String promptOverride, String infoLine) {
        super(Text.translatable("mcai.preview.title"));
        this.original = original;
        this.preview = preview;
        this.region = region;
        this.promptOverride = promptOverride;
        this.infoLine = infoLine;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 默认实现会铺一层暗色，把预览图压暗 —— 这里不要它。 */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 故意什么都不做
    }

    @Override
    protected void init() {
        releaseTexture();

        // 把预览图上传成纹理。它不会被修改，所以不需要每帧 upload()。
        try {
            texture = new NativeImageBackedTexture(preview);
            this.client.getTextureManager().registerTexture(TEXTURE_ID, texture);
        } catch (Throwable t) {
            texture = null;
        }

        int buttonWidth = 120;
        int buttonHeight = 20;
        int gap = 12;
        int totalWidth = buttonWidth * 2 + gap;
        int startX = (this.width - totalWidth) / 2;
        int y = this.height - 40;

        addDrawableChild(ButtonWidget.builder(Lang.text("mcai.preview.send"), button -> send())
                .dimensions(startX, y, buttonWidth, buttonHeight).build());
        addDrawableChild(ButtonWidget.builder(Lang.text("mcai.preview.discard"), button -> close())
                .dimensions(startX + buttonWidth + gap, y, buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xE0101010);

        if (texture != null && preview != null) {
            int availableWidth = (int) (this.width * 0.80);
            int availableHeight = (int) (this.height * 0.65);

            double scale = Math.min(
                    availableWidth / (double) Math.max(1, preview.getWidth()),
                    availableHeight / (double) Math.max(1, preview.getHeight()));
            // 不放大：小图拉大只会更糊，还占地方
            scale = Math.min(1.0, scale);

            int drawWidth = Math.max(1, (int) Math.round(preview.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(preview.getHeight() * scale));
            int x = (this.width - drawWidth) / 2;
            int y = Math.max(34, (this.height - drawHeight) / 2 - 10);

            context.drawTexture(TEXTURE_ID, x, y, 0.0f, 0.0f,
                    drawWidth, drawHeight, drawWidth, drawHeight);
            context.drawBorder(x - 1, y - 1, drawWidth + 2, drawHeight + 2, 0xFF55FFFF);
        } else {
            context.drawTextWithShadow(this.textRenderer, Lang.text("mcai.preview.no_texture"),
                    8, 40, 0xFFFF5555);
        }

        context.drawTextWithShadow(this.textRenderer, this.title, 8, 8, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Lang.text(infoLine), 8, 20, 0xFFAAAAAA);

        // 只有在这里调用父类：它渲染按钮，而 renderBackground 已经被我们改成空实现
        super.render(context, mouseX, mouseY, delta);
    }

    private void send() {
        NativeImage picked = this.original;
        this.handedOff = true;
        // super.close() → client.setScreen(null) → removed() → 释放纹理（连带预览图）
        super.close();
        VisionHandler.sendPicked(picked, region, promptOverride);
    }

    @Override
    public void removed() {
        releaseTexture();
        super.removed();
    }

    @Override
    public void close() {
        if (!handedOff) {
            // 放弃：原始截图是堆外内存，必须释放
            try {
                original.close();
            } catch (Throwable ignored) {
                // 已经关过就算了
            }
        }
        super.close();
    }

    private void releaseTexture() {
        if (texture != null) {
            try {
                // close() 会连带关掉它持有的那张预览图
                texture.close();
            } catch (Throwable ignored) {
                // 忽略
            }
            texture = null;
        }
        try {
            if (this.client != null && this.client.getTextureManager() != null) {
                this.client.getTextureManager().destroyTexture(TEXTURE_ID);
            }
        } catch (Throwable ignored) {
            // 纹理本来就不存在时 destroyTexture 可能抛，忽略
        }
    }
}
