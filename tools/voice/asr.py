#!/usr/bin/env python3
"""Local ASR check with faster-whisper (evaluation only, nothing ships with it).

    uv run --with faster-whisper python -I tools/voice/asr.py jobs.json results.json

jobs.json: [{"file": "...mp3", "language": "ro", "text": "expected"}, ...]
results.json: the same items plus "heard", "score" (character similarity of normalised texts, 0..1).
"""

import difflib
import json
import os
import re
import sys
import unicodedata
from pathlib import Path


def norm(text):
    text = unicodedata.normalize("NFC", text.lower())
    text = text.replace("ş", "ș").replace("ţ", "ț")
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(text.split())


def pcm(path):
    """16 kHz mono float32 through ffmpeg (newer PyAV drops an argument faster-whisper's decoder passes)."""
    import subprocess
    import numpy as np
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", "16000", "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768


def main():
    jobs = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    out = Path(sys.argv[2])
    done = {}
    if out.exists():
        done = {r["file"]: r for r in json.loads(out.read_text(encoding="utf-8"))}
    from faster_whisper import WhisperModel
    model = WhisperModel(os.environ.get("ASR_MODEL", "large-v3"), device="cpu", compute_type="int8",
                         cpu_threads=int(os.environ.get("ASR_THREADS", "16")))
    results = list(done.values())
    for i, job in enumerate(jobs):
        if job["file"] in done:
            continue
        try:
            segments, _ = model.transcribe(pcm(job["file"]), language=job["language"], beam_size=5,
                                           vad_filter=False, condition_on_previous_text=False)
            heard = " ".join(s.text.strip() for s in segments).strip()
        except Exception as error:  # a corrupt or empty file: recorded as heard nothing
            heard = f"<error {type(error).__name__}>"
        score = difflib.SequenceMatcher(None, norm(heard), norm(job["text"])).ratio()
        results.append({**job, "heard": heard, "score": round(score, 3)})
        if i % 10 == 0:
            out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"{i + 1}/{len(jobs)}", flush=True)
    out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    scores = [r["score"] for r in results]
    print(f"done {len(results)}, exact {sum(s == 1.0 for s in scores)}, >=0.9 {sum(s >= 0.9 for s in scores)}")


if __name__ == "__main__":
    main()
