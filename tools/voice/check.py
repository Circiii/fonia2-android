#!/usr/bin/env python3
"""Flags clips that fail the automatic checks: ASR mismatch, odd pace, long inner silence, silent, clipped.

    python -I tools/voice/check.py STATS.json ASR.json [OUT.json]

STATS from audio_stats.py, ASR from asr.py (items carry fisier, grup, text). A clip fails when the ASR text is not
close to the expected one (score < 0.9; words < 0.8 for clips of 1-2 words where ASR is less sure), when it speaks
slower than 4 or faster than 22 characters a second, has an inner silence over 0.9 s, is near silent or peaks at
0 dBFS.
"""

import json
import sys
from pathlib import Path


def main():
    stats = {Path(r["file"]).as_posix().split("staged/")[-1]: r for r in json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))}
    asr = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    fails = []
    for item in asr:
        s = stats.get(item["fisier"])
        reasons = []
        words = len(item["text"].split())
        if item["score"] < (0.8 if words <= 2 else 0.9):
            reasons.append(f"asr {item['score']}: {item['heard']}")
        if s:
            cps = len(item["text"]) / max(s["duration"], 0.01)
            if s["lufs"] is None or s["lufs"] < -30:
                reasons.append("silent")
            if s["peak"] is not None and s["peak"] > -0.05:
                reasons.append("clipped")
            if s["longest_inner_silence"] > 0.9:
                reasons.append(f"inner silence {s['longest_inner_silence']:.2f}s")
            if words > 1 and (cps < 4 or cps > 22):
                reasons.append(f"pace {cps:.1f} c/s ({s['duration']}s)")
        if reasons:
            fails.append({"fisier": item["fisier"], "grup": item["grup"], "text": item["text"], "reasons": reasons})
    total = len(asr)
    print(f"{total} checked, {total - len(fails)} pass, {len(fails)} fail")
    by = {}
    for f in fails:
        by[f["grup"]] = by.get(f["grup"], 0) + 1
    print("fails by group:", by)
    if len(sys.argv) > 3:
        Path(sys.argv[3]).write_text(json.dumps(fails, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
