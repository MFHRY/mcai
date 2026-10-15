"""Second rename pass: the Yarn names the first table missed.

tools/yarn_to_mojang.py covered the common types. Listing every remaining
`import net.minecraft.*` in the tree turned up another batch - block entities,
recipes, the screenshot helper, the world/level type - so they are collected
here rather than growing the first file's table indefinitely.

Same ordering rule as pass 1: fully-qualified paths are rewritten before bare
type names, otherwise `net.minecraft.world.World` would first become
`net.minecraft.world.Level` and the qualified rule would never match.

Run from the repository root:
    python tools/yarn_to_mojang_pass2.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")

QUALIFIED = [
    # block entities moved under world.level
    ("net.minecraft.block.entity.SignBlockEntity", "net.minecraft.world.level.block.entity.SignBlockEntity"),
    ("net.minecraft.block.entity.SignText", "net.minecraft.world.level.block.entity.SignText"),
    ("net.minecraft.block.entity.BlockEntity", "net.minecraft.world.level.block.entity.BlockEntity"),
    # recipes moved under world.item.crafting
    ("net.minecraft.recipe.CraftingRecipe", "net.minecraft.world.item.crafting.CraftingRecipe"),
    ("net.minecraft.recipe.RecipeEntry", "net.minecraft.world.item.crafting.RecipeHolder"),
    ("net.minecraft.recipe.Recipe", "net.minecraft.world.item.crafting.Recipe"),
    ("net.minecraft.recipe.Ingredient", "net.minecraft.world.item.crafting.Ingredient"),
    # registries
    ("net.minecraft.registry.entry.RegistryEntry", "net.minecraft.core.Holder"),
    # world / level
    ("net.minecraft.world.biome.Biome", "net.minecraft.world.level.biome.Biome"),
    ("net.minecraft.world.World", "net.minecraft.world.level.Level"),
    ("net.minecraft.util.math.Box", "net.minecraft.world.phys.AABB"),
    # client side
    ("net.minecraft.client.network.ClientPlayerEntity", "net.minecraft.client.player.LocalPlayer"),
    ("net.minecraft.client.network.ServerInfo", "net.minecraft.client.multiplayer.ServerData"),
    ("net.minecraft.client.level.ClientWorld", "net.minecraft.client.multiplayer.ClientLevel"),
    ("net.minecraft.client.gui.screen.ingame.HandledScreen",
     "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"),
    ("net.minecraft.client.gui.screen.GameMenuScreen", "net.minecraft.client.gui.screens.PauseScreen"),
    ("net.minecraft.client.gui.hud.DebugHud", "net.minecraft.client.gui.components.DebugScreenOverlay"),
    ("net.minecraft.client.gui.widget.ClickableWidget", "net.minecraft.client.gui.components.AbstractWidget"),
    ("net.minecraft.client.util.math.MatrixStack", "com.mojang.blaze3d.vertex.PoseStack"),
    ("net.minecraft.client.util.ScreenshotRecorder", "net.minecraft.client.Screenshot"),
    ("net.minecraft.client.gl.Framebuffer", "com.mojang.blaze3d.pipeline.RenderTarget"),
    # misc
    ("net.minecraft.entity.mob.HostileEntity", "net.minecraft.world.entity.monster.Enemy"),
    ("net.minecraft.item.tooltip.TooltipType", "net.minecraft.world.item.TooltipFlag"),
    ("net.minecraft.server.network.ServerPlayerEntity", "net.minecraft.server.level.ServerPlayer"),
]

BARE = [
    ("ClientPlayerEntity", "LocalPlayer"),
    ("ServerPlayerEntity", "ServerPlayer"),
    ("ClientWorld", "ClientLevel"),
    ("ServerInfo", "ServerData"),
    ("GameMenuScreen", "PauseScreen"),
    ("HandledScreen", "AbstractContainerScreen"),
    ("ClickableWidget", "AbstractWidget"),
    ("DebugHud", "DebugScreenOverlay"),
    ("MatrixStack", "PoseStack"),
    ("ScreenshotRecorder", "Screenshot"),
    ("Framebuffer", "RenderTarget"),
    ("RegistryEntry", "Holder"),
    ("TooltipType", "TooltipFlag"),
    ("HostileEntity", "Enemy"),
    ("RecipeEntry", "RecipeHolder"),
    # `Box` and `World` are short and appear in comments, so they are matched with
    # word boundaries and only after the qualified forms above have been applied.
    ("Box", "AABB"),
    ("World", "Level"),
]


def main() -> int:
    if not os.path.isdir(SRC):
        print(f"source dir not found: {SRC}")
        return 1

    files = 0
    total = 0
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            original = text
            for old, new in QUALIFIED:
                if old in text:
                    total += text.count(old)
                    text = text.replace(old, new)
            for old, new in BARE:
                text, n = re.subn(r"\b" + re.escape(old) + r"\b", new, text)
                total += n
            if text != original:
                with open(path, "w", encoding="utf-8", newline="") as handle:
                    handle.write(text)
                files += 1
                print(f"  {os.path.relpath(path, ROOT)}")

    print()
    print(f"pass 2: {total} occurrences across {files} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
