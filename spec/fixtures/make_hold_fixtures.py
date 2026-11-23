"""Synthetic `Hold` fixtures for the seven sounds without one (M3: one hold game per sound, screenshots mid-play).

    python spec/fixtures/make_hold_fixtures.py

48 kHz mono 16-bit, room tone around, deterministic (fixed seeds). They drive the live band so each sound's game
moves; they are NOT speech and say nothing about the judge's accuracy (the judge may well call them Retake/Unsure).

z_hold.wav  : 2.6 s voiced buzz (F0 140 Hz, harmonics) + an S-like hiss  -> voicing + friction (Zuzu flies)
j_hold.wav  : 2.6 s voiced buzz + a Ș-like hiss                          -> voicing + friction (the pinwheel spins)
ts_hold.wav : 4 short hiss bursts (90 ms) 380 ms apart                   -> onsets (Țuțu hops)
ce_hold.wav : 4 bursts: 70 ms hiss + 120 ms "i" vowel                    -> onsets (Cici splashes)
ge_hold.wav : 4 bursts: 60 ms voiced hiss + 140 ms "e" vowel             -> onsets (Gigel starts)
r_hold.wav  : 2.2 s voiced, amplitude-modulated at 26 Hz (a trill)       -> trill / taps (Rică roars)
l_hold.wav  : 4 "la" syllables (voiced, 220 ms) with dips between         -> voiced onsets (Lalalu sings)
"""
import wave
from pathlib import Path

import numpy as np

RATE = 48_000
OUT = Path(__file__).resolve().parent / "audio"


def tone(seconds, rng, dbfs=-66.0):
    return rng.standard_normal(int(seconds * RATE)) * 10 ** (dbfs / 20)


def hiss(seconds, centre, width, rng, dbfs=-22.0):
    n = int(seconds * RATE)
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    gain = np.exp(-0.5 * ((f - centre) / width) ** 2)
    gain[f < 1200] = 0
    x = np.fft.irfft(spec * gain, n)
    x /= np.sqrt(np.mean(x ** 2)) + 1e-12
    return x * 10 ** (dbfs / 20)


def voice(seconds, f0=140.0, dbfs=-20.0, formants=(700, 1200, 2600)):
    n = int(seconds * RATE)
    t = np.arange(n) / RATE
    vib = 1 + 0.01 * np.sin(2 * np.pi * 5 * t)
    phase = 2 * np.pi * np.cumsum(f0 * vib) / RATE
    x = np.zeros(n)
    for h in range(1, 30):
        fh = f0 * h
        if fh > 8000:
            break
        amp = sum(np.exp(-0.5 * ((fh - F) / 140) ** 2) for F in formants) + 0.05 / h
        x += amp * np.sin(h * phase)
    x /= np.sqrt(np.mean(x ** 2)) + 1e-12
    return x * 10 ** (dbfs / 20)


def ramp(x, ms=12):
    k = int(RATE * ms / 1000)
    if len(x) > 2 * k:
        w = np.linspace(0, 1, k)
        x[:k] *= w
        x[-k:] *= w[::-1]
    return x


def write(name, x):
    x = np.clip(x, -1, 1)
    with wave.open(str(OUT / name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((x * 32767).astype("<i2").tobytes())
    print(name, f"{len(x) / RATE:.2f} s")


def sustained(rng, centre, width):
    body = ramp(voice(2.6, dbfs=-21) + hiss(2.6, centre, width, rng, dbfs=-26))
    return np.concatenate([tone(0.35, rng), body, tone(0.9, rng)])


def bursts(rng, parts, gap=0.38, count=4):
    out = [tone(0.35, rng)]
    for _ in range(count):
        out.append(ramp(np.concatenate([p(rng) for p in parts]), ms=6))
        out.append(tone(gap, rng))
    out.append(tone(0.6, rng))
    return np.concatenate(out)


def main():
    rng = np.random.default_rng(7)
    write("z_hold.wav", sustained(rng, 8500, 1400))
    write("j_hold.wav", sustained(rng, 3200, 700))
    write("ts_hold.wav", bursts(rng, [lambda r: hiss(0.09, 7000, 1500, r, dbfs=-18)]))
    write("ce_hold.wav", bursts(rng, [lambda r: hiss(0.07, 3600, 800, r, dbfs=-20), lambda r: voice(0.12, 220, -20, (300, 2300, 3000))]))
    write("ge_hold.wav", bursts(rng, [lambda r: voice(0.06, 130, -24) + hiss(0.06, 3400, 700, r, dbfs=-26),
                                      lambda r: voice(0.14, 130, -19, (500, 1800, 2600))]))
    n = int(2.2 * RATE)
    t = np.arange(n) / RATE
    trill = voice(2.2, 120, -19, (600, 1300, 1700)) * (0.35 + 0.65 * (0.5 + 0.5 * np.sin(2 * np.pi * 26 * t)))
    write("r_hold.wav", np.concatenate([tone(0.35, rng), ramp(trill), tone(0.9, rng)]))
    la = []
    for _ in range(4):
        syl = voice(0.22, 200, -19, (650, 1250, 2700))
        syl[: int(0.05 * RATE)] *= 0.25  # the "l": quieter, then the vowel
        la += [ramp(syl), tone(0.16, rng, dbfs=-60)]
    write("l_hold.wav", np.concatenate([tone(0.35, rng)] + la + [tone(0.8, rng)]))


if __name__ == "__main__":
    main()
