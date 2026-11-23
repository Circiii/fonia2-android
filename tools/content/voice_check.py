#!/usr/bin/env python3
"""Checks every Foni clip in spec/content/voice/index.json (chapter 06 §7.4) and marks it checked or failed.

    uv run --with faster-whisper tools/content/voice_check.py [--all] [--keys k1,k2] [--no-asr]

Checks per clip:
- duration sane for its kind (word 0.3-1.8 s, line <= maxSeconds + 1 s, sentence <= 5 s, twister <= 8 s);
- not clipped (peak below -0.5 dBFS, no run of 3+ samples at full scale);
- loudness near the common level (-19 LUFS, within 2.5 LU; clips under 0.6 s are judged by their peak only);
- the transcript of a local ASR (faster-whisper "medium", on this PC, evaluation only) matches the text:
  exact after normalisation, or close (letters similarity >= 0.85, recorded as "fuzzy").
Clips that fail are listed and get checked=false with the reasons; voice_split.py / a retry regenerates them.
The ASR model must already be in the Hugging Face cache (HF_HUB_OFFLINE=1 is set here).
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
V2 = ROOT / "spec" / "content" / "v2"
os.environ.setdefault("HF_HUB_OFFLINE", "1")

DURATION = {"word": (0.3, 1.8), "sentence": (0.6, 5.0), "line": (0.3, None), "twister": (1.0, 8.0)}


def decode(path: Path, rate: int) -> np.ndarray:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(rate), "-f", "f32le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32)


def lufs(path: Path) -> float | None:
    out = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path), "-af", "ebur128=peak=true", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    match = re.findall(r"I:\s+(-?[\d.]+) LUFS", out)
    return float(match[-1]) if match else None


# How the ASR spells names it does not know.
NAMES = {"phony": "foni", "fonny": "foni", "fony": "foni", "foney": "foni", "phoni": "foni"}
# The ASR writes numbers as digits.
NUMBERS = {"3": "trei", "5": "cinci", "6": "șase", "7": "șapte", "10": "zece", "0": "zero"}
# Only the vowel diacritics are folded: the ASR often drops a final ă or swaps â/î on a lone word. Ș and Ț are
# never folded, because S/Ș and T/Ț are exactly what must be right.
VOWEL_FOLD = str.maketrans({"ă": "a", "â": "i", "î": "i"})


def normalise(text: str, keep_diacritics: bool = True) -> str:
    text = text.replace("ş", "ș").replace("ţ", "ț").replace("Ş", "Ș").replace("Ţ", "Ț").lower()
    text = text.replace("-", " ").replace("’", " ").replace("'", " ")
    if not keep_diacritics:
        text = "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")
    text = re.sub(r"[^\w\s]", " ", text).translate(VOWEL_FOLD)
    return " ".join(NUMBERS.get(w, NAMES.get(w, w)) for w in text.split())


def compare(expected: str, heard: str) -> tuple[str, float]:
    a, b = normalise(expected), normalise(heard)
    if a == b:
        return "exact", 1.0
    # "ține-n" is often heard as "ține în", "și-o" as "și o": compare without spaces too.
    if a.replace(" ", "") == b.replace(" ", "").replace("în", "n").replace("îi", "i"):
        return "exact", 1.0
    ratio = difflib.SequenceMatcher(None, a.replace(" ", ""), b.replace(" ", "")).ratio()
    bare = difflib.SequenceMatcher(None, normalise(expected, False).replace(" ", ""),
                                   normalise(heard, False).replace(" ", "")).ratio()
    return ("fuzzy" if min(ratio, bare + 0.05) >= 0.85 else "mismatch"), round(ratio, 3)


def regain(path: Path, gain_db: float) -> None:
    """Applies a gain to an encoded clip (decode, scale, re-encode at the same settings, peak kept <= -1.5 dBFS)."""
    audio = decode(path, 44100).astype(np.float32) * (10 ** (gain_db / 20))
    for _ in range(6):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", "44100", "-ac", "1", "-i", "-",
                        "-c:a", "aac", "-b:a", "48k", "-movflags", "+faststart", str(path)],
                       input=audio.tobytes(), check=True)
        peak = float(np.max(np.abs(decode(path, 44100))))
        if peak <= 10 ** (-1.5 / 20):
            return
        audio = audio * (10 ** (-1.5 / 20) / peak) * 0.97


def max_seconds() -> dict[str, float]:
    out = {}
    lines = json.loads((V2 / "foni_lines.json").read_text(encoding="utf-8"))["lines"]
    for line in lines:
        out[line["key"]] = line["maxSeconds"]
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true", help="re-check clips already checked")
    parser.add_argument("--keys", default="")
    parser.add_argument("--no-asr", action="store_true")
    parser.add_argument("--failed", action="store_true", help="re-check only clips that failed")
    parser.add_argument("--fix-loudness", action="store_true",
                        help="re-gain clips whose only problem is loudness, then re-check them")
    parser.add_argument("--finalize", default="", help="move failed clips to this folder and list them as dropped")
    args = parser.parse_args()
    index_path = VOICE / "index.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if args.finalize:
        import shutil
        out = Path(args.finalize)
        out.mkdir(parents=True, exist_ok=True)
        for key in [k for k, c in index["clips"].items() if not c.get("checked")]:
            clip = index["clips"].pop(key)
            if (VOICE / clip["file"]).exists():
                shutil.move(str(VOICE / clip["file"]), str(out / Path(clip["file"]).name))
            index.setdefault("dropped", {})[key] = {"text": clip["text"], "problems": clip.get("problems", []),
                                                    "heard": clip.get("asr", {}).get("heard")}
        index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"kept {len(index['clips'])} clips, dropped {len(index.get('dropped', {}))}")
        return 0
    limits = max_seconds()
    if args.fix_loudness:
        fixed = []
        for key, clip in index["clips"].items():
            problems = clip.get("problems", [])
            if problems and all(p.startswith("loudness") for p in problems) and clip.get("lufs") is not None:
                regain(VOICE / clip["file"], -19.0 - clip["lufs"])
                fixed.append(key)
        print(f"re-gained {len(fixed)} clips")
        args.keys = ",".join(fixed)
        args.no_asr = True
    wanted = set(k for k in args.keys.split(",") if k)
    if args.failed:
        wanted = {k for k, c in index["clips"].items() if not c.get("checked")}
    todo = [k for k, c in index["clips"].items()
            if (k in wanted) or (not wanted and (args.all or "asr" not in c))]
    model = None
    if not args.no_asr and todo:
        from faster_whisper import WhisperModel
        model = WhisperModel("medium", device="cpu", compute_type="int8")
    failed = []
    for key in todo:
        clip = index["clips"][key]
        path = VOICE / clip["file"]
        reasons = []
        audio = decode(path, 44100)
        seconds = len(audio) / 44100
        kind = "twister" if key.startswith("t.") else clip["kind"]
        low, high = DURATION.get(kind, (0.3, None))
        if kind == "line":
            # Chapter openings are two sentences; hold prompts one short sentence.
            high = 7.0 if key.startswith("open.") else limits.get(key, 4.0) + 1.0
        if seconds < low or (high is not None and seconds > high):
            reasons.append(f"duration {seconds:.2f}s outside {low}-{high}")
        peak = float(np.max(np.abs(audio))) if len(audio) else 0.0
        full = np.abs(audio) >= 0.999
        run = np.max(np.diff(np.flatnonzero(np.concatenate(([True], ~full, [True])))) - 1) if len(audio) else 0
        if peak > 10 ** (-0.5 / 20) or run >= 3:
            reasons.append(f"clipped (peak {20 * np.log10(peak + 1e-9):.1f} dBFS)")
        loud = lufs(path) if seconds >= 0.6 else None
        # Short clips are measured over few 400 ms blocks: allow more spread.
        if loud is not None and abs(loud - (-19.0)) > (2.5 if seconds >= 1.5 else 4.0):
            reasons.append(f"loudness {loud:.1f} LUFS")
        clip["lufs"] = loud
        clip["peakDb"] = round(20 * np.log10(peak + 1e-9), 1)
        clip["seconds"] = round(seconds, 3)
        if model is not None:
            # Half a second of silence on both sides: Whisper mishears clips that start on the first sample.
            pad = np.zeros(8000, dtype=np.float32)
            padded = np.concatenate([pad, decode(path, 16000), pad])
            # Lone words: prime the ASR with the language only (never with the expected word), else it drifts
            # into English or German spellings ("Suck", "Schach").
            prompt = "Cuvinte în limba română, rostite rar, pentru copii." if clip["kind"] == "word" else None
            segments, _ = model.transcribe(padded, language="ro", beam_size=5, initial_prompt=prompt,
                                           condition_on_previous_text=False, vad_filter=False)
            heard = " ".join(s.text.strip() for s in segments).strip()
            verdict, ratio = compare(clip["text"], heard)
            clip["asr"] = {"heard": heard, "match": verdict, "ratio": ratio}
            if verdict == "mismatch":
                reasons.append(f"ASR heard {heard!r}")
        if model is None and "asr" in clip and clip["asr"].get("match") == "mismatch":
            # Without a new ASR pass the earlier transcript still counts (re-compared with the current rules).
            verdict, ratio = compare(clip["text"], clip["asr"]["heard"])
            clip["asr"].update(match=verdict, ratio=ratio)
            if verdict == "mismatch":
                reasons.append(f"ASR heard {clip['asr']['heard']!r}")
        clip["checked"] = not reasons
        clip["problems"] = reasons
        if reasons:
            failed.append((key, reasons))
        print(f"{'ok  ' if not reasons else 'FAIL'} {key}: {clip['text']!r} -> {clip.get('asr', {}).get('heard')!r}"
              f" {'; '.join(reasons)}")
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    checked = sum(1 for c in index["clips"].values() if c.get("checked"))
    print(f"checked now: {len(todo)}, failed: {len(failed)}; index: {checked}/{len(index['clips'])} pass")
    for key, reasons in failed:
        print("failed:", key, "|", "; ".join(reasons))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
