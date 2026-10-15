package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import com.example.mcai.McaiMod;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 用操作系统自带的语音合成朗读文本（1.24 新增，无障碍功能）。
 *
 * <p>Java 本身没有 TTS，所以这里调用系统命令：
 * <ul>
 *   <li>Windows —— PowerShell 的 {@code System.Speech.Synthesis.SpeechSynthesizer}</li>
 *   <li>macOS —— 内置的 {@code say}</li>
 *   <li>Linux —— {@code spd-say}（speech-dispatcher）</li>
 * </ul>
 * 取不到对应命令时只记一条日志，不影响模组其它功能——TTS 是纯粹的附加能力。
 *
 * <p><b>注入安全：</b>朗读内容来自模型输出，会包含引号、换行、各种符号。
 * Windows 分支把文本通过 <b>stdin</b> 交给 PowerShell，命令行里只有一段常量脚本，
 * 因此不存在把模型输出当成 PowerShell 代码执行的可能，也避开了
 * "PowerShell + Base64" 这类会被杀软和平台扫描器盯上的混淆特征。
 * macOS / Linux 分支用 {@link ProcessBuilder} 的<b>参数列表</b>形式（不经过 shell），同样安全。
 */
public final class Speech {

    private Speech() {}

    private static final ExecutorService SPEAKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcai-tts");
        thread.setDaemon(true);
        return thread;
    });

    /** 单次朗读最长等待时间，超时就杀掉进程，避免卡住整条朗读队列。 */
    private static final long MAX_WAIT_SECONDS = 90L;

    /** 配置开启了 TTS 时，异步朗读这段文本。 */
    public static void speakIfEnabled(String text) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.tts) {
            return;
        }
        int limit = config.ttsMaxChars <= 0 ? 220 : config.ttsMaxChars;
        speakAsync(truncate(text, limit));
    }

    /** 无条件异步朗读（{@code /ai speak} 用，绕过开关）。 */
    public static void speakAsync(String text) {
        final String clean = sanitize(text);
        if (clean.isBlank()) {
            return;
        }
        SPEAKER.execute(() -> {
            try {
                speakBlocking(clean);
            } catch (Throwable t) {
                // 没装语音后端 / 命令不存在 —— 记一条就够，不要刷屏
                McaiMod.LOGGER.warn("mcAI TTS unavailable: {}", String.valueOf(t));
            }
        });
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    /** 去掉 Minecraft 的 § 颜色代码和控制字符，否则某些语音后端会念出乱码。 */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("§.", " ");
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        s = s.replace('\n', ' ').replace('\t', ' ');
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * Windows 上的朗读脚本。
     *
     * <p><b>整条命令是常量字面量，不含任何运行时数据</b>——待朗读的文本从 stdin 读入。
     * 这样既没有注入面，也不会落进"PowerShell + Base64"那类混淆特征里。
     */
    private static final String WINDOWS_SPEAK_SCRIPT =
            "Add-Type -AssemblyName System.Speech;"
                    + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    + "$t=[Console]::In.ReadToEnd();"
                    + "if($t.Trim().Length -gt 0){$s.Speak($t)}";

    private static void speakBlocking(String text) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder builder;

        if (os.contains("win")) {
            // 待朗读的文本走 **stdin**，命令里一个来自模型的字符都没有。
            //
            // 这里以前是把文本 Base64 之后拼进 -Command 字符串。那样确实也防注入
            // （脚本里只会出现 A-Za-z0-9+/=），但**"PowerShell + Base64"本身就是
            // 恶意软件的典型特征**，杀软和平台扫描器都会盯上它——对一个开源模组来说，
            // 这个观感代价远大于收益。改成 stdin 之后：
            //   · 防注入更强：文本根本不进命令行，连转义都不需要；
            //   · 命令是常量字面量，人类和扫描器都能一眼看明白它在干什么。
            builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Command", WINDOWS_SPEAK_SCRIPT);
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("say", text);
        } else {
            builder = new ProcessBuilder("spd-say", "--wait", text);
        }

        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = builder.start();

        if (os.contains("win")) {
            try (java.io.OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // 系统缺少语音组件时进程会提前退出，写管道随之失败。
                // 这是"念不出来"，不是错误，不该影响游戏里的任何东西。
            }
        }

        if (!process.waitFor(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }
}
