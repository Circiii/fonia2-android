#!/usr/bin/env python3
"""Turns raw ElevenLabs takes into app clips: trim, loudness, MP3 44.1 kHz mono 128 kbps.

    python -I tools/voice/process.py WORKDIR [--target -18.2] [--only fisier1,fisier2]

Takes come from WORKDIR/gen/<fisier without .mp3>/<take>/*.mp3; WORKDIR/picks.json may name the take per clip
(default: the last take folder in name order, t0 < t1 < t2). Output: WORKDIR/staged/<fisier>.
  - leading and trailing silence (below -50 dBFS) cut to 80 ms
  - plain gain to the target integrated loudness (median of the existing clips, -18.2 LUFS), then a limiter at
    -1 dBFS only if the gain pushed a peak above it (no compression otherwise, so S and Ș keep their highs)
"""

import json
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TRIM = ("silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.08:detection=peak,"
        "areverse,silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.08:detection=peak,areverse")


def measure(path):
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "ebur128=peak=true",
                          "-f", "null", "-"], capture_output=True, text=True, encoding="utf-8", errors="replace").stderr
    summary = err[err.rfind("Summary:"):]
    lufs = float(re.search(r"I:\s+(-?[\d.]+) LUFS", summary).group(1))
    peak = re.search(r"Peak:\s+(-?[\d.]+) dBFS", summary)
    return lufs, float(peak.group(1)) if peak else -99.0


def take_for(work, fisier, picks):
    base = work / "gen" / fisier[:-4]
    if fisier in picks:
        folder = base / picks[fisier]
    else:
        folders = sorted(p for p in base.iterdir() if p.is_dir() and list(p.glob("*.mp3")))
        if not folders:
            return None
        folder = folders[-1]
    files = sorted(folder.glob("*.mp3"), key=lambda p: p.stat().st_mtime)
    return files[-1] if files else None


def process(work, fisier, picks, target):
    source = take_for(work, fisier, picks)
    if source is None:
        return fisier, "no take"
    out = work / "staged" / fisier
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        trimmed = Path(tmp) / "t.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-af", TRIM, "-ac", "1", "-ar", "44100",
                        "-c:a", "pcm_f32le", str(trimmed)], check=True)
        lufs, peak = measure(trimmed)
        gain = target - lufs
        chain = f"volume={gain:.2f}dB"
        if peak + gain > -1.0:
            chain += ",alimiter=limit=0.89:attack=1:release=50:level=false"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(trimmed), "-af", chain, "-ac", "1", "-ar", "44100",
                        "-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0", str(out)], check=True)
    return fisier, source.parent.name


def main():
    work = Path(sys.argv[1])
    target = float(sys.argv[sys.argv.index("--target") + 1]) if "--target" in sys.argv else -18.2
    picks_path = work / "picks.json"
    picks = json.loads(picks_path.read_text(encoding="utf-8")) if picks_path.exists() else {}
    import csv
    rows = list(csv.DictReader((Path(__file__).parent / "generated-list.csv").open(encoding="utf-8-sig")))
    wanted = [r["fisier"] for r in rows]
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
        wanted = [f for f in wanted if f in only]
    with ThreadPoolExecutor(12) as pool:
        results = list(pool.map(lambda f: process(work, f, picks, target), wanted))
    used = {f: t for f, t in results}
    log = work / "takes.json"
    old = json.loads(log.read_text(encoding="utf-8")) if log.exists() else {}
    old.update(used)
    log.write_text(json.dumps(old, indent=0), encoding="utf-8")
    missing = [f for f, t in results if t == "no take"]
    print(f"processed {len(results) - len(missing)}, no take {len(missing)}: {missing[:10]}")


if __name__ == "__main__":
    main()
