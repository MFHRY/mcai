"""Third pass: 1.21.1 -> 1.20.1 API shape fixes.

The rename passes corrected NAMES (Yarn vs Mojang). This pass corrects
SIGNATURES AND SHAPES, which are a different class of problem: the name is
already right, but 1.20.1 has a different method, a different arity, or the
member simply does not exist yet.

Every replacement below was verified with `javap` against the real compile
classpath (forge-1.20.1-47.4.28_mapped_official_1.20.1.jar), not guessed.

Run from the repository root: python tools/forge_api_fixes.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")

# (old, new, comment) - applied to every file
GLOBAL = [
    # --- widgets ---------------------------------------------------------
    ("addDrawableChild(", "addRenderableWidget("),      # 1.21 renamed it
    ("addSelectableChild(", "addWidget("),              # 1.21 renamed it
    (".setText(", ".setValue("),                        # EditBox
    (".setChangedListener(", ".setResponder("),         # EditBox -> Consumer<String>
    (".setPlaceholder(", ".setHint("),                  # EditBox takes a Component
    (".setRenderTextProvider(", ".setFormatter("),

    # --- text metrics ----------------------------------------------------
    # Font.field / Font.method were both renamed
    # (fontHeight -> lineHeight, getWidth -> width) - handled per-file below
    # because "textRenderer" is also used as a parameter name in AvatarWidget.

    # --- sequences -------------------------------------------------------
    ("FormattedCharSequence.styledForwardsVisitedString(",
     "FormattedCharSequence.forward("),
    ("FormattedCharSequence.styledForwardsVisitedString",
     "FormattedCharSequence.forward"),

    # --- keys ------------------------------------------------------------
    (".getBoundKeyLocalizedText()", ".getTranslatedKeyMessage()"),
    ("InputConstants.Type.KEYSYM.createFromCode(",
     "InputConstants.Type.KEYSYM.getOrCreate("),
    ("Type.KEYSYM.createFromCode(", "Type.KEYSYM.getOrCreate("),

    # --- options ---------------------------------------------------------
    ("options.write()", "options.save()"),

    # --- Minecraft -------------------------------------------------------
    ("client.mouse.", "client.mouseHandler."),
    ("client.mouse;", "client.mouseHandler;"),
    ("client.crosshairTarget", "client.hitResult"),
    (".getWindowHandle()", ".getWindow()"),             # Window.getHandle -> getWindow
    ("getWindow().getHandle()", "getWindow().getWindow()"),

    # --- GuiGraphics -----------------------------------------------------
    (".getScaledWindowWidth()", ".guiWidth()"),
    (".getScaledWindowHeight()", ".guiHeight()"),
    (".getMatrices()", ".pose()"),

    # --- PoseStack -------------------------------------------------------
    # only safe where the receiver is a pose stack; the call sites are all
    # `matrices.push()` / `matrices.pop()` on the object returned by pose()
    ("matrices.push();", "matrices.pushPose();"),
    ("matrices.pop();", "matrices.popPose();"),

    # --- sound / textures ------------------------------------------------
    ("SimpleSoundInstance.master(", "SimpleSoundInstance.forUI("),
    (".registerTexture(", ".register("),
    (".destroyTexture(", ".release("),

    # --- inventory (Mojang `Inventory`, not Yarn `PlayerInventory`) ------
    ("inventory.size()", "inventory.getContainerSize()"),
    ("inventory.getStack(", "inventory.getItem("),

    # --- world / entity --------------------------------------------------
    (".getEntitiesByClass(", ".getEntitiesOfClass("),
    ("player.isDead()", "player.isDeadOrDying()"),

    # --- components ------------------------------------------------------
    # Component.styled(UnaryOperator<Style>) became withStyle in 1.20.1
    (".styled(style ->", ".withStyle(style ->"),
    (".styled((style) ->", ".withStyle((style) ->"),

    # --- registry --------------------------------------------------------
    # Registry.getId(T) is an int; getKey(T) is the ResourceLocation
    ("BuiltInRegistries.BLOCK.getId(block).toString()",
     "BuiltInRegistries.BLOCK.getKey(block).toString()"),
    ("BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()).toString()",
     "BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()"),

    # --- Screen.renderBackground lost its mouse/partialTick arguments ----
    ("public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta)",
     "public void renderBackground(GuiGraphics context)"),
    ("super.renderBackground(context, mouseX, mouseY, delta)",
     "super.renderBackground(context)"),
    ("super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)",
     "super.renderBackground(guiGraphics)"),
]

# per-file: the Screen field is `font` in Mojang mappings, but AvatarWidget uses
# `textRenderer` as a PARAMETER name and must not be touched.
TEXT_RENDERER_FIELD_FILES = [
    "client/ConfigScreen.java",
    "client/AvatarEditScreen.java",
    "client/VisionCaptureScreen.java",
    "client/VisionPreviewScreen.java",
]

# Font member renames apply everywhere, including inside AvatarWidget
FONT_MEMBERS = [
    (".fontHeight", ".lineHeight"),
    ("textRenderer.getWidth(", "textRenderer.width("),
    ("font.getWidth(", "font.width("),
]

# JsonObject has no isEmpty() in the Gson bundled with 1.20.1
JSON_OBJECT_EMPTY = [
    ("usage.isEmpty()", "usage.size() == 0"),
    ("delta.isEmpty()", "delta.size() == 0"),
]


def apply(path: str, pairs) -> tuple[str, int]:
    with io.open(path, encoding="utf-8") as handle:
        text = original = handle.read()
    count = 0
    for old, new in pairs:
        if old in text:
            count += text.count(old)
            text = text.replace(old, new)
    if text != original:
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
    return text, count


def main() -> int:
    total = 0
    changed = []

    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, SRC).replace(os.sep, "/")

            pairs = list(GLOBAL)
            pairs += FONT_MEMBERS
            pairs += JSON_OBJECT_EMPTY
            if any(rel.endswith(f) for f in TEXT_RENDERER_FIELD_FILES):
                pairs.append(("textRenderer", "font"))

            _text, count = apply(path, pairs)
            if count:
                total += count
                changed.append((rel, count))

    for rel, count in sorted(changed):
        print(f"  {count:4d}  {rel}")
    print()
    print(f"applied {total} API-shape fixes across {len(changed)} files")

    # the F3 debug check is a structural change, done separately
    widget = os.path.join(SRC, "com", "example", "mcai", "client", "AvatarWidget.java")
    if os.path.isfile(widget):
        with io.open(widget, encoding="utf-8") as handle:
            text = handle.read()
        old_block = ("        DebugScreenOverlay debugHud = client.getDebugHud();\n"
                     "        if (debugHud != null && debugHud.shouldShowDebugHud()) {")
        new_block = ("        // 1.20.1 has no getDebugHud()/shouldShowDebugHud(); the flag that\n"
                     "        // says \"the F3 overlay is up\" is a plain field on Minecraft.\n"
                     "        if (client.renderDebug) {")
        if old_block in text:
            text = text.replace(old_block, new_block)
            with io.open(widget, "w", encoding="utf-8", newline="") as handle:
                handle.write(text)
            print("  rewritten AvatarWidget F3-overlay check -> client.renderDebug")
        else:
            print("  !! AvatarWidget debug block not found in the expected shape")

    return 0


if __name__ == "__main__":
    sys.exit(main())
