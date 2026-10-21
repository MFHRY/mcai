"""Verify that a built mod jar actually contains what the loader needs.

Why this exists
---------------
mcAI Forge 1.0.0 was published with no `pack.mcmeta`. Forge then skipped the
mod's resource pack entirely, so every item model, texture and translation
vanished - the items rendered as the purple/black missing texture and the chat
showed raw keys like `mcai.cmd.status`. The jar compiled fine and the mod loaded
fine; nothing failed until a player opened the game.

Compiling proves the code is valid Java. It says nothing about whether the jar
is *complete*. This script checks the completeness part.

Usage
-----
    python tools/check_jar.py [jar ...]

With no arguments it checks every jar under build/libs, skipping -sources/-dev
artifacts. Exits 1 if anything required is missing.
"""
import io
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS = os.path.join(ROOT, "build", "libs")

LANG_DIR = "assets/mcai/lang/"
EXPECTED_LANGS = ["en_us", "zh_cn", "zh_tw", "ja_jp", "ko_ru_placeholder"]

# Files that must never end up inside a distributable jar.
JUNK_SUFFIXES = ("-sources.jar", "-dev.jar", "-sources-dev.jar")


def find_jars() -> list:
    if not os.path.isdir(LIBS):
        return []
    found = []
    for name in sorted(os.listdir(LIBS)):
        if not name.endswith(".jar"):
            continue
        if "sources" in name or name.endswith("-dev.jar"):
            continue
        found.append(os.path.join(LIBS, name))
    return found


class Report:
    def __init__(self, jar: str) -> None:
        self.jar = jar
        self.problems = []
        self.notes = []

    def fail(self, message: str) -> None:
        self.problems.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)


def check(entry: str, names, report: Report, required: bool = True) -> bool:
    present = entry in names
    if required and not present:
        report.fail(f"missing required entry: {entry}")
    return present


def check_common(zf: zipfile.ZipFile, names, report: Report) -> None:
    # --- language files -------------------------------------------------
    lang_files = sorted(n for n in names if n.startswith(LANG_DIR) and n.endswith(".json"))
    if len(lang_files) != 8:
        report.fail(f"expected 8 language files under {LANG_DIR}, found {len(lang_files)}: {lang_files}")

    key_sets = {}
    for entry in lang_files:
        raw = zf.read(entry).decode("utf-8")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as error:
            report.fail(f"{entry} is not valid JSON: {error}")
            continue
        key_sets[entry] = set(data.keys())
        if not data:
            report.fail(f"{entry} is empty")

    if len(key_sets) > 1:
        reference_entry = next(iter(key_sets))
        reference = key_sets[reference_entry]
        for entry, keys in key_sets.items():
            if keys == reference:
                continue
            missing = reference - keys
            extra = keys - reference
            if missing:
                report.fail(f"{entry} is missing {len(missing)} key(s), e.g. {sorted(missing)[:3]}")
            if extra:
                report.fail(f"{entry} has {len(extra)} key(s) the others lack, e.g. {sorted(extra)[:3]}")
    if key_sets:
        report.note(f"{len(key_sets)} language files, ~{len(next(iter(key_sets.values())))} keys each")

    # --- class entry point ----------------------------------------------
    if "com/example/mcai/McaiMod.class" not in names:
        report.fail("missing com/example/mcai/McaiMod.class (the mod entry point)")


def check_forge(zf, names, report: Report) -> None:
    # The whole 1.0.0 bug in one line: without this, Forge drops the resource pack.
    if check("pack.mcmeta", names, report):
        try:
            meta = json.loads(zf.read("pack.mcmeta").decode("utf-8"))
        except json.JSONDecodeError as error:
            report.fail(f"pack.mcmeta is not valid JSON: {error}")
            return
        pack = meta.get("pack")
        if not isinstance(pack, dict):
            report.fail('pack.mcmeta has no "pack" object')
        elif "pack_format" not in pack:
            report.fail('pack.mcmeta has no "pack.pack_format"')
        else:
            report.note(f"pack.mcmeta pack_format={pack['pack_format']}")

    toml_entry = "META-INF/mods.toml"
    if check(toml_entry, names, report):
        toml = zf.read(toml_entry).decode("utf-8", "replace")
        for required in ("modLoader", "loaderVersion", 'modId="mcai"', "version="):
            if required not in toml:
                report.fail(f"mods.toml does not contain {required!r}")
        if "${version}" in toml:
            report.fail("mods.toml still contains the ${version} placeholder "
                        "(processResources did not expand it)")
        if 'modId="minecraft"' not in toml:
            report.fail("mods.toml declares no dependency on minecraft")

    if "fabric.mod.json" in names:
        report.fail("Forge jar contains fabric.mod.json - wrong branch or stale resource")

    if "assets/mcai/icon.png" not in names:
        report.note("no assets/mcai/icon.png (the mod-list logo will be missing)")


def check_fabric(zf, names, report: Report) -> None:
    entry = "fabric.mod.json"
    if check(entry, names, report):
        try:
            mod = json.loads(zf.read(entry).decode("utf-8"))
        except json.JSONDecodeError as error:
            report.fail(f"fabric.mod.json is not valid JSON: {error}")
            return
        if mod.get("id") != "mcai":
            report.fail(f'fabric.mod.json id is {mod.get("id")!r}, expected "mcai"')
        if not mod.get("version"):
            report.fail("fabric.mod.json has no version")
        depends = mod.get("depends") or {}
        if "fabricloader" not in depends:
            report.fail("fabric.mod.json does not depend on fabricloader")

    if "META-INF/mods.toml" in names:
        report.fail("Fabric jar contains META-INF/mods.toml - wrong branch or stale resource")


def check_one(jar: str) -> bool:
    report = Report(jar)
    print(f"checking {os.path.basename(jar)}")

    with zipfile.ZipFile(jar) as zf:
        names = set(zf.namelist())
        is_forge = "META-INF/mods.toml" in names
        is_fabric = "fabric.mod.json" in names

        if is_forge and is_fabric:
            report.fail("jar contains BOTH mods.toml and fabric.mod.json")
        elif not is_forge and not is_fabric:
            report.fail("jar is neither a Forge nor a Fabric mod (no mods.toml, no fabric.mod.json)")
        else:
            print(f"  detected: {'Forge' if is_forge else 'Fabric'}")

        check_common(zf, names, report)
        if is_forge:
            check_forge(zf, names, report)
        elif is_fabric:
            check_fabric(zf, names, report)

    for note in report.notes:
        print(f"  note: {note}")
    for problem in report.problems:
        print(f"  [FAIL] {problem}")

    if report.problems:
        print(f"  RESULT: FAILED ({len(report.problems)} problem(s))")
        return False
    print("  RESULT: OK")
    return True


def main() -> int:
    jars = sys.argv[1:] or find_jars()
    if not jars:
        print(f"no jars to check (looked in {LIBS})")
        return 1

    ok = True
    for jar in jars:
        if not os.path.isfile(jar):
            print(f"not found: {jar}")
            ok = False
            continue
        ok = check_one(jar) and ok
        print()

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
