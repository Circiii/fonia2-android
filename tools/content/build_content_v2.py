#!/usr/bin/env python3
"""Builds spec/content/v2/*.json from the authoring source (content_src.py, lines_src.py) and spec/feedback.json.

    uv run tools/content/build_content_v2.py

Derived fields (ids, voice keys, syllables, difficulty, otherSounds, cluster, ageBand) are computed here with the
G2P helper, so the authoring source stays short. Then run check_content_v2.py.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import content_src as src  # noqa: E402
import lines_src as lines  # noqa: E402
import ro_g2p as g2p  # noqa: E402

ROOT = HERE.parent.parent
OUT = ROOT / "spec" / "content" / "v2"
SCHEMA = 2

WORD_RUNG_CODE = {"wordsInitial": "wi", "wordsMedial": "wm", "wordsFinal": "wf", "clusters": "wc"}
WORD_RUNG_POS = {"wordsInitial": "initial", "wordsMedial": "medial", "wordsFinal": "final", "clusters": "cluster"}
ISOLATED = re.compile(r"(?i:\b([a-zăâîșț])\1{2,}\b|\b([a-zăâîșț]{1,2})(-\2){2,}\b)"
                      r"|(?<![\w-])[bcdfghjklmnpqrstvwxzșț](?![\w-])"
                      r"|(?:[:,]|\bapoi)\s+(?:ce|ge|ci|gi)\b")


def slugify(text: str) -> str:
    table = {"ă": "ae", "â": "y", "î": "y", "ș": "sh", "ț": "tz"}
    lowered = "".join(table.get(c, c) for c in text.lower())
    return re.sub(r"[^a-z0-9]+", "-", lowered).strip("-")


def voice_for_text(key: str, text: str) -> str:
    """Q9: text with an isolated sound or a bare syllable chain is never synthesised."""
    return "human-needed" if ISOLATED.search(text) else key


def parse_words(spec: str) -> list[dict]:
    out = []
    for token in [t.strip() for t in spec.split(";") if t.strip()]:
        parts = token.split()
        word = parts[0]
        emoji = None
        check = False
        for extra in parts[1:]:
            if extra == "V":
                check = True
            else:
                emoji = extra
        out.append({"text": word, "emoji": emoji, "needsTherapistCheck": check})
    return out


def other_sounds(text: str, sound: str) -> list[str]:
    found = g2p.sounds_in(text) - {sound}
    return [s for s in src.ORDER if s in found]


def difficulty(text: str, sound: str, rung: str) -> int:
    syl = sum(g2p.syllable_count(w) for w in g2p.words_of(text))
    others = other_sounds(text, sound)
    cluster = g2p.in_cluster(text, sound)
    score = 1
    if syl >= 3 or others or cluster or rung == "clusters":
        score = 2
    if syl >= 4 or len(others) >= 2 or (others and (cluster or rung == "clusters")):
        score = 3
    return score


def age_band(text: str, sound: str) -> str:
    """6+ for long words (4+ syllables) or words with two other hard sounds; the rest is for every age (other hard
    sounds alone only push a word to the second half of a block, see LessonBuilder)."""
    syl = sum(g2p.syllable_count(w) for w in g2p.words_of(text))
    return "6+" if syl >= 4 or len(other_sounds(text, sound)) >= 2 else "4+"


def review() -> dict:
    return {"status": "draft", "by": None, "date": None}


def build_sound(sound: str, feedback_kinds: dict) -> dict:
    spec = src.SOUNDS[sound]
    slug = src.SLUG[sound]
    hero = spec["hero"]
    letter = spec["letter"]
    chapters = {}
    for rung in src.RUNGS:
        if rung == "listen":
            text = hero["listen"]
        elif rung == "isolated":
            text = hero["isolated"]
        else:
            text = src.CHAPTER_TEMPLATES[rung].format(name=hero["name"], letter=letter)
        key = f"open.{slug}.{rung}"
        chapters[rung] = {"key": key, "text": text, "voice": voice_for_text(key, text)}

    items: list[dict] = []
    emoji_of: dict[str, str | None] = {}

    # Listen: Foni says the word right or mixed up; the mixed-up form needs a human recording (chapter 06 §7.5).
    for n, token in enumerate([t.strip() for t in spec["listen"].split(";") if t.strip()], 1):
        pair, kind = token.split(":")
        target, foil = pair.split(">")
        items.append({
            "id": f"{slug}.l.{n}", "type": "listen", "rung": "listen", "text": target, "foil": foil, "mimics": kind,
            "emoji": None, "icon": slugify(target), "voice": f"w.{slugify(target)}", "foilVoice": "human-needed",
            "difficulty": 1, "ageBand": "4+", "needsTherapistCheck": False, "review": review(), "source": "original",
        })

    for n, prompt in enumerate(spec["hold"]["prompts"], 1):
        hid = f"{slug}.h.{n}"
        items.append({
            "id": hid, "type": "hold", "rung": "isolated", "text": spec["hold"]["model"], "mode": spec["holdMode"],
            "prompt": prompt, "promptVoice": f"{hid}.prompt", "voice": "human-needed",
            "difficulty": n, "ageBand": "4+", "needsTherapistCheck": False, "review": review(), "source": "original",
        })

    for shape, rung, field in (("CV", "syllables", "cv"), ("VC", "back", "vc"), ("VCV", "back", "vcv")):
        for text in spec[field].split():
            items.append({
                "id": f"{slug}.y.{shape.lower()}.{slugify(text)}", "type": "syllable", "rung": rung, "text": text,
                "shape": shape, "voice": "human-needed", "difficulty": {"CV": 1, "VC": 2, "VCV": 2}[shape],
                "ageBand": "4+", "needsTherapistCheck": False, "review": review(), "source": "original",
            })

    for rung in ("wordsInitial", "wordsMedial", "wordsFinal", "clusters"):
        words = parse_words(spec[rung])
        built = []
        for w in words:
            text = w["text"]
            ws = slugify(text)
            emoji_of.setdefault(text.lower(), w["emoji"])
            d = difficulty(text, sound, rung)
            built.append({
                "id": f"{slug}.{WORD_RUNG_CODE[rung]}.{ws}", "type": "word", "rung": rung, "text": text,
                "pos": WORD_RUNG_POS[rung], "emoji": w["emoji"], "icon": ws, "voice": f"w.{ws}",
                "syllables": sum(g2p.syllable_count(x) for x in g2p.words_of(text)), "difficulty": d,
                "ageBand": age_band(text, sound), "otherSounds": other_sounds(text, sound),
                "cluster": g2p.in_cluster(text, sound), "needsTherapistCheck": w["needsTherapistCheck"],
                "review": review(), "source": "original",
            })
        built.sort(key=lambda item: item["difficulty"])  # stable: authoring order inside a difficulty
        items.extend(built)

    for token in [t.strip() for t in spec["pairs"].split(";") if t.strip()]:
        parts = token.split()
        a, b = parts[0].split("/")
        contrast = parts[1]
        check = len(parts) > 2 and parts[2] == "V"
        items.append({
            "id": f"{slug}.p.{slugify(a)}-{slugify(b)}", "type": "pair", "rung": "pairs", "text": f"{a} / {b}",
            "a": a, "b": b, "contrast": contrast,
            "emojiA": src.PAIR_EMOJI.get(a, emoji_of.get(a)), "emojiB": src.PAIR_EMOJI.get(b, emoji_of.get(b)),
            "voiceA": f"w.{slugify(a)}", "voiceB": f"w.{slugify(b)}", "difficulty": 2, "ageBand": "4+",
            "needsTherapistCheck": check, "review": review(), "source": "original",
        })

    for n, text in enumerate(spec["sentences"], 1):
        count = len(g2p.words_of(text))
        sid = f"{slug}.n.{n}"
        items.append({
            "id": sid, "type": "sentence", "rung": "sentences", "text": text, "words": count,
            "voice": voice_for_text(sid, text), "difficulty": 1 if count <= 4 else 2,
            "ageBand": "4+" if count <= 6 else "7+", "needsTherapistCheck": False, "review": review(),
            "source": "original",
        })

    for n, (pictures, story_lines) in enumerate(spec["stories"], 1):
        rid = f"{slug}.r.{n}"
        items.append({
            "id": rid, "type": "story", "rung": "retell", "text": " ".join(story_lines), "pictures": pictures,
            "lines": story_lines, "lineVoices": [f"{rid}.{i}" for i in range(1, len(story_lines) + 1)],
            "voice": None, "difficulty": 2, "ageBand": "4+", "needsTherapistCheck": False, "review": review(),
            "source": "original",
        })

    for n, text in enumerate(spec["missions"], 1):
        mid = f"{slug}.m.{n}"
        items.append({
            "id": mid, "type": "mission", "rung": "everyday", "text": text, "voice": voice_for_text(mid, text),
            "difficulty": 1, "ageBand": "4+", "needsTherapistCheck": False, "review": review(), "source": "original",
        })

    # Listen items: picture of the target word.
    for item in items:
        if item["type"] == "listen":
            item["emoji"] = emoji_of.get(item["text"].lower()) or src.PAIR_EMOJI.get(item["text"])

    errors = [{"kind": k["kind"], "instructionKey": k["instructionKey"], "adultKey": k["adultKey"]}
              for k in feedback_kinds.get(sound, [])]

    return {
        "schema": SCHEMA, "contentVersion": src.CONTENT_VERSION, "sound": sound, "slug": slug, "letter": letter,
        "graphemes": spec["graphemes"],
        "hero": {"name": hero["name"], "who": hero["who"], "emoji": hero["emoji"], "says": hero["says"],
                 "saysVoice": "human-needed", "line": hero["line"], "lineVoice": "human-needed", "chapters": chapters},
        "voiced": spec["voiced"], "holdMode": spec["holdMode"],
        "confusions": src.CONFUSIONS[sound], "howParent": spec["howParent"], "warmups": spec["warmups"],
        "rungs": src.RUNGS, "plan": src.PLAN[sound], "errors": errors, "items": items,
    }


def build_twisters() -> dict:
    out = []
    for n, (sounds, text, source, folk_ref, age) in enumerate(src.TWISTERS, 1):
        tid = f"t.{n}"
        sound_list = sounds.split(",")
        syl = sum(g2p.syllable_count(w) for w in g2p.words_of(text))
        out.append({
            "id": tid, "type": "twister", "rung": "twister", "sounds": sound_list, "text": text,
            "source": source, "folkRef": folk_ref, "tempos": ["slow", "step"], "ageBand": age,
            "difficulty": 1 if syl <= 8 else (2 if syl <= 12 else 3), "syllables": syl,
            "voice": voice_for_text(tid, text), "needsTherapistCheck": source == "folk" and age == "7+",
            "review": review(),
        })
    return {"schema": SCHEMA, "contentVersion": src.CONTENT_VERSION, "twisters": out}


def build_lines(feedback: dict) -> dict:
    out = []
    for key, category, when, text, max_seconds in lines.LINES:
        out.append({"key": key, "category": category, "when": when, "text": text, "maxSeconds": max_seconds,
                    "voice": voice_for_text(key, text)})
    for wid, say in src.WARMUPS.items():
        key = f"warm.{wid}"
        out.append({"key": key, "category": "warmup", "when": f"warm-up {wid}", "text": say, "maxSeconds": 4.0,
                    "voice": voice_for_text(key, say)})
    # Error-kind instructions and verdict lines: the child texts of spec/feedback.json, same keys.
    for kind in feedback["kinds"]:
        out.append({"key": kind["instructionKey"], "category": "errorKind", "when": f"{kind['sound']}:{kind['kind']}",
                    "text": kind["child"], "maxSeconds": 2.5,
                    "voice": voice_for_text(kind["instructionKey"], kind["child"]), "sound": kind["sound"],
                    "kind": kind["kind"]})
    for verdict, entry in feedback["verdicts"].items():
        out.append({"key": entry["instructionKey"], "category": "verdict", "when": verdict, "text": entry["child"],
                    "maxSeconds": 1.5, "voice": voice_for_text(entry["instructionKey"], entry["child"])})
    for reason, entry in feedback["retake"].items():
        out.append({"key": entry["instructionKey"], "category": "retake", "when": reason, "text": entry["child"],
                    "maxSeconds": 2.5, "voice": voice_for_text(entry["instructionKey"], entry["child"])})
    return {"schema": SCHEMA, "contentVersion": src.CONTENT_VERSION, "feedbackVersion": feedback["version"],
            "lines": out}


def build_parent() -> dict:
    return {
        "schema": SCHEMA, "contentVersion": src.CONTENT_VERSION,
        "cards": lines.PARENT_CARDS, "tips": lines.TIPS, "summaries": lines.SUMMARIES, "reminders": lines.REMINDERS,
        "privacy": lines.PRIVACY, "rungNames": lines.RUNG_NAMES, "positionNames": lines.POSITION_NAMES,
        "partners": {s: src.CONFUSIONS[s][0] for s in src.ORDER},
    }


def build_lesson() -> dict:
    """F2 template and the hidden adaptation (N11), as data. The builder in :core:lessons reads only this."""
    return {
        "schema": SCHEMA, "contentVersion": src.CONTENT_VERSION,
        "template": {
            "opening": [0, 15], "warmup": [15, 45], "block1": [50, 105], "move": [105, 120],
            "block2": [120, 170], "block3": [175, 220], "final": [220, 240],
            "warmupMaxSeconds": 60, "warmupExercises": 2,
            "oneMore": {"block": 6, "seconds": 90},
            "longPauseDays": 7, "surpriseSlot": [4, 6],
        },
        "blocks": {
            "listen": {"block1": None, "block2": 4, "block3": 4},
            "warmup": {"block1": None, "block2": 2, "block3": 1},
            "isolated": {"block1": "warmup", "block2": 5, "block3": 5},
            "syllables": {"block1": "isolated", "block2": 4, "block3": 4},
            "back": {"block1": "syllables", "block2": 4, "block3": 4},
            "wordsInitial": {"block1": "syllables", "block2": 9, "block3": 9},
            "wordsMedial": {"block1": "syllables", "block2": 9, "block3": 9},
            "wordsFinal": {"block1": "syllables", "block2": 9, "block3": 9},
            "clusters": {"block1": "syllables", "block2": 9, "block3": 9},
            "pairs": {"block1": "words", "block2": 3, "block3": 2},
            "sentences": {"block1": "words", "block2": 3, "block3": 3},
            "twister": {"block1": "sentences", "block2": 1, "block3": 1},
            "retell": {"block1": "sentences", "block2": 1, "block3": 1},
            "everyday": {"block1": "words", "block2": 1, "block3": 3},
        },
        "block1Counts": {"warmup": 2, "isolated": 6, "syllables": 4, "words": 6, "sentences": 3},
        "takes": {"listen": 1, "hold": 1, "syllables": 1, "words": 1, "pairs": 1, "sentence": 1, "twister": 2,
                  "retell": 1, "mission": 1, "warmup": 3},
        "adaptation": {
            "targetLow": 0.80, "targetHigh": 0.85, "window": 10,
            "harderAbove": 0.90, "harderBlocks": 2, "easierBelow": 0.70,
            "firstSessionNewShare": 0.5,
            "levels": {
                "easier": {"holdSeconds": 1.0, "trainLength": 2, "newShare": 0.15, "modelBeforeEach": True,
                           "tempo": "slow"},
                "steady": {"holdSeconds": 1.5, "trainLength": 3, "newShare": 0.30, "modelBeforeEach": False,
                           "tempo": "step"},
                "harder": {"holdSeconds": 3.0, "trainLength": 5, "newShare": 0.40, "modelBeforeEach": False,
                           "tempo": "step"},
            },
        },
        "review": {"intervals": [1, 2, 4, 8, 16], "maxPerSession": 3},
    }


def write(path: Path, data: dict) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, ensure_ascii=False, indent=1) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> int:
    feedback = json.loads((ROOT / "spec" / "feedback.json").read_text(encoding="utf-8"))
    by_sound: dict[str, list] = {}
    for kind in feedback["kinds"]:
        by_sound.setdefault(kind["sound"], []).append(kind)

    files: dict[str, str] = {}
    index = []
    for sound in src.ORDER:
        data = build_sound(sound, by_sound)
        rel = f"sounds/{src.SLUG[sound]}.json"
        files[rel] = write(OUT / rel, data)
        index.append({"id": sound, "slug": src.SLUG[sound], "letter": data["letter"], "file": rel,
                      "hero": data["hero"]["name"], "emoji": data["hero"]["emoji"], "holdMode": data["holdMode"],
                      "voiced": data["voiced"], "confusions": data["confusions"]})
    files["twisters.json"] = write(OUT / "twisters.json", build_twisters())
    files["foni_lines.json"] = write(OUT / "foni_lines.json", build_lines(feedback))
    files["parent.json"] = write(OUT / "parent.json", build_parent())
    files["lesson.json"] = write(OUT / "lesson.json", build_lesson())
    files["sounds.json"] = write(OUT / "sounds.json", {
        "schema": SCHEMA, "contentVersion": src.CONTENT_VERSION, "rungs": src.RUNGS, "sounds": index,
        "warmups": [{"id": k, "say": v} for k, v in src.WARMUPS.items()],
        "files": ["twisters.json", "foni_lines.json", "parent.json", "lesson.json"],
    })
    write(OUT / "content.lock.json", {"schema": SCHEMA, "contentVersion": src.CONTENT_VERSION,
                                      "sha256": dict(sorted(files.items()))})
    print(f"wrote {len(files) + 1} files to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
