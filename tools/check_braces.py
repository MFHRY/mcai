"""Sanity-check brace balance in every Java file.

A cheap pre-flight before spending six minutes on a Gradle build: an unbalanced
brace in one file makes javac report "class, interface, enum or record expected"
at every subsequent method, which buries the actual cause.

The scan is a single-pass state machine, not a regex pile. Regexes get this
wrong in a way that produces false alarms: stripping `//` line comments before
string literals turns the `//` inside "https://api.deepseek.com/v1" into a
comment start, which eats the rest of the line and leaves the brace count off.

Usage: python tools/check_braces.py
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java")


def brace_delta(text: str) -> tuple[int, int]:
    """Return (opens, closes) counting only braces in real code."""
    opens = closes = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if ch == "/" and nxt == "/":          # line comment
            while i < n and text[i] != "\n":
                i += 1
        elif ch == "/" and nxt == "*":        # block comment
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                i += 1
            i += 2
        elif ch == '"' and text[i:i + 3] == '"""':   # text block
            i += 3
            while i < n and text[i:i + 3] != '"""':
                i += 1
            i += 3
        elif ch == '"':                       # string literal
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif ch == "'":                       # char literal
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
        else:
            if ch == "{":
                opens += 1
            elif ch == "}":
                closes += 1
            i += 1
    return opens, closes


def main() -> int:
    bad = []
    total = 0
    for dirpath, _dirnames, filenames in os.walk(SRC):
        for name in sorted(filenames):
            if not name.endswith(".java"):
                continue
            total += 1
            path = os.path.join(dirpath, name)
            with io.open(path, encoding="utf-8") as handle:
                text = handle.read()
            opens, closes = brace_delta(text)
            if opens != closes:
                bad.append((os.path.relpath(path, ROOT), opens, closes))

    print(f"scanned {total} java files")
    if not bad:
        print("RESULT: OK - every file has balanced braces")
        return 0

    print(f"[FAIL] {len(bad)} file(s) unbalanced:")
    for path, opens, closes in bad:
        print(f"   {opens - closes:+3d}  opens={opens} closes={closes}  {path}")
    print("\nRESULT: FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
