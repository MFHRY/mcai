package com.example.mcai.client;

import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

/**
 * 物品栏一键分析（1.25 新增）：把背包内容整理成清单，连同玩家自己的问题一起发给 AI。
 *
 * <p><b>为什么只发物品名和数量，不发截图：</b>一段文字清单大约 60~120 token，
 * 而同样信息的背包截图要几千个视觉 token。这里能省下来的钱是实打实的十倍级别。
 *
 * <p>顺序沿用 {@link PlayerInventory} 的原生槽位顺序（主背包 → 护甲 → 副手），
 * 这样 AI 看到的排列和你按 E 打开时看到的是一致的，回答里说"第 9 格"才对得上。
 */
public final class InventoryQuery {

    private InventoryQuery() {}

    /** 由 {@code /ai inv [问题]} 调用；{@code extraQuestion} 为 null 时用默认问题。 */
    public static void trigger(String extraQuestion) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        StringBuilder items = new StringBuilder();
        int kinds = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            kinds++;
            items.append("- ").append(stack.getName().getString())
                    .append(" x").append(stack.getCount()).append('\n');
        }

        if (kinds == 0) {
            ClientChat.sendLiteral(Lang.tr("mcai.inv.empty"));
            return;
        }

        String ask = (extraQuestion == null || extraQuestion.isBlank())
                ? Lang.tr("mcai.inv.default_question")
                : extraQuestion.trim();
        // 上下文里只记一个短标签。原始"问题"是一整份背包清单，原样记进去会让
        // 之后每一轮提问都重新带上它——token 会莫名其妙变多，而玩家看不出原因。
        ChatHandler.ask(Lang.tr("mcai.inv.question", items.toString(), ask),
                Lang.tr("mcai.inv.history_note", ask), null);
    }
}
