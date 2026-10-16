package com.example.mcai.vision;

import com.example.mcai.util.VisionResolution;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

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
 *   <li>{@code Screenshot.takeScreenshot(RenderTarget)} 是<b>同步返回 NativeImage</b>，
 *       不是新版那种回调 API，且必须在渲染线程调用。</li>
 *   <li>它<b>内部已经调用过 mirrorVertically()</b>（字节码 offset 35），
 *       所以<b>绝不能再翻一次</b>，否则截图上下颠倒。</li>
 *   <li>{@code NativeImage} 占<b>堆外内存</b>，不 close 会真泄漏 —— vanilla 的保存路径
 *       也是在 finally 里 close 的。这是整个流程里唯一真正的泄漏风险。</li>
 *   <li>{@code NativeImage.getPixelRGBA(x, y)} 返回 <b>ARGB</b>（我构造真实 NativeImage 实测过
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

    /** 编码结果。{@code jpeg} 是原始 JPEG 字节（Discord 播报要用），{@code base64} 是给模型的。 */
    public record EncodedImage(String base64, byte[] jpeg, int width, int height) {
        /** JPEG 字节数，日志用。 */
        public int jpegBytes() {
            return jpeg == null ? 0 : jpeg.length;
        }
    }

    /**
     * 步骤 1：抓取当前画面。<b>必须在客户端渲染线程调用。</b>
     *
     * @return 截图；失败返回 null。拿到后必须交给 {@link #encode} 处理（由它负责释放）。
     */
    public static NativeImage grab() {
        Minecraft client = Minecraft.getInstance();
        RenderTarget framebuffer = client.getMainRenderTarget();
        if (framebuffer == null) {
            return null;
        }
        return Screenshot.takeScreenshot(framebuffer);
    }

    /**
     * 步骤 2：像素转换 + 按配置缩放 + JPEG(0.8) + Base64。<b>耗时操作，必须放在后台线程。</b>
     *
     * <p>本方法<b>接管 image 的所有权</b>：无论成功失败都会 close 掉它，
     * 并 flush 掉中间产生的每一个 BufferedImage。
     */
    public static EncodedImage encode(NativeImage image, String resolution) throws IOException {
        return encode(image, resolution, null);
    }

    /**
     * 步骤 2：像素转换 + 可选裁剪 + 按配置缩放 + JPEG(0.8) + Base64。<b>耗时操作，必须放在后台线程。</b>
     *
     * <p>本方法<b>接管 image 的所有权</b>：无论成功失败都会 close 掉它，
     * 并 flush 掉中间产生的每一个 BufferedImage。
     *
     * @param crop 可为 null。非 null 时先按比例裁出这块区域再缩放（#4 GUI 感知：
     *             只发界面那一块，图片 token 大约降到四分之一）
     */
    public static EncodedImage encode(NativeImage image, String resolution,
                                      com.example.mcai.util.ScreenCrop.Region crop) throws IOException {
        return encode(image, resolution, crop, null);
    }

    /**
     * 步骤 2：像素转换 + 可选裁剪 + 可选隐私遮罩 + 按配置缩放 + JPEG(0.8) + Base64。
     * <b>耗时操作，必须放在后台线程。</b>
     *
     * <p>本方法<b>接管 image 的所有权</b>：无论成功失败都会 close 掉它，
     * 并 flush 掉中间产生的每一个 BufferedImage。
     *
     * @param crop  可为 null。非 null 时先按比例裁出这块区域再缩放（#4 GUI 感知：
     *              只发界面那一块，图片 token 大约降到四分之一）
     * @param masks 可为 null。非 null 时在缩放之前把这些比例区域涂黑（1.24 隐私遮罩：
     *              避免把聊天栏、玩家名这类内容送到第三方服务）
     */
    public static EncodedImage encode(NativeImage image, String resolution,
                                      com.example.mcai.util.ScreenCrop.Region crop,
                                      java.util.List<com.example.mcai.util.ScreenCrop.Region> masks)
            throws IOException {
        BufferedImage source = null;
        BufferedImage cropped = null;
        BufferedImage working = null;
        try {
            source = toBufferedImage(image);

            BufferedImage base = source;
            if (crop != null) {
                cropped = cropRegion(source, crop);
                if (cropped != null) {
                    base = cropped;
                }
            }

            // 遮罩在裁剪之后、缩放之前做：先裁掉的不需要再涂，遮罩区域按裁剪后的图重新换算
            if (masks != null && !masks.isEmpty()) {
                BufferedImage masked = toRgb(base);
                applyMasks(masked, masks, crop);
                // 注意遮罩这一层现在成了新的 base，不能再被当成"中间的临时图"提前 flush
                if (cropped != null) {
                    cropped.flush();
                }
                if (source != null && source != cropped) {
                    source.flush();
                }
                source = null;
                cropped = null;
                base = masked;
                if (working != null) {
                    working.flush();
                }
            }

            int targetHeight = VisionResolution.targetHeight(resolution);
            if (targetHeight > 0 && base.getHeight() > targetHeight) {
                working = scaleToHeight(base, targetHeight);
            } else {
                // 不缩放，但 JPEG 没有 alpha 通道，仍需转成 TYPE_INT_RGB
                working = toRgb(base);
            }

            byte[] jpeg = toJpeg(working, JPEG_QUALITY);
            String base64 = Base64.getEncoder().encodeToString(jpeg);
            return new EncodedImage(base64, jpeg, working.getWidth(), working.getHeight());
        } finally {
            // 堆外内存，必须释放
            image.close();
            if (working != null && working != source && working != cropped) {
                working.flush();
            }
            if (cropped != null) {
                cropped.flush();
            }
            if (source != null) {
                source.flush();
            }
        }
    }

    /**
     * 生成一张"即将发送的样子"的预览图（1.24 发送前预览用）：裁剪 → 遮罩 → 缩放到适合显示。
     *
     * <p><b>不接管 {@code source} 的所有权</b>，调用方仍要用它去做真正的编码。
     * 返回的 NativeImage 由调用方负责 close（预览界面会交给纹理去关）。
     *
     * @param maxWidth  预览图最大宽度（像素）
     * @param maxHeight 预览图最大高度（像素）
     */
    public static NativeImage previewOf(NativeImage source,
                                        com.example.mcai.util.ScreenCrop.Region crop,
                                        java.util.List<com.example.mcai.util.ScreenCrop.Region> masks,
                                        int maxWidth, int maxHeight) {
        BufferedImage sourceImage = null;
        BufferedImage cropped = null;
        BufferedImage working = null;
        try {
            sourceImage = toBufferedImage(source);

            BufferedImage base = sourceImage;
            if (crop != null) {
                cropped = cropRegion(sourceImage, crop);
                if (cropped != null) {
                    base = cropped;
                }
            }

            working = toRgb(base);
            if (masks != null && !masks.isEmpty()) {
                applyMasks(working, masks, crop);
            }

            double scale = Math.min(
                    maxWidth / (double) Math.max(1, working.getWidth()),
                    maxHeight / (double) Math.max(1, working.getHeight()));
            scale = Math.min(1.0, scale);

            int targetWidth = Math.max(1, (int) Math.round(working.getWidth() * scale));
            int targetHeight = Math.max(1, (int) Math.round(working.getHeight() * scale));

            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(working, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }

            NativeImage out = new NativeImage(targetWidth, targetHeight, false);
            for (int y = 0; y < targetHeight; y++) {
                for (int x = 0; x < targetWidth; x++) {
                    // getRGB 返回 ARGB，NativeImage.setColor 也接受 ARGB，可以直接搬
                    out.setPixelRGBA(x, y, scaled.getRGB(x, y));
                }
            }
            scaled.flush();
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (working != null) {
                working.flush();
            }
            if (cropped != null) {
                cropped.flush();
            }
            if (sourceImage != null) {
                sourceImage.flush();
            }
        }
    }

    /**
     * 把遮罩区域涂黑。
     *
     * @param crop 已经裁剪过时传入裁剪区域，用来把「原图比例坐标」换算回「当前图比例坐标」
     */
    private static void applyMasks(BufferedImage image,
                                   java.util.List<com.example.mcai.util.ScreenCrop.Region> masks,
                                   com.example.mcai.util.ScreenCrop.Region crop) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();

        for (com.example.mcai.util.ScreenCrop.Region mask : masks) {
            if (mask == null) {
                continue;
            }
            // 遮罩本身也是"整屏比例"定义的，裁剪之后要按裁剪框做一次归一化
            double nx0 = mask.x0(), ny0 = mask.y0(), nx1 = mask.x1(), ny1 = mask.y1();
            if (crop != null) {
                double cw = crop.width();
                double ch = crop.height();
                if (cw <= 0.0 || ch <= 0.0) {
                    continue;
                }
                nx0 = (mask.x0() - crop.x0()) / cw;
                nx1 = (mask.x1() - crop.x0()) / cw;
                ny0 = (mask.y0() - crop.y0()) / ch;
                ny1 = (mask.y1() - crop.y0()) / ch;
            }

            int x = (int) Math.floor(Math.max(0.0, nx0) * imageWidth);
            int y = (int) Math.floor(Math.max(0.0, ny0) * imageHeight);
            int right = (int) Math.ceil(Math.min(1.0, nx1) * imageWidth);
            int bottom = (int) Math.ceil(Math.min(1.0, ny1) * imageHeight);

            x = Math.max(0, Math.min(x, imageWidth));
            y = Math.max(0, Math.min(y, imageHeight));
            right = Math.max(x, Math.min(right, imageWidth));
            bottom = Math.max(y, Math.min(bottom, imageHeight));

            int w = right - x;
            int h = bottom - y;
            if (w <= 0 || h <= 0) {
                continue;
            }
            int[] black = new int[w * h];
            java.util.Arrays.fill(black, 0xFF000000);
            image.setRGB(x, y, w, h, black, 0, w);
        }
    }

    /** 按比例裁出一块区域；区域非法（太小/越界）时返回 null，让调用方用原图。 */
    private static BufferedImage cropRegion(BufferedImage source, com.example.mcai.util.ScreenCrop.Region crop) {
        int x = (int) Math.floor(crop.x0() * source.getWidth());
        int y = (int) Math.floor(crop.y0() * source.getHeight());
        int w = (int) Math.round(crop.width() * source.getWidth());
        int h = (int) Math.round(crop.height() * source.getHeight());

        // 夹到图片范围内，避免 setRGB 越界抛异常
        x = Math.max(0, Math.min(x, source.getWidth() - 1));
        y = Math.max(0, Math.min(y, source.getHeight() - 1));
        w = Math.max(1, Math.min(w, source.getWidth() - x));
        h = Math.max(1, Math.min(h, source.getHeight() - y));

        try {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            // getRGB 整块取出来、整块塞进去，比逐像素快很多
            int[] pixels = source.getRGB(x, y, w, h, null, 0, w);
            out.setRGB(0, 0, w, h, pixels, 0, w);
            return out;
        } catch (Throwable t) {
            return null;
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
                pixels[rowOffset + x] = image.getPixelRGBA(x, y);
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
