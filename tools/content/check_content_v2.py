#!/usr/bin/env python3
"""Checks the Fonia 2 content (spec/content/v2) against the rules of chapter 06 §3.4 and the task rules.

    uv run tools/content/check_content_v2.py [--quiet]

Fails (exit 1) on any error; warnings are printed but do not fail. Rules:
- schema: files, required fields, known SoundId / Rung / ErrorKind values, unique ids, lock checksums;
- orthography: no cedilla Ş/Ţ, no ã, no em dash; banned words (child and parent texts, plus tools/lint_copy.py);
- position by pronunciation (ro_g2p): the target is really initial / medial / final / next to a consonant,
  with the traps ce/ci vs che/chi, ge/gi vs ghe/ghi, x = [ks], silent final i, ț vs t;
- the typical confusion partner never appears in the early rungs (syllables … wordsFinal);
- difficulty rises inside each word list; otherSounds matches the G2P;
- pairs differ by exactly one phoneme, the contrast being the target and its partner;
- lengths: child lines ≤ 8 words, chapter lines ≤ 12 words per sentence, practice sentences ≤ 6 (4+) / 8 (7+);
- counts per rung against the plan (N16: fewer than 12 is a warning);
- folk twisters carry folkRef; error-kind lines equal spec/feedback.json; Q9: isolated sounds and syllables are
  never synthesised; the voice index points at existing, checked files.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ro_g2p as g2p  # noqa: E402

ROOT = HERE.parent.parent
V2 = ROOT / "spec" / "content" / "v2"
VOICE = ROOT / "spec" / "content" / "voice"

SOUNDS = ["s", "ș", "z", "j", "ț", "ce", "ge", "r", "l"]
RUNGS = ["listen", "warmup", "isolated", "syllables", "back", "wordsInitial", "wordsMedial", "wordsFinal",
         "clusters", "pairs", "sentences", "twister", "retell", "everyday"]
EARLY = {"syllables", "back", "wordsInitial", "wordsMedial", "wordsFinal"}
ERROR_KINDS = ["omission", "stopping", "devoicing", "placeSwap", "lateral", "interdental", "deaffrication",
               "affricatePlace", "rToL", "rToGlide", "rToVowel", "rUvular", "lToGlide", "lToR", "weakOrShort"]
TYPES = {"listen", "hold", "syllable", "word", "pair", "sentence", "story", "mission"}
POS_RUNG = {"wordsInitial": "initial", "wordsMedial": "medial", "wordsFinal": "final", "clusters": "cluster"}
CONTRASTS = set(SOUNDS) | {"t", "d", "k", "i", "∅"}
CONTRAST_PHONEME = {**g2p.SOUND_PHONEME, "t": "t", "d": "d", "k": "k", "i": "i", "∅": "∅"}

FORBIDDEN = re.compile(r"(?i)\b(greșit\w*|greșeal\w*|diagnostic\w*|terapi\w*|tratează|tratament\w*|vindecă|"
                       r"risc\w*|severitat\w*|tulburar\w*|metoda carmen ivanov)\b")
CEDILLA = "ŞşŢţ"
ISOLATED = re.compile(r"(?i:\b([a-zăâîșț])\1{2,}\b|\b([a-zăâîșț]{1,2})(-\2){2,}\b)"
                      r"|(?<![\w-])[bcdfghjklmnpqrstvwxzșț](?![\w-])"
                      r"|(?:[:,]|\bapoi)\s+(?:ce|ge|ci|gi)\b")


def load_lint_patterns():
    path = ROOT / "tools" / "lint_copy.py"
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location("lint_copy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return getattr(module, "BANNED", None)


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.caught: dict[str, int] = {}

    def error(self, rule: str, message: str) -> None:
        self.errors.append(f"[{rule}] {message}")
        self.caught[rule] = self.caught.get(rule, 0) + 1

    def warn(self, rule: str, message: str) -> None:
        self.warnings.append(f"[{rule}] {message}")


def words(text: str) -> list[str]:
    return g2p.words_of(text)


def sentences_of(text: str) -> list[str]:
    return [s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s]


def walk_strings(node, where: str):
    if isinstance(node, str):
        yield where, node
    elif isinstance(node, dict):
        for key, value in node.items():
            yield from walk_strings(value, f"{where}.{key}")
    elif isinstance(node, list):
        for i, value in enumerate(node):
            yield from walk_strings(value, f"{where}[{i}]")


# Keys whose values are technical, not copy: skipped by the banned-word check.
TECH_KEYS = re.compile(r"\.(id|key|voice|voiceA|voiceB|promptVoice|foilVoice|lineVoices\[\d+\]|icon|file|slug|rung|"
                       r"type|pos|shape|mode|mimics|contrast|sound|sounds\[\d+\]|kind|instructionKey|adultKey|"
                       r"category|source|folkRef|status|ageBand|when|contentVersion|feedbackVersion)$")


def check_text_rules(report: Report, name: str, data, lint) -> None:
    for where, text in walk_strings(data, name):
        for ch in text:
            if ch in CEDILLA:
                report.error("cedilla", f"{where}: cedilla {ch!r} in {text!r}; use comma-below Ș/Ț")
            elif ch in "ãÃ":
                report.error("cedilla", f"{where}: 'ã' in {text!r}; use 'ă'")
            elif ch == "—":
                report.error("dash", f"{where}: em dash in {text!r}")
        if TECH_KEYS.search(where):
            continue
        for match in FORBIDDEN.finditer(text):
            report.error("forbidden", f"{where}: forbidden word {match.group(0)!r} in {text!r}")
        if lint is not None:
            for match in lint.finditer(text):
                report.error("forbidden", f"{where}: banned wording {match.group(0)!r} (tools/lint_copy.py) in {text!r}")


def check_word(report: Report, sound: str, item: dict, confusions: list[str]) -> None:
    text = item["text"]
    rung = item["rung"]
    want = POS_RUNG[rung]
    where = item["id"]
    found = g2p.positions(text, sound)
    if want == "cluster":
        if not g2p.in_cluster(text, sound):
            report.error("position", f"{where}: {text!r} has no {sound.upper()} next to another consonant "
                                     f"(phonemes {g2p.core(g2p.phonemes(text))})")
    elif want not in found:
        hint = ""
        low = text.lower()
        if sound in ("ce", "ge") and re.search(r"[cg]h[ei]", low):
            hint = " (che/chi and ghe/ghi are hard [k]/[g])"
        elif low.endswith("i") and want == "medial":
            hint = " (a final i after a consonant is silent, so the sound is final)"
        elif sound == "ț" and "ț" not in low:
            hint = " (t is not ț)"
        report.error("position", f"{where}: {text!r} has {sound.upper()} at {sorted(found) or 'nowhere'}, "
                                 f"not {want}{hint}")
    computed = [s for s in SOUNDS if s in (g2p.sounds_in(text) - {sound})]
    if item.get("otherSounds") != computed:
        report.error("otherSounds", f"{where}: otherSounds {item.get('otherSounds')} but G2P finds {computed}")
    if rung in EARLY:
        bad = [s for s in computed if s in confusions]
        if bad:
            report.error("confusion", f"{where}: {text!r} in the early rung {rung} contains the confusion "
                                      f"partner {bad}")
    if computed and item.get("difficulty", 1) < 2:
        report.error("difficulty", f"{where}: other hard sounds {computed} but difficulty 1")
    if "x" in text.lower() and sound != "s" and "s" not in computed:
        report.error("position", f"{where}: x contains [s]")
    if not (item.get("emoji") or item.get("icon")):
        report.error("picture", f"{where}: no emoji or icon keyword")


def check_syllable(report: Report, sound: str, item: dict, confusions: list[str]) -> None:
    text, shape = item["text"], item.get("shape")
    want = {"CV": "initial", "VC": "final", "VCV": "medial"}.get(shape)
    if want is None:
        report.error("schema", f"{item['id']}: unknown syllable shape {shape!r}")
        return
    found = g2p.positions(text, sound, final_i_vowel=(shape != "VC"))
    if want not in found:
        report.error("position", f"{item['id']}: syllable {text!r} ({shape}) has {sound.upper()} at {sorted(found)}")
    if item.get("voice") != "human-needed":
        report.error("voice", f"{item['id']}: syllables are never synthesised (Q9); voice must be human-needed")
    expected_rung = "syllables" if shape == "CV" else "back"
    if item["rung"] != expected_rung:
        report.error("schema", f"{item['id']}: {shape} syllables belong to {expected_rung}")
    bad = [s for s in g2p.sounds_in(text) - {sound} if s in confusions]
    if bad:
        report.error("confusion", f"{item['id']}: syllable contains the confusion partner {bad}")


def check_pair(report: Report, sound: str, item: dict) -> None:
    a, b, contrast = item.get("a"), item.get("b"), item.get("contrast")
    if contrast not in CONTRASTS:
        report.error("schema", f"{item['id']}: unknown contrast {contrast!r}")
        return
    diff = g2p.single_substitution(a, b)
    expected = {g2p.SOUND_PHONEME[sound], CONTRAST_PHONEME[contrast]}
    if diff is None:
        report.error("pairs", f"{item['id']}: {a!r} and {b!r} differ by more than one sound")
    elif set(diff) != expected:
        report.error("pairs", f"{item['id']}: {a!r}/{b!r} differ in {diff}, expected {sorted(expected)}")
    if not (item.get("emojiA") and item.get("emojiB")):
        report.warn("picture", f"{item['id']}: a pair word has no emoji")


def check_sound_file(report: Report, sound: str, data: dict, twisters: list[dict], counts: dict) -> None:
    slug = data.get("slug")
    for field in ("schema", "contentVersion", "sound", "slug", "letter", "graphemes", "hero", "voiced", "holdMode",
                  "confusions", "howParent", "warmups", "rungs", "plan", "items"):
        if field not in data:
            report.error("schema", f"{slug}: missing field {field!r}")
    if data.get("sound") != sound:
        report.error("schema", f"{slug}: sound {data.get('sound')!r} != {sound!r}")
    if data.get("rungs") != RUNGS:
        report.error("schema", f"{slug}: rungs must be the fourteen in order")
    if data.get("holdMode") not in ("sustain", "burst"):
        report.error("schema", f"{slug}: holdMode must be sustain or burst (N9)")
    chapters = data.get("hero", {}).get("chapters", {})
    for rung in RUNGS:
        entry = chapters.get(rung)
        if not entry:
            report.error("schema", f"{slug}: hero.chapters has no {rung!r}")
            continue
        for s in sentences_of(entry["text"]):
            if len(words(s)) > 12:
                report.error("length", f"{entry['key']}: chapter sentence over 12 words: {s!r}")
        if ISOLATED.search(entry["text"]) and entry.get("voice") != "human-needed":
            report.error("voice", f"{entry['key']}: contains an isolated sound but is marked for synthesis")
    if data.get("hero", {}).get("saysVoice") != "human-needed":
        report.error("voice", f"{slug}: hero.says is an isolated sound; saysVoice must be human-needed (Q9)")

    confusions = data.get("confusions", [])
    items = data.get("items", [])
    per_rung: dict[str, list[dict]] = {}
    for item in items:
        for field in ("id", "type", "rung", "text", "review"):
            if field not in item:
                report.error("schema", f"{slug}: item without {field!r}: {item.get('id')}")
        if item.get("type") not in TYPES:
            report.error("schema", f"{item.get('id')}: unknown type {item.get('type')!r}")
            continue
        if item.get("rung") not in RUNGS:
            report.error("schema", f"{item.get('id')}: unknown rung {item.get('rung')!r}")
            continue
        if not item["id"].startswith(f"{slug}."):
            report.error("schema", f"{item['id']}: id must start with {slug}.")
        if item.get("ageBand") not in ("4+", "6+", "7+"):
            report.error("schema", f"{item['id']}: ageBand {item.get('ageBand')!r}")
        per_rung.setdefault(item["rung"], []).append(item)
        kind = item["type"]
        if kind == "word":
            check_word(report, sound, item, confusions)
        elif kind == "syllable":
            check_syllable(report, sound, item, confusions)
        elif kind == "pair":
            check_pair(report, sound, item)
        elif kind == "listen":
            if item.get("mimics") not in ERROR_KINDS:
                report.error("schema", f"{item['id']}: mimics {item.get('mimics')!r} is not an ErrorKind")
            if item.get("foilVoice") != "human-needed":
                report.error("voice", f"{item['id']}: Foni's mixed-up word is never synthesised (chapter 06 §7.5)")
            if item.get("foil") == item.get("text"):
                report.error("schema", f"{item['id']}: foil equals the target")
            if not g2p.positions(item["text"], sound):
                report.error("position", f"{item['id']}: listen word {item['text']!r} has no {sound.upper()}")
        elif kind == "hold":
            if item.get("voice") != "human-needed":
                report.error("voice", f"{item['id']}: isolated sounds are never synthesised (Q9)")
            if item.get("mode") != data.get("holdMode"):
                report.error("schema", f"{item['id']}: hold mode differs from the sound's holdMode")
            if len(words(item.get("prompt", ""))) > 8:
                report.error("length", f"{item['id']}: prompt over 8 words")
            if ISOLATED.search(item.get("prompt", "")):
                report.error("voice", f"{item['id']}: the prompt contains the isolated sound; keep it in the model")
        elif kind == "sentence":
            n = len(words(item["text"]))
            limit = 6 if item.get("ageBand") == "4+" else 8
            if n > limit:
                report.error("length", f"{item['id']}: {n} words, over {limit} for {item.get('ageBand')}")
            hits = g2p.count_sound(item["text"], sound)
            if hits == 0:
                report.error("position", f"{item['id']}: sentence without {sound.upper()}: {item['text']!r}")
            elif hits < 2:
                report.warn("density", f"{item['id']}: only one {sound.upper()} in {item['text']!r}")
        elif kind == "story":
            if len(item.get("pictures", [])) != 3 or len(item.get("lines", [])) != 3:
                report.error("schema", f"{item['id']}: a story has 3 pictures and 3 lines")
            for line in item.get("lines", []):
                if len(words(line)) > 8:
                    report.error("length", f"{item['id']}: story line over 8 words: {line!r}")
            if g2p.count_sound(item["text"], sound) < 2:
                report.warn("density", f"{item['id']}: the story barely uses {sound.upper()}")
        elif kind == "mission":
            if g2p.count_sound(item["text"], sound) == 0 and sound.upper() not in item["text"]:
                report.warn("density", f"{item['id']}: mission without {sound.upper()}: {item['text']!r}")
        if item.get("needsTherapistCheck") and item.get("review", {}).get("status") == "approved":
            pass

    # Difficulty rises inside each word list; duplicates inside a list.
    for rung, group in per_rung.items():
        texts = [i["text"].lower() for i in group if i["type"] != "hold"]
        for t in {t for t in texts if texts.count(t) > 1}:
            report.error("duplicate", f"{slug}.{rung}: {t!r} appears twice")
        if rung in POS_RUNG:
            levels = [i.get("difficulty", 1) for i in group]
            if levels != sorted(levels):
                report.error("difficulty", f"{slug}.{rung}: difficulty does not rise: {levels}")
            emojis = [i.get("emoji") for i in group if i.get("emoji")]
            for e in {e for e in emojis if emojis.count(e) > 1}:
                report.warn("picture", f"{slug}.{rung}: picture {e} used twice in one list")
            no_emoji = [i["text"] for i in group if not i.get("emoji")]
            if no_emoji:
                report.warn("picture", f"{slug}.{rung}: needs a drawing: {', '.join(no_emoji)}")
    if per_rung.get("clusters") and per_rung.get("wordsInitial"):
        avg = lambda g: sum(i["difficulty"] for i in g) / len(g)  # noqa: E731
        if avg(per_rung["clusters"]) < avg(per_rung["wordsInitial"]):
            report.error("difficulty", f"{slug}: clusters are easier on average than wordsInitial")

    # Counts against the plan.
    plan = data.get("plan", {})
    minimum = {"listen": 6, "isolated": 3, "syllables": 5, "back": 10, "pairs": 4, "sentences": 6, "retell": 3,
               "everyday": 5}
    row = {}
    for rung in RUNGS:
        n = len(per_rung.get(rung, []))
        if rung == "twister":
            n = sum(1 for t in twisters if sound in t["sounds"])
        elif rung == "warmup":
            n = len(data.get("warmups", []))
        row[rung] = n
        if rung in plan:
            approved_or_draft = n
            if approved_or_draft < plan[rung]:
                report.error("count", f"{slug}.{rung}: {n} items, plan {plan[rung]}")
            if n < 12:
                report.warn("count", f"{slug}.{rung}: {n} items, under 12 (N16 accepts it, repeated)")
        elif rung in minimum and n < minimum[rung]:
            report.error("count", f"{slug}.{rung}: {n} items, at least {minimum[rung]}")
        elif rung == "twister" and n < 2:
            report.error("count", f"{slug}.twister: {n} twisters, at least 2")
        elif rung == "warmup" and not 2 <= n <= 6:
            report.error("count", f"{slug}.warmup: {n} warm-up references")
    counts[sound] = row


def check_twisters(report: Report, data: dict) -> list[dict]:
    out = data.get("twisters", [])
    for t in out:
        if t.get("source") not in ("original", "folk"):
            report.error("source", f"{t.get('id')}: source missing or unknown")
        if t.get("source") == "folk" and not t.get("folkRef"):
            report.error("source", f"{t.get('id')}: folk twister without folkRef")
        if t.get("tempos") != ["slow", "step"]:
            report.error("schema", f"{t.get('id')}: tempos must be [slow, step] (chapter 06 §6.3)")
        hits = sum(g2p.count_sound(t["text"], s) for s in t.get("sounds", []))
        if any(s not in SOUNDS for s in t.get("sounds", [])):
            report.error("schema", f"{t.get('id')}: unknown sound in {t.get('sounds')}")
        if hits < 3:
            report.error("density", f"{t.get('id')}: a twister needs the sound at least 3 times: {t['text']!r}")
        if ISOLATED.search(t["text"]) and t.get("voice") != "human-needed":
            report.error("voice", f"{t.get('id')}: contains an isolated sound but is marked for synthesis")
    return out


def check_lines(report: Report, data: dict, feedback: dict, unlock: dict | None) -> None:
    lines = data.get("lines", [])
    keys = [l["key"] for l in lines]
    for k in {k for k in keys if keys.count(k) > 1}:
        report.error("duplicate", f"foni_lines: key {k!r} twice")
    by_key = {l["key"]: l for l in lines}
    required = ["greeting", "model", "step", "praise", "almost", "again", "unsure", "end", "errorKind", "move",
                "lock", "warmup", "verdict", "retake"]
    for category in required:
        if not any(l["category"] == category for l in lines):
            report.error("schema", f"foni_lines: no line in category {category!r}")
    for l in lines:
        n = len(words(l["text"]))
        child = l["category"] not in ("adult", "warmup")
        if child and n > 8:
            report.error("length", f"{l['key']}: child line of {n} words (max 8): {l['text']!r}")
        if ISOLATED.search(l["text"]) and l.get("voice") != "human-needed":
            report.error("voice", f"{l['key']}: contains an isolated sound but is marked for synthesis")
    # Error-kind lines are the child texts of spec/feedback.json, under the same keys.
    for kind in feedback["kinds"]:
        line = by_key.get(kind["instructionKey"])
        if line is None:
            report.error("feedback", f"foni_lines lacks {kind['instructionKey']} from spec/feedback.json")
        elif line["text"] != kind["child"]:
            report.error("feedback", f"{kind['instructionKey']}: text differs from spec/feedback.json")
        if len(words(kind["child"])) > 8:
            report.error("length", f"spec/feedback.json {kind['instructionKey']}: over 8 words")
    if unlock:
        have = {(k["sound"], k["kind"]) for k in feedback["kinds"]}
        for entry in unlock.get("entries", []):
            if (entry["sound"], entry["kind"]) not in have:
                report.error("feedback", f"spec/unlock.json names {entry['sound']}:{entry['kind']} with no message")


def check_parent(report: Report, data: dict) -> None:
    if len(data.get("cards", [])) != 6:
        report.error("schema", "parent.json: six ParentCards expected")
    covered = [r for card in data.get("cards", []) for r in card.get("rungs", [])]
    if sorted(covered) != sorted(RUNGS):
        report.error("schema", "parent.json: the cards must cover each rung exactly once")
    if len(data.get("tips", [])) != 10:
        report.error("schema", "parent.json: ten tips expected")
    for group in ("summaries", "reminders"):
        for entry in data.get(group, []):
            used = set(re.findall(r"\{(\w+)\}", entry["text"]))
            if used != set(entry.get("vars", [])):
                report.error("schema", f"parent.json {group}.{entry['id']}: vars {entry.get('vars')} != {sorted(used)}")
    if set(data.get("rungNames", {})) != set(RUNGS):
        report.error("schema", "parent.json: rungNames must name the fourteen rungs")


def check_voice(report: Report, referenced: dict[str, str]) -> dict:
    index_path = VOICE / "index.json"
    stats = {"clips": 0, "referenced": len(referenced), "covered": 0}
    if not index_path.exists():
        report.warn("voice", "spec/content/voice/index.json missing: no clips yet")
        return stats
    index = json.loads(index_path.read_text(encoding="utf-8"))
    clips = index.get("clips", {})
    stats["clips"] = len(clips)
    for key, clip in clips.items():
        path = VOICE / clip["file"]
        if not path.exists():
            report.error("voice", f"voice/index.json {key}: file {clip['file']} missing")
        if not clip.get("checked"):
            report.error("voice", f"voice/index.json {key}: clip did not pass voice_check.py")
        if key in referenced and referenced[key] != clip.get("text"):
            report.error("voice", f"voice/index.json {key}: text {clip.get('text')!r} != content {referenced[key]!r}")
    stats["covered"] = sum(1 for k in referenced if k in clips)
    return stats


def referenced_voices(report: Report, sound_files: dict, twisters: list, lines: list) -> dict[str, str]:
    """voice key -> text; one key must always mean one text."""
    out: dict[str, str] = {}

    def add(key, text, where):
        if not key or key == "human-needed":
            return
        if key in out and out[key] != text:
            report.error("voice", f"{where}: voice key {key!r} means both {out[key]!r} and {text!r}")
        out.setdefault(key, text)

    for data in sound_files.values():
        for entry in data["hero"]["chapters"].values():
            add(entry["voice"], entry["text"], entry["key"])
        for item in data["items"]:
            t = item["type"]
            if t in ("word", "listen", "sentence", "mission"):
                add(item.get("voice"), item["text"], item["id"])
            elif t == "pair":
                add(item["voiceA"], item["a"], item["id"])
                add(item["voiceB"], item["b"], item["id"])
            elif t == "hold":
                add(item["promptVoice"], item["prompt"], item["id"])
            elif t == "story":
                for key, line in zip(item["lineVoices"], item["lines"]):
                    add(key, line, item["id"])
    for t in twisters:
        add(t.get("voice"), t["text"], t["id"])
    for l in lines:
        add(l.get("voice"), l["text"], l["key"])
    return out


def check_lock(report: Report) -> None:
    lock = json.loads((V2 / "content.lock.json").read_text(encoding="utf-8"))
    for rel, digest in lock.get("sha256", {}).items():
        path = V2 / rel
        if not path.exists():
            report.error("lock", f"content.lock.json names missing {rel}")
            continue
        # Hash with LF line ends: a Windows checkout (core.autocrlf) must not break the lock.
        actual = hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        if actual != digest:
            report.error("lock", f"{rel}: checksum differs from content.lock.json (rebuild)")


SELF_TEST = [
    # (sound, rung, word, rule that must fire)
    ("ce", "wordsInitial", "chei", "position"),      # che/chi is [k]
    ("ge", "wordsInitial", "ghete", "position"),     # ghe/ghi is [g]
    ("ce", "wordsMedial", "nuci", "position"),       # final i is silent: CE is final
    ("ț", "wordsInitial", "tată", "position"),       # t is not ț
    ("ce", "wordsMedial", "lăcustă", "position"),    # cu is [k]
    ("ș", "wordsInitial", "șosete", "confusion"),    # S inside an early Ș word
    ("l", "wordsInitial", "lingură", "confusion"),   # R inside an early L word
    ("s", "clusters", "sandviș", "position"),        # S is not next to a consonant
    ("ș", "wordsInitial", "taxi", "position"),       # x is [ks], no Ș
]


def self_test() -> list[str]:
    """The rules must catch the classic traps; returns the cases they missed."""
    missed = []
    for sound, rung, word, rule in SELF_TEST:
        probe = Report()
        item = {"id": f"selftest.{word}", "type": "word", "rung": rung, "text": word, "difficulty": 3, "icon": word,
                "otherSounds": [s for s in SOUNDS if s in (g2p.sounds_in(word) - {sound})]}
        check_word(probe, sound, item, json.loads('{"s":["ș","z","ț"],"ș":["s","j"],"z":["s","j"],"j":["z","ș"],'
                                                  '"ț":["s","ce"],"ce":["ț","ș","ge"],"ge":["ce","z","j"],'
                                                  '"r":["l"],"l":["r"]}')[sound])
        if rule not in probe.caught:
            missed.append(f"{sound} {rung} {word!r} should fail {rule}")
    pair = Report()
    check_pair(pair, "ț", {"id": "selftest.pair", "a": "țeapă", "b": "ceapă", "contrast": "ce", "emojiA": "x",
                           "emojiB": "x"})
    if "pairs" not in pair.caught:
        missed.append("țeapă/ceapă should fail pairs (two sounds differ)")
    return missed


def main() -> int:
    quiet = "--quiet" in sys.argv
    report = Report()
    for miss in self_test():
        report.error("selftest", miss)
    lint = load_lint_patterns()
    index = json.loads((V2 / "sounds.json").read_text(encoding="utf-8"))
    feedback = json.loads((ROOT / "spec" / "feedback.json").read_text(encoding="utf-8"))
    unlock_path = ROOT / "spec" / "unlock.json"
    unlock = json.loads(unlock_path.read_text(encoding="utf-8")) if unlock_path.exists() else None
    twisters_data = json.loads((V2 / "twisters.json").read_text(encoding="utf-8"))
    lines_data = json.loads((V2 / "foni_lines.json").read_text(encoding="utf-8"))
    parent = json.loads((V2 / "parent.json").read_text(encoding="utf-8"))
    lesson = json.loads((V2 / "lesson.json").read_text(encoding="utf-8"))

    if [s["id"] for s in index["sounds"]] != SOUNDS:
        report.error("schema", "sounds.json must list the nine SoundId values in order")
    twisters = check_twisters(report, twisters_data)
    check_text_rules(report, "twisters", twisters_data, lint)
    sound_files = {}
    counts: dict = {}
    all_ids: list[str] = [t["id"] for t in twisters]
    for entry in index["sounds"]:
        data = json.loads((V2 / entry["file"]).read_text(encoding="utf-8"))
        sound_files[entry["id"]] = data
        check_sound_file(report, entry["id"], data, twisters, counts)
        check_text_rules(report, entry["slug"], data, lint)
        all_ids += [i["id"] for i in data["items"]]
    for i in {i for i in all_ids if all_ids.count(i) > 1}:
        report.error("duplicate", f"id {i!r} used twice")
    check_lines(report, lines_data, feedback, unlock)
    check_text_rules(report, "foni_lines", lines_data, lint)
    check_parent(report, parent)
    check_text_rules(report, "parent", parent, lint)
    if set(lesson.get("blocks", {})) != set(RUNGS):
        report.error("schema", "lesson.json: blocks must cover the fourteen rungs")
    referenced = referenced_voices(report, sound_files, twisters, lines_data["lines"])
    voice_stats = check_voice(report, referenced)
    check_lock(report)

    if not quiet:
        short = ["listen", "warmup", "isolated", "syllables", "back", "wordsInitial", "wordsMedial", "wordsFinal",
                 "clusters", "pairs", "sentences", "twister", "retell", "everyday"]
        head = ["snd", "lis", "wrm", "iso", "syl", "bck", "wI", "wM", "wF", "clu", "pai", "sen", "tw", "ret", "eve"]
        print(" ".join(f"{h:>4}" for h in head))
        for sound in SOUNDS:
            row = counts.get(sound, {})
            print(" ".join([f"{sound:>4}"] + [f"{row.get(r, 0):>4}" for r in short]))
        print(f"lines: {len(lines_data['lines'])}, twisters: {len(twisters)}, voice keys: {voice_stats['referenced']}, "
              f"clips: {voice_stats['clips']}, covered: {voice_stats['covered']}")
        for w in report.warnings:
            print("warning:", w)
    for e in report.errors:
        print("ERROR:", e)
    print(f"{len(report.errors)} errors, {len(report.warnings)} warnings")
    if report.caught:
        print("caught by rule:", ", ".join(f"{k}={v}" for k, v in sorted(report.caught.items())))
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
