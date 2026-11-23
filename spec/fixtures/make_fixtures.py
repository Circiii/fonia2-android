"""Builds the audio fixtures of the app's fixture mode (A3): 48 kHz mono 16-bit WAV, a few seconds each.

    python spec/fixtures/make_fixtures.py                      # s_hold.wav, sh_hold.wav (synthetic hiss)
    python spec/fixtures/make_fixtures.py <dir with sarah_*.wav> # also trims the two spoken-word clips

s_hold.wav  : 0.35 s room tone, 3.0 s shaped noise centred near 9.2 kHz (an S hiss), 0.9 s room tone.
              (A child S: the child prior of :engine:dsp puts the S/Ș boundary at a 7.9 kHz centroid, so an
              8 kHz hiss would be a borderline S for a child.)
sh_hold.wav : the same with the noise centred near 3.2 kHz (a Ș hiss).
sarah_s.wav / sarah_sh.wav : ElevenLabs test voice, synthetic: the FIRST WORD only ("Sare" / "Șarpe") of the two list
takes (the source files are run-together word lists; FIRST_WORD holds the hand-checked spans), with room tone around
it. One word, so the judge's word gate (0.5x-2.5x of the expected duration) accepts it. They only prove the chain
runs; they say nothing about accuracy on children.

fillers.wav : the same synthetic voice speaking freely with fillers (sarah_fillers.wav, 15.5 s, whole), 0.4 s room tone
              before and 1.0 s after; capped at 20 s. The speaking screens' fixture (`--es fixture fillers`).

The trailing room tone matters: the take ends when the voice stops, so a fixture must fall quiet before it ends.
Deterministic (fixed seeds), so re-running gives identical files.
"""
import sys
import wave
from pathlib import Path

import numpy as np

RATE = 48_000
OUT = Path(__file__).resolve().parent / "audio"


def room_tone(seconds, rng, dbfs=-66.0):
    return rng.standard_normal(int(seconds * RATE)) * 10 ** (dbfs / 20)


def shaped_hiss(seconds, centre_hz, width_hz, rng, dbfs=-20.0):
    n = int(seconds * RATE)
    spectrum = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    gain = np.exp(-0.5 * ((f - centre_hz) / width_hz) ** 2)
    gain[f < 1200] = 0.0  # no voice energy: a pure unvoiced fricative
    x = np.fft.irfft(spectrum * gain, n)
    # 80 ms ramps and a slow 3.5 Hz wobble of +-8 %, so it is not a perfectly flat machine hiss
    t = np.arange(n) / RATE
    env = np.minimum(1.0, np.minimum(t / 0.08, (seconds - t) / 0.08)) * (1 + 0.08 * np.sin(2 * np.pi * 3.5 * t))
    x *= env
    rms = np.sqrt(np.mean(x ** 2))
    return x * (10 ** (dbfs / 20) / rms)


def centroid(x):
    mag = np.abs(np.fft.rfft(x))
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float((f * mag).sum() / mag.sum())


def write(name, x):
    OUT.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    print(f"{name}: {len(x) / RATE:.2f} s")


def read(path):
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == RATE and w.getsampwidth() == 2, path
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768
        if w.getnchannels() > 1:
            x = x.reshape(-1, w.getnchannels()).mean(axis=1)
    return x


# Seconds of the source list take that hold its first word (checked on a 10 ms RMS envelope: "Sare" ends in a clean
# 180 ms pause; "Șarpe" ends after the final "e", before the next word's frication).
FIRST_WORD = {"sarah_s.wav": (0.10, 0.66), "sarah_sh.wav": (0.10, 0.64)}


def trim_speech(x, rng, before=0.3, after=0.8, cap=4.0):
    hop = RATE // 100
    rms = np.array([np.sqrt(np.mean(x[i:i + hop] ** 2)) for i in range(0, len(x) - hop, hop)])
    active = np.where(rms > rms.max() * 0.05)[0]
    a, b = active[0] * hop, (active[-1] + 1) * hop
    speech = x[a:b][: int((cap - before - after) * RATE)]
    return np.concatenate([room_tone(before, rng), speech, room_tone(after, rng)])


def main():
    rng = np.random.default_rng(20261121)
    for name, centre, width in (("s_hold.wav", 9200, 1600), ("sh_hold.wav", 3200, 800)):
        hiss = shaped_hiss(3.0, centre, width, rng)
        print(f"  centroid of the hiss: {centroid(hiss):.0f} Hz")
        write(name, np.concatenate([room_tone(0.35, rng), hiss, room_tone(0.9, rng)]))
    if len(sys.argv) > 1:
        src = Path(sys.argv[1])
        for name in ("sarah_s.wav", "sarah_sh.wav"):
            x = read(src / name)
            a, b = FIRST_WORD[name]
            write(name, trim_speech(x[int(a * RATE):int(b * RATE)], rng))
        fillers = read(src / "sarah_fillers.wav")[: int(18.6 * RATE)]
        write("fillers.wav", np.concatenate([room_tone(0.4, rng), fillers, room_tone(1.0, rng)]))


if __name__ == "__main__":
    main()
