package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import com.google.gson.JsonObject;
import java.time.LocalDate;

/**
 * Token 统计（含跨天清零 + 使用明细）。聊天链路和截图识别链路共用这一份，
 * 避免两处逻辑不一致。
 *
 * <p>规则：每次请求成功后累加 {@code daily_tokens}；如果 {@code token_date} 不是今天，
 * 先把计数清零、日期改成今天，再累加。
 *
 * <p>跨天清零与累加都包在 {@link ConfigManager#update} 里：那是对 {@code configRef}
 * 的 copy-on-write（复制副本 → 改副本 → CAS），而不是直接改动那个被多处共享读的快照。
 * 早期实现直接写 {@code config.dailyTokens += tokens}，会在聊天链路和截图识别链路并发时
 * 丢计数，也破坏了“快照不可变”的约定。
 *
 * <p><b>读方法全部是非破坏性的</b>：跨天了直接返回 0，而不是顺手把配置写盘一次
 * （写盘留给下一次真实累加时再做）。
 */
public final class TokenStats {

    private TokenStats() {}

    /**
     * 从一次成功的接口响应里取 token 并累计，同时写一条使用明细。
     *
     * @param source 调用来源，{@code "chat"} 或 {@code "vision"}，仅用于明细展示
     */
    public static void recordFrom(JsonObject responseRoot, String source) {
        int total = AiResponseParser.extractTotalTokens(responseRoot);
        int prompt = AiResponseParser.extractPromptTokens(responseRoot);
        int completion = AiResponseParser.extractCompletionTokens(responseRoot);

        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        String model = config == null ? "?" : config.model;

        // 估算人民币花费:需要 prompt/output token 数 + 单价。缺一不可就跳过,不要瞎猜。
        double cost = 0.0;
        if (prompt >= 0 && completion >= 0) {
            PricingCatalog.Quote price = PricingCatalog.find(model);
            if (price != null) {
                cost = price.estimate(prompt, completion);
            }
        }

        record(total, cost);

        // 明细里连花费一起记下来，供 /ai token、/ai cost 展示
        UsageLog.appendAsync(model, source, total, cost);
    }

    /** 一次调用同时累加 token 与花费（必要时先跨天清零）。二者都不大于 0 直接跳过。 */
    public static void record(int tokens, double cost) {
        if (tokens <= 0 && cost <= 0) {
            return;
        }
        try {
            ConfigManager.getInstance().update(config -> {
                // 跨天清零做在副本上，CAS 成功才算生效；失败会由 update() 重试整个 mutator
                if (isStale(config)) {
                    config.dailyTokens = 0;
                    config.dailyCostYuan = 0.0;
                    config.tokenDate = LocalDate.now();
                }
                if (tokens > 0) {
                    config.dailyTokens += tokens;
                }
                if (cost > 0) {
                    config.dailyCostYuan += cost;
                }
            });
        } catch (Exception e) {
            // 统计失败不能影响正常聊天 / 识别
        }
    }

    /** 累加指定 token 数（必要时先跨天清零）。 */
    public static void add(int tokens) {
        record(tokens, 0.0);
    }

    /** 累加人民币花费（跨天清零逻辑与 token 一致）。 */
    public static void addCost(double yuan) {
        record(0, yuan);
    }

    /** 今日已消耗的 token。记录日期不是今天时返回 0（真正的清零会在下一次累加时落盘）。 */
    public static int todayTotal() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || isStale(config)) {
            return 0;
        }
        return Math.max(0, config.dailyTokens);
    }

    /** 今日已累计的人民币花费(元)。 */
    public static double todayCost() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || isStale(config)) {
            return 0.0;
        }
        return Math.max(0.0, config.dailyCostYuan);
    }

    /**
     * 今日预算是否已超支。预算 <=0 表示未启用预算，永不视为超支。
     */
    public static boolean isOverDailyBudget() {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || config.dailyBudgetYuan <= 0) {
            return false;
        }
        return todayCost() >= config.dailyBudgetYuan;
    }

    /** 配置里的日期是否不是今天（即“刚到新的一天、还没发生第一次累加”）。 */
    private static boolean isStale(ConfigManager.ConfigData config) {
        return config.tokenDate == null || !config.tokenDate.isEqual(LocalDate.now());
    }
}