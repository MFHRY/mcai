package com.example.mcai.client;

import com.example.mcai.util.ClientChat;
import com.example.mcai.util.Lang;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;

/**
 * 准星查询（1.25 新增）：看准一个方块或生物按一下键，把它的名字和注册 ID 交给 AI 解释。
 *
 * <p><b>为什么要把注册 ID 一起发过去：</b>只发中文名的话，模组添加的方块很容易和原版
 * 或另一个模组重名（"铜块"在三个模组里都可能有），模型只能猜。带上
 * {@code namespace:path} 形式的 ID，模型就能确切知道是哪一个，也能因此认出这是模组内容。
 *
 * <p>方块还额外带上坐标，因为同一个方块在不同位置的含义可能不同（比如你已经放了一个
 * 命令方块在这里，模型结合坐标能给更贴合的建议）。
 */
public final class CrosshairQuery {

    private CrosshairQuery() {}

    /** 由 {@code key.mcai.what}（默认 V）和 {@code /ai what} 共同调用。 */
    public static void trigger() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        HitResult hit = client.crosshairTarget;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            ClientChat.sendLiteral(Lang.tr("mcai.what.nothing"));
            return;
        }

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = client.level.getBlockState(pos);
            Block block = state.getBlock();
            String name = block.getName().getString();
            String id = Registries.BLOCK.getId(block).toString();
            ChatHandler.ask(Lang.tr("mcai.what.block", name, id,
                    pos.getX(), pos.getY(), pos.getZ()));
            return;
        }

        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            String name = entity.getName().getString();
            String id = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            // 生物多带一个血量：问"要不要打"的时候，这是最关键的判断依据
            if (entity instanceof LivingEntity living) {
                ChatHandler.ask(Lang.tr("mcai.what.entity_living", name, id,
                        Math.round(living.getHealth()), Math.round(living.getMaxHealth())));
            } else {
                ChatHandler.ask(Lang.tr("mcai.what.entity", name, id));
            }
            return;
        }

        // 理论上走不到这里（HitResult 只有 MISS / BLOCK / ENTITY 三种），但别把玩家晾着
        ClientChat.sendLiteral(Lang.tr("mcai.what.nothing"));
    }
}
