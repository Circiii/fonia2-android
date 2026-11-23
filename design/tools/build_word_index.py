"""Builds design/src/main/assets/fonia/pictures/words.json: word (lower case, comma-below diacritics) -> picture code.

Sources: the v2 content in spec/content/v2 (every item with `text` + `emoji`, pairs with `a`/`emojiA`, `b`/`emojiB`)
and, when given, the old content's json/sounds.json (`text` + `picture`). Only codes that exist as a picture file
are written. Run from the repo root:

    python -I design/tools/build_word_index.py [path/to/fonia-content]
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PICTURES = os.path.join(ROOT, "design", "src", "main", "assets", "fonia", "pictures")


def norm(word):
    w = word.strip().lower()
    return w.replace("ş", "ș").replace("ţ", "ț")


def code(emoji):
    return "-".join("%x" % ord(ch) for ch in emoji if ord(ch) != 0xFE0F)


def main():
    have = {f[:-4] for f in os.listdir(PICTURES) if f.endswith(".png")}
    words = {}

    def put(text, c):
        if text and c in have:
            words.setdefault(norm(text), c)

    def walk(o):
        if isinstance(o, dict):
            if o.get("emoji") and isinstance(o.get("text"), str):
                put(o["text"], code(o["emoji"]))
            if o.get("emojiA") and o.get("a"):
                put(o["a"], code(o["emojiA"]))
            if o.get("emojiB") and o.get("b"):
                put(o["b"], code(o["emojiB"]))
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)

    for f in sorted(glob.glob(os.path.join(ROOT, "spec", "content", "v2", "**", "*.json"), recursive=True)):
        with open(f, encoding="utf-8") as fh:
            walk(json.load(fh))
    if len(sys.argv) > 1:
        with open(os.path.join(sys.argv[1], "json", "sounds.json"), encoding="utf-8") as fh:
            text = fh.read()
        for w, c in re.findall(r'"text": "([^"]+)", "picture": "([0-9a-f-]+)"', text):
            put(w, c)
    out = os.path.join(PICTURES, "words.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(dict(sorted(words.items())), fh, ensure_ascii=False, indent=0)
    print(len(words), "words ->", out)


if __name__ == "__main__":
    main()
