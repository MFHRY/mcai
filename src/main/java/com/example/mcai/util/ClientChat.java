package com.example.mcai.util;

import com.example.mcai.ConfigManager;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 往本地聊天栏发消息的小工具（不经过服务器，不会产生额外网络流量）。
 *
 * <p>ChatHandler 内部已经有一份私有实现，这里单独抽出来是给新增模块共用的，
 * 避免为了复用去改动已经稳定工作的 ChatHandler。
 */
public final class ClientChat {

    private ClientChat() {}

    /** 在主线程上把消息塞进聊天栏；从任意线程调用都安全。 */
    public static void send(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            // 客户端还没起来（理论上不该发生），静默跳过而不是抛 NPE
            return;
        }
        client.execute(() -> {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(text);
            }
        });
    }

    public static void sendLiteral(String text) {
        send(Text.literal(text));
    }

    /**
     * 按配置把模型的思维链（{@code reasoning_content}）发到聊天栏。聊天和截图识别共用。
     *
     * <p>先输出思考、后输出答案，这样答案始终落在聊天栏最底部（最靠近输入框、最容易看到）。
     * 关掉显示或模型没有返回思考内容时什么也不做。
     */
    public static void sendReasoning(JsonObject responseRoot) {
        ConfigManager.ConfigData config = ConfigManager.getInstance().get();
        if (config == null || !config.showReasoning) {
            return;
        }
        String text = ReasoningFormatter.format(
                AiResponseParser.extractReasoning(responseRoot), config.reasoningMaxChars);
        if (text != null) {
            sendLiteral(text);
        }
    }
}
