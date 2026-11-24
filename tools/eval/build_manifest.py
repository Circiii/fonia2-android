# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Builds the judge's evaluation manifest (v2) from local audio. Nothing here is committed but this script.

    uv run tools/eval/build_manifest.py            (or: python -I tools/eval/build_manifest.py)

Writes tools/eval/out/manifest.jsonl: one line per JUDGMENT (a clip judged against one target), with the clip, what
the voice was asked to say, the voice and its sex/age label, the target sound, the text the learner was asked for,
level, position and the expected label ("correct", or "<kind>(<heard as>)"). The JVM runner
(speech/src/test/kotlin/tech/fonia/judge/eval/EvalV2Runner.kt) judges every line; summarize.py makes the tables.

Sources (local only):
  content  content/voice/ro + manifest.json  the prompt voice ("Andrea"), woman, ~270 words / syllables / sounds
  v1       tmp/test_audio/words_v1/<voice>   ElevenLabs 2026-11-21: Sarah, Lily (woman), Roger (man)
  v2       tmp/test_audio/words_v2/<voice>   ElevenLabs 2026-11-24: Liam (young man), Will (man), Sally (young,
                                             "children's storyteller"), Jessica (young woman), Roger extras
  aude     output/aude/clips/*.wav           six words of v1's voices recorded back through the emulator's mic path

Roles: "real" (a word judged as itself: correct), "cross" (the same audio judged against a neighbouring target, as in
v1: an S word asked as its Ș twin is a placeSwap heard as S), "said" (the voice was asked for the substituted word:
"lac" for "rac"), "burst" ("ci-ci-ci"), plus "edits" (runner-made S/Ș cut, -24 dB, burst-for-frication).
Every voice is SYNTHETIC: these numbers say nothing about children or about a real teenager's voice.
"""
from __future__ import annotations

import json
import os
import re
import sys
import unicodedata

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HSIC = os.environ.get("FONIA_HSIC", r"C:\Users\circi\Desktop\HSIC")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")

VOICES = {
    # voice: (sex, age, group)
    "content": ("f", "adult", "woman"),
    "sarah": ("f", "adult", "woman"),
    "lily": ("f", "adult", "woman"),
    "roger": ("m", "adult", "man"),
    "will": ("m", "adult", "man"),
    "liam": ("m", "young", "young"),
    "sally": ("f", "young", "young"),
    "jessica": ("f", "young", "young"),
}

GRAPHEMES = {"s": ["s"], "ș": ["ș"], "z": ["z"], "j": ["j"], "ț": ["ț"], "ce": ["ce", "ci"], "ge": ["ge", "gi"],
             "r": ["r"], "l": ["l"]}
RIVALS = {
    "s": ["ș", "z", "ț", "j", "x", "ce", "ci", "ge", "gi"],
    "ș": ["s", "z", "ț", "j", "ce", "ci", "ge", "gi"],
    "z": ["s", "ș", "ț", "j", "ce", "ci", "ge", "gi"],
    "j": ["s", "ș", "ț", "z", "ce", "ci", "ge", "gi"],
    "ț": ["s", "ș", "z", "j", "ce", "ci", "ge", "gi"],
    "ce": ["s", "ș", "z", "j", "ț", "ge", "gi"],
    "ge": ["s", "ș", "z", "j", "ț", "ce", "ci"],
    "r": ["l"],
    "l": ["r"],
}
SYLLABLE = re.compile(r"^[aeiouăâî]{0,2}(s|ș|z|j|ț|r|l|c|g)[aeiouăâî]{0,3}$")

# v1's said words ("words_v1") and v2's: spoken -> [(text asked of the learner, target, expected)].
SUBSTITUTIONS = {
    "iac": [("rac", "r", "rToGlide"), ("lac", "l", "lToGlide")],
    "maie": [("mare", "r", "rToGlide"), ("male", "l", "lToGlide")],
    "cai": [("car", "r", "rToGlide"), ("cal", "l", "lToGlide")],
    "ac": [("rac", "r", "omission"), ("lac", "l", "omission")],
    "rac": [("lac", "l", "lToR(r)")],
    "mare": [("male", "l", "lToR(r)")],
    "car": [("cal", "l", "lToR(r)")],
    "pară": [("pală", "l", "lToR(r)")],
    "cer": [("ger", "ge", "devoicing(ce)")],
    "zer": [("ger", "ge", "affricatePlace(z)")],
    "jer": [("ger", "ge", "deaffrication(j)")],
    # The voices read "fuji" the English way, [dʒ] (EVAL_v1): not a GE error, so not scored.
    "fuji": [("fugi", "ge", "n/a:englishReading")],
    "fuci": [("fugi", "ge", "devoicing(ce)")],
    "ter": [("cer", "ce", "stopping")],
    "șer": [("cer", "ce", "deaffrication(ș)")],
    "țer": [("cer", "ce", "affricatePlace(ț)")],
    "tară": [("țară", "ț", "stopping")],
    "ceară": [("țară", "ț", "affricatePlace(ce)")],
    "asă": [("ață", "ț", "deaffrication(s)")],
    "ată": [("ață", "ț", "stopping")],
    "jare": [("zare", "z", "placeSwap(j)")],
    "dare": [("zare", "z", "stopping")],
    "busă": [("buză", "z", "devoicing(s)")],
    "zoc": [("joc", "j", "placeSwap(z)")],
    "doc": [("joc", "j", "stopping")],
    # v2 (2026-11-24)
    "sar": [("zar", "z", "devoicing(s)")],
    "sap": [("țap", "ț", "deaffrication(s)")],
    "tap": [("țap", "ț", "stopping")],
    "șină": [("cină", "ce", "deaffrication(ș)")],
    "ci-ci-ci": [("gi-gi-gi", "ge", "devoicing(ce)")],
    "ge-ge-ge": [("ce-ce-ce", "ce", "n/a:voicedForCe")],
}
# Not real words (or not the word they stand for): judged only in their substitution roles.
NOT_REAL = {"iac", "maie", "ac", "jer", "fuji", "fuci", "ter", "șer", "țer", "tară", "asă", "ată", "jare", "busă",
            "zoc", "doc", "tap"}
# The ElevenLabs file names keep only the first 5 letters of the text.
V2_WORDS = ("sare sete sită soare sus masă nas si se sold șarpe șir șes șoc ușă coș și șe șold zar zebră zi buză ze "
            "sar joc jeleu jir ajutor garaj ji je țap țel ține ață colț sap tap cer cină ceas pace ci ce șină "
            "ci-ci-ci ger gem girafă fugi deget ge gi jer ge-ge-ge rac rece rid mare car tren lac male cal lemn lin "
            "lună iac").split()


def ro(text: str) -> str:
    return unicodedata.normalize("NFC", text.lower()).replace("ş", "ș").replace("ţ", "ț")


def sounds_of(word: str) -> list[str]:
    w = ro(word)
    return [s for s in GRAPHEMES if any(g in w for g in GRAPHEMES[s]) and not any(r in w for r in RIVALS[s])]


def level_of(word: str) -> str:
    w = ro(word)
    if "-" in w:
        return "syllable"
    if len(w) >= 4 and len(set(w)) == 1:
        return "isolated"
    if len(w) <= 4 and SYLLABLE.match(w):
        return "syllable"
    return "word"


def position_of(word: str, grapheme: str) -> str:
    w = ro(word)
    i = w.find(grapheme)
    if i <= 0:
        return "initial"
    if i + len(grapheme) >= len(w):
        return "final"
    return "medial"


def swap(word: str, a: str, b: str) -> str:
    w = ro(word)
    i = w.find(a)
    return w if i < 0 else w[:i] + b + w[i + len(a):]


def next_vowel(word: str, grapheme: str) -> str:
    """The letter after the target (for the vowel-context tables): 'i', 'e', 'a', … or '#' at the end / '-' none."""
    w = ro(word)
    i = w.find(grapheme)
    if i < 0:
        return "-"
    j = i + len(grapheme)
    # CE/CI/GE/GI: the vowel is part of the grapheme.
    if grapheme in ("ce", "ci", "ge", "gi"):
        return grapheme[1]
    return w[j] if j < len(w) else "#"


def judgments(clip: dict, said: str, real: bool, edits: bool) -> list[dict]:
    w = ro(said)
    out = []
    lvl = level_of(w)

    def add(text, sound, expected, role, note=""):
        g = next((g for g in GRAPHEMES[sound] if g in ro(text)), GRAPHEMES[sound][0])
        pos = "any" if lvl == "isolated" or "-" in text else position_of(text, g)
        out.append(dict(clip, text=ro(text), sound=sound, level=level_of(text), position=pos, expected=expected,
                        role=role, context=next_vowel(text, g), note=note,
                        edits=bool(edits and role == "real" and sound in ("s", "ș") and lvl == "word")))

    if real:
        for s in sounds_of(w):
            add(w, s, "correct", "burst" if "-" in w else "real")
            pos = position_of(w, next(g for g in GRAPHEMES[s] if g in w))
            if s == "s":
                add(swap(w, "s", "ș"), "ș", "placeSwap(s)", "cross")
                if pos != "final":
                    add(swap(w, "s", "z"), "z", "devoicing(s)", "cross")
                add(swap(w, "s", "ț"), "ț", "deaffrication(s)", "cross")
            elif s == "ș":
                add(swap(w, "ș", "s"), "s", "placeSwap(ș)", "cross")
                if pos != "final":
                    add(swap(w, "ș", "j"), "j", "devoicing(ș)", "cross")
            elif s == "l" and lvl != "isolated":
                add(swap(w, "l", "r"), "r", "rToL(l)", "cross")
    for text, sound, expected in SUBSTITUTIONS.get(w, []):
        if expected.startswith("n/a"):
            continue
        add(text, sound, expected, "said")
    return out


def clip(file: str, said: str, voice: str, source: str, asked: str) -> dict:
    sex, age, group = VOICES[voice]
    return dict(file=file, said=ro(said), asked=asked, voice=voice, sex=sex, age=age, group=group, source=source)


def main() -> None:
    rows: list[dict] = []
    # content/voice (the prompt voice)
    content = os.environ.get("FONIA_CONTENT_VOICE", os.path.join(ROOT, "content", "voice"))
    with open(os.path.join(content, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    for key, rel in manifest.items():
        if not key.startswith("ro|ro|"):
            continue
        w = ro(key[6:])
        if not re.fullmatch(r"[a-zăâîșț]+", w) or not sounds_of(w):
            continue
        rows += judgments(clip(os.path.join(content, rel), w, "content", "content", w), w, True, True)
    # words_v1 and words_v2
    for folder, source in (("words_v1", "v1"), ("words_v2", "v2")):
        base = os.path.join(HSIC, "tmp", "test_audio", folder)
        if not os.path.isdir(base):
            continue
        for voice in sorted(os.listdir(base)):
            d = os.path.join(base, voice)
            if not os.path.isdir(d):
                continue
            for name in sorted(os.listdir(d)):
                if not name.endswith(".mp3"):
                    continue
                stem = ro(name[:-4])
                if source == "v2":
                    short = stem.removeprefix("tts_").rsplit("_", 2)[0]
                    said = next((w for w in V2_WORDS if w == short or (len(short) == 5 and w.startswith(short))), None)
                    if said is None:
                        print("unknown v2 file", name, file=sys.stderr)
                        continue
                else:
                    said = stem
                if source == "v2" and said == "si":
                    # Dropped (2026-11-24): every new voice's "si" sits at its own Ș (centroid 4.8-6 kHz, peak 3.4-3.7 kHz,
                    # ZIPA prefers [ʃ]/[ts]/[t] to [s]); the TTS said something else, so it is no ground truth.
                    continue
                real = said not in NOT_REAL
                rows += judgments(clip(os.path.join(d, name), said, voice, source, said), said, real, True)
    # aude: v1 words recorded back through the emulator microphone path
    aude = os.path.join(HSIC, "output", "aude", "clips", "words")
    if os.path.isdir(aude):
        for name in sorted(os.listdir(aude)):
            if not name.endswith(".wav"):
                continue
            if "_" not in name or name.split("_", 1)[0] not in VOICES:
                continue
            voice, said = name[:-4].split("_", 1)
            said = ro(said)
            rows += judgments(clip(os.path.join(aude, name), said, voice, "aude", said), said, said not in NOT_REAL, False)
    os.makedirs(OUT, exist_ok=True)
    for i, r in enumerate(rows):
        r["id"] = i
    with open(os.path.join(OUT, "manifest.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    clips = {r["file"] for r in rows}
    by = {}
    for r in rows:
        k = (r["group"], "correct" if r["expected"] == "correct" else "error")
        by[k] = by.get(k, 0) + 1
    print(f"{len(rows)} judgments on {len(clips)} clips -> {os.path.join(OUT, 'manifest.jsonl')}")
    for k in sorted(by):
        print(f"  {k[0]:6s} {k[1]:8s} {by[k]}")


if __name__ == "__main__":
    main()
