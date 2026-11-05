"""Mechanically rename Yarn (Fabric) names to Mojang official mapping names.

This is the bulk of the Forge port: most of the 6000 lines are just Minecraft API
calls whose *name* differs between the two mapping sets. Doing it by hand across
45 files invites inconsistency, so it is done here in one ordered pass.

Ordering matters. Qualified package paths must be rewritten BEFORE the bare type
names, otherwise `net.minecraft.text.Text` would first become
`net.minecraft.text.Component` and the qualified rule would no longer match.

What this does NOT do (it is not mechanical - these are API redesigns):
  * Fabric API events -> Forge events
  * fabric.mod.json -> mods.toml (already done)
  * ClientPlayNetworking / PayloadTypeRegistry -> Forge SimpleChannel
  * Item registration -> DeferredRegister

Run from the repository root:
    python tools/yarn_to_mojang.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")

# --- Pass 1: fully-qualified package paths (longest first) -------------------
QUALIFIED = [
    ("net.minecraft.client.texture.NativeImageBackedTexture", "net.minecraft.client.renderer.texture.DynamicTexture"),
    ("net.minecraft.client.sound.PositionedSoundInstance", "net.minecraft.client.resources.sounds.SimpleSoundInstance"),
    ("net.minecraft.client.gui.widget.TextFieldWidget", "net.minecraft.client.gui.components.EditBox"),
    ("net.minecraft.client.gui.widget.ButtonWidget", "net.minecraft.client.gui.components.Button"),
    ("net.minecraft.client.gui.screen.Screen", "net.minecraft.client.gui.screens.Screen"),
    ("net.minecraft.entity.player.PlayerInventory", "net.minecraft.world.entity.player.Inventory"),
    ("net.minecraft.entity.player.PlayerEntity", "net.minecraft.world.entity.player.Player"),
    ("net.minecraft.client.option.KeyBinding", "net.minecraft.client.KeyMapping"),
    ("net.minecraft.client.MinecraftClient", "net.minecraft.client.Minecraft"),
    ("net.minecraft.client.font.TextRenderer", "net.minecraft.client.gui.Font"),
    ("net.minecraft.client.gui.DrawContext", "net.minecraft.client.gui.GuiGraphics"),
    ("net.minecraft.client.util.InputUtil", "com.mojang.blaze3d.platform.InputConstants"),
    ("net.minecraft.client.texture.NativeImage", "com.mojang.blaze3d.platform.NativeImage"),
    ("net.minecraft.util.math.MathHelper", "net.minecraft.util.Mth"),
    ("net.minecraft.util.math.BlockPos", "net.minecraft.core.BlockPos"),
    ("net.minecraft.util.hit.EntityHitResult", "net.minecraft.world.phys.EntityHitResult"),
    ("net.minecraft.util.hit.BlockHitResult", "net.minecraft.world.phys.BlockHitResult"),
    ("net.minecraft.util.hit.HitResult", "net.minecraft.world.phys.HitResult"),
    ("net.minecraft.util.TypedActionResult", "net.minecraft.world.InteractionResultHolder"),
    ("net.minecraft.util.Identifier", "net.minecraft.resources.ResourceLocation"),
    ("net.minecraft.util.Formatting", "net.minecraft.ChatFormatting"),
    ("net.minecraft.registry.Registries", "net.minecraft.core.registries.BuiltInRegistries"),
    ("net.minecraft.registry.Registry", "net.minecraft.core.Registry"),
    ("net.minecraft.text.MutableText", "net.minecraft.network.chat.MutableComponent"),
    ("net.minecraft.text.ClickEvent", "net.minecraft.network.chat.ClickEvent"),
    ("net.minecraft.text.HoverEvent", "net.minecraft.network.chat.HoverEvent"),
    ("net.minecraft.text.Style", "net.minecraft.network.chat.Style"),
    ("net.minecraft.text.Text", "net.minecraft.network.chat.Component"),
    ("net.minecraft.sound.SoundEvents", "net.minecraft.sounds.SoundEvents"),
    ("net.minecraft.sound.SoundEvent", "net.minecraft.sounds.SoundEvent"),
    ("net.minecraft.item.ItemStack", "net.minecraft.world.item.ItemStack"),
    ("net.minecraft.item.Item", "net.minecraft.world.item.Item"),
    ("net.minecraft.item.Items", "net.minecraft.world.item.Items"),
    ("net.minecraft.block.BlockState", "net.minecraft.world.level.block.state.BlockState"),
    ("net.minecraft.block.Block", "net.minecraft.world.level.block.Block"),
    ("net.minecraft.entity.LivingEntity", "net.minecraft.world.entity.LivingEntity"),
    ("net.minecraft.entity.Entity", "net.minecraft.world.entity.Entity"),
]

# --- Pass 2: bare type names ------------------------------------------------
# Word-boundary matched, so `TextRenderer` and `TextReader` survive `Text`.
BARE = [
    ("MutableText", "MutableComponent"),
    ("NativeImageBackedTexture", "DynamicTexture"),
    ("PositionedSoundInstance", "SimpleSoundInstance"),
    ("TextFieldWidget", "EditBox"),
    ("ButtonWidget", "Button"),
    ("MinecraftClient", "Minecraft"),
    ("DrawContext", "GuiGraphics"),
    ("KeyBinding", "KeyMapping"),
    ("InputUtil", "InputConstants"),
    ("TextRenderer", "Font"),
    ("PlayerInventory", "Inventory"),
    ("PlayerEntity", "Player"),
    ("TypedActionResult", "InteractionResultHolder"),
    ("Identifier", "ResourceLocation"),
    ("Formatting", "ChatFormatting"),
    ("MathHelper", "Mth"),
    ("NativeImage", "NativeImage"),   # same name, kept for clarity
    ("Text", "Component"),
]

# --- Pass 3: method / field renames ----------------------------------------
METHODS = [
    # Chat HUD went through a rename: inGameHud.getChatHud() -> gui.getChat()
    (r"\.inGameHud\.getChatHud\(\)", ".gui.getChat()"),
    (r"\.inGameHud\b", ".gui"),
    (r"\.currentScreen\b", ".screen"),
    (r"\.textRenderer\b", ".font"),
    # client.world -> client.level  (only the Minecraft field, not WorldContext's own)
    (r"\bclient\.world\b", "client.level"),
    (r"\bMinecraft\.getInstance\(\)\.world\b", "Minecraft.getInstance().level"),
    (r"\.drawTextWithShadow\(", ".drawString("),
    (r"\.drawTexture\(", ".blit("),
    (r"\.drawBorder\(", ".renderOutline("),
    (r"\.wasPressed\(\)", ".consumeClick()"),
    (r"InputConstants\.isKeyPressed\(", "InputConstants.isKeyDown("),
    (r"\.isOf\(", ".is("),
    (r"\.isClient\(\)", ".isClientSide()"),
    (r"\.shouldPause\(\)", ".isPauseScreen()"),
    # Item.Settings -> Item.Properties, maxCount -> stacksTo
    (r"\bItem\.Settings\b", "Item.Properties"),
    (r"\.maxCount\(", ".stacksTo("),
    # ItemStack name accessor
    (r"\.getName\(\)\.getString\(\)", ".getHoverName().getString()"),
]


def apply(text: str) -> tuple[str, int]:
    changes = 0
    for old, new in QUALIFIED:
        if old == new:
            continue
        if old in text:
            changes += text.count(old)
            text = text.replace(old, new)
    for old, new in BARE:
        if old == new:
            continue
        pattern = re.compile(r"\b" + re.escape(old) + r"\b")
        text, n = pattern.subn(new, text)
        changes += n
    for pattern, new in METHODS:
        text, n = re.subn(pattern, new, text)
        changes += n
    return text, changes


def main() -> int:
    if not os.path.isdir(SRC):
        print(f"source dir not found: {SRC}")
        return 1

    total_files = 0
    total_changes = 0
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                original = handle.read()
            updated, changes = apply(original)
            if changes:
                with open(path, "w", encoding="utf-8", newline="") as handle:
                    handle.write(updated)
                total_files += 1
                total_changes += changes
                print(f"  {changes:4d}  {os.path.relpath(path, ROOT)}")

    print()
    print(f"rewrote {total_changes} occurrences across {total_files} files")
    print("NOTE: Fabric API event usage is untouched - that part is a redesign, not a rename.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
