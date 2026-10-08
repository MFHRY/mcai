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
    private ScheduledFuture<?> pendingWrite = null;

    private ConfigManager() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
        configRef.set(new ConfigData());
        loadAsync();
    }

    public ConfigData get() { return configRef.get(); }

    public void update(Consumer<ConfigData> mutator) {
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
            configRef.set(loaded);
            if (needsWrite || !Files.exists(configPath)) {
                writeToDisk(loaded);
            }
        }, ioExecutor).exceptionally(ex -> {
            System.err.println("[mcAI] 配置加载失败: " + ex.getMessage());
            return null;
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
            System.err.println("[mcAI] 读取配置文件失败: " + e.getMessage());
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
            System.err.println("[mcAI] 写入配置文件失败: " + e.getMessage());
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

        public ConfigData() {}

        public ConfigData(ConfigData src) {
            this.apiKey = src.apiKey; this.apiUrl = src.apiUrl; this.model = src.model;
            this.availableModels = src.availableModels != null ? List.copyOf(src.availableModels) : List.of();
            this.mode = src.mode;
            this.availableModes = src.availableModes != null ? List.copyOf(src.availableModes) : List.of();
            this.visionResolution = src.visionResolution; this.dailyTokens = src.dailyTokens;
            this.showReasoning = src.showReasoning; this.reasoningMaxChars = src.reasoningMaxChars;
            this.tokenDate = src.tokenDate;
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
            return changed;
        }
    }
}