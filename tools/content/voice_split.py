#!/usr/bin/env python3
"""Cuts synthesised batches into clips, normalises loudness and encodes them (I5).

    uv run --with numpy --with faster-whisper tools/content/voice_split.py PLAN.json RAW_DIR 0-5

RAW_DIR/b000/, b001/ … each hold the mp3 of one batch of the plan (voice_plan.py --out). A batch holds N texts
separated by blank lines. A local ASR (faster-whisper "medium", word timestamps, on this PC only) is aligned to
the expected words, and each cut falls at the quietest 10 ms between two texts. The whole batch is measured once
with ffmpeg loudnorm and every clip gets the same gain to -19 LUFS (short clips cannot be measured alone); after
AAC encoding the clip is backed off until it peaks at or below -1.5 dBFS.
Silence is trimmed to 60 ms before and 120 ms after the speech. Output: AAC in .m4a, mono, 44.1 kHz, 48 kbps, in
spec/content/voice/clips/<key>.m4a; the clip is added to voice/index.json with checked=false until voice_check.py
passes it.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
VOICE = ROOT / "spec" / "content" / "voice"
CLIPS = VOICE / "clips"
RATE = 44100
TARGET_LUFS = -19.0
PEAK_DBTP = -1.5
HEAD_S, TAIL_S = 0.06, 0.12
FRAME = 441  # 10 ms


def decode(path: Path) -> np.ndarray:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).copy()


def loudness(path: Path) -> float:
    out = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path), "-af", "loudnorm=print_format=json", "-f", "null",
                          "-"], capture_output=True, text=True).stderr
    data = json.loads(out[out.rindex("{"):out.rindex("}") + 1])
    return float(data["input_i"])


def loudness_of(audio: np.ndarray) -> float:
    """Integrated loudness of raw samples (ffmpeg loudnorm, first pass)."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-f", "f32le", "-ar", str(RATE), "-ac", "1", "-i", "-", "-af",
                          "loudnorm=print_format=json", "-f", "null", "-"], input=audio.astype(np.float32).tobytes(),
                         capture_output=True).stderr.decode("utf-8", "replace")
    data = json.loads(out[out.rindex("{"):out.rindex("}") + 1])
    return float(data["input_i"])


def frame_db(audio: np.ndarray) -> np.ndarray:
    n = len(audio) // FRAME
    frames = audio[: n * FRAME].reshape(n, FRAME)
    rms = np.sqrt(np.mean(frames ** 2, axis=1) + 1e-12)
    return 20 * np.log10(rms)


def silent_runs(db: np.ndarray, threshold: float, min_frames: int) -> list[tuple[int, int]]:
    runs, start = [], None
    for i, value in enumerate(db):
        if value < threshold:
            start = i if start is None else start
        elif start is not None:
            if i - start >= min_frames:
                runs.append((start, i))
            start = None
    if start is not None and len(db) - start >= min_frames:
        runs.append((start, len(db)))
    return runs


def cut_points(audio: np.ndarray, n: int) -> list[int] | None:
    """Fallback when there is no ASR: the n-1 longest pauses."""
    db = frame_db(audio)
    threshold = max(db) - 40.0
    speech = np.where(db >= threshold)[0]
    if len(speech) == 0:
        return None
    first, last = speech[0], speech[-1]
    runs = [r for r in silent_runs(db, threshold, 8) if r[0] > first and r[1] <= last]
    if len(runs) < n - 1:
        return None
    chosen = sorted(sorted(runs, key=lambda r: r[1] - r[0], reverse=True)[: n - 1])
    return [((a + b) // 2) * FRAME for a, b in chosen]


def plain(word: str) -> str:
    word = word.replace("ş", "ș").replace("ţ", "ț").lower()
    word = "".join(c for c in unicodedata.normalize("NFD", word) if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]", "", word)


def aligned_cut_points(audio: np.ndarray, items: list[dict], model) -> list[int] | None:
    """Cuts between items where the ASR words of one item end and the next begin, at the quietest 10 ms there."""
    segments, _ = model.transcribe(resample16k(audio), language="ro", beam_size=5, word_timestamps=True,
                                   condition_on_previous_text=False, vad_filter=False)
    heard = [(plain(w.word), w.start, w.end) for s in segments for w in (s.words or []) if plain(w.word)]
    expected, owner = [], []
    for k, item in enumerate(items):
        for w in re.split(r"[\s-]+", item["speak"]):
            if plain(w):
                expected.append(plain(w))
                owner.append(k)
    matcher = difflib.SequenceMatcher(None, expected, [h[0] for h in heard], autojunk=False)
    first_time = [None] * len(items)
    last_time = [None] * len(items)
    for block in matcher.get_matching_blocks():
        for j in range(block.size):
            k = owner[block.a + j]
            start, end = heard[block.b + j][1], heard[block.b + j][2]
            first_time[k] = start if first_time[k] is None else min(first_time[k], start)
            last_time[k] = end if last_time[k] is None else max(last_time[k], end)
    return cuts_from_anchors(audio, first_time, last_time)


def cuts_from_anchors(audio: np.ndarray, first_time: list, last_time: list) -> list[int] | None:
    """Between two anchored texts: the quietest 10 ms of the gap. A run of texts the ASR did not anchor (a lone
    word it missed) is cut at the longest pauses inside the gap around the run."""
    db = frame_db(audio)
    n = len(first_time)
    end_frame = len(db)
    threshold = max(db) - 40.0
    points: list[int] = []
    k = 0
    while k < n - 1:
        if last_time[k] is not None and first_time[k + 1] is not None:
            a, b = int(last_time[k] * 100), int(first_time[k + 1] * 100) + 1
            if b <= a:
                return None
            window = db[a:b]
            points.append((a + int(np.argmin(window))) * FRAME)
            k += 1
            continue
        # Unanchored run: texts k+1 .. j-1 (and maybe k itself) have no ASR words.
        start_item = k
        j = k + 1
        while j < n and first_time[j] is None:
            j += 1
        # ASR word times often touch: search from inside the previous anchored word to inside the next one.
        if first_time[start_item] is not None and last_time[start_item] is not None:
            left = int((first_time[start_item] + last_time[start_item]) * 50)
        else:
            left = points[-1] // FRAME if points else 0
        right = int((first_time[j] + last_time[j]) * 50) if j < n else end_frame
        needed = j - start_item  # boundaries between start_item|start_item+1 … j-1|j
        if j >= n:
            needed = n - 1 - start_item
        runs = [r for r in silent_runs(db[left:right], threshold, 6)]
        runs = [(x + left, y + left) for x, y in runs if y + left < right]
        if len(runs) < needed:
            return None
        chosen = sorted(sorted(runs, key=lambda r: r[1] - r[0], reverse=True)[:needed])
        points.extend(((x + y) // 2) * FRAME for x, y in chosen)
        k = start_item + needed
    return points if len(points) == n - 1 else None


def resample16k(audio: np.ndarray) -> np.ndarray:
    out = subprocess.run(["ffmpeg", "-v", "error", "-f", "f32le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                          "-ar", "16000", "-f", "f32le", "-"], input=audio.astype(np.float32).tobytes(),
                         capture_output=True, check=True).stdout
    return np.frombuffer(out, np.float32)


def trim(segment: np.ndarray) -> np.ndarray:
    db = frame_db(segment)
    if len(db) == 0:
        return segment
    speech = np.where(db >= max(db) - 40.0)[0]
    start = max(0, speech[0] * FRAME - int(HEAD_S * RATE))
    end = min(len(segment), (speech[-1] + 1) * FRAME + int(TAIL_S * RATE))
    return segment[start:end]


def encode(audio: np.ndarray, out: Path) -> None:
    """AAC can overshoot the input peak; back off until the decoded clip peaks at or below -1.5 dBFS."""
    for _ in range(6):
        encode_once(audio.copy(), out)
        peak = float(np.max(np.abs(decode(out)))) if out.exists() else 0.0
        if peak <= 10 ** (PEAK_DBTP / 20):
            return
        audio = audio * (10 ** (PEAK_DBTP / 20) / peak) * 0.97


def encode_once(audio: np.ndarray, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    # Short fades so a cut never clicks.
    fade = min(len(audio) // 4, int(0.01 * RATE))
    if fade > 0:
        ramp = np.linspace(0, 1, fade, dtype=np.float32)
        audio[:fade] *= ramp
        audio[-fade:] *= ramp[::-1]
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                    "-c:a", "aac", "-b:a", "48k", "-movflags", "+faststart", str(out)],
                   input=audio.astype(np.float32).tobytes(), check=True)


def safe_name(key: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", key)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan")
    parser.add_argument("raw", help="folder with b000/, b001/ ... each holding one batch mp3")
    parser.add_argument("batches", help="comma list of batch numbers, or a-b")
    parser.add_argument("--fresh", action="store_true", help="start a new index.json")
    parser.add_argument("--voice", default="Matilda")
    parser.add_argument("--voice-id", default="XrExE9yKIg1WjnnlVkGX")
    parser.add_argument("--model", default="eleven_multilingual_v2")
    args = parser.parse_args()
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    from faster_whisper import WhisperModel
    model = WhisperModel("medium", device="cpu", compute_type="int8")
    plan = json.loads(Path(args.plan).read_text(encoding="utf-8"))["batches"]
    if "-" in args.batches:
        first, last = args.batches.split("-")
        numbers = list(range(int(first), int(last) + 1))
    else:
        numbers = [int(x) for x in args.batches.split(",")]
    index_path = VOICE / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() and not args.fresh else {
        "schema": 1, "voice": args.voice, "voiceId": args.voice_id, "model": args.model, "language": "ro",
        "settings": {"stability": 0.6, "similarity_boost": 0.75, "style": 0, "speaker_boost": True},
        "format": "m4a (AAC-LC), mono, 44.1 kHz, 48 kbps; -19 LUFS, peak <= -1.5 dBFS", "clips": {}, "dropped": {}}
    status = 0
    for number in numbers:
        files = sorted(Path(args.raw, f"b{number:03d}").glob("*.mp3"))
        if not files:
            print(f"batch {number}: no mp3", file=sys.stderr)
            status = 2
            continue
        items = plan[number]["items"]
        newest = max(files, key=lambda f: f.stat().st_mtime)
        audio = decode(newest)
        points = [] if len(items) == 1 else aligned_cut_points(audio, items, model)
        if points is None:
            print(f"batch {number}: could not align the {len(items)} texts", file=sys.stderr)
            status = 2
            continue
        gain_db = TARGET_LUFS - loudness(newest)
        edges = [0] + points + [len(audio)]
        for item, a, b in zip(items, edges[:-1], edges[1:]):
            segment = trim(audio[a:b].copy())
            # Long enough to measure alone (several 400 ms blocks): its own gain; else the batch gain.
            own = loudness_of(segment) if len(segment) >= 1.5 * RATE else None
            segment = segment * (10 ** (((TARGET_LUFS - own) if own is not None and own > -70 else gain_db) / 20))
            name = f"clips/{safe_name(item['key'])}.m4a"
            encode(segment, VOICE / name)
            index["clips"][item["key"]] = {"file": name, "text": item["text"], "speak": item["speak"],
                                           "kind": item["kind"], "voice": args.voice, "checked": False,
                                           "batch": number}
        print(f"batch {number}: {len(items)} clips")
        index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    return status


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
