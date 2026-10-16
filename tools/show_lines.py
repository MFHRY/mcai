"""Print specific source lines from the port, for eyeballing ambiguous errors.

Usage: python tools/show_lines.py <relative/path.java> <n> [n2 n3 ...]
       python tools/show_lines.py --at <file:line> [<file:line> ...]
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def show(rel: str, numbers, context: int = 0) -> None:
    path = os.path.join(ROOT, rel)
    if not os.path.isfile(path):
        print(f"  !! not found: {rel}")
        return
    with io.open(path, encoding="utf-8") as handle:
        lines = handle.read().split("\n")
    print(f"--- {rel} ({len(lines)} lines)")
    for n in numbers:
        lo = max(1, n - context)
        hi = min(len(lines), n + context)
        for i in range(lo, hi + 1):
            mark = ">>" if i == n else "  "
            print(f"{mark}{i:5d}: {lines[i - 1]}")
    print()


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2

    if args[0] == "--at":
        grouped = {}
        for spec in args[1:]:
            rel, _, num = spec.rpartition(":")
            grouped.setdefault(rel, []).append(int(num))
        for rel, nums in grouped.items():
            show(rel, sorted(nums), context=2)
        return 0

    rel = args[0]
    nums = [int(x) for x in args[1:]]
    show(rel, nums, context=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
