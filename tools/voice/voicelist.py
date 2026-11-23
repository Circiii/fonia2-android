#!/usr/bin/env python3
"""Port of fonia-app tools/missing_clips.py + tools/voices/build_list.py, over the content/ submodule.

    python -I tools/voice/voicelist.py            # writes tools/voice/voice-list.csv and missing-clips.txt
    python -I tools/voice/voicelist.py --check    # only prints the counts

voice-list.csv: every clip the app still needs (groups A practice texts, B instructions, C short prompts),
same columns as Ștefan's list. domain/src/test/resources/missing-clips.txt: the model texts (group A) with no
clip, in the format ContentTests reads. Texts in tools/voice/human-needed.txt are model texts that ElevenLabs
could not say right; they stay in missing-clips.txt and are marked for a human recording.
"""

import csv
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTENT = ROOT / "content"
JSON = CONTENT / "json"
MANIFEST = CONTENT / "voice/manifest.json"
LIST = ROOT / "tools/voice/voice-list.csv"
MISSING = ROOT / "domain/src/test/resources/missing-clips.txt"
COLUMNS = ["grup", "limba", "voce", "viteza", "text", "de_citit", "fisier", "unde"]
ENGLISH_VOICES = ["en", "en2", "en3", "en4"]
AS_SOUND = {"L": "lll", "S": "sss", "Z": "zzz", "Ș": "șșș", "J": "jjj", "Ț": "ț", "CE": "ce", "GE": "ge", "R": "rrr"}


def load(name):
    return json.loads((JSON / f"{name}.json").read_text(encoding="utf-8"))


def normalised(text):
    return " ".join(text.split()).lower()


def file_for(language, voice, text):
    digest = hashlib.sha1(normalised(text).encode("utf-8")).hexdigest()[:14]
    return f"{language}/{voice}/{digest}.mp3"


def model_texts():
    for sound in load("sounds"):
        where = f"sound {sound['id']}"
        for text in (sound["hero"]["says"], sound["hero"]["story"]):
            yield "ro", ["ro"], text, f"{where}, hero"
        for key, text in sorted((sound["hero"].get("chapters") or {}).items()):
            if text.strip():
                yield "ro", ["ro"], text.strip(), f"{where}, chapter {key}"
        if sound["isAffricate"]:
            unit = sound["graphemes"][-1]
            yield "ro", ["ro"], "-".join([unit] * 3), f"{where}, bursts"
        for group, syllables in sound["syllables"].items():
            for text in syllables:
                yield "ro", ["ro"], text, f"{where}, syllables {group}"
        for group, words in sound["words"].items():
            for word in words:
                yield "ro", ["ro"], word["text"], f"{where}, words {group}"
        for contrast in sound["contrasts"]:
            for pair in contrast["pairs"]:
                pair_where = f"{where}, pair {pair['a']['text']}/{pair['b']['text']}"
                yield "ro", ["ro"], pair["a"]["text"], pair_where
                yield "ro", ["ro"], pair["b"]["text"], pair_where
                if "spoken" in pair:
                    yield "ro", ["ro"], pair["spoken"], pair_where
        for text in sound["sentences"]:
            yield "ro", ["ro"], text, f"{where}, sentence"
        for twister in sound["twisters"]:
            yield "ro", ["ro"], twister["text"], f"{where}, twister"
        for retell in sound["retell"]:
            for text in retell["lines"]:
                yield "ro", ["ro"], text, f"{where}, retell"

    english = load("english")
    for contrast in english["contrasts"]:
        where = f"english {contrast['id']}"
        if contrast["kind"] == "minimalPairs":
            for pair in contrast["pairs"]:
                yield "en", ENGLISH_VOICES, pair["a"], where
                yield "en", ENGLISH_VOICES, pair["b"], where
        elif contrast["kind"] == "stress":
            for pair in contrast["pairs"]:
                yield "en", ["en"], pair["a"], where
        else:
            for word in contrast["words"]:
                yield "en", ["en"], word, where
        for text in contrast["sentences"]:
            yield "en", ["en"], text, f"{where}, sentence"
    for text in english["twisters"]:
        yield "en", ["en"], text, "english twister"
    for lesson in english["melody"]["lessons"]:
        for text in lesson["lines"]:
            yield "en", ["en"], text, f"english melody, {lesson['title']}"

    diction = load("diction")
    for text in diction["finalIWords"]:
        yield "ro", ["ro"], text, "diction, final i"
    for pair in diction["intonationPairs"]:
        yield "ro", ["ro"], pair["statement"], "diction, intonation"
        yield "ro", ["ro"], pair["question"], "diction, intonation"
    for line in diction["expressionLines"]:
        yield "ro", ["ro"], line["text"], "diction, expression"
    for text in diction["paceTexts"]:
        yield "ro", ["ro"], text["text"], "diction, pace"
    for text in diction["pauseTexts"]:
        yield "ro", ["ro"], text["spoken"], "diction, pauses"

    yield "ro", ["ro"], "Mi-a plăcut să exersăm împreună. Pa-pa!", "end of session, goodbye"

    listening = load("listening")
    for group in ("check", "voicingPairs"):
        for pair in listening[group]:
            yield "ro", ["ro"], pair["a"]["text"], f"listening {group}"
            yield "ro", ["ro"], pair["b"]["text"], f"listening {group}"
            if "spoken" in pair:
                yield "ro", ["ro"], pair["spoken"], f"listening {group}"


def practice_speed(text, where, says, stories):
    if text in says or "syllables" in where:
        return 0.85
    if text in stories:
        return 0.95
    if where.startswith(("diction, expression", "diction, pace", "diction, pauses")):
        return 1.0
    return 0.92


def group_a():
    sounds = load("sounds")
    says = {s["hero"]["says"] for s in sounds}
    stories = {s["hero"]["story"] for s in sounds}
    for language, voices, text, where in model_texts():
        for voice in voices:
            yield "A", language, voice, practice_speed(text, where, says, stories), text, "", where


def group_b():
    used = set()
    for sound in load("sounds"):
        where = f"sunetul {sound['letter']}"
        for line in sound["how"]:
            yield "B", "ro", "ro", 0.92, line, "", f"{where}, cum se face"
        for fix in sound["fixes"]:
            yield "B", "ro", "ro", 0.92, fix["tip"], "", f"{where}, sfat"
        for mission in sound["missions"]:
            yield "B", "ro", "ro", 0.92, mission["text"], "", f"{where}, misiune"
        for retell in sound["retell"]:
            yield "B", "ro", "ro", 0.92, retell["prompt"], "", f"{where}, repovestire"
        for describe in sound["describe"]:
            yield "B", "ro", "ro", 0.92, describe["prompt"], "", f"{where}, descriere"
        used.update(sound["warmups"])
    for warmup in load("exercises"):
        if warmup["id"] in used:
            yield "B", "ro", "ro", 0.92, warmup["say"], "", f"încălzire, {warmup['name']}"
    for plan in load("coach_plans"):
        for step in plan["steps"]:
            yield "B", "ro", "ro", 0.92, step["say"], "", f"cum spui {plan['letter']}, {step['label']}"


def group_c():
    def row(text, where, spoken=""):
        return "C", "ro", "ro", 0.92, text, spoken, where

    sounds = load("sounds")
    letters = {s["id"]: s["letter"].split("/")[0] for s in sounds}
    for text in ("Ce ai auzit?", "Ce e în poză?", "Suflă în lumânare"):
        yield row(text, "întrebare")
    for text in (
        "Te-ai străduit. Bravo!", "Ai lucrat cu atenție. Bravo!", "Ai spus până la capăt. Bravo!",
        "Ai mai încercat o dată. Așa!", "Ai încercat din nou și a ieșit!", "Ai avut răbdare. Bravo!",
        "Ai ridicat limba sus. Bravo!", "Ai suflat aer rece. Bravo!", "Ai suflat aer cald. Bravo!",
        "Bravo! Limba a vibrat.", "Am înțeles fiecare cuvânt",
    ):
        yield row(text, "laudă")
    yield row("Hai să mai încercăm", "reacție")
    for text in (
        "Nu te-am auzit", "E zgomot în jur", "A fost prea scurt", "A sunat prea tare", "Nu știu sigur",
        "Te-am auzit", "Ascultă și tu", "Nu aud bine sunetul", "Mai încercăm o dată", "Nu am putut asculta",
    ):
        yield row(text, "reacție")
    for text in (
        "Vorbește mai aproape de telefon.", "Mergi într-un loc mai liniștit și mai încearcă.",
        "Mai spune o dată, rar.", "Ține telefonul puțin mai departe.", "Mai încearcă o dată.",
        "Spune propoziția rar, cuvânt cu cuvânt.",
        "Dacă ai căști, scoate-le: cu microfonul telefonului aud mai bine.",
    ):
        yield row(text, "sfat scurt")
    for sound in sounds:
        letter = sound["letter"].split("/")[0]
        heard = AS_SOUND[letter]
        where = f"sunetul {letter}"
        yield row(f"Auzi {letter}?", where, f"Auzi {heard}?")
        yield row(f"Ține {letter} cât poți", where, f"Ține {heard} cât poți")
        for contrast in sound["contrasts"]:
            other = letters[contrast["with"]]
            yield row(f"{letter} sau {other}?", where, f"{heard} sau {AS_SOUND[other]}?")


def voice_rows(clips):
    seen = {}
    for group, language, voice, speed, text, spoken, where in [*group_a(), *group_b(), *group_c()]:
        if f"{language}|{voice}|{normalised(text)}" in clips:
            continue
        key = (language, voice, normalised(text))
        if key in seen:
            if where not in seen[key]["unde"]:
                seen[key]["unde"] += f"; {where}"
            continue
        seen[key] = {"grup": group, "limba": language, "voce": voice, "viteza": speed, "text": text,
                     "de_citit": spoken if spoken != text else "", "fisier": file_for(language, voice, text),
                     "unde": where}
    return list(seen.values())


def missing_lines(clips):
    missing = {}
    for language, voices, text, where in model_texts():
        absent = [v for v in voices if f"{language}|{v}|{normalised(text)}" not in clips]
        if absent:
            info = missing.setdefault((language, text), {"voices": [], "where": []})
            info["voices"] += [v for v in absent if v not in info["voices"]]
            if where not in info["where"]:
                info["where"].append(where)
    rows = sorted(f"{l} {','.join(i['voices'])} | {t} | {'; '.join(i['where'])}" for (l, t), i in missing.items())
    count = sum(len(i["voices"]) for i in missing.values())
    return rows, count


def main():
    clips = json.loads(MANIFEST.read_text(encoding="utf-8"))
    listed = voice_rows(clips)
    for group in "ABC":
        chosen = [r for r in listed if r["grup"] == group]
        print(f"{group}: {len(chosen)} clips, {sum(len(r['de_citit'] or r['text']) for r in chosen)} characters")
    rows, count = missing_lines(clips)
    print(f"missing model texts: {len(rows)}, clips {count}")
    if "--check" in sys.argv:
        return
    with LIST.open("w", encoding="utf-8-sig", newline="") as out:
        writer = csv.DictWriter(out, COLUMNS)
        writer.writeheader()
        writer.writerows(listed)
    header = [
        "# Model texts with no recorded clip. Regenerate with: python -I tools/voice/voicelist.py",
        "# Each line: language voices | text | where it is used",
        "# The app stays silent for these (no device voice). Texts that need a human recording are in tools/voice/human-needed.txt.",
        f"# Texts: {len(rows)}",
        f"# Clips to record: {count}",
    ]
    MISSING.write_bytes(("\n".join(header + rows) + "\n").encode("utf-8"))  # LF, as committed


if __name__ == "__main__":
    main()
