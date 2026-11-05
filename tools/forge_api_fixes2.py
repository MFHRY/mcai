"""Fourth pass: the remaining 1.21.1 -> 1.20.1 API shape fixes.

Separated from forge_api_fixes.py only for reviewability - both are "the name is
already right, the member is not". Every entry was checked with javap against the
real compile classpath.

Two entries here undo damage done by the earlier passes, which is worth calling
out because both were silent and would have been confusing to hit later:

  * the `Registries.` -> `BuiltInRegistries.` rule also matched the tail of
    `ForgeRegistries.`, producing the non-existent `ForgeBuiltInRegistries`
  * `x.isEmpty()` -> `x.size() == 0` was applied to a String in ApiClient, and
    made `!x.isEmpty()` parse as `(!x.size()) == 0`

Run from the repository root: python tools/forge_api_fixes2.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")

GLOBAL = [
    # --- undo the two self-inflicted bugs --------------------------------
    ("ForgeBuiltInRegistries", "ForgeRegistries"),
    ("!usage.size() == 0", "usage.size() != 0"),
    ("!delta.size() == 0", "delta.size() != 0"),
    # delta is a String in ApiClient, where isEmpty() is correct
    ("delta.size() == 0", "delta.isEmpty()"),

    # --- GuiGraphics -----------------------------------------------------
    ("drawCenteredTextWithShadow(", "drawCenteredString("),
    ("context.drawTooltip(", "context.renderTooltip("),
    (".drawTooltip(", ".renderTooltip("),

    # --- Screen lifecycle ------------------------------------------------
    # 1.20.1 calls it onClose; close() only arrived in a later version
    ("public void close() {", "public void onClose() {"),
    ("super.close();", "super.onClose();"),

    # --- recipes (1.20.1 has no RecipeHolder wrapper) --------------------
    (".getRecipeManager().values()", ".getRecipeManager().getRecipes()"),
    ("recipe.createIcon()", "recipe.getToastSymbol()"),
    ("ingredient.getMatchingStacks()", "ingredient.getItems()"),

    # --- versions / mod metadata ----------------------------------------
    ("SharedConstants.getGameVersion().getName()", "SharedConstants.getCurrentVersion().getName()"),
    ("mod.getMetadata().getName()", "mod.getDisplayName()"),
    ("mod.getMetadata().getId()", "mod.getModId()"),

    # --- server info -----------------------------------------------------
    ("server.address", "server.ip"),
    ("client.getCurrentServerEntry()", "client.getCurrentServer()"),
    ("client.getServer()", "client.getSingleplayerServer()"),

    # --- styles ----------------------------------------------------------
    (".withUnderline(", ".withUnderlined("),

    # --- misc client -----------------------------------------------------
    ("client.renderDebug", "client.options.renderDebug"),
    ("mouseHandler.isCursorLocked()", "mouseHandler.isMouseGrabbed()"),
    ("mouseHandler.getX()", "mouseHandler.xpos()"),
    ("mouseHandler.getY()", "mouseHandler.ypos()"),
    ("binding.setBoundKey(", "binding.setKey("),
    ("font.trimToWidth(", "font.plainSubstrByWidth("),
    ("client.getFramebuffer()", "client.getMainRenderTarget()"),

    # --- window ----------------------------------------------------------
    (".getScaledWidth()", ".getGuiScaledWidth()"),
    (".getScaledHeight()", ".getGuiScaledHeight()"),

    # --- entity / world --------------------------------------------------
    ("player.getOffHandStack()", "player.getOffhandItem()"),
    ("player.getBlockPos()", "player.blockPosition()"),
    ("world.getRegistryKey()", "world.dimension()"),
    ("biome.getKey()", "biome.unwrapKey()"),
    ("world.getLightLevel(pos)", "world.getMaxLocalRawBrightness(pos)"),
    ("world.getTimeOfDay()", "world.getDayTime()"),
    # Enemy is an interface, not an Entity subclass, so it cannot be used as a
    # Class<T extends Entity> argument; Monster is the concrete hostile base
    ("getEntitiesOfClass(Enemy.class", "getEntitiesOfClass(Monster.class"),
    ("net.minecraft.world.entity.monster.Enemy;", "net.minecraft.world.entity.monster.Monster;"),

    # --- images ----------------------------------------------------------
    (".setColor(", ".setPixelRGBA("),
    (".getColor(", ".getPixelRGBA("),

    # --- language --------------------------------------------------------
    ("net.minecraft.util.Language", "net.minecraft.locale.Language"),
]

# The Screen subclasses reference the Yarn field `client`; Mojang calls it
# `minecraft`. Only these two files use the bare name.
SCREEN_CLIENT_FILES = [
    "client/VisionPreviewScreen.java",
    "client/AvatarEditScreen.java",
]


def main() -> int:
    total = 0
    changed = []

    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, SRC).replace(os.sep, "/")

            with io.open(path, encoding="utf-8") as handle:
                text = original = handle.read()

            count = 0
            for old, new in GLOBAL:
                if old in text:
                    count += text.count(old)
                    text = text.replace(old, new)

            if any(rel.endswith(f) for f in SCREEN_CLIENT_FILES):
                text, n = re.subn(r"(?<![\w.])client(?![\w])", "minecraft", text)
                count += n

            if text != original:
                with io.open(path, "w", encoding="utf-8", newline="") as handle:
                    handle.write(text)
                total += count
                changed.append((rel, count))

    for rel, count in sorted(changed):
        print(f"  {count:4d}  {rel}")
    print()
    print(f"applied {total} fixes across {len(changed)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
