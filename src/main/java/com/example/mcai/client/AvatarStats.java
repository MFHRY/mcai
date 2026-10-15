package com.example.mcai.client;

import com.example.mcai.ConfigManager;
import com.example.mcai.util.ApiClient;
import com.example.mcai.util.Lang;
import com.example.mcai.util.LocalProvider;
import com.example.mcai.util.ModelCatalog;
import com.example.mcai.util.PersonaCatalog;
import com.example.mcai.util.PricingCatalog;
import com.example.mcai.util.ResponseCache;
import com.example.mcai.util.TokenStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「鲸鱼娘」挂件的气泡数据（1.25 新增）。
 *
 * <p>渲染和交互在 {@link AvatarWidget}，<b>算什么、显示什么在这里</b>。分开的理由：
 * 渲染代码必须每帧跑而这里只在"打开气泡"时算一次，混在一起很容易不小心把网络请求
 * 放进渲染循环里。
 *
 * <p>{@link #lines()} 只在气泡被打开时调用，所以余额查询也是"你点了才查"，
 * 不会在后台偷偷轮询。
 */
public final class AvatarStats {

    private AvatarStats() {}

    /** 余额多久之内不重复查询；手动刷新（重新打开气泡）不会比这更频繁。 */
    private static final long BALANCE_TTL_MILLIS = 60_000L;

    private static final ExecutorService BALANCE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcai-balance-worker");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile Double balance = null;
    private static volatile long balanceFetchedAt = 0L;
    private static volatile boolean balanceLoading = false;

    /**
     * 气泡里要显示的每一行（已带 § 颜色码，不含换行）。
     *
     * <p>显示哪几项由配置 {@code avatar_bubble_items} 决定；一项都没有时给一行占位文字，
     * 免得气泡变成一个空白方块让人以为坏了。
     */
    public static List<String> lines() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return List.of();
        }
        List<String> wanted = config.avatarBubbleItems == null ? List.of() : config.avatarBubbleItems;
        List<String> out = new ArrayList<>();

        if (wanted.contains("cost")) {
            out.add(Lang.tr("mcai.avatar.line_cost",
                    formatMoney(TokenStats.todayCost()), TokenStats.todayTotal()));
        }

        if (wanted.contains("budget")) {
            if (config.dailyBudgetYuan > 0.0) {
                double spent = TokenStats.todayCost();
                double remain = Math.max(0.0, config.dailyBudgetYuan - spent);
                int percent = (int) Math.round(spent / config.dailyBudgetYuan * 100.0);
                out.add(Lang.tr("mcai.avatar.line_budget", formatMoney(remain), percent));
            } else {
                out.add(Lang.tr("mcai.avatar.line_budget_off"));
            }
        }

        if (wanted.contains("peak")) {
            boolean peak = PricingCatalog.isPeakHour();
            out.add(Lang.tr(peak ? "mcai.avatar.line_peak_on" : "mcai.avatar.line_peak_off",
                    formatDuration(PricingCatalog.untilTransition())));
        }

        if (wanted.contains("model")) {
            out.add(Lang.tr("mcai.avatar.line_model",
                    ModelCatalog.describe(config.model),
                    PersonaCatalog.describe(config.persona)));
        }

        if (wanted.contains("cache")) {
            out.add(Lang.tr("mcai.avatar.line_cache", ResponseCache.size()));
        }

        if (wanted.contains("balance")) {
            if (!supportsBalance(config)) {
                out.add(Lang.tr("mcai.avatar.line_balance_na"));
            } else {
                refreshBalance(config);
                Double value = balance;
                if (balanceLoading && value == null) {
                    out.add(Lang.tr("mcai.avatar.line_balance_loading"));
                } else if (value == null) {
                    out.add(Lang.tr("mcai.avatar.line_balance_unknown"));
                } else {
                    out.add(Lang.tr("mcai.avatar.line_balance", formatMoney(value)));
                }
            }
        }

        if (out.isEmpty()) {
            out.add(Lang.tr("mcai.avatar.line_none"));
        }
        return out;
    }

    /** 供界面显示"当前余额"用；没查到返回 null。 */
    public static Double balance() {
        return balance;
    }

    /** 供界面显示加载状态用。 */
    public static boolean balanceLoading() {
        return balanceLoading;
    }

    // ------------------------------------------------------------ 余额查询

    /**
     * 只有 DeepSeek 官方提供"用 API key 查余额"的接口，别的厂商没有，
     * 所以这里必须判断<b>支持不支持</b>，而不是发一个注定 404 的请求然后报错——
     * 那会让玩家以为是自己 key 坏了。
     */
    private static boolean supportsBalance(ConfigManager.ConfigData config) {
        String url = config.apiUrl == null ? "" : config.apiUrl.toLowerCase(java.util.Locale.ROOT);
        return url.contains("deepseek");
    }

    /** 余额接口在 API 根域名上，不在 {@code /v1} 下面。 */
    private static String balanceEndpoint(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int v1beta = base.indexOf("/v1beta");
        if (v1beta >= 0) {
            base = base.substring(0, v1beta);
        }
        int v1 = base.indexOf("/v1");
        if (v1 >= 0) {
            base = base.substring(0, v1);
        }
        return base + "/user/balance";
    }

    private static void refreshBalance(ConfigManager.ConfigData config) {
        if (balanceLoading) {
            return;
        }
        long now = System.currentTimeMillis();
        // 60 秒内不重复查：余额不会一秒一变，而每次开气泡都打一次接口是浪费
        if (balance != null && now - balanceFetchedAt < BALANCE_TTL_MILLIS) {
            return;
        }
        final String key = LocalProvider.requiresApiKey(config.apiUrl, config.apiKey)
                ? com.example.mcai.util.ApiKeyManager.pick(config.apiKey, config.backupApiKeys).key()
                : LocalProvider.effectiveApiKey(config.apiUrl, config.apiKey);
        final String endpoint = balanceEndpoint(config.apiUrl);

        balanceLoading = true;
        BALANCE_EXECUTOR.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .GET()
                        .build();
                HttpResponse<String> response =
                        ApiClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    Double parsed = parseBalance(response.body());
                    if (parsed != null) {
                        balance = parsed;
                        balanceFetchedAt = System.currentTimeMillis();
                    }
                }
            } catch (Throwable ignored) {
                // 查不到就保持 null（界面显示"未知"），不要因为一个附加信息去打扰玩家
            } finally {
                balanceLoading = false;
            }
        });
    }

    /**
     * 从 DeepSeek 的余额响应里取人民币总额。
     *
     * <p>响应形如
     * {@code {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00"}]}}。
     * 优先取 CNY 那一项；只有别的币种时取第一项，总比显示"未知"强。
     */
    private static Double parseBalance(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("balance_infos") || !root.get("balance_infos").isJsonArray()) {
                return null;
            }
            JsonArray infos = root.getAsJsonArray("balance_infos");
            JsonObject fallback = null;
            for (JsonElement element : infos) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject info = element.getAsJsonObject();
                String currency = info.has("currency") && !info.get("currency").isJsonNull()
                        ? info.get("currency").getAsString() : "";
                if ("CNY".equalsIgnoreCase(currency)) {
                    return readBalance(info);
                }
                if (fallback == null) {
                    fallback = info;
                }
            }
            return fallback == null ? null : readBalance(fallback);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double readBalance(JsonObject info) {
        if (!info.has("total_balance") || info.get("total_balance").isJsonNull()) {
            return null;
        }
        // 这个字段在官方接口里是字符串而不是数字，两种都兼容一下
        String raw = info.get("total_balance").getAsString();
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 格式化

    private static String formatMoney(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Math.max(0.0, value));
    }

    /** 把倒计时说成"1小时23分"这种中文口语化的形式。 */
    private static String formatDuration(Duration duration) {
        long totalMinutes = Math.max(0L, duration.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours <= 0) {
            return minutes + "min";
        }
        return hours + "h " + minutes + "min";
    }
}
