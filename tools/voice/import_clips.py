#!/usr/bin/env python3
"""Port of fonia-app tools/voices/import_clips.py for content/voice: checks clips, copies them, updates manifest.json.

    python -I tools/voice/import_clips.py STAGED_DIR [--replace fisier1,fisier2]

STAGED_DIR holds files at their `fisier` path (ro/ro/80c8ee34dbae85.mp3). Clips listed in human-needed.txt are
skipped. --replace overwrites existing clips whose key is already in the manifest (regenerated old clips).
Unlike iOS, the JSON "recorded"/"needsRecording" flags are left alone: content/json is synced from iOS and its
import refreshes them (tools/voice/REPORT.md lists what changes).
"""

import csv
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / "tools/voice"
CLIPS = ROOT / "content/voice"
MANIFEST = CLIPS / "manifest.json"


def normalised(text):
    return " ".join(text.split()).lower()


def problems_with(path):
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                            "stream=codec_name,sample_rate,channels,bit_rate:format=duration", "-of", "json", str(path)],
                           capture_output=True, text=True)
    if probe.returncode != 0:
        return ["not a valid audio file"]
    info = json.loads(probe.stdout)
    stream = info["streams"][0]
    errors = []
    if stream["codec_name"] != "mp3":
        errors.append(f"codec {stream['codec_name']}")
    if stream["sample_rate"] != "44100":
        errors.append(f"{stream['sample_rate']} Hz")
    if stream["channels"] != 1:
        errors.append(f"{stream['channels']} channels")
    if int(stream.get("bit_rate") or 0) < 96000:
        errors.append(f"{int(stream.get('bit_rate') or 0) // 1000} kbps")
    if float(info["format"]["duration"]) < 0.2:
        errors.append("too short")
    volume = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "volumedetect", "-f", "null", "-"],
                            capture_output=True, text=True).stderr
    mean = re.search(r"mean_volume: (-?[\d.]+) dB", volume)
    if mean and float(mean.group(1)) < -35:
        errors.append(f"near silent ({mean.group(1)} dB)")
    return errors


def main():
    staged = Path(sys.argv[1])
    replace = set(sys.argv[sys.argv.index("--replace") + 1].split(",")) if "--replace" in sys.argv else set()
    rows = {r["fisier"]: r for r in csv.DictReader((HERE / "generated-list.csv").open(encoding="utf-8-sig"))}
    human = {line.split(" | ")[3].strip() for line in (HERE / "human-needed.txt").read_text(encoding="utf-8").splitlines()
             if line and not line.startswith("#")}
    clips = json.loads(MANIFEST.read_text(encoding="utf-8"))
    added, replaced, rejected = 0, 0, []
    for fisier, row in rows.items():
        if fisier in human:
            continue
        source = staged / fisier
        if not source.exists():
            rejected.append(f"{fisier}: missing")
            continue
        errors = problems_with(source)
        if errors:
            rejected.append(f"{fisier}: {', '.join(errors)}")
            continue
        target = CLIPS / fisier
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        clips[f"{row['limba']}|{row['voce']}|{normalised(row['text'])}"] = fisier
        added += 1
    for fisier in replace:
        if fisier not in clips.values():
            rejected.append(f"{fisier}: not in the manifest")
            continue
        errors = problems_with(staged / fisier)
        if errors:
            rejected.append(f"{fisier}: {', '.join(errors)}")
            continue
        shutil.copyfile(staged / fisier, CLIPS / fisier)
        replaced += 1
    MANIFEST.write_bytes((json.dumps(clips, ensure_ascii=False, indent=1) + "\n").encode("utf-8"))  # LF, as committed
    print(f"added {added}, replaced {replaced}, rejected {len(rejected)}")
    for line in rejected:
        print("  " + line)
    if rejected:
        sys.exit(1)


if __name__ == "__main__":
    main()
