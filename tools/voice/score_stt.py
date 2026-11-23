#!/usr/bin/env python3
"""Scores transcripts (ElevenLabs Scribe via the MCP tool, or asr.py) against what each clip should say.

    python -I tools/voice/score_stt.py OUT.json transcripts1.json [transcripts2.json ...]

Transcript items: {"fisier", "heard"}. Expected text = de_citit or text from voice-list.csv. Both sides are
lowercased, stripped of punctuation, ș/ț cedilla forms unified and runs of one letter collapsed ("sss" = "s",
"rrrr" = "r"), since ASR writes a held sound with any number of letters. Prints the pass rate per group; writes
every item with its score.
"""

import csv
import difflib
import json
import re
import sys
import unicodedata
from pathlib import Path

LIST = Path(__file__).resolve().parent / "generated-list.csv"


def norm(text):
    text = unicodedata.normalize("NFC", text.lower()).replace("ş", "ș").replace("ţ", "ț")
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"[^\w\s]", " ", text)
    text = re.sub(r"(\w)\1+", r"\1", text)
    return " ".join(text.split())


def main():
    rows = {r["fisier"]: r for r in csv.DictReader(LIST.open(encoding="utf-8-sig"))}
    items = []
    for name in sys.argv[2:]:
        items += json.loads(Path(name).read_text(encoding="utf-8"))
    out, by = [], {}
    for item in items:
        row = rows[item["fisier"]]
        expected = row["de_citit"] or row["text"]
        score = difflib.SequenceMatcher(None, norm(item["heard"]), norm(expected)).ratio()
        ok = norm(item["heard"]) == norm(expected) or score >= 0.95
        out.append({"fisier": item["fisier"], "grup": row["grup"], "text": expected, "heard": item["heard"],
                    "score": round(score, 3), "ok": ok})
        g = by.setdefault(row["grup"], [0, 0])
        g[0] += ok
        g[1] += 1
    Path(sys.argv[1]).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    for g, (ok, n) in sorted(by.items()):
        print(f"{g}: {ok}/{n} pass")


if __name__ == "__main__":
    main()
