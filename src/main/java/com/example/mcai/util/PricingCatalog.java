package com.example.mcai.util;

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoField;

/**
 * 模型单价表：把某个模型一次调用读到的 token 数换算成估算的人民币花费。
 *
 * <p><b>价格来源：DeepSeek 官方定价页</b>
 * （https://api-docs.deepseek.com/zh-cn/quick_start/pricing/，2026-10 抓取），
 * 单位为「每 1M tokens / 人民币」：
 *
 * <pre>
 * 模型               输入(缓存未命中)      输出
 *               空闲      高峰        空闲    高峰
 * deepseek-flash   1.0      2.0        4.0    8.0
 * deepseek-v4-pro  4.5      9.0       13.5   27.0
 * </pre>
 *
 * <p>高峰/空闲按「北京时间」判定：周一至周五（不含中国法定节假日）
 * 9:00-12:00、14:00-18:00 为高峰，其余（周末及中国法定节假日全天）为空闲。
 * 中国法定节假日无法在纯本地代码里精确推算，本类按「周末一律空闲 + 工作日按时段」近似。
 * <b>这是估算，不是账单</b>——界面里会注明有约 1 分钱内的偏差。
 *
 * <p>缓存命中价格（便宜得多）需要读到 {@code usage.prompt_cache_hit_tokens} 才能精确，
 * 目前按最保守的「缓存未命中」单价计算，即只可能高估、不会低估。
 *
 * <p>价格表只在 {@link #find(String)} 里给已知模型返回数据（返回 {@link Quote}，可为 null），
 * 其它模型用 null 标记「未配置单价」，界面显示"价格未知"而不是瞎猜。
 */
public final class PricingCatalog {

    private PricingCatalog() {}

    /** 一次调用的单价快照。 */
    public record Quote(double inputPerMillion, double outputPerMillion) {
        /** 估算一次调用的人民币花费；token 数为负或 0 返回 0。 */
        public double estimate(int inputTokens, int outputTokens) {
            if (inputTokens <= 0 && outputTokens <= 0) {
                return 0.0;
            }
            return (inputTokens / 1_000_000.0 * inputPerMillion)
                    + (outputTokens / 1_000_000.0 * outputPerMillion);
        }
    }

    /** 查询某个模型当前时段的单价。查不到返回 null。 */
    public static Quote find(String modelId) {
        Price price = priceFor(modelId);
        if (price == null) {
            return null;
        }
        boolean peak = isPeakHour();
        double in = (peak ? price.peakInput : price.offInput);
        double out = (peak ? price.peakOutput : price.offOutput);
        return new Quote(in, out);
    }

    private record Price(double offInput, double peakInput, double offOutput, double peakOutput) {}

    private static Price priceFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        String m = modelId.trim().toLowerCase(java.util.Locale.ROOT);
        if (m.equals("deepseek-flash") || m.equals("deepseek-v4-flash")) {
            return new Price(1.0, 2.0, 4.0, 8.0);
        }
        if (m.equals("deepseek-v4-pro")) {
            return new Price(4.5, 9.0, 13.5, 27.0);
        }
        // 其余模型（gpt / claude / qwen / gemini / openai ...）本表不含 => 返回 null。
        return null;
    }

    /**
     * 距离下一次「峰 / 谷」切换还有多久（1.25 新增，给挂件的气泡用）。
     *
     * <p>切换点只有四个：工作日 9:00（谷→峰）、12:00（峰→谷）、14:00（谷→峰）、18:00（峰→谷）。
     * 周末整体是谷价，所以周五 18:00 之后的下一个切换点是<b>下周一 9:00</b>——
     * 这也是为什么这里必须整天跳过周六周日，而不是简单地"往后找下一个整点"。
     *
     * <p>返回值永远是正的；时区解析失败这种几乎不可能的情况按 1 小时估。
     */
    public static java.time.Duration untilTransition() {
        try {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            for (int dayOffset = 0; dayOffset <= 8; dayOffset++) {
                java.time.ZonedDateTime day = now.plusDays(dayOffset);
                int dow = day.get(ChronoField.DAY_OF_WEEK); // 1=Mon ... 7=Sun
                if (dow == 6 || dow == 7) {
                    continue; // 周末没有切换点
                }
                for (int hour : new int[]{9, 12, 14, 18}) {
                    java.time.ZonedDateTime candidate = day
                            .withHour(hour).withMinute(0).withSecond(0).withNano(0);
                    if (candidate.isAfter(now)) {
                        java.time.Duration duration = java.time.Duration.between(now, candidate);
                        return duration.isNegative() ? java.time.Duration.ZERO : duration;
                    }
                }
            }
        } catch (DateTimeException e) {
            // 落到下面的兜底
        }
        return java.time.Duration.ofHours(1);
    }

    /**
     * 当前北京时间是否高峰时段。实时取系统时钟并换算 UTC+8，避免依赖用户机器时区配置
     * 与地区设置不一致。
     */
    public static boolean isPeakHour() {
        try {
            java.time.ZonedDateTime now =
                    java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            int day = now.get(ChronoField.DAY_OF_WEEK); // 1=Mon ... 7=Sun
            if (day == 6 || day == 7) {
                return false; // 周六周日
            }
            int hour = now.getHour();
            // 周一至周五：9-12、14-18 高峰
            return (hour >= 9 && hour < 12) || (hour >= 14 && hour < 18);
        } catch (DateTimeException e) {
            // 时区解析失败这种几乎不可能发生时，按保守高峰计
            return true;
        }
    }
}