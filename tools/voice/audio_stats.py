#!/usr/bin/env python3
"""Loudness, peak, duration and silence of MP3 clips, with ffmpeg.

    python -I tools/voice/audio_stats.py out.json file1.mp3 file2.mp3 ...
    python -I tools/voice/audio_stats.py out.json --dir content/voice/ro/ro --sample 60
"""

import json
import random
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def stats(path):
    path = str(path)
    err = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af",
         "ebur128=peak=true,silencedetect=n=-45dB:d=0.35", "-f", "null", "-"],
        capture_output=True, text=True, encoding="utf-8", errors="replace").stderr
    summary = err[err.rfind("Summary:"):]
    lufs = re.search(r"I:\s+(-?[\d.]+) LUFS", summary)
    peak = re.search(r"Peak:\s+(-?[\d.inf]+) dBFS", summary)
    dur = re.search(r"Duration: (\d+):(\d+):([\d.]+)", err)
    duration = int(dur.group(1)) * 3600 + int(dur.group(2)) * 60 + float(dur.group(3)) if dur else 0.0
    starts = [float(x) for x in re.findall(r"silence_start: (-?[\d.]+)", err)]
    ends = [(float(a), float(b)) for a, b in re.findall(r"silence_end: ([\d.]+) \| silence_duration: ([\d.]+)", err)]
    # Inner silences: those that neither start at 0 nor run to the end.
    inner = [d for (end, d) in ends if end - d > 0.05 and end < duration - 0.05]
    return {"file": path, "lufs": float(lufs.group(1)) if lufs else None,
            "peak": float(peak.group(1)) if peak and peak.group(1) not in ("-inf", "inf") else None,
            "duration": round(duration, 3), "longest_inner_silence": max(inner) if inner else 0.0}


def main():
    out = sys.argv[1]
    args = sys.argv[2:]
    if args and args[0] == "--dir":
        files = sorted(Path(args[1]).glob("*.mp3"))
        if len(args) > 3 and args[2] == "--sample":
            random.seed(7)
            files = random.sample(files, min(int(args[3]), len(files)))
    elif args and args[0] == "--tree":
        files = sorted(Path(args[1]).rglob("*.mp3"))
    else:
        files = [Path(a) for a in args]
    with ThreadPoolExecutor(12) as pool:
        result = list(pool.map(stats, files))
    Path(out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    values = sorted(r["lufs"] for r in result if r["lufs"] is not None)
    if values:
        print(f"{len(result)} files, median {values[len(values)//2]:.1f} LUFS, range {values[0]:.1f}..{values[-1]:.1f}")
    peaks = sorted(r["peak"] for r in result if r["peak"] is not None)
    if peaks:
        print(f"peak median {peaks[len(peaks)//2]:.1f} dBFS, max {peaks[-1]:.1f}")


if __name__ == "__main__":
    main()
