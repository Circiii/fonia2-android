#!/usr/bin/env python3
"""Longest stretch of hiss (s), clip length (s) and median hiss centroid (Hz) (energy above 3.5 kHz dominates) in each clip: a held S/Ș/Z/J must be a long hiss,
not "es". Prints seconds per file.

    uv run --with numpy python -I tools/voice/fricative.py a.mp3 b.mp3 ...
"""

import json
import subprocess
import sys

import numpy as np

RATE = 22050


def hiss(path):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         capture_output=True).stdout
    x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768
    frame = int(RATE * 0.02)
    n = len(x) // frame
    if n == 0:
        return 0.0, 0.0
    frames = x[: n * frame].reshape(n, frame) * np.hanning(frame)
    spec = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    freqs = np.fft.rfftfreq(frame, 1 / RATE)
    high = spec[:, freqs >= 3500].sum(1)
    total = spec.sum(1) + 1e-12
    loud = 10 * np.log10(total + 1e-12) > (10 * np.log10(total.max()) - 35)
    flag = (high / total > 0.5) & loud
    best = run = 0
    for f in flag:
        run = run + 1 if f else 0
        best = max(best, run)
    # Spectral centroid of the hiss frames: S sits higher (about 6-8 kHz) than Ș (about 3-5 kHz).
    centroid = (spec[flag] * freqs).sum(1) / spec[flag].sum(1) if flag.any() else np.array([0.0])
    return round(best * 0.02, 2), round(n * 0.02, 2), int(np.median(centroid))


if __name__ == "__main__":
    out = {p: hiss(p) for p in sys.argv[1:]}
    print(json.dumps(out, ensure_ascii=False, indent=0))
