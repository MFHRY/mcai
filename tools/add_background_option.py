"""1.0.1: wire the switchable screen background into the remaining screens and
add its language keys.

Three separate jobs that are all mechanical:
  1. ConfigScreen / VisionCaptureScreen need renderBackground overrides
  2. 8 language files need the same 7 new keys, inserted in the same position
  3. gradle.properties needs the version bump

Run from the repository root: python tools/add_background_option.py
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "com", "example", "mcai", "client")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "mcai", "lang")

OVERRIDE = '''
    /** 界面背景跟随 {@code screen_background} 设置（默认仍是原版的暗色渐变）。 */
    @Override
    public void renderBackground(GuiGraphics context) {
        ScreenBackgrounds.render(this, context);
    }
'''

SCREENS = ["ConfigScreen.java", "VisionCaptureScreen.java"]

# key -> {lang: text}
NEW_KEYS = {
    "mcai.config.group.background": {
        "en_us": "Screen background",
        "zh_cn": "界面背景",
        "zh_tw": "介面背景",
        "ja_jp": "画面の背景",
        "ko_kr": "화면 배경",
        "ru_ru": "Фон экрана",
        "es_es": "Fondo de pantalla",
        "fr_fr": "Arrière-plan de l'écran",
    },
    "mcai.config.screen_background": {
        "en_us": "Background style",
        "zh_cn": "背景样式",
        "zh_tw": "背景樣式",
        "ja_jp": "背景スタイル",
        "ko_kr": "배경 스타일",
        "ru_ru": "Стиль фона",
        "es_es": "Estilo de fondo",
        "fr_fr": "Style d'arrière-plan",
    },
    "mcai.config.background.dim": {
        "en_us": "Dim (vanilla)",
        "zh_cn": "暗色渐变（原版）",
        "zh_tw": "暗色漸層（原版）",
        "ja_jp": "暗転（バニラ）",
        "ko_kr": "어둡게 (바닐라)",
        "ru_ru": "Затемнение (ваниль)",
        "es_es": "Oscurecido (vanilla)",
        "fr_fr": "Assombri (vanilla)",
    },
    "mcai.config.background.dirt": {
        "en_us": "Dirt",
        "zh_cn": "泥土",
        "zh_tw": "泥土",
        "ja_jp": "土",
        "ko_kr": "흙",
        "ru_ru": "Земля",
        "es_es": "Tierra",
        "fr_fr": "Terre",
    },
    "mcai.config.background.none": {
        "en_us": "Transparent",
        "zh_cn": "透明",
        "zh_tw": "透明",
        "ja_jp": "透明",
        "ko_kr": "투명",
        "ru_ru": "Прозрачный",
        "es_es": "Transparente",
        "fr_fr": "Transparent",
    },
    "mcai.config.background.custom": {
        "en_us": "Custom image",
        "zh_cn": "自定义图片",
        "zh_tw": "自訂圖片",
        "ja_jp": "カスタム画像",
        "ko_kr": "사용자 이미지",
        "ru_ru": "Своё изображение",
        "es_es": "Imagen propia",
        "fr_fr": "Image personnalisée",
    },
    "mcai.config.background_image": {
        "en_us": "Background image path",
        "zh_cn": "背景图片路径",
        "zh_tw": "背景圖片路徑",
        "ja_jp": "背景画像のパス",
        "ko_kr": "배경 이미지 경로",
        "ru_ru": "Путь к изображению фона",
        "es_es": "Ruta de la imagen de fondo",
        "fr_fr": "Chemin de l'image de fond",
    },
}

ANCHOR = "mcai.config.group.misc"


def add_overrides() -> int:
    added = 0
    for name in SCREENS:
        path = os.path.join(JAVA, name)
        if not os.path.isfile(path):
            print(f"  !! missing {name}")
            continue
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
        if "ScreenBackgrounds.render(this" in text:
            print(f"  = {name} already wired")
            continue
        # insert right before the render() method
        match = re.search(r"\n    @Override\n    public void render\(GuiGraphics", text)
        if not match:
            print(f"  !! {name}: could not find render(GuiGraphics) to anchor to")
            continue
        text = text[:match.start()] + "\n" + OVERRIDE + text[match.start():]
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        print(f"  + {name}: renderBackground override added")
        added += 1
    return added


def add_lang_keys() -> None:
    names = sorted(f for f in os.listdir(LANG) if f.endswith(".json"))
    for name in names:
        code = name[:-5]
        path = os.path.join(LANG, name)
        with io.open(path, encoding="utf-8") as handle:
            data = json.load(handle, object_pairs_hook=dict)
        if "mcai.config.screen_background" in data:
            print(f"  = {name} already has the keys")
            continue
        rebuilt = {}
        inserted = False
        for key, value in data.items():
            if key == ANCHOR and not inserted:
                for new_key, texts in NEW_KEYS.items():
                    rebuilt[new_key] = texts.get(code, texts["en_us"])
                inserted = True
            rebuilt[key] = value
        if not inserted:
            for new_key, texts in NEW_KEYS.items():
                rebuilt[new_key] = texts.get(code, texts["en_us"])
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(json.dumps(rebuilt, ensure_ascii=False, indent=2) + "\n")
        print(f"  + {name}: {len(NEW_KEYS)} keys added")


def bump_version() -> None:
    path = os.path.join(ROOT, "gradle.properties")
    with io.open(path, encoding="utf-8") as handle:
        text = handle.read()
    updated = text.replace("mod_version=1.0.0", "mod_version=1.0.1")
    if updated != text:
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(updated)
        print("  + gradle.properties: mod_version -> 1.0.1")
    else:
        print("  = gradle.properties already at 1.0.1")


def main() -> int:
    print("screens:")
    add_overrides()
    print("language files:")
    add_lang_keys()
    print("version:")
    bump_version()
    return 0


if __name__ == "__main__":
    sys.exit(main())
