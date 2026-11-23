#!/usr/bin/env python3
"""Writes tools/voice/overrides.json for the clips that failed the checks, and prints their file names.

    python -I tools/voice/plan_retakes.py SCORED.json TAKE [--extra fisier1,fisier2]

Round t1 swaps the model: Romanian practice texts (made with multilingual_v2) go to eleven_v3, which in the
tests said S/Ș, J/Ș and CE/GE more exactly; instructions and prompts (eleven_v3) go to multilingual_v2.
English words stay on multilingual_v2 (the model of the existing English clips) with a full stop, which keeps
an isolated word from being swallowed. Round t2 keeps the model of t1 and only resamples.
"""

import csv
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIST = HERE / "generated-list.csv"
OVERRIDES = HERE / "overrides.json"


def main():
    scored = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    take = sys.argv[2]
    rows = {r["fisier"]: r for r in csv.DictReader(LIST.open(encoding="utf-8-sig"))}
    failed = [s["fisier"] for s in scored if not s["ok"]]
    if "--extra" in sys.argv:
        failed += sys.argv[sys.argv.index("--extra") + 1].split(",")
    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8")) if OVERRIDES.exists() else {}
    for fisier in dict.fromkeys(failed):
        row = rows[fisier]
        spoken = row["de_citit"] or row["text"]
        if take == "t1":
            if row["limba"] == "en":
                say = spoken if spoken[-1] in ".?!" else spoken + "."
                overrides[fisier] = {"say": say, "model": "eleven_multilingual_v2"}
            elif row["grup"] == "A":
                overrides[fisier] = {"say": spoken, "model": "eleven_v3"}
            else:
                overrides[fisier] = {"say": spoken, "model": "eleven_multilingual_v2"}
    OVERRIDES.write_text(json.dumps(overrides, ensure_ascii=False, indent=1), encoding="utf-8")
    print(",".join(dict.fromkeys(failed)))


if __name__ == "__main__":
    main()
