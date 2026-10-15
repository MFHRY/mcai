package com.example.mcai.util;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 统一的 HTTP 出口：聊天链路和截图链路都走这里。
 *
 * <p>以前两处各自建 {@code HttpClient}、各自拼请求、各自判断状态码，于是
 * "加自动重试"要改两遍、"加厂商适配"要改两遍，早晚会跑偏。现在协议差异在
 * {@link ApiProvider}，传输层（超时、重试、取消）在这里。
 *
 * <p><b>取消与重试是怎么共存的：</b>用 {@code sendAsync} 拿到 future 交给
 * {@link RequestController} 登记，然后在工作线程上 {@code get()} 阻塞取结果。
 * 这样既保住了 1.24 "取消会真正中断连接"的行为（{@code future.cancel(true)} 会让
 * 底层交换取消，阻塞的 {@code get()} 抛 {@link CancellationException}），
 * 又能顺序地做重试——不需要把重试写成回调套回调。
 *
 * <p><b>重试边界：</b>只重试网络异常和 408/425/429/5xx。4xx（除了这几个）是请求本身有问题，
 * 重试一万次也一样，直接报错更好。流式请求只在"还没读到任何内容"时重试，
 * 一旦开始往 Action Bar 推字就绝不重试——否则玩家会看到半截回答重复一遍。
 */
public final class ApiClient {

    private ApiClient() {}

    /** 连接超时；读超时由每次调用的 {@link Options#timeout()} 控制。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 一次调用的传输参数。
     *
     * @param apiUrl   配置里的 {@code api_url}
     * @param apiKey   这一轮选中的 key
     * @param retryMax 最多重试几次
     * @param timeout  单次尝试的读超时
     */
    public record Options(String apiUrl, String apiKey, int retryMax, Duration timeout) {

        /** 从配置里取重试次数，顺手夹到合理范围，免得配成 999 把玩家的请求拖死。 */
        public static Options of(String apiUrl, String apiKey, int retryMax, Duration timeout) {
            int capped = Math.max(0, Math.min(5, retryMax));
            return new Options(apiUrl, apiKey, capped, timeout);
        }
    }

    /** 一次调用的结果。usage 是各厂商字段归一化之后的 OpenAI 名字。 */
    public record StreamResult(String content, String reasoning, JsonObject usage) {}

    /**
     * 调用失败。
     *
     * <p>{@code status} 的含义：{@code > 0} = 服务端返回的 HTTP 码；
     * {@code -1} = 网络层失败（连不上 / 超时 / 被中断）；{@code 0} = 请求还没发出去就构造失败
     * （多半是玩家把 api_url 填成了非法地址）。
     */
    public static final class ApiException extends Exception {
        public final int status;
        public final boolean cancelled;

        public ApiException(int status, String detail, boolean cancelled) {
            super(detail);
            this.status = status;
            this.cancelled = cancelled;
        }
    }

    // ------------------------------------------------------------ 非流式

    /** 发一次非流式请求，返回<b>归一化之后</b>的响应。 */
    public static JsonObject send(ApiProvider provider, Options options, ApiRequest request,
                                  BooleanSupplier alive) throws ApiException {
        int attempt = 0;
        while (true) {
            ensureAlive(alive);
            HttpRequest httpRequest = build(provider, options, request);
            try {
                CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(
                        httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                RequestController.track(future);
                HttpResponse<String> response =
                        future.get(options.timeout().toMillis(), TimeUnit.MILLISECONDS);

                int status = response.statusCode();
                if (status == 200) {
                    return provider.normalizeResponse(AiResponseParser.parseObject(response.body()));
                }
                if (attempt >= options.retryMax() || !retryable(status)) {
                    throw new ApiException(status, HttpErrorCatalog.describe(status), false);
                }
            } catch (CancellationException e) {
                throw new ApiException(-1, "cancelled", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "interrupted", true);
            } catch (TimeoutException e) {
                if (attempt >= options.retryMax()) {
                    throw new ApiException(-1, "timeout", false);
                }
            } catch (ExecutionException e) {
                if (attempt >= options.retryMax()) {
                    throw new ApiException(-1, rootMessage(e), false);
                }
            }
            attempt++;
            backoff(attempt, alive);
        }
    }

    // ------------------------------------------------------------ 流式

    /**
     * 发一次流式请求。每收到一段正文就回调 {@code onDelta}（用于实时刷新 Action Bar）。
     *
     * <p>返回的是<b>累积后</b>的完整正文，调用方不需要自己拼。
     */
    public static StreamResult stream(ApiProvider provider, Options options, ApiRequest request,
                                      BooleanSupplier alive, Consumer<String> onDelta)
            throws ApiException {
        int attempt = 0;
        while (true) {
            ensureAlive(alive);
            HttpRequest httpRequest = build(provider, options, request);
            try {
                CompletableFuture<HttpResponse<Stream<String>>> future = HTTP.sendAsync(
                        httpRequest, HttpResponse.BodyHandlers.ofLines());
                RequestController.track(future);
                HttpResponse<Stream<String>> response =
                        future.get(options.timeout().toMillis(), TimeUnit.MILLISECONDS);

                int status = response.statusCode();
                if (status == 200) {
                    // 已经拿到 200，从这里开始不再重试：内容可能已经推到界面上了
                    return readStream(provider, response.body(), alive, onDelta);
                }

                // 错误响应是普通 JSON 而不是 SSE，读掉它免得连接挂起
                try (Stream<String> body = response.body()) {
                    body.forEach(line -> { /* 丢弃 */ });
                }
                if (attempt >= options.retryMax() || !retryable(status)) {
                    throw new ApiException(status, HttpErrorCatalog.describe(status), false);
                }
            } catch (CancellationException e) {
                throw new ApiException(-1, "cancelled", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(-1, "interrupted", true);
            } catch (TimeoutException e) {
                if (attempt >= options.retryMax()) {
                    throw new ApiException(-1, "timeout", false);
                }
            } catch (ExecutionException e) {
                if (attempt >= options.retryMax()) {
                    throw new ApiException(-1, rootMessage(e), false);
                }
            }
            attempt++;
            backoff(attempt, alive);
        }
    }

    /**
     * 读 SSE 流。读取过程中的任何异常都<b>不再上抛</b>，而是把已经收到的部分返回——
     * 半截回答总比一句"网络错误"有用，而且能避免重试造成的重复显示。
     */
    private static StreamResult readStream(ApiProvider provider, Stream<String> body,
                                           BooleanSupplier alive, Consumer<String> onDelta) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JsonObject usage = new JsonObject();

        try (Stream<String> lines = body) {
            for (String line : (Iterable<String>) lines::iterator) {
                // 玩家取消了 → 立刻停止读取，不再把后续分片推到 Action Bar
                if (!alive.getAsBoolean()) {
                    break;
                }
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String payload = trimmed.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (payload.equals("[DONE]")) {
                    break;
                }
                JsonObject normalized = provider.normalizeDelta(AiResponseParser.parseObject(payload));
                if (normalized == null) {
                    continue;
                }
                String delta = AiResponseParser.extractDeltaContent(normalized);
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(content.toString());
                    }
                }
                // 思维链也是分片到达的，先攒起来，收完再按和非流式一样的规则显示
                String reasonDelta = AiResponseParser.extractDeltaReasoning(normalized);
                if (reasonDelta != null && !reasonDelta.isEmpty()) {
                    reasoning.append(reasonDelta);
                }
                // 各家的 usage 可能分散在不同分片里（Claude 的输入在 message_start、
                // 输出在 message_delta），所以按字段逐个合并，而不是只留最后一个分片
                if (normalized.has("usage") && normalized.get("usage").isJsonObject()) {
                    for (Map.Entry<String, com.google.gson.JsonElement> entry
                            : normalized.getAsJsonObject("usage").entrySet()) {
                        usage.add(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Throwable ignored) {
            // 见方法注释：读取中断时保留已收到的内容
        }

        return new StreamResult(content.toString(), reasoning.toString(),
                usage.isEmpty() ? null : usage);
    }

    // ------------------------------------------------------------ 内部

    private static HttpRequest build(ApiProvider provider, Options options, ApiRequest request)
            throws ApiException {
        String endpoint = provider.endpoint(options.apiUrl(), request.model(), request.stream());
        String body = provider.buildBody(request);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(options.timeout())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Accept", request.stream() ? "text/event-stream" : "application/json");
            provider.applyAuth(builder, options.apiKey());
            return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            // 地址非法：这不是服务端的问题，重试也没用
            throw new ApiException(0, "bad-url", false);
        }
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504 || status == 522 || status == 524;
    }

    /** 指数退避：400ms、800ms、1600ms…最多 3 秒，避免把限流中的服务打得更惨。 */
    private static void backoff(int attempt, BooleanSupplier alive) {
        long millis = Math.min(3000L, 400L * (1L << Math.max(0, attempt - 1)));
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (!alive.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void ensureAlive(BooleanSupplier alive) throws ApiException {
        if (alive != null && !alive.getAsBoolean()) {
            throw new ApiException(-1, "cancelled", true);
        }
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
