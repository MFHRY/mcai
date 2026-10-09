package com.example.mcai.util;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token 使用明细日志：记录「什么时候、用的哪个模型、走的哪条链路、花了多少 token」。
 *
 * <p>写到 {@code config/mcai-usage.csv}，CSV 格式方便直接用 Excel 打开：
 * <pre>
 * time,model,source,tokens
 * 2026-10-08 21:30:12,deepseek-flash,chat,3985
 * 2026-10-08 21:35:44,deepseek-flash,vision,666
 * </pre>
 *
 * <p>为什么不塞进 mcai.json：明细会无限增长，写进配置文件会让它越来越臃肿、
 * 而且每次保存都要重写整个文件。CSV 追加写更合适。
 *
 * <p>读写全部在这个类自己的守护线程上完成，绝不阻塞游戏主线程。
 */
public final class UsageLog {

    public static final String FILE_NAME = "mcai-usage.csv";

    private static final String HEADER = "time,model,source,tokens,cost";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter MONTH_DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-usage-io");
        thread.setDaemon(true);
        return thread;
    });

    private UsageLog() {}

    /** 一次调用的记录。 */
    public record Entry(String time, String model, String source, int tokens, double cost) {

        /** 今天的记录只显示时分秒；更早的补上月日，避免跨天后分不清。 */
        public String displayTime() {
            try {
                LocalDateTime stamp = LocalDateTime.parse(time, STAMP);
                if (stamp.toLocalDate().isEqual(LocalDate.now())) {
                    return stamp.format(TIME_ONLY);
                }
                return stamp.format(MONTH_DAY_TIME);
            } catch (Exception e) {
                return time;
            }
        }
    }

    /** 延迟获取路径：FabricLoader 在模组初始化期间才可用，不要在静态字段里取。 */
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static String displayPath() {
        return "config/" + FILE_NAME;
    }

    /** 追加一条记录（异步，立即返回）。 */
    public static void appendAsync(String model, String source, int tokens) {
        appendAsync(model, source, tokens, 0.0);
    }

    /** 追加一条记录（异步，立即返回）。 */
    public static void appendAsync(String model, String source, int tokens, double cost) {
        String line = LocalDateTime.now().format(STAMP)
                + "," + sanitize(model)
                + "," + sanitize(source)
                + "," + tokens
                + "," + formatCost(cost);

        IO.execute(() -> {
            try {
                Path path = file();
                Files.createDirectories(path.getParent());
                boolean fresh = !Files.exists(path);
                String payload = (fresh ? HEADER + System.lineSeparator() : "")
                        + line + System.lineSeparator();
                Files.writeString(path, payload, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("[mcAI] Failed to append usage log: " + e.getMessage());
            }
        });
    }

    /** 格式化 cost 成尽量紧凑的字符串（去掉尾随零）; 省到小数点后 2 位。 */
    private static String formatCost(double cost) {
        if (cost <= 0.0) {
            return "0";
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", cost);
        return s;
    }

    /** 读取最近 {@code count} 条记录（异步）。返回顺序为「从新到旧」。 */
    public static CompletableFuture<List<Entry>> readRecentAsync(int count) {
        return CompletableFuture.supplyAsync(() -> readRecent(count), IO);
    }

    /** 某一天汇总。 */
    public record DayTotal(String date, int tokens, double cost) {}

    /** 按天汇总最近 {@code days} 天（异步），返回顺序为「从旧到新」，没有记录的天补 0。 */
    public static CompletableFuture<List<DayTotal>> dailyTotalsAsync(int days) {
        return CompletableFuture.supplyAsync(() -> dailyTotals(days), IO);
    }

    private static List<DayTotal> dailyTotals(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        // 先把日期槽建好（含没有记录的天），这样柱状图不会缺列
        java.util.LinkedHashMap<LocalDate, int[]> tokens = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<LocalDate, Double> costs = new java.util.LinkedHashMap<>();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            tokens.put(d, new int[]{0});
            costs.put(d, 0.0);
        }

        try {
            Path path = file();
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    Entry entry = parse(line);
                    if (entry == null) {
                        continue;
                    }
                    LocalDate day = dayOf(entry.time());
                    if (day == null || !tokens.containsKey(day)) {
                        continue;
                    }
                    tokens.get(day)[0] += Math.max(0, entry.tokens());
                    costs.put(day, costs.get(day) + Math.max(0.0, entry.cost()));
                }
            }
        } catch (Exception e) {
            System.err.println("[mcAI] Failed to aggregate usage log: " + e.getMessage());
        }

        List<DayTotal> out = new java.util.ArrayList<>();
        for (LocalDate d : tokens.keySet()) {
            out.add(new DayTotal(d.toString(), tokens.get(d)[0], costs.get(d)));
        }
        return out;
    }

    /** 从 {@code yyyy-MM-dd HH:mm:ss} 里取日期部分。 */
    private static LocalDate dayOf(String time) {
        if (time == null || time.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(time.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Entry> readRecent(int count) {
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return Collections.emptyList();
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<Entry> entries = new ArrayList<>();
            // 从文件末尾往前取，得到的就是从新到旧
            for (int i = lines.size() - 1; i >= 0 && entries.size() < count; i--) {
                Entry entry = parse(lines.get(i));
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (Exception e) {
            System.err.println("[mcAI] Failed to read usage log: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank() || line.startsWith("time,")) {
            return null;
        }
        // 兼容老的 4 列格式:多出来/少掉的列都要防一下
        String[] parts = line.split(",", -1);
        if (parts.length < 4) {
            return null;
        }
        try {
            String time = parts[0].trim();
            String model = parts[1].trim();
            String source = parts[2].trim();
            int tokens = Integer.parseInt(parts[3].trim());
            double cost = (parts.length >= 5 && !parts[4].isBlank())
                    ? Double.parseDouble(parts[4].trim()) : 0.0;
            return new Entry(time, model, source, tokens, cost);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** CSV 里不能出现逗号和换行。 */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        return value.replace(',', '_').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
