#!/usr/bin/env python3
"""Chooses the take of every clip from the transcript checks, and lists what gets no clip.

    python -I tools/voice/pick.py WORKDIR

Reads WORKDIR/stt_scored.json (t0), stt_scored_t1.json, stt_scored_t2.json and tools/voice/decisions.json:
  accept: {fisier: take} takes accepted by hand where the ASR spelling differs but the sound is the one asked
          for (a held "ț" written "Tss", "gi-gi-gi" written "Gigi", "cooed" written "Cood").
Writes WORKDIR/picks.json ({fisier: take}) and tools/voice/human-needed.txt (texts no take got right: no clip,
the app stays silent there until a person records them).
"""

import csv
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main():
    work = Path(sys.argv[1])
    decisions = json.loads((HERE / "decisions.json").read_text(encoding="utf-8"))
    rows = {r["fisier"]: r for r in csv.DictReader((HERE / "generated-list.csv").open(encoding="utf-8-sig"))}
    status = {}
    for take, name in (("t0", "stt_scored.json"), ("t1", "stt_scored_t1.json"), ("t2", "stt_scored_t2.json")):
        for item in json.loads((work / name).read_text(encoding="utf-8")):
            status.setdefault(item["fisier"], {})[take] = item["ok"]
    picks, human = {}, []
    for fisier in rows:
        takes = status.get(fisier, {})
        if fisier in decisions["accept"]:
            picks[fisier] = decisions["accept"][fisier]
            continue
        good = [t for t in ("t2", "t1", "t0") if takes.get(t)]
        if not takes:
            picks[fisier] = "t0"  # not transcribed (none: every clip went through Scribe)
        elif good:
            picks[fisier] = good[0]
        else:
            human.append(fisier)
    (work / "picks.json").write_text(json.dumps(picks, indent=0), encoding="utf-8")
    lines = ["# Texts ElevenLabs could not say right after 3 takes (two models), checked by ASR. No clip: the app",
             "# stays silent for them. A person should record them. Format: group | language voice | text | file"]
    for fisier in human:
        r = rows[fisier]
        lines.append(f"{r['grup']} | {r['limba']} {r['voce']} | {r['text']} | {fisier}")
    data = ("\n".join(lines) + "\n").encode("utf-8")
    (HERE / "human-needed.txt").write_bytes(data)
    # ContentTests reads the same list: a child-facing text with no clip must be on it.
    (HERE.parents[1] / "domain/src/test/resources/human-needed.txt").write_bytes(data)
    by = {}
    for t in picks.values():
        by[t] = by.get(t, 0) + 1
    print(f"picked {len(picks)} {by}, human-needed {len(human)}")


if __name__ == "__main__":
    main()
