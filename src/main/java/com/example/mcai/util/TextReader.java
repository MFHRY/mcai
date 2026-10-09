package com.example.mcai.util;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        World world = client.world;
        if (player == null || world == null) {
            return null;
        }

        HitResult hit = client.crosshairTarget;
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
        Text[] messages;
        try {
            messages = side.getMessages(false);
        } catch (Throwable t) {
            return;
        }
        for (Text message : messages) {
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
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return null;
        }
        Found main = bookOf(player.getMainHandStack());
        return main != null ? main : bookOf(player.getOffHandStack());
    }

    private static Found bookOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            var content = stack.get(net.minecraft.component.DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (content == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            // title 字段是 Filterable<String>
            String title = content.title() == null ? "" : content.title().raw();
            if (!title.isBlank()) {
                lines.add(title.trim());
            }
            for (var page : content.pages()) {
                if (page == null) {
                    continue;
                }
                // pages 的元素是 Filterable<Text>，要再取一次字符串
                Text pageText = page.raw();
                String raw = pageText == null ? null : pageText.getString();
                if (raw != null && !raw.isBlank()) {
                    lines.add(raw.trim());
                }
            }
            String text = String.join("\n", lines).trim();
            return text.isEmpty() ? null : new Found(Lang.tr("mcai.read.source_book"), text);
        } catch (Throwable t) {
            return null;
        }
    }
}
