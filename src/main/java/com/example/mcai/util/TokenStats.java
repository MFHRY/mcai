package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import com.google.gson.JsonObject;
import java.time.LocalDate;

/**
 * Token 统计（含跨天清零 + 使用明细）。聊天链路和截图识别链路共用这一份，
 * 避免两处逻辑不一致。
 *
 * <p>规则：每次请求成功后累加 {@code daily_tokens}；如果 {@code token_date} 不是今天，
 * 先把计数清零、日期改成今天，再累加。写盘走 {@link ConfigManager#save()}，是异步的。
 *
 * <p>同时在 {@link UsageLog} 里追加一条「什么时候 / 哪个模型 / 哪条链路 / 多少 token」的明细，
 * 供 {@code /ai token} 展示。
 */
public final class TokenStats {

    private TokenStats() {}

    /**
     * 从一次成功的接口响应里取 total_tokens 并累计，同时写一条使用明细。
     *
     * @param source 调用来源，{@code "chat"} 或 {@code "vision"}，仅用于明细展示
     */
    public static void recordFrom(JsonObject responseRoot, String source) {
        int total = AiResponseParser.extractTotalTokens(responseRoot);
        if (total > 0) {
            add(total);
        }

        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String model = config == null ? "?" : config.model;

        // 估算人民币花费:需要 prompt/output token 数 + 单价。缺一不可就跳过,不要瞎猜。
        double cost = 0.0;
        int prompt = AiResponseParser.extractPromptTokens(responseRoot);
        int completion = AiResponseParser.extractCompletionTokens(responseRoot);
        if (prompt >= 0 && completion >= 0) {
            PricingCatalog.Quote price = PricingCatalog.find(model);
            if (price != null) {
                cost = price.estimate(prompt, completion);
                addCost(cost);
            }
        }

        // 明细里连花费一起记下来，供 /ai token、/ai cost 展示
        UsageLog.appendAsync(model, source, total, cost);
    }

    /** 累加指定 token 数，必要时先跨天清零。 */
    public static void add(int tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            ConfigManager manager = ConfigManager.getInstance();
            ConfigManager.ConfigData config = manager.get();
            if (config == null) {
                return;
            }
            ensureDayReset();
            config.dailyTokens += tokens;
            manager.save();
        } catch (Exception e) {
            // 统计失败不能影响正常聊天 / 识别
        }
    }

    /**
     * 今日已消耗的 token。若记录的日期不是今天，说明还没发生今天的第一次请求，
     * 对外应显示 0（真正的清零会在下一次 {@link #add} 时落盘）。
     */
    public static int todayTotal() {
        ensureDayReset();
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return 0;
        }
        return Math.max(0, config.dailyTokens);
    }

    /** 累加人民币花费（跨天清零逻辑与 token 一致）。 */
    public static void addCost(double yuan) {
        if (yuan <= 0) {
            return;
        }
        try {
            ConfigManager manager = ConfigManager.getInstance();
            ConfigManager.ConfigData config = manager.get();
            if (config == null) {
                return;
            }
            ensureDayReset();
            config.dailyCostYuan += yuan;
            manager.save();
        } catch (Exception e) {
            // 统计失败不影响功能
        }
    }

    /** 今日已累计的人民币花费(元)。 */
    public static double todayCost() {
        ensureDayReset();
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null) {
            return 0.0;
        }
        return config.dailyCostYuan;
    }

    /**
     * 今日预算是否已超支。预算 <=0 表示未启用预算，永不视为超支。
     */
    public static boolean isOverDailyBudget() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || config.dailyBudgetYuan <= 0) {
            return false;
        }
        ensureDayReset();
        return config.dailyCostYuan >= config.dailyBudgetYuan;
    }

    /** 把「token 与费用」一起做跨天清零。 */
    private static void ensureDayReset() {
        try {
            ConfigManager manager = ConfigManager.getInstance();
            ConfigManager.ConfigData config = manager.get();
            if (config == null) {
                return;
            }
            LocalDate today = LocalDate.now();
            if (config.tokenDate == null || !config.tokenDate.isEqual(today)) {
                config.dailyTokens = 0;
                config.dailyCostYuan = 0.0;
                config.tokenDate = today;
                manager.save();
            }
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
