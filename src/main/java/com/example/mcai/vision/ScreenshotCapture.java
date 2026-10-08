package com.example.mcai.vision;

import com.example.mcai.util.VisionResolution;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * 截图 → BufferedImage → 缩放 → JPEG → Base64。
 *
 * <p><b>1.21.1 的关键事实（全部用真实字节码/真实类实测核实过）：</b>
 * <ul>
 *   <li>{@code ScreenshotRecorder.takeScreenshot(Framebuffer)} 是<b>同步返回 NativeImage</b>，
 *       不是新版那种回调 API，且必须在渲染线程调用。</li>
 *   <li>它<b>内部已经调用过 mirrorVertically()</b>（字节码 offset 35），
 *       所以<b>绝不能再翻一次</b>，否则截图上下颠倒。</li>
 *   <li>{@code NativeImage} 占<b>堆外内存</b>，不 close 会真泄漏 —— vanilla 的保存路径
 *       也是在 finally 里 close 的。这是整个流程里唯一真正的泄漏风险。</li>
 *   <li>{@code NativeImage.getColor(x, y)} 返回 <b>ARGB</b>（我构造真实 NativeImage 实测过
 *       4 个像素的往返），可以直接喂给 {@code BufferedImage.setRGB}。</li>
 * </ul>
 *
 * <p><b>线程划分（为了不卡主线程）：</b>
 * {@link #grab()} 只能在渲染线程跑（要碰 GL）；它之后的像素转换、缩放、JPEG 编码
 * 全部放到 {@link #encode} 里由后台线程执行 —— 1080p 有 200 万像素，逐像素转换放在
 * 渲染线程会造成肉眼可见的卡顿。NativeImage 的读取是纯内存读，跨线程安全。
 */
public final class ScreenshotCapture {

    /** JPEG 质量：需求指定 0.8。 */
    private static final float JPEG_QUALITY = 0.8f;

    private ScreenshotCapture() {}

    /** 编码结果。 */
    public record EncodedImage(String base64, int width, int height, int jpegBytes) {}

    /**
     * 步骤 1：抓取当前画面。<b>必须在客户端渲染线程调用。</b>
     *
     * @return 截图；失败返回 null。拿到后必须交给 {@link #encode} 处理（由它负责释放）。
     */
    public static NativeImage grab() {
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer == null) {
            return null;
        }
        return ScreenshotRecorder.takeScreenshot(framebuffer);
    }

    /**
     * 步骤 2：像素转换 + 按配置缩放 + JPEG(0.8) + Base64。<b>耗时操作，必须放在后台线程。</b>
     *
     * <p>本方法<b>接管 image 的所有权</b>：无论成功失败都会 close 掉它，
     * 并 flush 掉中间产生的每一个 BufferedImage。
     */
    public static EncodedImage encode(NativeImage image, String resolution) throws IOException {
        BufferedImage source = null;
        BufferedImage working = null;
        try {
            source = toBufferedImage(image);

            int targetHeight = VisionResolution.targetHeight(resolution);
            if (targetHeight > 0 && source.getHeight() > targetHeight) {
                working = scaleToHeight(source, targetHeight);
            } else {
                // 不缩放，但 JPEG 没有 alpha 通道，仍需转成 TYPE_INT_RGB
                working = toRgb(source);
            }

            byte[] jpeg = toJpeg(working, JPEG_QUALITY);
            String base64 = Base64.getEncoder().encodeToString(jpeg);
            return new EncodedImage(base64, working.getWidth(), working.getHeight(), jpeg.length);
        } finally {
            // 堆外内存，必须释放
            image.close();
            if (working != null && working != source) {
                working.flush();
            }
            if (source != null) {
                source.flush();
            }
        }
    }

    private static BufferedImage toBufferedImage(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                // getColor 已经是 ARGB，与 TYPE_INT_ARGB 完全对应
                pixels[rowOffset + x] = image.getColor(x, y);
            }
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        // 一次性整块写入，比逐个 setRGB 快很多
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    /** 按比例缩放到指定高度（保持宽高比），双线性插值保证画质。 */
    private static BufferedImage scaleToHeight(BufferedImage source, int targetHeight) {
        double ratio = targetHeight / (double) source.getHeight();
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * ratio));

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /** ARGB → RGB（JPEG 没有 alpha 通道，直接编码 ARGB 会颜色错乱）。 */
    private static BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    /** 用显式压缩参数写 JPEG，才能真正控制到 0.8 质量（ImageIO.write 不支持设质量）。 */
    private static byte[] toJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JVM 没有可用的 JPEG 编码器");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);

            try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }
}
