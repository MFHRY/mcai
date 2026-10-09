package com.example.mcai.util;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Discord webhook 播报（#15）。
 *
 * <p>往玩家自己填的 webhook 地址 POST 一条消息，可以带一张图。
 * <b>这个功能是纯出口</b>——不会调用任何 AI 接口，所以不烧 token。
 *
 * <p>Discord 要「文字 + 文件」时必须是 {@code multipart/form-data}：
 * {@code payload_json} 放 JSON 正文，{@code file} 放图片字节。
 * 这里手写 multipart，避免为了一个小功能引入额外的 HTTP 库。
 *
 * <p><b>隐私提醒：</b>webhook 是玩家自己配置的，默认空着（关闭）。
 * 一旦填了，死亡截图会被发到 Discord 服务器上，README 与界面都要写明。
 */
public final class DiscordWebhook {

    private DiscordWebhook() {}

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 只发文字。 */
    public static void sendTextAsync(String webhookUrl, String message) {
        sendAsync(webhookUrl, message, null, null);
    }

    /**
     * 发一条带图的播报（异步，立即返回，失败只打日志不影响游戏）。
     *
     * @param jpeg      图片字节；null 表示只发文字
     * @param filename  Discord 上显示的文件名
     */
    public static void sendAsync(String webhookUrl, String message,
                                 byte[] jpeg, String filename) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        // 复制一份，避免调用方复用缓冲区
        final byte[] imageCopy = jpeg == null ? null : jpeg.clone();
        final String name = (filename == null || filename.isBlank()) ? "image.jpg" : filename;

        Thread thread = new Thread(() -> {
            try {
                HttpRequest request = (imageCopy == null)
                        ? jsonRequest(webhookUrl, message)
                        : multipartRequest(webhookUrl, message, imageCopy, name);
                HttpResponse<String> response =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 300) {
                    System.err.println("[mcAI] Discord webhook returned " + response.statusCode());
                }
            } catch (Throwable t) {
                System.err.println("[mcAI] Discord webhook failed: " + t.getMessage());
            }
        }, "mcai-discord");
        thread.setDaemon(true);
        thread.start();
    }

    private static HttpRequest jsonRequest(String url, String message) {
        String body = "{\"content\":\"" + escapeJson(message) + "\"}";
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private static HttpRequest multipartRequest(String url, String message, byte[] jpeg, String filename)
            throws Exception {
        String boundary = "----mcai" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 1) payload_json
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"payload_json\"\r\n");
        write(out, "Content-Type: application/json; charset=UTF-8\r\n\r\n");
        write(out, "{\"content\":\"" + escapeJson(message) + "\"}\r\n");

        // 2) 图片文件
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n");
        write(out, "Content-Type: image/jpeg\r\n\r\n");
        out.write(jpeg);
        write(out, "\r\n");

        // 3) 结束
        write(out, "--" + boundary + "--\r\n");

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
    }

    private static void write(ByteArrayOutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 转义 JSON 字符串里的特殊字符（Discord 会原样显示）。 */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // 其它控制字符直接丢掉，避免破坏 JSON
                    if (c >= 0x20) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
