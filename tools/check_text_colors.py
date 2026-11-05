"""Fail the build when a text colour is missing its alpha channel.

Why this exists
---------------
Minecraft 1.21.11 changed text rendering so the colour you pass to
`DrawContext.drawText*` is used as-is, alpha included. The old habit of writing
`0xFFFFFF` for "white" now means *fully transparent white*, so the text is laid
out and simply never appears - no exception, no log line, nothing to grep for.
The 1.26.0 config screen shipped exactly like that: the vanilla widgets drew
their own labels fine while every line the mod drew itself was invisible.
26.1 renamed the calls to `GuiGraphicsExtractor#text(...)` /
`#centeredText(...)` and kept the behaviour, so both spellings are checked.

Any colour that reaches a text call therefore needs 8 hex digits (0xAARRGGBB).

Usage
-----
    python tools/check_text_colors.py [path ...]

Defaults to src/main/java. Exits 1 when a 6-digit literal reaches a text call.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PATHS = [os.path.join(ROOT, "src", "main", "java")]

# 文本绘制入口。两套都要管：
#   1.21.x  DrawContext.drawText / drawCenteredText，以及本项目包的一层助手
#   26.x    GuiGraphicsExtractor.text(...) / .centeredText(...)（名字换了，
#           alpha 语义没变）—— 漏掉这一套时，配置界面的标题、分组标题、
#           底部提示和按键名会整片看不见，而 CI 一路绿灯。
TEXT_CALL = re.compile(
    r"\b(?:draw[A-Za-z]*Text[A-Za-z]*|drawTruncated|drawLabel)\s*\("
    r"|\b(?:context|graphics|guiGraphics|graphicsExtractor|extractor)"
    r"\s*\.\s*(?:text|centeredText|drawString)\s*\("
)
HEX6 = re.compile(r"\b0x([0-9A-Fa-f]{6})\b")
HEX8 = re.compile(r"\b0x([0-9A-Fa-f]{8})\b")


def java_files(paths):
    for path in paths:
        if os.path.isfile(path) and path.endswith(".java"):
            yield path
            continue
        for root, _, files in os.walk(path):
            for name in sorted(files):
                if name.endswith(".java"):
                    yield os.path.join(root, name)


def main() -> int:
    paths = sys.argv[1:] or DEFAULT_PATHS
    problems = []
    checked = 0

    for path in java_files(paths):
        src = open(path, encoding="utf-8").read()
        checked += 1
        for statement in src.split(";"):
            if not TEXT_CALL.search(statement):
                continue
            for match in HEX6.finditer(statement):
                line = src[: src.index(statement) + match.start()].count("\n") + 1
                problems.append(
                    f"{os.path.relpath(path, ROOT)}:{line}: text colour "
                    f"{match.group(0)} has no alpha channel (write 0xFF{match.group(1).upper()})"
                )

    for problem in problems:
        print(f"  [FAIL] {problem}")

    if problems:
        print(f"RESULT: FAILED - {len(problems)} text colour(s) without alpha, {checked} files scanned")
        return 1

    print(f"RESULT: OK - every text colour is 0xAARRGGBB ({checked} files scanned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
