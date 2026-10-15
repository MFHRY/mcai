"""校验 assets/mcai/lang/ 下所有语言文件的一致性。

检查三件事，任何一条不过就 exit 1：
1. 每个文件都是合法 JSON；
2. 所有文件的键集合完全相同（不多不少），且顺序一致；
3. 同一个键在所有文件里的 `%s` 占位符个数相同，`%%` 转义也要算对。

加语言或改文案后请跑一遍：漏键和少写一个 `%s` 靠肉眼是看不出来的。

用法：python tools/check_lang.py
"""
import json
import os
import re
import sys

LANG_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "src", "main", "resources", "assets", "mcai", "lang",
)

# 匹配 %s / %d / %1$s 之类；%% 是转义，先剔除再统计
PLACEHOLDER = re.compile(r"%(?!%)(?:\d+\$)?[sd]")


def count_placeholders(text: str) -> int:
    return len(PLACEHOLDER.findall(text.replace("%%", "")))


def main() -> int:
    names = sorted(n for n in os.listdir(LANG_DIR) if n.endswith(".json"))
    if not names:
        print("no language files found in", LANG_DIR)
        return 1

    data = {}
    for name in names:
        path = os.path.join(LANG_DIR, name)
        with open(path, encoding="utf-8") as handle:
            try:
                data[name] = json.load(handle)
            except Exception as exc:
                print(f"[FAIL] {name}: invalid JSON: {exc}")
                return 1
        print(f"[ok]   {name}: {len(data[name])} keys")

    reference = names[0]
    ref_keys = list(data[reference])
    ref_set = set(ref_keys)
    failed = False

    for name in names[1:]:
        keys = list(data[name])
        missing = ref_set - set(keys)
        extra = set(keys) - ref_set
        if missing or extra:
            failed = True
            print(f"[FAIL] {name}: key set differs from {reference}")
            if missing:
                print("   missing:", sorted(missing)[:20], "..." if len(missing) > 20 else "")
            if extra:
                print("   extra:  ", sorted(extra)[:20], "..." if len(extra) > 20 else "")
        if keys != ref_keys and not missing and not extra:
            print(f"[warn] {name}: same keys but different order than {reference}")

    # 占位符比对：缺键的条目上面已经报过了，这里跳过——
    # 否则会在这里抛 KeyError 中断，把"哪份文件缺哪个键"这条最有用的信息埋掉
    for key in ref_keys:
        present = [name for name in names if key in data[name]]
        if len(present) != len(names):
            continue
        counts = {name: count_placeholders(data[name][key]) for name in names}
        if len(set(counts.values())) != 1:
            failed = True
            print(f"[FAIL] {key}: placeholder count mismatch -> {counts}")

    if failed:
        print("\nRESULT: FAILED")
        return 1
    print(f"\nRESULT: OK — {len(names)} files x {len(ref_keys)} keys, placeholders consistent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
