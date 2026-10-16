package com.example.mcai.util;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取玩家眼前的"书面文字"（#5 告示牌 / 成书翻译）。
 *
 * <p><b>为什么值得单独做一条链路：</b>告示牌和书里的文字本来就在内存里，
 * 直接读出来发给模型是<b>纯文本请求</b>（几百 token），而截图识别要把整张图
 * 编码成 base64 发过去（一千多 token 起步，还依赖模型真的认得出字）。
 * 所以这条路径又快又准又便宜。
 *
 * <p>两条来源：
 * <ol>
 *   <li><b>告示牌</b>：从准星射线命中的方块上取 {@code SignBlockEntity}，
 *       分别读正面/背面文字；</li>
 *   <li><b>成书</b>：读主手/副手物品的书写书内容（1.21.1 用数据组件）。</li>
 * </ol>
 * 两者都取不到时返回 null，由调用方给出提示。
 */
public final class TextReader {

    private TextReader() {}

    /** 读取结果。 */
    public record Found(String source, String text) {}

    /** 尝试读取眼前的告示牌，或手中的成书；都读不到返回 null。 */
    public static Found read() {
        Found sign = readSign();
        if (sign != null) {
            return sign;
        }
        return readHeldBook();
    }

    // ------------------------------------------------------------- 告示牌

    private static Found readSign() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        Level world = client.level;
        if (player == null || world == null) {
            return null;
        }

        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos pos = blockHit.getBlockPos();
        BlockEntity entity = world.getBlockEntity(pos);
        if (!(entity instanceof SignBlockEntity sign)) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        appendSignSide(sb, sign.getFrontText());
        appendSignSide(sb, sign.getBackText());

        String text = sb.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        return new Found(Lang.tr("mcai.read.source_sign"), text);
    }

    /** 把告示牌一面的非空行拼起来。 */
    private static void appendSignSide(StringBuilder sb, SignText side) {
        if (side == null) {
            return;
        }
        Component[] messages;
        try {
            messages = side.getMessages(false);
        } catch (Throwable t) {
            return;
        }
        for (Component message : messages) {
            if (message == null) {
                continue;
            }
            String line = message.getString();
            if (line != null && !line.isBlank()) {
                sb.append(line.trim()).append('\n');
            }
        }
    }

    // --------------------------------------------------------------- 成书

    /**
     * 读手中的书写书（主手优先，其次副手）。
     *
     * <p>1.21.1 的物品数据已经从 NBT 换成了数据组件，所以这里走
     * {@code DataComponentTypes.WRITTEN_BOOK_CONTENT}。取不到就返回 null，
     * 不影响告示牌那条路径。
     */
    private static Found readHeldBook() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return null;
        }
        Found main = bookOf(player.getMainHandItem());
        return main != null ? main : bookOf(player.getOffhandItem());
    }

    private static Found bookOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            // 1.20.1 还没有数据组件 —— 那是 1.20.5+ 才引入的体系。成书的内容此时
            // 仍然存在 NBT 里：title 是一个普通字符串，pages 是「JSON 文本」组成的
            // 字符串列表。所以这里不能照抄 1.21.1 的 DataComponentTypes 写法。
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains("pages", Tag.TAG_LIST)) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            String title = tag.getString("title");
            if (title != null && !title.isBlank()) {
                lines.add(title.trim());
            }
            ListTag pages = tag.getList("pages", Tag.TAG_STRING);
            for (int i = 0; i < pages.size(); i++) {
                String raw = pages.getString(i);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // 每一页是 JSON 形式的文本组件，要先反序列化再取纯文本
                Component pageText = Component.Serializer.fromJson(raw);
                String text = pageText == null ? null : pageText.getString();
                if (text != null && !text.isBlank()) {
                    lines.add(text.trim());
                }
            }
            String text = String.join("\n", lines).trim();
            return text.isEmpty() ? null : new Found(Lang.tr("mcai.read.source_book"), text);
        } catch (Throwable t) {
            return null;
        }
    }
}
