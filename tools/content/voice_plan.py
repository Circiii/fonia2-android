#!/usr/bin/env python3
"""Plans the Foni voice clips (I5): which keys to synthesise, in which order, packed into batches.

    uv run tools/content/voice_plan.py [--groups lines,words-s,...] [--max-chars N] [--out plan.json]

Every voice key of the content that is not "human-needed" is a candidate; one text = one clip (keys that share a
text share the file). Groups, in priority order: lines (foni_lines.json), words of S and Ș, hold prompts,
chapter openings, the other sounds' words, sentences, missions, stories, twisters. A batch is up to `--batch`
texts joined by blank lines; voice_split.py later cuts it at the longest pauses and checks every piece.
Already generated keys (voice/index.json) are skipped.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
V2 = ROOT / "spec" / "content" / "v2"
VOICE = ROOT / "spec" / "content" / "voice"

ORDER = ["s", "ș", "z", "j", "ț", "ce", "ge", "r", "l"]
SLUG = {"s": "s", "ș": "sh", "z": "z", "j": "j", "ț": "ts", "ce": "ce", "ge": "ge", "r": "r", "l": "l"}


def speak_as(kind: str, text: str) -> str:
    """What is sent to the synthesiser: a lone word gets a capital and a full stop, so it is said as a whole
    utterance and not as part of a list."""
    if kind == "word":
        return text[:1].upper() + text[1:] + "."
    return text


def collect() -> dict[str, list[dict]]:
    index = json.loads((V2 / "sounds.json").read_text(encoding="utf-8"))
    groups: dict[str, list[dict]] = {}
    seen: dict[str, str] = {}

    def add(group: str, key: str, text: str, kind: str, sound: str | None = None) -> None:
        if not key or key == "human-needed" or key in seen:
            return
        seen[key] = text
        groups.setdefault(group, []).append({"key": key, "text": text, "kind": kind, "sound": sound,
                                             "speak": speak_as(kind, text)})

    lines = json.loads((V2 / "foni_lines.json").read_text(encoding="utf-8"))["lines"]
    for line in lines:
        add("lines", line["voice"], line["text"], "line")
    files = {e["id"]: json.loads((V2 / e["file"]).read_text(encoding="utf-8")) for e in index["sounds"]}
    for sound in ORDER:
        data = files[sound]
        group = f"words-{SLUG[sound]}"
        for item in data["items"]:
            if item["type"] in ("word", "listen"):
                add(group, item["voice"], item["text"], "word", sound)
            elif item["type"] == "pair":
                add(group, item["voiceA"], item["a"], "word", sound)
                add(group, item["voiceB"], item["b"], "word", sound)
    for sound in ORDER:
        for item in files[sound]["items"]:
            if item["type"] == "hold":
                add("prompts", item["promptVoice"], item["prompt"], "line", sound)
    for sound in ORDER:
        for entry in files[sound]["hero"]["chapters"].values():
            add("openings", entry["voice"], entry["text"], "line", sound)
    for sound in ORDER:
        for item in files[sound]["items"]:
            if item["type"] == "sentence":
                add("sentences", item["voice"], item["text"], "sentence", sound)
    for sound in ORDER:
        for item in files[sound]["items"]:
            if item["type"] == "mission":
                add("missions", item["voice"], item["text"], "line", sound)
            elif item["type"] == "story":
                for key, line in zip(item["lineVoices"], item["lines"]):
                    add("stories", key, line, "sentence", sound)
    for t in json.loads((V2 / "twisters.json").read_text(encoding="utf-8"))["twisters"]:
        add("twisters", t["voice"], t["text"], "sentence")
    return groups


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--groups", default="")
    parser.add_argument("--batch", type=int, default=20)
    parser.add_argument("--chars", type=int, default=330)
    parser.add_argument("--out", default="")
    args = parser.parse_args()
    groups = collect()
    done = set()
    index_path = VOICE / "index.json"
    if index_path.exists():
        index = json.loads(index_path.read_text(encoding="utf-8"))
        # Done = a checked clip whose text is still the content's text; failed or changed ones are planned again.
        current = {e["key"]: e["text"] for g in groups.values() for e in g}
        done = {k for k, c in index.get("clips", {}).items() if c.get("checked") and current.get(k) == c["text"]}
        done |= set(index.get("dropped", {}))
    texts_done: set[str] = set()
    wanted = [g for g in args.groups.split(",") if g] or list(groups)
    total = 0
    batches = []
    for name in groups:
        todo = [e for e in groups[name] if e["key"] not in done]
        chars = sum(len(e["speak"]) for e in todo)
        print(f"{name:12s} {len(groups[name]):4d} keys, todo {len(todo):4d}, {chars:6d} chars")
        if name not in wanted:
            continue
        total += chars
        # One clip per distinct spoken text; the other keys with the same text reuse it.
        unique = []
        for e in todo:
            if e["speak"] in texts_done:
                continue
            texts_done.add(e["speak"])
            unique.append(e)
        chunk: list[dict] = []
        for e in unique + [None]:
            size = sum(len(x["speak"]) for x in chunk)
            if chunk and (e is None or len(chunk) >= args.batch or size + len(e["speak"]) > args.chars):
                # The ElevenLabs tool names its file after the first 5 characters: start with a text whose first
                # 5 characters are file-name safe and do not reach the blank-line separator.
                safe = [x for x in chunk if len(x["speak"]) >= 5 and not set(x["speak"][:5]) & set('?:*"<>|/\\')]
                if safe and safe[0] is not chunk[0]:
                    chunk.remove(safe[0])
                    chunk.insert(0, safe[0])
                batches.append({"group": name, "items": chunk, "text": "\n\n".join(x["speak"] for x in chunk)})
                chunk = []
            if e is not None:
                chunk.append(e)
    print(f"selected: {total} chars in {len(batches)} batches")
    if args.out:
        Path(args.out).write_text(json.dumps({"batches": batches}, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
