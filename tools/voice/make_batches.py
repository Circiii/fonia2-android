#!/usr/bin/env python3
"""Splits voice-list.csv into generation jobs for the ElevenLabs MCP tool (text_to_speech).

    python -I tools/voice/make_batches.py WORKDIR N [--only file1,file2] [--take r1]

Writes WORKDIR/batches/batch_<i>.json. Each job carries the exact text_to_speech arguments and its own output
directory WORKDIR/gen/<fisier without .mp3>/<take>/, so a clip is found again by its folder whatever name the tool
gives the file. Decisions (see REPORT.md):
  A  practice texts: eleven_multilingual_v2, Ștefan's settings, no tags: same model as the 939 existing clips.
  B  instructions:   eleven_v3, stability 0.5 (Natural), "[warmly]" in front.
  C  short prompts:  eleven_v3, stability 0.5; praise "[cheerful]", the rest "[warmly]", letter lines no tag.
"""

import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIST = ROOT / "tools/voice/generated-list.csv"
VOICES = {"ro": "9xtWifnxcfeH01tkoXjT", "en": "oClOrzqamOXmtcB8iqTj", "en2": "nzFihrBIvB34imQBuxub",
          "en3": "jkUnCsbErmJrcbWk1Hmh", "en4": "h9CKu0pq1LSjIPhJEGCv"}
OVERRIDES = ROOT / "tools/voice/overrides.json"  # fisier -> {"say": ..., "model": ...} for retakes


def job(row, work, take, override):
    spoken = row["de_citit"] or row["text"]
    group = row["grup"]
    if group == "A":
        model, stability, say = "eleven_multilingual_v2", 0.62, spoken
    else:
        model, stability = "eleven_v3", 0.5
        if row["de_citit"]:
            tag = ""
        elif group == "C" and "laudă" in row["unde"]:
            tag = "[cheerful] "
        else:
            tag = "[warmly] "
        say = tag + spoken
    if override:
        say = override.get("say", say)
        model = override.get("model", model)
        stability = 0.62 if model == "eleven_multilingual_v2" else 0.5
    out = Path(work) / "gen" / row["fisier"][:-4] / take
    return {"fisier": row["fisier"], "group": group, "text": row["text"],
            "args": {"text": say, "voice_id": VOICES[row["voce"]], "model_id": model, "stability": stability,
                     "similarity_boost": 0.8, "style": 0, "use_speaker_boost": True, "speed": float(row["viteza"]),
                     "language": row["limba"], "output_format": "mp3_44100_128",
                     "output_directory": str(out).replace("/", "\\")}}


def main():
    work, n = sys.argv[1], int(sys.argv[2])
    only = None
    take = "t0"
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    if "--take" in sys.argv:
        take = sys.argv[sys.argv.index("--take") + 1]
    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8")) if OVERRIDES.exists() else {}
    rows = list(csv.DictReader(LIST.open(encoding="utf-8-sig")))
    if only is not None:
        rows = [r for r in rows if r["fisier"] in only]
    order = {"B": 0, "C": 1, "A": 2}
    rows.sort(key=lambda r: order[r["grup"]])
    jobs = [job(r, work, take, overrides.get(r["fisier"])) for r in rows]
    folder = Path(work) / "batches"
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob(f"batch_{take}_*.json"):
        old.unlink()
    for i in range(n):
        part = jobs[i::n]
        (folder / f"batch_{take}_{i}.json").write_text(json.dumps(part, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"batch_{take}_{i}: {len(part)} jobs, {sum(len(j['args']['text']) for j in part)} characters")


if __name__ == "__main__":
    main()
