"""Group javac errors in a Gradle plain-console log by the symbol they complain about.

javac puts the real information on the lines AFTER the `error:` line:

    Foo.java:61: error: cannot find symbol
            addDrawableChild(...)
            ^
      symbol:   method addDrawableChild(Button)
      location: class Foo

so grepping for "error:" alone tells you where but not what. This walks the log
and buckets by (message, symbol, location) with a few example sites each.

Usage: python tools/summarize_errors.py <logfile> [outfile]
"""
import io
import os
import re
import sys
import collections

ERR = re.compile(r"^(.*\.java):(\d+):\s*(error|warning):\s*(.*)$")
SYM = re.compile(r"^\s*(symbol|location|required|found|reason):\s*(.*)$")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: summarize_errors.py <logfile> [outfile]")
        return 2
    log_path = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else None

    if not os.path.isfile(log_path):
        print(f"log not found: {log_path}")
        return 1

    with io.open(log_path, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().split("\n")

    buckets = collections.defaultdict(list)
    counts = collections.Counter()

    i = 0
    while i < len(lines):
        m = ERR.match(lines[i].strip())
        if not m:
            i += 1
            continue
        path, line_no, kind, message = m.groups()
        symbol = location = ""
        j = i + 1
        while j < len(lines) and j < i + 12:
            if ERR.match(lines[j].strip()):
                break
            sm = SYM.match(lines[j])
            if sm:
                if sm.group(1) == "symbol" and not symbol:
                    symbol = sm.group(2).strip()
                elif sm.group(1) == "location" and not location:
                    location = sm.group(2).strip()
            j += 1

        if kind == "error":
            key = (message, symbol, location)
            counts[key] += 1
            if len(buckets[key]) < 5:
                buckets[key].append(f"{os.path.basename(path)}:{line_no}")
        i = j

    report = []
    report.append(f"total errors: {sum(counts.values())}")
    report.append(f"distinct causes: {len(counts)}")
    report.append("")
    for (message, symbol, location), n in counts.most_common():
        report.append(f"=== [{n}x] {message}")
        if symbol:
            report.append(f"    symbol:   {symbol}")
        if location:
            report.append(f"    location: {location}")
        report.append(f"    sites:    {', '.join(buckets[(message, symbol, location)])}")
        report.append("")

    text = "\n".join(report)
    if out_path:
        with io.open(out_path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        print(f"written to {out_path}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
