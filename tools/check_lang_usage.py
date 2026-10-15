"""反查：Java 源码里用到的语言键，是否都存在于语言文件里。

比"等作者自己列清单"可靠得多——直接把所有形如 "mcai.xxx.yyy" 的字符串字面量抠出来，
逐个到 zh_cn.json 里查。顺带报出「语言文件里有、但代码里从没引用过」的键（可能是废弃键）。

用法：python tools/check_lang_usage.py
退出码 1 表示有缺失键。
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_DIR = os.path.join(ROOT, "src", "main", "java")
LANG_FILE = os.path.join(ROOT, "src", "main", "resources", "assets", "mcai", "lang", "zh_cn.json")

# 语言键的样子：以 mcai. / key.mcai. / item.mcai. / tooltip.mcai. 开头。
# category.mcai 没有后缀，所以单独放一个分支。
KEY_LITERAL = re.compile(
    r'"(category\.mcai|(?:mcai|key\.mcai|item\.mcai|tooltip\.mcai|block\.mcai)\.[A-Za-z0-9_.]+)"'
)

# 这些是文件名/路径，不是语言键
NOT_KEYS = {"mcai.json", "mcai-usage.csv", "mcai-usage"}


def main() -> int:
    with open(LANG_FILE, encoding="utf-8") as handle:
        known = set(json.load(handle))

    used = {}
    for dirpath, _dirnames, filenames in os.walk(JAVA_DIR):
        for filename in filenames:
            if not filename.endswith(".java"):
                continue
            path = os.path.join(dirpath, filename)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            for key in KEY_LITERAL.findall(text):
                if key in NOT_KEYS or key.endswith(".json") or key.endswith(".csv"):
                    continue
                # 形如 "mcai.config.group." + id 或 "mcai.cmd.flag_" + id 的动态拼接：
                # 抠出来的是前缀，不是完整键
                if key.endswith(".") or key.endswith("_"):
                    continue
                used.setdefault(key, set()).add(filename)

    missing = sorted(k for k in used if k not in known)
    unused = sorted(k for k in known
                    if k not in used and k.startswith(("mcai.", "key.mcai.", "category.mcai")))

    print(f"language keys defined : {len(known)}")
    print(f"language keys used    : {len(used)}")
    print()

    if missing:
        print(f"[FAIL] {len(missing)} key(s) used in Java but MISSING from zh_cn.json:")
        for key in missing:
            print(f"   {key}   <- {', '.join(sorted(used[key]))}")
    else:
        print("[ok] every key referenced in Java exists in the language file")

    if unused:
        print()
        print(f"[note] {len(unused)} key(s) defined but never referenced "
              "(may be dynamic or obsolete):")
        for key in unused:
            print(f"   {key}")

    print()
    if missing:
        print("RESULT: FAILED")
        return 1
    print("RESULT: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
