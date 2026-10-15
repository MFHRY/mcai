package com.example.mcai.util;

import java.util.List;

/**
 * 一次问答请求的<b>厂商无关</b>表示。
 *
 * <p>聊天链路和截图链路都先拼出这个对象，再由 {@link ApiProvider} 翻译成各家自己的 JSON。
 * 这样"多带一张图"或"多带一条历史"只需要改调用方一处，而不是在三套请求体里各改一遍。
 *
 * @param model          模型 id
 * @param system         系统提示词（Claude 走顶层 {@code system}，Gemini 走 {@code systemInstruction}）
 * @param history        多轮历史，每个元素是 {@code {用户, 助手}}
 * @param userText       本次提问
 * @param imagesBase64   附在本次提问上的图片（base64，<b>不带</b> data URI 前缀）
 * @param imageMimeType  图片的 MIME 类型。截图链路是 {@code image/jpeg}（省带宽），
 *                       其它地方默认 {@code image/png}。写错这一项有些服务端会直接拒收，
 *                       所以它是请求的一部分，不能在三家适配里各写死一个值。
 * @param stream         是否流式
 */
public record ApiRequest(String model,
                         String system,
                         List<String[]> history,
                         String userText,
                         List<String> imagesBase64,
                         String imageMimeType,
                         boolean stream) {

    /** 默认的图片类型；调用方没特别说明时用这个。 */
    public static final String DEFAULT_IMAGE_MIME = "image/png";

    /** 纯文本、不带图、非流式。 */
    public static ApiRequest text(String model, String system, String userText,
                                  List<String[]> history, boolean stream) {
        return new ApiRequest(model, system, history, userText, List.of(),
                DEFAULT_IMAGE_MIME, stream);
    }

    /** 带一张图的非流式请求。 */
    public static ApiRequest image(String model, String system, String userText,
                                   String base64, String mimeType) {
        return new ApiRequest(model, system, List.of(), userText, List.of(base64),
                mimeType == null || mimeType.isBlank() ? DEFAULT_IMAGE_MIME : mimeType, false);
    }

    /** 取图片类型，空值兜底成 PNG，免得拼出 {@code data:;base64,} 这种非法前缀。 */
    public String mime() {
        return (imageMimeType == null || imageMimeType.isBlank()) ? DEFAULT_IMAGE_MIME : imageMimeType;
    }
}
