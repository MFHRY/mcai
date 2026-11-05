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
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
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
            // 1.20.5+ 起物品内容不再存在 NBT 里（没有 stack.getTag() 了），
            // 而是"数据组件"。成书读 WRITTEN_BOOK_CONTENT，书与笔读 WRITABLE_BOOK_CONTENT。
            List<String> lines = new ArrayList<>();

            WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (written != null) {
                String title = written.title().get(false);
                if (title != null && !title.isBlank()) {
                    lines.add(title.trim());
                }
                for (Filterable<Component> page : written.pages()) {
                    Component pageText = page.get(false);
                    String text = pageText == null ? null : pageText.getString();
                    if (text != null && !text.isBlank()) {
                        lines.add(text.trim());
                    }
                }
            } else {
                WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
                if (writable == null) {
                    return null;
                }
                for (Filterable<String> page : writable.pages()) {
                    String text = page.get(false);
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    // 书与笔里存的是纯文本，可能带着 § 颜色码，去掉再发
                    lines.add(Component.literal(text).getString().trim());
                }
            }

            String text = String.join("\n", lines).trim();
            return text.isEmpty() ? null : new Found(Lang.tr("mcai.read.source_book"), text);
        } catch (Throwable t) {
            return null;
        }
    }
}
