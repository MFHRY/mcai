package com.example.mcai.client;

import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import com.example.mcai.util.RequestController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * 按 <b>ESC</b> 中止正在进行中的请求（1.24 新增）。
 *
 * <p><b>为什么不注册成普通快捷键：</b>Minecraft 会先处理 ESC（在游戏里打开暂停菜单），
 * 等轮到快捷键系统时它已经被消耗掉了，{@code KeyMapping.consumeClick()} 永远为 false。
 * 所以这里直接轮询按键状态，绕开快捷键系统。
 *
 * <p><b>为什么允许"暂停菜单刚被 ESC 打开"这一种情况：</b>
 * 轮询发生在 {@code END_CLIENT_TICK}，而 ESC 打开暂停菜单发生在更早的
 * {@code handleInputEvents}。也就是说我们检查时 {@code currentScreen} 已经是暂停菜单了。
 * 如果严格要求"必须没有界面"，这个功能就永远不会触发。于是这里的规则是：
 * <b>只在游戏内、或刚被 ESC 打开的暂停菜单上生效</b>；如果玩家本来就开着背包/箱子，
 * 那么 ESC 的语义是"关界面"，我们不去抢它。
 *
 * <p>副作用是按下 ESC 时暂停菜单也会打开。这是可接受的——{@code /ai cancel} 提供了
 * 一条不打开任何界面的等价路径。
 */
public final class RequestCancelKey {

    private RequestCancelKey() {}

    private static boolean escapeWasDown = false;

    /** 由 {@code McaiModClient} 的统一 tick 分发器调用。 */
    public static void tick(Minecraft client) {
        if (!RequestController.isBusy()) {
            // 没有请求在跑时完全不干预，ESC 保持原版行为
            escapeWasDown = false;
            return;
        }

        Screen screen = client.screen;
        boolean inGameLike = screen == null || screen instanceof PauseScreen;
        if (!inGameLike) {
            // 开着背包之类界面：ESC 是用来关界面的，不要抢
            escapeWasDown = false;
            return;
        }

        boolean down;
        try {
            down = InputConstants.isKeyDown(client.getWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE);
        } catch (Throwable t) {
            return;
        }

        // 只在"按下"的那一刻触发一次，按住不放不会反复取消
        if (down && !escapeWasDown) {
            if (RequestController.cancel()) {
                ClientChat.sendLiteral(Lang.tr("mcai.cmd.cancel_done"));
            }
        }
        escapeWasDown = down;
    }
}
