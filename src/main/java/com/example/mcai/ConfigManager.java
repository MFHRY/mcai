package com.example.mcai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.VisionResolution;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    public static ConfigManager getInstance() { return INSTANCE; }

    private static final String CONFIG_FILE = "mcai.json";
    private static final long DEBOUNCE_MS = 500L;

    /**
     * Gson 2.10.1 (the version shipped with Minecraft 1.21.1) has NO built-in support for
     * java.time types. Without this adapter every serialization/deserialization of
     * ConfigData fails with:
     *   JsonIOException: Failed making field 'java.time.LocalDate#year' accessible
     * That is a RuntimeException, so the `catch (IOException e)` in writeToDisk() did not
     * catch it and mcai.json was never created - which in game looked like
     * "apiKey is always empty" and "token stats never persist".
     *
     * NOTE: this is deliberately a nested class that is instantiated *inline* in the gson
     * field initializer below, instead of being held in a static field.
     * `INSTANCE` at the top of this class is created from ConfigManager's static
     * initializer, and Java runs static initializers in textual order - so a static field
     * declared after INSTANCE is still null at that moment, and
     * GsonBuilder.registerTypeAdapter(type, null) throws IllegalArgumentException.
     * Instantiating inline makes this independent of static-initialization order.
     */
    private static final class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            String text = in.nextString();
            return (text == null || text.isBlank()) ? null : LocalDate.parse(text);
        }
    }

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
            .create();
    private final Path configPath;

    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcai-config-io");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<ConfigData> configRef = new AtomicReference<>();
    private final ConfigData initial;
    private ScheduledFuture<?> pendingWrite = null;

    /**
     * 初始磁盘加载完成的信号。{@link #update} / {@link #save} 会先等它一份，
     * 否则玩家在配置还没读进来时就做了修改，会被随后到达的磁盘值覆盖——相当于把
     * mcai.json 里的真实配置冲掉。
     */
    private final CompletableFuture<Void> initialLoad = new CompletableFuture<>();

    private ConfigManager() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        this.initial = new ConfigData();
        configRef.set(initial);
        loadAsync();
    }

    public ConfigData get() { return configRef.get(); }

    public void update(Consumer<ConfigData> mutator) {
        // 修改前先等初始加载完成，避免基于默认占位快照做修改（会丢掉磁盘里的真实配置）
        awaitInitialLoad();
        ConfigData updated;
        ConfigData current;
        do {
            current = configRef.get();
            updated = new ConfigData(current);
            mutator.accept(updated);
        } while (!configRef.compareAndSet(current, updated));
        scheduleDebouncedSave();
    }

    public CompletableFuture<Void> save() {
        return CompletableFuture.runAsync(() -> writeToDisk(configRef.get()), ioExecutor);
    }

    /** 等初始加载完成（最多 5 秒），防止启动竞态覆盖玩家刚做的修改。 */
    private void awaitInitialLoad() {
        if (initialLoad.isDone()) {
            return;
        }
        try {
            initialLoad.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            System.err.println("[mcAI] Timed out waiting for initial config load: " + e.getMessage());
        }
    }

    private void loadAsync() {
        CompletableFuture.runAsync(() -> {
            ConfigData loaded;
            if (Files.exists(configPath)) {
                loaded = readFromDisk();
                if (loaded == null) loaded = new ConfigData();
            } else {
                loaded = new ConfigData();
            }
            boolean needsWrite = loaded.fillDefaults();
            // 只在 configRef 仍是「构造时的那个初始占位」时才应用磁盘配置。
            // update() 和 save() 都在等 initialLoad，正常情况下这里不会有并发修改，
            // 但万一有，CAS 失败就放弃本次加载，以已经发生的修改为准。
            if (configRef.compareAndSet(initial, loaded)) {
                if (needsWrite || !Files.exists(configPath)) {
                    writeToDisk(loaded);
                }
            }
        }, ioExecutor).whenComplete((ignored, ex) -> {
            if (ex != null) {
                System.err.println("[mcAI] Failed to load config: " + ex.getMessage());
            }
            initialLoad.complete(null);
        });
    }

    private synchronized void scheduleDebouncedSave() {
        if (pendingWrite != null && !pendingWrite.isDone()) pendingWrite.cancel(false);
        ConfigData snapshot = configRef.get();
        pendingWrite = ioExecutor.schedule(() -> writeToDisk(snapshot), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private ConfigData readFromDisk() {
        try (Reader reader = Files.newBufferedReader(configPath)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            return gson.fromJson(jsonObject, ConfigData.class);
        } catch (Exception e) {
            System.err.println("[mcAI] Failed to read config file: " + e.getMessage());
            return null;
        }
    }

    private void writeToDisk(ConfigData data) {
        Path tmpPath = configPath.resolveSibling(CONFIG_FILE + ".tmp");
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(tmpPath)) {
                gson.toJson(data, writer);
            }
            Files.move(tmpPath, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[mcAI] Failed to write config file: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(tmpPath); } catch (IOException ignored) {}
        }
    }

    public static class ConfigData {
        // DeepSeek official platform defaults.
        // Model ids verified against GET https://api.deepseek.com/models
        //   -> "deepseek-flash" (the "v4 flash" model), "deepseek-v4-pro"
        // The API key is intentionally NOT defaulted here: never hardcode a secret in source.
        private static final String DEFAULT_API_URL = "https://api.deepseek.com/v1";
        private static final String DEFAULT_MODEL = "deepseek-flash";
        private static final List<String> DEFAULT_MODELS = List.of("deepseek-flash", "deepseek-v4-pro");

        /**
         * 思考过程在聊天栏默认最多显示多少字。
         * 实测同一个问题 flash 会返回约 4100 字、pro 约 6600 字的思维链，
         * 全量灌进聊天栏会刷屏几十行，所以默认截断。
         * 设为 0 表示显示完整思考过程。
         */
        private static final int DEFAULT_REASONING_MAX_CHARS = 500;

        @SerializedName("api_key") public String apiKey = "";
        @SerializedName("api_url") public String apiUrl = DEFAULT_API_URL;
        @SerializedName("model") public String model = DEFAULT_MODEL;
        @SerializedName("available_models") public List<String> availableModels = DEFAULT_MODELS;
        @SerializedName("mode") public String mode = "chat";
        @SerializedName("available_modes") public List<String> availableModes = Arrays.asList("chat", "vision");
        @SerializedName("vision_resolution") public String visionResolution = VisionResolution.DEFAULT;
        @SerializedName("show_reasoning") public boolean showReasoning = true;
        @SerializedName("reasoning_max_chars") public int reasoningMaxChars = DEFAULT_REASONING_MAX_CHARS;
        @SerializedName("daily_tokens") public int dailyTokens = 0;
        @SerializedName("token_date") public LocalDate tokenDate = LocalDate.now();

        // ---- 1.20 新增字段 ----
        @SerializedName("daily_cost_yuan") public double dailyCostYuan = 0.0;
        @SerializedName("daily_budget_yuan") public double dailyBudgetYuan = 0.0;
        @SerializedName("backup_api_keys") public List<String> backupApiKeys = List.of();
        @SerializedName("history_turns") public int historyTurns = 4;
        @SerializedName("death_recap") public boolean deathRecap = false;
        @SerializedName("discord_webhook") public String discordWebhook = "";
        @SerializedName("persona") public String persona = "default";
        @SerializedName("available_personas") public List<String> availablePersonas = List.of("default");
        @SerializedName("recipe_cache") public boolean recipeCache = true;
        @SerializedName("streaming") public boolean streaming = true;
        // ---- 触发词前缀 & 翻译（1.22 新增） ----
        @SerializedName("chat_prefix") public String chatPrefix = "!ai";
        @SerializedName("translate_lang") public String translateLang = "中文";

        // ---- 1.24 新增 ----

        /** 相同问题命中本地缓存时直接回答，不再发请求（零 token）。仅存内存，重启即失效。 */
        @SerializedName("response_cache") public boolean responseCache = true;
        /** 缓存最多保留多少条问答。 */
        @SerializedName("response_cache_size") public int responseCacheSize = 64;

        /** 是否把「游戏版本 + 已加载模组列表」拼进系统提示词，让 AI 知道你装了什么。 */
        @SerializedName("env_context") public boolean envContext = true;
        /** 模组列表最多写入多少个名字，防止提示词过长。 */
        @SerializedName("env_context_max_mods") public int envContextMaxMods = 60;

        /** 今日花费达到预算的这个比例时提醒一次。<=0 或 >=1 表示关闭提醒。 */
        @SerializedName("budget_warn_ratio") public double budgetWarnRatio = 0.8;

        /** 长期记忆：每次提问都会带上，让 AI 记住你的基地坐标、正在建的东西等。 */
        @SerializedName("memories") public List<String> memories = List.of();

        /** 发送前先弹预览窗口确认（默认关闭，避免多一步操作）。 */
        @SerializedName("vision_preview") public boolean visionPreview = false;

        /** 发图前把指定区域涂黑，避免把聊天栏/玩家名送到第三方。 */
        @SerializedName("privacy_mask") public boolean privacyMask = false;
        /** 涂黑区域，格式 "x0,y0,x1,y1"，比例坐标（0~1，左上原点）。默认遮住原版聊天栏。 */
        @SerializedName("privacy_mask_regions") public List<String> privacyMaskRegions =
                List.of("0.0,0.70,0.52,1.0");

        /** 危险预警：血量低于阈值且附近有敌对生物时，自动截图问 AI。 */
        @SerializedName("danger_alert") public boolean dangerAlert = false;
        /** 触发预警的血量（半颗心 = 1）。 */
        @SerializedName("danger_health") public int dangerHealth = 6;
        /** 敌对生物的检测半径（方块）。 */
        @SerializedName("danger_radius") public double dangerRadius = 12.0;
        /** 两次预警之间的最小间隔（秒）。 */
        @SerializedName("danger_cooldown_seconds") public int dangerCooldownSeconds = 60;

        /** 用系统语音朗读 AI 的回答（实验性，默认关闭）。 */
        @SerializedName("tts") public boolean tts = false;
        /** 朗读时最多读多少个字，防止念一整篇。 */
        @SerializedName("tts_max_chars") public int ttsMaxChars = 220;

        // ---- 1.25 新增 ----

        /**
         * 用哪套 API 协议。{@code auto} 按 {@code api_url} 猜：
         * 含 {@code generativelanguage.googleapis.com} → gemini，
         * 含 {@code anthropic.com} → claude，其余按 OpenAI 兼容处理。
         */
        @SerializedName("api_provider") public String apiProvider = "auto";

        /** 请求失败自动重试几次。只对网络异常和 429/5xx 重试，0 = 不重试。 */
        @SerializedName("retry_max") public int retryMax = 2;

        /** 回答末尾附上这一次调用按真实 usage 算出的花费。 */
        @SerializedName("show_request_cost") public boolean showRequestCost = true;

        /** 鲸鱼娘挂件是否显示。 */
        @SerializedName("avatar_visible") public boolean avatarVisible = true;

        /** 挂件吸附的角落：bottom_left / bottom_right / top_left / top_right。 */
        @SerializedName("avatar_corner") public String avatarCorner = "bottom_left";

        /** 相对角落的像素偏移。 */
        @SerializedName("avatar_offset_x") public int avatarOffsetX = 8;
        @SerializedName("avatar_offset_y") public int avatarOffsetY = 8;

        /** 挂件缩放倍率。 */
        @SerializedName("avatar_scale") public double avatarScale = 1.0;

        /**
         * 自定义头像 PNG 的绝对路径，留空则用内置的原创鲸鱼娘。
         *
         * <p>做成路径而不是内置图片，是因为第三方挂件的美术素材<b>不在其开源许可范围内</b>
         * （见 dsh-whale-widget 的 PROVENANCE.md），不能随本模组一起分发。
         * 玩家想用哪张图就自己指过去，本模组不打包任何来路不明的素材。
         */
        @SerializedName("avatar_image") public String avatarImage = "";

        /** 按压 / 松开挂件时播放音效。 */
        @SerializedName("avatar_sound") public boolean avatarSound = true;

        /** 气泡里显示哪些条目，可选 cost / budget / peak / model / cache / balance。 */
        @SerializedName("avatar_bubble_items") public List<String> avatarBubbleItems =
                List.of("cost", "budget", "peak", "model", "cache", "balance");

        public ConfigData() {}

        public ConfigData(ConfigData src) {
            this.apiKey = src.apiKey; this.apiUrl = src.apiUrl; this.model = src.model;
            this.availableModels = src.availableModels != null ? List.copyOf(src.availableModels) : List.of();
            this.mode = src.mode;
            this.availableModes = src.availableModes != null ? List.copyOf(src.availableModes) : List.of();
            this.visionResolution = src.visionResolution; this.dailyTokens = src.dailyTokens;
            this.showReasoning = src.showReasoning; this.reasoningMaxChars = src.reasoningMaxChars;
            this.tokenDate = src.tokenDate;
            this.dailyCostYuan = src.dailyCostYuan; this.dailyBudgetYuan = src.dailyBudgetYuan;
            this.backupApiKeys = src.backupApiKeys != null ? List.copyOf(src.backupApiKeys) : List.of();
            this.historyTurns = src.historyTurns; this.deathRecap = src.deathRecap;
            this.discordWebhook = src.discordWebhook; this.persona = src.persona;
            this.availablePersonas = src.availablePersonas != null ? List.copyOf(src.availablePersonas) : List.of("default");
            this.recipeCache = src.recipeCache; this.streaming = src.streaming;
            this.chatPrefix = src.chatPrefix; this.translateLang = src.translateLang;
            // ---- 1.24 ----
            this.responseCache = src.responseCache; this.responseCacheSize = src.responseCacheSize;
            this.envContext = src.envContext; this.envContextMaxMods = src.envContextMaxMods;
            this.budgetWarnRatio = src.budgetWarnRatio;
            this.memories = src.memories != null ? List.copyOf(src.memories) : List.of();
            this.visionPreview = src.visionPreview;
            this.privacyMask = src.privacyMask;
            this.privacyMaskRegions = src.privacyMaskRegions != null
                    ? List.copyOf(src.privacyMaskRegions) : List.of();
            this.dangerAlert = src.dangerAlert; this.dangerHealth = src.dangerHealth;
            this.dangerRadius = src.dangerRadius; this.dangerCooldownSeconds = src.dangerCooldownSeconds;
            this.tts = src.tts; this.ttsMaxChars = src.ttsMaxChars;
            // ---- 1.25 ----
            this.apiProvider = src.apiProvider; this.retryMax = src.retryMax;
            this.showRequestCost = src.showRequestCost;
            this.avatarVisible = src.avatarVisible; this.avatarCorner = src.avatarCorner;
            this.avatarOffsetX = src.avatarOffsetX; this.avatarOffsetY = src.avatarOffsetY;
            this.avatarScale = src.avatarScale; this.avatarImage = src.avatarImage;
            this.avatarSound = src.avatarSound;
            this.avatarBubbleItems = src.avatarBubbleItems != null
                    ? List.copyOf(src.avatarBubbleItems) : List.of();
        }

        public boolean fillDefaults() {
            boolean changed = false;
            if (apiKey == null) { apiKey = ""; changed = true; }
            if (apiUrl == null || apiUrl.isBlank()) { apiUrl = DEFAULT_API_URL; changed = true; }
            if (model == null || model.isBlank()) { model = DEFAULT_MODEL; changed = true; }
            if (availableModels == null || availableModels.isEmpty()) { availableModels = DEFAULT_MODELS; changed = true; }
            if (mode == null || mode.isBlank()) { mode = "chat"; changed = true; }
            if (availableModes == null || availableModes.isEmpty()) { availableModes = Arrays.asList("chat", "vision"); changed = true; }
            if (visionResolution == null || visionResolution.isBlank()) { visionResolution = VisionResolution.DEFAULT; changed = true; }
            if (reasoningMaxChars < 0) { reasoningMaxChars = DEFAULT_REASONING_MAX_CHARS; changed = true; }
            if (tokenDate == null) { tokenDate = LocalDate.now(); changed = true; }
            if (backupApiKeys == null) { backupApiKeys = List.of(); changed = true; }
            if (availablePersonas == null || availablePersonas.isEmpty()) { availablePersonas = List.of("default"); changed = true; }
            if (persona == null || persona.isBlank()) { persona = "default"; changed = true; }
            if (dailyBudgetYuan < 0.0) { dailyBudgetYuan = 0.0; changed = true; }
            if (historyTurns < 0) { historyTurns = 0; changed = true; }
            if (discordWebhook == null) { discordWebhook = ""; changed = true; }
            if (chatPrefix == null || chatPrefix.isBlank()) { chatPrefix = "!ai"; changed = true; }
            if (translateLang == null || translateLang.isBlank()) { translateLang = "中文"; changed = true; }
            // ---- 1.24 ----
            if (responseCacheSize < 0) { responseCacheSize = 64; changed = true; }
            if (envContextMaxMods < 0) { envContextMaxMods = 60; changed = true; }
            if (budgetWarnRatio < 0.0) { budgetWarnRatio = 0.8; changed = true; }
            if (memories == null) { memories = List.of(); changed = true; }
            if (privacyMaskRegions == null) {
                privacyMaskRegions = List.of("0.0,0.70,0.52,1.0"); changed = true;
            }
            if (dangerHealth < 1) { dangerHealth = 6; changed = true; }
            if (dangerRadius <= 0.0) { dangerRadius = 12.0; changed = true; }
            if (dangerCooldownSeconds < 0) { dangerCooldownSeconds = 60; changed = true; }
            if (ttsMaxChars < 0) { ttsMaxChars = 220; changed = true; }
            // ---- 1.25 ----
            if (apiProvider == null || apiProvider.isBlank()) { apiProvider = "auto"; changed = true; }
            if (retryMax < 0) { retryMax = 2; changed = true; }
            if (avatarCorner == null || avatarCorner.isBlank()) { avatarCorner = "bottom_left"; changed = true; }
            if (avatarScale <= 0.0) { avatarScale = 1.0; changed = true; }
            if (avatarImage == null) { avatarImage = ""; changed = true; }
            if (avatarBubbleItems == null || avatarBubbleItems.isEmpty()) {
                avatarBubbleItems = List.of("cost", "budget", "peak", "model", "cache", "balance");
                changed = true;
            }
            return changed;
        }
    }
}