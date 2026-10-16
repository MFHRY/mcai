package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 屏幕角落的「鲸鱼娘」HUD 挂件（1.25 新增）。
 *
 * <p>把 dsh-whale-widget 网页挂件的交互搬到游戏 HUD 上：可拖动、点击弹气泡、按 Q 压扁、
 * 悬浮提示。气泡里显示什么由 {@link AvatarStats} 决定，本类只负责画和响应鼠标键盘。
 *
 * <h2>几个刻意的设计</h2>
 *
 * <p><b>1. 图片加载永远不碰渲染线程的磁盘 IO。</b>渲染回调里每帧都可能跑，绝不能出现
 * {@code Files.readAllBytes}。所以「读字节」（文件或 jar 内置资源）全部丢给
 * {@link #LOADER} 单线程池；渲染线程只做 {@code NativeImage.read(byte[])} 解码 +
 * {@code registerTexture} 上传。<b>只在首次、或配置里的图片路径变了时</b>才会重新加载；
 * 失败一次就永久沉默（记一条 debug 日志），不会每帧重试刷屏。
 *
 * <p><b>2. 纹理用 {@code TextureManager.registerTexture} 动态注册</b>，ResourceLocation 是
 * {@code mcai:avatar_widget}——故意不带 {@code textures/} 前缀，避免和资源包里同名的
 * 静态贴图抢 ID。这也是 {@code VisionPreviewScreen} 已经在用的同一套写法。
 *
 * <p><b>3. 光标坐标自己换算。</b>{@code Mouse} 里存的 {@code x/y} 是<b>窗口像素</b>，
 * 不是 GUI 坐标（反编译 {@code Mouse.onMouseButton} 确认过：原版自己做的换算就是
 * {@code mouse.x * scaledWidth / width}）。所以这里照抄同一个公式，不猜。
 *
 * <p><b>4. 交互闸门见 {@link #interactionEnabled}。</b>这是本类唯一一处"功能被主动关掉"，
 * 原因写在那个方法上面，请务必先读它再改。
 */
public final class AvatarWidget {

    private AvatarWidget() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcai-avatar");

    /** 动态纹理 ID。不带 textures/ 前缀是故意的，见类注释。 */
    private static final ResourceLocation TEXTURE_ID = new ResourceLocation("mcai", "avatar_widget");

    /** avatarScale == 1.0 时头像长边的 GUI 像素数。 */
    private static final int BASE_SIZE = 64;

    /** 位移小于这个值算「点击」，不算「拖动」。 */
    private static final float MIN_DRAG_DISTANCE = 4.0F;

    /** 只负责把图片字节读出来，绝不在渲染线程上跑。 */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-avatar-loader");
        thread.setDaemon(true);
        return thread;
    });

    // ---------------------------------------------------------------- 纹理状态
    // 以下字段只在渲染线程读写。

    /** 当前纹理对应的「来源描述」；和配置里的 avatarImage 不一致就重新加载。 */
    private static String textureSpec = null;

    /** 纹理已上传、可以画了。 */
    private static boolean textureReady = false;

    /** 这次加载失败了，在 spec 变化之前不再重试。 */
    private static boolean textureFailed = false;

    /** 在途的后台读取；渲染线程只 poll {@code isDone()}，不 get 阻塞。 */
    private static CompletableFuture<byte[]> pendingBytes = null;

    private static int texWidth = 1;
    private static int texHeight = 1;

    // ---------------------------------------------------------------- 交互状态

    private static boolean leftWasDown = false;
    private static boolean dragging = false;
    private static double dragStartCursorX = 0.0;
    private static double dragStartCursorY = 0.0;
    private static int dragStartX = 0;
    private static int dragStartY = 0;
    private static int dragX = 0;
    private static int dragY = 0;
    private static float dragDistance = 0.0F;

    private static boolean bubbleOpen = false;
    private static List<String> bubbleLines = List.of();

    private static boolean qWasDown = false;
    private static float squash = 1.0F;
    private static float squashVelocity = 0.0F;

    /** 本帧鼠标是否落在头像上（每帧重置）。 */
    private static boolean hovered = false;

    /** 打开/收起气泡的快捷键（默认 B）。鼠标点不到 HUD，所以这件事必须由键盘承担。 */
    private static KeyMapping bubbleKey;

    /** "还没配头像图"的提示是否已经发过。每个游戏进程只提示一次，避免变成骚扰。 */
    private static boolean hintShown = false;

    /**
     * 在 {@code McaiModClient.onInitializeClient()} 里调用。
     *
     * <p><b>为什么还要一个快捷键（1.25 补）：</b>HUD 挂件的"点击"在原版里根本点不到——
     * 游戏进行中光标被锁进窗口中心，而光标自由时必然有别的界面开着，且没有 mixin 就
     * 无法消费鼠标事件。<b>结论：鼠标点击在纯原版下不可用。</b>所以"打开气泡"这件事
     * 改由快捷键承担（默认 B，可在原版「选项 → 控制」里改），
     * "拖动位置"则由 {@link AvatarEditScreen} 承担（{@code /ai avatar edit}）。
     *
     * <p>按键轮询走 tick 事件而不是渲染回调：渲染回调在开着界面时不会被调用，
     * 那样按键就会"卡住"到界面关闭才生效。
     */
    /**
     * 创建快捷键对象。
     *
     * <p>Forge 把"创建 {@code KeyMapping}"和"登记到游戏"拆成了两步：
     * 这里只 new，登记交给 {@link #registerKeyMappings}（mod 事件总线）。
     * Fabric 的 {@code KeyBindingHelper} 是一步到位，所以这里是结构差异。
     */
    public static void createKeyMappings() {
        bubbleKey = new KeyMapping(
                "key.mcai.avatar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.mcai");
    }

    /** 在 mod 事件总线上登记；不登记的话这个键不会出现在「选项 → 控制」里。 */
    public static void registerKeyMappings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        if (bubbleKey == null) {
            createKeyMappings();
        }
        event.register(bubbleKey);
    }

    /**
     * 由 {@code McaiModClient} 的统一 tick 分发器调用。
     *
     * <p>按键轮询走 tick 而不是渲染回调：渲染回调在开着界面时不会被调用，
     * 那样按键会"卡住"，等到界面关闭才一次性生效。
     */
    public static void onClientTick(Minecraft client) {
        if (bubbleKey != null) {
            while (bubbleKey.consumeClick()) {
                toggleBubble();
            }
        }
        hintOnce(client);
    }

    /**
     * 挂件开着、但头像图（含内置那张）始终加载不出来时，只提示一次（每个游戏进程一次）。
     *
     * <p>判据是 {@link #textureFailed} 而不是 {@code !textureReady}：图片是异步加载的，
     * 加载途中 {@code textureReady} 也是 false，用后者会在第一帧误报一次。
     *
     * <p>也不做成常驻 HUD 文字：那会一直挡着游戏画面。一次、并且说清楚去哪儿配，就够了。
     */
    private static void hintOnce(Minecraft client) {
        if (hintShown || client == null || client.player == null || client.level == null) {
            return;
        }
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.avatarVisible) {
            return;
        }
        if (textureSpec == null) {
            ensureTexture(client, config);
            return;
        }
        if (!textureFailed) {
            return; // 还在加载，或已经就绪——都不该打扰玩家
        }
        hintShown = true;
        ClientChat.sendLiteral(Lang.tr("mcai.avatar.no_image"));
    }

    // ================================================================ 渲染入口

    /**
     * HUD 渲染入口，由 {@code RenderGuiEvent.Post} 调用。
     *
     * <p><b>Forge 版与 Fabric 版的差异：</b>Fabric 的 {@code HudRenderCallback} 回调是
     * {@code (DrawContext, RenderTickCounter)}。{@code RenderTickCounter} 是 <b>1.20.5+</b>
     * 才有的类，1.20.1 里根本不存在 —— 这里改成接收事件给出的 {@code partialTick}
     * （当前 tick 内的时间比例，0~1），语义上正对原代码里的 {@code getLastFrameDuration()}。
     *
     * <p>夹到 {@code [0.001, 1]} 是给 Q 弹弹簧用的：partialTick 在极端情况下可能为 0
     * （同一帧内被调用多次），而弹簧的积分步长一旦是 0 就会除以零。
     */
    public static void onRenderGui(GuiGraphics context, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }

        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.avatarVisible) {
            return;
        }
        // 不在世界里（标题界面 / 断开连接）不画
        if (client.player == null || client.level == null) {
            return;
        }
        // F3 打开时让位给调试信息
        // 1.20.1 has no getDebugHud()/shouldShowDebugHud(); the flag that
        // says "the F3 overlay is up" is a plain field on Minecraft.
        if (client.options.renderDebug) {
            return;
        }

        // 先确认画得出来，再处理输入：图都没加载好时不该为它播 Q 的音效
        ensureTexture(client, config);
        if (!textureReady) {
            // 还没加载好 / 加载失败：静默不画，绝不崩、也不每帧重试
            hovered = false;
            leftWasDown = false;
            return;
        }

        float deltaTicks = Math.max(0.001F, Math.min(1.0F, partialTick));
        updateQState(client, config, deltaTicks);

        int screenW = context.guiWidth();
        int screenH = context.guiHeight();

        int[] size = computeDrawSize(config);
        int drawW = size[0];
        int drawH = size[1];

        int[] laid = layout(config, drawW, drawH, screenW, screenH);
        int curX = dragging ? dragX : laid[0];
        int curY = dragging ? dragY : laid[1];

        hovered = false;
        handleMouse(client, config, curX, curY, drawW, drawH, screenW, screenH);

        // 松手吸附会写回配置，而 update() 是换一个新 ConfigData 实例——
        // 上面拿到的 config 已经是旧的，必须重新取一次，否则位置要等下一帧才动。
        ConfigManager.ConfigData fresh = ConfigManager.getInstance().get();
        if (fresh != null) {
            config = fresh;
        }

        // 拖动中位置可能刚被 update 过，重新取一次最终位置
        int[] finalPos = dragging ? new int[]{dragX, dragY}
                : layout(config, drawW, drawH, screenW, screenH);
        curX = finalPos[0];
        curY = finalPos[1];

        drawAvatar(context, curX, curY, drawW, drawH);
        if (bubbleOpen) {
            drawBubble(context, client.font, curX, curY, drawW, drawH, screenW, screenH);
        }
        if (hovered && !dragging) {
            drawTooltip(context, client.font, curX, curY, drawH, screenW, screenH);
        }
    }

    // ================================================================ 绘制

    /**
     * 画头像。纵向压扁靠 {@code squash} 做非等比缩放——必须走矩阵，
     * 因为 {@code drawTexture} 的宽高是 int，塞不下 0.62 这种小数。
     */
    private static void drawAvatar(GuiGraphics context, int x, int y, int w, int h) {
        // 体积守恒的挤压：纵向 sy，横向补 2 - sy
        float sy = squash;
        float sx = 2.0F - squash;

        PoseStack matrices = context.pose();
        matrices.pushPose();
        try {
            matrices.translate(x + w / 2.0F, y + h / 2.0F, 0.0F);
            matrices.scale(sx, sy, 1.0F);
            matrices.translate(-w / 2.0F, -h / 2.0F, 0.0F);
            context.blit(TEXTURE_ID, 0, 0, 0.0F, 0.0F, w, h, w, h);
        } finally {
            // 万一 drawTexture 抛了，也不能把矩阵栈留脏
            matrices.popPose();
        }
    }

    /** 点击头像弹出的气泡：半透明底 + 边框 + 逐行文字。 */
    private static void drawBubble(GuiGraphics context, Font textRenderer,
                                   int avatarX, int avatarY, int avatarW, int avatarH,
                                   int screenW, int screenH) {
        List<String> lines = bubbleLines;
        if (lines.isEmpty()) {
            lines = List.of(Lang.tr("mcai.avatar.bubble_empty"));
        }

        int pad = 4;
        int lineHeight = textRenderer.lineHeight + 1;
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, textRenderer.width(line));
        }
        int w = textWidth + pad * 2;
        int h = lines.size() * lineHeight + pad * 2 - 1;
        if (w < 40) {
            w = 40;
        }

        // 优先放右侧，右边放不下就翻到左边
        int x = avatarX + avatarW + 4;
        if (x + w > screenW - 2) {
            x = avatarX - w - 4;
        }
        x = Mth.clamp(x, 2, Math.max(2, screenW - w - 2));
        int y = Mth.clamp(avatarY, 2, Math.max(2, screenH - h - 2));

        context.fill(x, y, x + w, y + h, 0xC0101018);
        context.renderOutline(x, y, w, h, 0xFF55FFFF);

        int textX = x + pad;
        int textY = y + pad;
        context.enableScissor(x, y, x + w, y + h);
        try {
            for (String line : lines) {
                // 行文本自带 § 颜色码，这里的 0xFFFFFFFF 只是兜底基色
                context.drawString(textRenderer, line, textX, textY, 0xFFFFFFFF);
                textY += lineHeight;
            }
        } finally {
            context.disableScissor();
        }
    }

    /** 悬浮提示：一小段说明，放头像下方，下面放不下就翻到上方。 */
    private static void drawTooltip(GuiGraphics context, Font textRenderer,
                                    int avatarX, int avatarY, int avatarH,
                                    int screenW, int screenH) {
        String line1 = Lang.tr("mcai.avatar.tip_click");
        String line2 = Lang.tr("mcai.avatar.tip_drag");

        int pad = 4;
        int w = Math.max(textRenderer.width(line1), textRenderer.width(line2)) + pad * 2;
        int h = (textRenderer.lineHeight + 1) * 2 + pad * 2 - 1;

        int x = Mth.clamp(avatarX, 2, Math.max(2, screenW - w - 2));
        int y = avatarY + avatarH + 4;
        if (y + h > screenH - 2) {
            y = avatarY - h - 4;
        }
        y = Mth.clamp(y, 2, Math.max(2, screenH - h - 2));

        context.fill(x, y, x + w, y + h, 0xB0101018);
        context.renderOutline(x, y, w, h, 0xFF55FFFF);
        context.drawString(textRenderer, line1, x + pad, y + pad, 0xFFFFFFFF);
        context.drawString(textRenderer, line2, x + pad, y + pad + textRenderer.lineHeight + 1,
                0xFFAAAAAA);
    }

    // ================================================================ 布局

    /** 按 avatarScale 和图片长宽比算出绘制尺寸（等比缩放进 BASE_SIZE 的方框）。 */
    private static int[] computeDrawSize(ConfigManager.ConfigData config) {
        double scale = config.avatarScale;
        if (!Double.isFinite(scale) || scale <= 0.0) {
            scale = 1.0;
        }
        scale = Mth.clamp(scale, 0.25, 4.0);
        int base = Math.max(8, (int) Math.round(BASE_SIZE * scale));

        double aspect = texWidth / (double) Math.max(1, texHeight);
        int w;
        int h;
        if (aspect >= 1.0) {
            w = base;
            h = Math.max(1, (int) Math.round(base / aspect));
        } else {
            h = base;
            w = Math.max(1, (int) Math.round(base * aspect));
        }
        return new int[]{w, h};
    }

    /** 由 avatarCorner + offset 算出左上角坐标。 */
    private static int[] layout(ConfigManager.ConfigData config, int w, int h, int screenW, int screenH) {
        String corner = config.avatarCorner == null ? "bottom_left" : config.avatarCorner;
        int offX = config.avatarOffsetX;
        int offY = config.avatarOffsetY;

        int x;
        int y;
        switch (corner) {
            case "top_left" -> {
                x = offX;
                y = offY;
            }
            case "top_right" -> {
                x = screenW - w - offX;
                y = offY;
            }
            case "bottom_right" -> {
                x = screenW - w - offX;
                y = screenH - h - offY;
            }
            default -> {
                // bottom_left 及任何未知取值都按左下处理
                x = offX;
                y = screenH - h - offY;
            }
        }
        return new int[]{x, y};
    }

    // ================================================================ 输入

    /**
     * 交互闸门。
     *
     * <p>两个条件都不能少：
     * <ul>
     *   <li>{@code currentScreen == null}——<b>绝不在别的界面还开着的时候动</b>。
     *       我们没有 mixin 可以消费鼠标事件，如果背包开着还响应，点挂件会真的去搬动
     *       物品、甚至把手上那叠丢到窗口外。</li>
     *   <li>{@code !mouse.isCursorLocked()}——原版在游戏进行中会把光标锁进窗口中心，
     *       此时 {@code Mouse} 里的坐标既不可见也没有意义，悬浮/命中判断全是假象。</li>
     * </ul>
     *
     * <p><b>已知后果（1.25 已给出替代路径）：</b>这两个条件在原版 1.21.1 下几乎不可能
     * 同时成立——游戏里光标是锁定的，光标自由时又必然开着某个界面。所以这里的
     * 拖动 / 点击在纯原版下处于休眠状态，<b>这是刻意的</b>：宁可功能不触发，
     * 也不要让玩家在背包里点挂件把物品丢出去。
     *
     * <p>两个动作因此各自走了真正走得通的入口：
     * 「开关气泡」→ {@code key.mcai.avatar}（默认 B，见 {@link #register()}）；
     * 「调整位置」→ {@link AvatarEditScreen}（{@code /ai avatar edit}）——
     * 那是个普通界面，鼠标本来就是自由的，命中判断和点击消费都由它自己负责。
     * 下面这段代码保留着，是为了将来若真的引入 mixin，可以直接接上而不用重写。
     */
    private static boolean interactionEnabled(Minecraft client) {
        return client.screen == null && !client.mouseHandler.isMouseGrabbed();
    }

    private static void handleMouse(Minecraft client, ConfigManager.ConfigData config,
                                    int x, int y, int w, int h, int screenW, int screenH) {
        long handle = client.getWindow().getWindow();
        boolean down;
        try {
            down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        } catch (Throwable t) {
            down = false;
        }

        // Mouse 里是窗口像素；换算公式抄自原版 Mouse.onMouseButton
        int winW = Math.max(1, client.getWindow().getWidth());
        int winH = Math.max(1, client.getWindow().getHeight());
        double mouseX = client.mouseHandler.xpos() * screenW / (double) winW;
        double mouseY = client.mouseHandler.ypos() * screenH / (double) winH;

        boolean inside = mouseX >= x && mouseX < x + (double) w
                && mouseY >= y && mouseY < y + (double) h;
        hovered = inside;

        if (!interactionEnabled(client)) {
            // 光标不可信：丢掉拖动状态，只记录按键边沿免得解禁瞬间误判成一次拖动
            dragging = false;
            leftWasDown = down;
            return;
        }

        if (down && !leftWasDown && inside) {
            // 按下：进入「可能是点击、也可能是拖动」的中间态
            dragging = true;
            dragStartCursorX = mouseX;
            dragStartCursorY = mouseY;
            dragStartX = x;
            dragStartY = y;
            dragX = x;
            dragY = y;
            dragDistance = 0.0F;
        } else if (down && dragging) {
            double dx = mouseX - dragStartCursorX;
            double dy = mouseY - dragStartCursorY;
            dragDistance = Math.max(dragDistance, (float) Math.hypot(dx, dy));
            dragX = Mth.clamp(dragStartX + (int) Math.round(dx), 0, Math.max(0, screenW - w));
            dragY = Mth.clamp(dragStartY + (int) Math.round(dy), 0, Math.max(0, screenH - h));
        } else if (!down && leftWasDown && dragging) {
            dragging = false;
            if (dragDistance < MIN_DRAG_DISTANCE) {
                toggleBubble();
            } else {
                snapToNearestCorner(dragX, dragY, w, h, screenW, screenH);
            }
        }

        leftWasDown = down;
    }

    /** 松手后吸附到最近的角落，并把角落 + 偏移写回配置。 */
    private static void snapToNearestCorner(int x, int y, int w, int h, int screenW, int screenH) {
        boolean right = x + w / 2.0 > screenW / 2.0;
        boolean bottom = y + h / 2.0 > screenH / 2.0;

        String corner = (bottom ? "bottom" : "top") + "_" + (right ? "right" : "left");
        int offsetX = right ? Math.max(0, screenW - (x + w)) : Math.max(0, x);
        int offsetY = bottom ? Math.max(0, screenH - (y + h)) : Math.max(0, y);

        try {
            ConfigManager.getInstance().update(c -> {
                c.avatarCorner = corner;
                c.avatarOffsetX = offsetX;
                c.avatarOffsetY = offsetY;
            });
        } catch (Throwable t) {
            LOGGER.debug("[mcAI] failed to persist avatar position", t);
        }
    }

    /**
     * 再点一次收起。内容只在「打开」这一刻取，不给渲染循环增加每帧开销。
     *
     * <p>public 是因为它有两个外部调用方：{@code key.mcai.avatar} 快捷键，
     * 以及 {@link AvatarEditScreen} 里的点击。
     */
    public static void toggleBubble() {
        if (bubbleOpen) {
            bubbleOpen = false;
            bubbleLines = List.of();
            return;
        }
        try {
            List<String> lines = AvatarStats.lines();
            List<String> copy = new ArrayList<>();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null) {
                        copy.add(line);
                    }
                }
            }
            bubbleLines = List.copyOf(copy);
        } catch (Throwable t) {
            LOGGER.debug("[mcAI] avatar bubble stats failed", t);
            bubbleLines = List.of();
        }
        bubbleOpen = true;
    }

    // ============================================== 1.25 给编辑界面用的公开接口

    /**
     * 让 {@link AvatarEditScreen} 能确保纹理已开始加载。
     *
     * <p>必须显式调用：纹理加载挂在 HUD 渲染回调里，而<b>界面开着时 HUD 渲染回调根本不跑</b>，
     * 不主动触发的话编辑界面里会是一片空白，玩家以为坏了。
     */
    public static void prepare(Minecraft client, ConfigManager.ConfigData config) {
        if (client == null || config == null) {
            return;
        }
        ensureTexture(client, config);
    }

    /** 纹理是否已就绪。编辑界面用它决定要不要提示"图片加载失败"。 */
    public static boolean isTextureReady() {
        return textureReady;
    }

    /**
     * 校验一个头像图片路径能不能用，供 {@code /ai avatar image <路径>} 在写进配置之前检查。
     *
     * <p>刻意<b>先验证再保存</b>：如果直接把一个打不开的路径写进配置，玩家看到的现象是
     * "挂件不见了"，而完全联想不到是自己路径填错了——这是最难排查的一类问题。
     *
     * @return null 表示没问题；否则返回给玩家看的错误说明
     */
    public static String validateImagePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null; // 留空是合法操作，表示"清掉，不显示挂件"
        }
        String spec = raw.trim();
        // 玩家从资源管理器复制路径时，Windows 会带上这层引号，直接 Path.of 会找不到文件
        if (spec.length() >= 2 && spec.startsWith("\"") && spec.endsWith("\"")) {
            spec = spec.substring(1, spec.length() - 1).trim();
        }
        Path path;
        try {
            path = Path.of(spec);
        } catch (Throwable t) {
            return Lang.tr("mcai.cmd.avatar_bad_path", spec);
        }
        if (!Files.isRegularFile(path)) {
            return Lang.tr("mcai.cmd.avatar_not_found", spec);
        }
        String lower = spec.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".png")) {
            // 只接受 PNG：加载走的是 NativeImage.read，它认的就是 PNG 字节
            return Lang.tr("mcai.cmd.avatar_not_png", spec);
        }
        return null;
    }

    /** 把玩家给的路径去掉 Windows 复制时带上的引号。 */
    public static String normalizeImagePath(String raw) {
        if (raw == null) {
            return "";
        }
        String spec = raw.trim();
        if (spec.length() >= 2 && spec.startsWith("\"") && spec.endsWith("\"")) {
            spec = spec.substring(1, spec.length() - 1).trim();
        }
        return spec;
    }

    /** 按 avatarScale + 图片长宽比算出的绘制尺寸。 */
    public static int[] widgetSize(ConfigManager.ConfigData config) {
        return computeDrawSize(config);
    }

    /** 按 avatarCorner + offset 算出的左上角坐标。 */
    public static int[] positionFor(ConfigManager.ConfigData config, int w, int h, int screenW, int screenH) {
        return layout(config, w, h, screenW, screenH);
    }

    /**
     * 把挂件画在指定位置。
     *
     * <p>编辑界面走的是<b>同一个</b>绘制方法，这样"编辑时看到的"和"实际显示的"
     * 一定一致；各画各的话迟早会出现"摆好了但实际偏几像素"这种没法排查的问题。
     */
    public static void renderWidget(GuiGraphics context, Font textRenderer,
                                    int x, int y, int w, int h, int screenW, int screenH) {
        drawAvatar(context, x, y, w, h);
        if (bubbleOpen) {
            drawBubble(context, textRenderer, x, y, w, h, screenW, screenH);
        }
    }

    /**
     * 把当前位置吸附到最近的角落并写回配置。
     *
     * <p>写完之后调用方必须<b>重新取一次</b> {@code ConfigManager.getInstance().get()}：
     * {@code update()} 是换一个新实例，旧快照会晚一帧才更新。
     */
    public static void savePosition(int x, int y, int w, int h, int screenW, int screenH) {
        snapToNearestCorner(x, y, w, h, screenW, screenH);
    }

    // ================================================================ Q 键 + 音效

    /** 按住 Q 纵向压扁，松开弹回；用弹簧插值，不瞬间跳变。 */
    private static void updateQState(Minecraft client, ConfigManager.ConfigData config,
                                     float deltaTicks) {
        boolean down;
        try {
            down = InputConstants.isKeyDown(client.getWindow().getWindow(), GLFW.GLFW_KEY_Q);
        } catch (Throwable t) {
            down = false;
        }

        if (down != qWasDown) {
            qWasDown = down;
            // 按下和松开各响一次
            if (config.avatarSound) {
                playClick(client);
            }
        }

        // getLastFrameDuration() 的单位是 tick（20 tick = 1 秒）
        float dt = Mth.clamp(deltaTicks / 20.0F, 0.0F, 0.1F);
        float target = down ? 0.62F : 1.0F;

        squashVelocity += (target - squash) * 320.0F * dt;
        squashVelocity *= (float) Math.exp(-22.0 * dt);
        squash += squashVelocity * dt;
        // 夹一下，避免极端掉帧时弹簧发散
        squash = Mth.clamp(squash, 0.45F, 1.25F);
    }

    private static void playClick(Minecraft client) {
        try {
            // SoundEvents.UI_BUTTON_CLICK 是 Holder.Reference<SoundEvent>，
            // 走的是 master(Holder<SoundEvent>, float) 这个重载。
            client.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        } catch (Throwable t) {
            LOGGER.debug("[mcAI] avatar click sound failed", t);
        }
    }

    // ================================================================ 纹理加载

    /**
     * 保证纹理和配置一致。只在「首次」和「avatarImage 变了」时重新加载。
     *
     * <p>渲染线程上不做任何磁盘操作——读字节在 {@link #LOADER} 上，这里只 poll 结果。
     */
    private static void ensureTexture(Minecraft client, ConfigManager.ConfigData config) {
        String spec = config.avatarImage == null ? "" : config.avatarImage.trim();

        if (textureSpec == null || !textureSpec.equals(spec)) {
            releaseTexture(client);
            textureSpec = spec;
            textureFailed = false;
            pendingBytes = CompletableFuture.supplyAsync(() -> readBytes(spec), LOADER);
        }

        if (textureFailed || pendingBytes == null || !pendingBytes.isDone()) {
            return;
        }

        CompletableFuture<byte[]> future = pendingBytes;
        pendingBytes = null;

        byte[] bytes;
        try {
            bytes = future.get();
        } catch (Throwable t) {
            bytes = null;
        }

        if (bytes == null || bytes.length == 0) {
            // 失败就沉默：不再重试，最多留一条 debug 日志
            textureFailed = true;
            textureReady = false;
            LOGGER.debug("[mcAI] avatar image unavailable ({}), widget stays hidden",
                    spec.isEmpty() ? "builtin" : spec);
            return;
        }

        NativeImage image = null;
        try {
            image = NativeImage.read(bytes);
            texWidth = Math.max(1, image.getWidth());
            texHeight = Math.max(1, image.getHeight());
            DynamicTexture texture = new DynamicTexture(image);
            image = null; // 所有权交给纹理，失败路径里不要再 close 一次
            client.getTextureManager().register(TEXTURE_ID, texture);
            textureReady = true;
            textureFailed = false;
        } catch (Throwable t) {
            textureReady = false;
            textureFailed = true;
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                    // 关不掉也不影响：反正这张已经不要了
                }
            }
            LOGGER.debug("[mcAI] avatar texture upload failed", t);
        }
    }

    /**
     * 在后台线程上读玩家指定的图片字节。
     *
     * <p><b>本模组不内置任何头像美术素材</b>，只认 {@code avatar_image} 指向的那个文件；
     * 没有配就是没有图，界面会提示去哪儿配，而不是画一个凑合的东西顶上。
     * 这样模组的 CC0 授权就只覆盖代码，不牵扯任何来路需要解释的美术素材。
     *
     * @return 图片字节；没配、路径不存在或读不出来时返回 {@code null}
     */
    private static byte[] readBytes(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(spec);
            if (Files.isRegularFile(path)) {
                return Files.readAllBytes(path);
            }
            LOGGER.debug("[mcAI] configured avatar image not found: {}", spec);
        } catch (Throwable t) {
            LOGGER.debug("[mcAI] configured avatar image path is unusable: {}", spec, t);
        }
        return null;
    }

    private static void releaseTexture(Minecraft client) {
        textureReady = false;
        pendingBytes = null;
        if (client == null) {
            return;
        }
        try {
            client.getTextureManager().release(TEXTURE_ID);
        } catch (Throwable t) {
            LOGGER.debug("[mcAI] failed to release avatar texture", t);
        }
    }
}
