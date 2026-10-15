package com.example.mcai.vision;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;
import com.example.mcai.client.ThinkingIndicator;
import com.example.mcai.util.AiResponseParser;
import com.example.mcai.util.ApiClient;
import com.example.mcai.util.ApiProvider;
import com.example.mcai.util.ApiRequest;
import com.example.mcai.util.ClientChat;
import com.example.mcai.util.EnvironmentContext;
import com.example.mcai.util.HttpErrorCatalog;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.ApiKeyManager;
import com.example.mcai.util.HistoryStore;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.RequestController;
import com.example.mcai.util.ScreenCrop;
import com.example.mcai.util.SystemPrompt;
import com.example.mcai.util.TokenStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 快捷键 H：截取当前画面 → 本地压缩 → 发给多模态模型 → 结果回聊天栏。
 *
 * <p><b>1.21.1 的坑（已核实）：</b>
 * <ul>
 *   <li>快捷键类叫 {@code net.minecraft.client.KeyMapping}，<b>不叫 KeyMapping</b>；
 *       而且 1.21.1 <b>没有 KeyMapping.Category</b> 这种东西，分类参数就是一个普通的
 *       String 翻译键（这里用 "category.mcai"）。</li>
 *   <li>{@code KeyBindingHelper.registerKeyBinding} 必须在客户端初始化阶段调用。</li>
 * </ul>
 *
 * <p><b>模型差异（实测）：</b> {@code deepseek-flash} 能真正读图（实测准确读出隐藏文字），
 * 而 {@code deepseek-v4-pro} <b>不支持图片输入</b>，它不会报错，只会礼貌地回一句
 * "我无法读取这张图片" —— 既浪费 token 又让人困惑。所以这里在发请求前先拦一道。
 *
 * <p>所有面向玩家的文案都走语言文件，中英文共用一个 jar。
 */
public final class VisionHandler {

    private static final String KEY_TRANSLATION = "key.mcai.vision";
    private static final String KEY_BUILD_TRANSLATION = "key.mcai.build";
    private static final String KEY_PICK_TRANSLATION = "key.mcai.pick";
    private static final String KEY_WHAT_TRANSLATION = "key.mcai.what";
    private static final String KEY_CATEGORY = "category.mcai";

    private static final long COOLDOWN_MILLIS = 3000L;
    private static final long MIN_INTERVAL_MS = 300L;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static KeyMapping visionKey;
    private static KeyMapping buildKey;
    private static KeyMapping pickKey;
    private static KeyMapping whatKey;

    private static final AtomicLong nextAllowedTime = new AtomicLong(0L);
    private static volatile long lastTriggerAt = 0L;

    private static final ExecutorService VISION_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-vision-worker");
        thread.setDaemon(true);
        return thread;
    });

    private VisionHandler() {}

    /** 在 McaiModClient.onInitializeClient() 里调用。 */
    public static void register() {
        // 1.21.1：分类是普通 String，不是 KeyMapping.Category
        visionKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_TRANSLATION, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, KEY_CATEGORY));

        // #2 红石 / 建筑读图：另开一个键，走同一条截图链路但换提示词
        buildKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_BUILD_TRANSLATION, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KEY_CATEGORY));

        // 1.24 框选截图：只把框住的那一块发出去，直接省下视觉 token
        pickKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_PICK_TRANSLATION, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, KEY_CATEGORY));

        // 1.25 准星查询：看准一个方块/生物按一下，把它的名字和 ID 交给 AI 解释
        whatKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_WHAT_TRANSLATION, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (visionKey.consumeClick()) {
                trigger(null);
            }
            while (buildKey.consumeClick()) {
                trigger(Lang.tr("mcai.vision.prompt.build"));
            }
            while (pickKey.consumeClick()) {
                beginRegionPick(null);
            }
            while (whatKey.consumeClick()) {
                com.example.mcai.client.CrosshairQuery.trigger();
            }
        });
    }

    // ------------------------------------------------------------ 1.25 改键入口

    /**
     * 把四个快捷键对象暴露给设置界面，让玩家能在 {@code /ai config} 里直接改键。
     *
     * <p><b>为什么不在 mcai.json 里另存一份按键：</b>Fabric 注册的快捷键本来就会出现在
     * 原版「选项 → 控制」里，并能改键。<b>唯一的真相是原版的 {@code options.txt}</b>。
     * 如果我们再存一份，玩家在原版界面里改了之后两边就打架了，而"哪个才算数"取决于
     * 启动顺序——这种 bug 极难排查。所以这里只暴露对象给界面去改，不引入第二份状态。
     */
    public static KeyMapping visionKeyBinding() { return visionKey; }

    public static KeyMapping buildKeyBinding() { return buildKey; }

    public static KeyMapping pickKeyBinding() { return pickKey; }

    public static KeyMapping whatKeyBinding() { return whatKey; }

    // ------------------------------------------------------------ 1.24 框选截图

    /**
     * R 键：先抓下当前这一帧，再弹出框选界面。
     *
     * <p><b>顺序很重要。</b>必须先截图、后开界面：如果先开界面再截图，
     * 帧缓冲里会残留框选界面自己画的暗色遮罩，等于把遮罩一起发给模型。
     * 先抓帧还有一个好处——玩家框选时参考的就是<b>即将发送的那一帧</b>。
     */
    public static void beginRegionPick(String promptOverride) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < MIN_INTERVAL_MS) {
            return;
        }
        ConfigManager.ConfigData config = preflight(client);
        if (config == null) {
            return;
        }
        if (client.screen != null) {
            // 已经开着别的界面（背包/聊天）时不要叠加，否则框选会被上层界面挡住
            ClientChat.sendLiteral(Lang.tr("mcai.pick.close_screen"));
            return;
        }

        NativeImage image;
        try {
            image = ScreenshotCapture.grab();
        } catch (Throwable t) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_failed", rootMessage(t)));
            return;
        }
        if (image == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_null"));
            return;
        }

        lastTriggerAt = now;
        nextAllowedTime.set(now + COOLDOWN_MILLIS);

        try {
            client.setScreen(new com.example.mcai.client.VisionCaptureScreen(image, promptOverride));
        } catch (Throwable t) {
            // 界面没打开就必须自己把这张堆外内存放掉
            try {
                image.close();
            } catch (Throwable ignored) {
                // 忽略
            }
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_failed", rootMessage(t)));
        }
    }

    /** 框选界面点了确认：按配置决定是先预览，还是直接发。 */
    public static void onRegionPicked(NativeImage image, ScreenCrop.Region region, String promptOverride) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (image == null) {
            return;
        }
        if (config == null || !config.visionPreview) {
            sendPicked(image, region, promptOverride);
            return;
        }

        // 预览要显示"处理之后的样子"，所以先把裁剪 + 遮罩 + 缩放做一遍。
        // previewOf 不接管源图所有权，源图还要留着做真正的编码。
        NativeImage preview = ScreenshotCapture.previewOf(image, region, masksFromConfig(config), 640, 360);
        if (preview == null) {
            // 生成预览失败就直接发，不要因为一个附加功能把主流程卡死
            sendPicked(image, region, promptOverride);
            return;
        }

        Minecraft client = Minecraft.getInstance();
        String info = Lang.tr("mcai.preview.info", preview.getWidth(), preview.getHeight(),
                estimateLine(config, region));
        client.setScreen(new com.example.mcai.client.VisionPreviewScreen(
                image, preview, region, promptOverride, info));
    }

    /** 真正把框选好的截图编码并发出去。 */
    public static void sendPicked(NativeImage image, ScreenCrop.Region region, String promptOverride) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            try {
                image.close();
            } catch (Throwable ignored) {
                // 忽略
            }
            ClientChat.sendLiteral(Lang.tr("mcai.error.config_load"));
            return;
        }
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();
        processAsync(config, image, thinking, promptOverride, region, masksFromConfig(config));
    }

    /** 把配置里的隐私遮罩字符串解析成比例区域；关闭时返回 null。 */
    private static java.util.List<ScreenCrop.Region> masksFromConfig(ConfigManager.ConfigData config) {
        if (config == null || !config.privacyMask || config.privacyMaskRegions == null
                || config.privacyMaskRegions.isEmpty()) {
            return null;
        }
        java.util.List<ScreenCrop.Region> out = new java.util.ArrayList<>();
        for (String raw : config.privacyMaskRegions) {
            ScreenCrop.Region region = parseRegion(raw);
            if (region != null) {
                out.add(region);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 解析 {@code "x0,y0,x1,y1"}；格式不对就跳过这一条，不让它影响整张图。 */
    static ScreenCrop.Region parseRegion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("[,，]");
        if (parts.length != 4) {
            return null;
        }
        try {
            double x0 = Double.parseDouble(parts[0].trim());
            double y0 = Double.parseDouble(parts[1].trim());
            double x1 = Double.parseDouble(parts[2].trim());
            double y1 = Double.parseDouble(parts[3].trim());
            double left = Math.max(0.0, Math.min(x0, x1));
            double right = Math.min(1.0, Math.max(x0, x1));
            double top = Math.max(0.0, Math.min(y0, y1));
            double bottom = Math.min(1.0, Math.max(y0, y1));
            if (right - left <= 0.0 || bottom - top <= 0.0) {
                return null;
            }
            return new ScreenCrop.Region(left, top, right, bottom);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这次请求的大致花费，用在预览界面上让玩家心里有数。 */
    private static String estimateLine(ConfigManager.ConfigData config, ScreenCrop.Region region) {
        double ratio = region == null ? 1.0 : Math.max(0.01, region.width() * region.height());
        int height = com.example.mcai.util.VisionResolution.targetHeight(config.visionResolution);
        if (height <= 0) {
            height = 1080;
        }
        // 视觉 token 的粗略经验值：每 750 像素约 1 个 token
        int pixels = (int) Math.round(height * height * (16.0 / 9.0) * ratio);
        int imageTokens = Math.max(1, pixels / 750);
        com.example.mcai.util.PricingCatalog.Quote quote =
                com.example.mcai.util.PricingCatalog.find(config.model);
        if (quote == null) {
            return Lang.tr("mcai.preview.cost_unknown", imageTokens);
        }
        double yuan = quote.estimate(imageTokens + 120, 220);
        return Lang.tr("mcai.preview.cost", imageTokens, String.format(java.util.Locale.ROOT, "%.4f", yuan));
    }

    /**
     * 所有视觉请求共用的前置校验。任一项不过都会自己发提示并返回 null。
     *
     * <p>抽出来是为了让「按 H」「按 G」「按 R 框选」「危险预警」四条入口
     * 走<b>完全同一套</b>校验，不会出现某个入口漏掉预算拦截这种情况。
     */
    private static ConfigManager.ConfigData preflight(Minecraft client) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.config_load"));
            return null;
        }
        // 本地推理服务（Ollama / LM Studio）不需要 API Key，不要因为没有 key 把它拦掉
        if (LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)) {
            ClientChat.sendLiteral(Lang.tr(LocalProvider.isLocal(config.apiUrl)
                    ? "mcai.vision.local_hint"
                    : "mcai.error.no_apikey"));
            return null;
        }
        if (config.apiUrl == null || config.apiUrl.isBlank()) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.no_apiurl"));
            return null;
        }
        if (config.model == null || config.model.isBlank()) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.no_model"));
            return null;
        }
        // 实测 deepseek-v4-pro 不支持图片输入，且它不会报错、只会敷衍一句，
        // 所以这里直接拦下来并告诉玩家怎么办，省得白烧 token。
        if (!ModelCatalog.lookup(config.model).supportsVision()) {
            ClientChat.sendLiteral(Lang.tr("mcai.vision.no_vision", config.model));
            return null;
        }
        // 今日预算超支时拦下,别继续烧钱
        if (TokenStats.isOverDailyBudget()) {
            ClientChat.sendLiteral(Lang.tr("mcai.cmd.budget_blocked"));
            return null;
        }
        return config;
    }

    /**
     * 可复用的截图识别入口（#2 红石读图 / #4 GUI 感知 / #16 无障碍描述 共用）。
     *
     * @param promptOverride 自定提示词；null 表示用默认的 {@code mcai.vision.prompt}
     */
    public static void trigger(String promptOverride) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        // 键盘 repeat / 同一 tick 多次触发时兜底，避免连点
        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < MIN_INTERVAL_MS) {
            return;
        }

        long allowedAt = nextAllowedTime.get();
        if (now < allowedAt) {
            long remainSeconds = (allowedAt - now + 999L) / 1000L;
            ClientChat.sendLiteral(Lang.tr("mcai.vision.cooldown", remainSeconds));
            return;
        }

        ConfigManager.ConfigData config = preflight(client);
        if (config == null) {
            return;
        }

        // ---- 校验全部通过，正式开跑，此时才消耗冷却 ----
        lastTriggerAt = now;
        nextAllowedTime.set(now + COOLDOWN_MILLIS);

        // 从按下快捷键的这一刻开始计时（截图 + 压缩 + 网络全部算玩家感知的等待）
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();

        // #4 GUI 感知：如果此刻开着背包/箱子这类容器界面，只截界面那一块。
        // 图片 token 大致与像素数成正比，裁到界面能把开销降到约四分之一，
        // 同时模型也更容易看清格子内容。必须在渲染线程、截图之前取。
        com.example.mcai.util.ScreenCrop.Region crop =
                com.example.mcai.util.ScreenCrop.forCurrentScreen();

        NativeImage image;
        try {
            // 必须在渲染线程抓取（END_CLIENT_TICK 就在渲染线程上）
            image = ScreenshotCapture.grab();
        } catch (Throwable t) {
            thinking.finish();
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_failed", rootMessage(t)));
            return;
        }
        if (image == null) {
            thinking.finish();
            ClientChat.sendLiteral(Lang.tr("mcai.vision.grab_null"));
            return;
        }

        processAsync(config, image, thinking, promptOverride, crop, masksFromConfig(config));
    }

    /** 像素转换 / 裁剪 / 缩放 / JPEG / HTTP 全部在后台线程完成，绝不占用渲染线程。 */
    private static void processAsync(ConfigManager.ConfigData config, NativeImage image,
                                     ThinkingIndicator.Request thinking, String promptOverride,
                                     com.example.mcai.util.ScreenCrop.Region crop,
                                     java.util.List<com.example.mcai.util.ScreenCrop.Region> masks) {
        // encode() 接管 image 所有权，无论成功失败都会 close，不会泄漏堆外内存
        VISION_EXECUTOR.execute(() -> {
            try {
                ScreenshotCapture.EncodedImage encoded =
                        ScreenshotCapture.encode(image, config.visionResolution, crop, masks);

                McaiMod.LOGGER.info("mcAI screenshot encoded: {}x{}, JPEG {} KB, vision_resolution={}, cropped={}",
                        encoded.width(), encoded.height(),
                        encoded.jpegBytes() / 1024, config.visionResolution, crop != null);

                sendRequest(config, encoded.base64(), promptOverride, thinking, null);
            } catch (Throwable t) {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.encode_failed", rootMessage(t)));
            } finally {
                // 幂等兜底：任何漏网的路径都不会让加载符号永远转下去
                thinking.finish();
            }
        });
    }

    /**
     * 直接用一份已经编码好的截图发请求（供死亡复盘 #1 复用）。
     *
     * <p>调用方负责做好前置校验（key、预算、模型是否支持读图），并保证在后台线程调用。
     */
    public static void sendEncoded(ConfigManager.ConfigData config, String base64,
                                   String prompt, String historyNote) {
        ThinkingIndicator.Request thinking = ThinkingIndicator.begin();
        try {
            sendRequest(config, base64, prompt, thinking, historyNote);
        } finally {
            // 幂等兜底
            thinking.finish();
        }
    }

    /**
     * 可复用的视觉请求（#2 红石读图 / #4 GUI 感知 / #5 翻译 / #16 无障碍 / #1 死亡复盘 共用）。
     *
     * @param promptOverride 自定提示词；null 表示用默认的 {@code mcai.vision.prompt}
     * @param historyNote    非 null 时，把这次问答也记进多轮上下文（用文字摘要，不放图片 base64）
     */
    public static void sendRequest(ConfigManager.ConfigData config, String base64,
                                   String promptOverride, ThinkingIndicator.Request thinking,
                                   String historyNote) {
        // 本地服务不需要 key；云端走多 key 轮换，并把句柄一路带着，好精确归因失败的那一把
        ApiKeyManager.PickedKey picked = LocalProvider.isLocal(config.apiUrl)
                ? new ApiKeyManager.PickedKey(LocalProvider.effectiveApiKey(config.apiUrl, config.apiKey), null)
                : ApiKeyManager.pick(config.apiKey, config.backupApiKeys);

        String prompt = promptOverride == null ? Lang.tr("mcai.vision.prompt") : promptOverride;
        // 截图链路走 JPEG（体积比 PNG 小很多），所以 MIME 必须跟着一起传：
        // 声明成 PNG 而 actual 是 JPEG 时，有的服务端直接拒收，有的会解出一张花屏
        ApiRequest request = ApiRequest.image(config.model, SystemPrompt.build(), prompt,
                base64, "image/jpeg");

        // 1.24 可取消请求：先领一个 id，并登记"取消时停掉思考动画"
        final long requestId = RequestController.begin();
        RequestController.setCancelHook(thinking::finish);

        try {
            ApiProvider provider = ApiProvider.detect(config.apiProvider, config.apiUrl);
            ApiClient.Options options = ApiClient.Options.of(
                    config.apiUrl, picked.key(), config.retryMax, REQUEST_TIMEOUT);
            JsonObject root = ApiClient.send(provider, options, request,
                    () -> RequestController.isCurrent(requestId));

            // 玩家按 ESC 取消过（或被新请求顶掉）→ 静默丢弃，绝不弹出"幽灵回答"
            if (!RequestController.isCurrent(requestId)) {
                return;
            }

            ApiKeyManager.recordResult(picked, 200);
            String reply = handleSuccess(root, thinking.finish());
            if (historyNote != null) {
                HistoryStore.remember(historyNote, reply);
            }
        } catch (ApiClient.ApiException e) {
            thinking.finish();
            // 取消不是错误，安静收场（提示语由快捷键那一侧负责发）
            if (e.cancelled || !RequestController.isCurrent(requestId)) {
                return;
            }
            if (e.status > 0) {
                ApiKeyManager.recordResult(picked, e.status);
                // 401 / 402 / 429 等按统一字典给出解释（跟随游戏语言）
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed",
                        e.status + ": " + HttpErrorCatalog.describe(e.status)));
            } else if (e.status == 0) {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed", Lang.tr("mcai.error.code0")));
            } else {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed_network", e.getMessage()));
            }
        } catch (Throwable t) {
            thinking.finish();
            if (RequestController.isCurrent(requestId)) {
                ClientChat.sendLiteral(Lang.tr("mcai.vision.failed_network", rootMessage(t)));
            }
        } finally {
            RequestController.finish(requestId);
        }
    }

    /**
     * 展示识别结果。
     *
     * @param root 已经由 {@link ApiProvider} 归一化成 OpenAI 形状的响应
     * @return 这次识别得到的回答文本（供调用方记进多轮上下文）。
     */
    private static String handleSuccess(JsonObject root, long elapsedMillis) {
        if (root == null) {
            ClientChat.sendLiteral(Lang.tr("mcai.error.unparsable"));
            return null;
        }

        String reply = AiResponseParser.extractContent(root);
        if (reply == null || reply.isBlank()) {
            reply = Lang.tr("mcai.reply.empty");
        }

        // Token 统计与聊天共用同一套跨天清零逻辑，并写一条使用明细。
        // 1.25：返回值是这次按真实 usage 算出的花费，附在回答末尾（和聊天链路一致）。
        double cost = TokenStats.recordFrom(root, "vision");

        // 先输出思维链、再输出答案（与聊天链路行为一致）
        ClientChat.sendReasoning(root);

        StringBuilder suffix = new StringBuilder(" ")
                .append(ThinkingIndicator.formatElapsed(elapsedMillis));
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config != null && config.showRequestCost && cost > 0.0) {
            suffix.append(' ').append(Lang.tr("mcai.chat.cost", formatYuan(cost)));
        }

        ClientChat.sendLiteral("§f[AI] " + reply.replace("\n", " ").trim() + suffix);
        return reply;
    }

    private static String formatYuan(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", Math.max(0.0, value));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }
}
