"""Fonia's sound logo, built note by note (no generator): a short, bright mallet run and a bell chord.

    python tools/sfx/make_logo.py <variant> <out.wav>

Variants: 'hop' (four quick rising notes, then the chord), 'salut' (three notes, call and answer),
'tril' (a rolled run into a high bell). The note times printed at the end are the ones the start screen follows.
"""
import sys
import numpy as np
from scipy.signal import fftconvolve, butter, sosfilt

SR = 48000
rng = np.random.default_rng(11)

def hz(name):
    names = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
    semis = names[name[0]] + (1 if '#' in name else 0) + 12 * (int(name[-1]) + 1)
    return 440.0 * 2 ** ((semis - 69) / 12)

def env(n, attack=0.002):
    a = int(SR * attack)
    e = np.ones(n)
    e[:a] = np.linspace(0, 1, a) ** 0.5
    return e

def mallet(f, dur=0.6, amp=1.0):
    """A marimba-like bar: the fundamental, the bar's 4th and 10th partials dying fast, a warm octave below, a soft knock."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / 0.20)
    x += 0.32 * np.sin(2 * np.pi * 3.98 * f * t) * np.exp(-t / 0.045)
    x += 0.10 * np.sin(2 * np.pi * 9.9 * f * t) * np.exp(-t / 0.018)
    x += 0.22 * np.sin(2 * np.pi * 0.5 * f * t) * np.exp(-t / 0.11)
    knock = rng.normal(0, 1, n) * np.exp(-t / 0.004)
    x += 0.18 * sosfilt(butter(2, [1500, 6000], 'bandpass', fs=SR, output='sos'), knock)
    return amp * x * env(n)

def bell(f, dur=1.5, amp=1.0):
    """A small vibraphone-bell: stretched partials, the high ones fading first, a slow shimmer."""
    n = int(SR * dur)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for ratio, a, decay in ((1, 1.0, 0.75), (2.0, 0.5, 0.55), (3.01, 0.28, 0.36), (4.2, 0.16, 0.22), (5.43, 0.09, 0.14)):
        x += a * np.sin(2 * np.pi * ratio * f * t + rng.uniform(0, 6.28)) * np.exp(-t / decay)
    x *= 1 + 0.06 * np.sin(2 * np.pi * 5.5 * t)
    return amp * x * env(n, 0.003)

def sparkle(start, dur=0.5, amp=0.05):
    """A few tiny high glints after the chord."""
    n = int(SR * (start + dur + 0.3))
    x = np.zeros(n)
    for _ in range(9):
        at = int(SR * (start + rng.uniform(0, dur)))
        m = int(SR * 0.09)
        t = np.arange(m) / SR
        x[at:at + m] += amp * np.sin(2 * np.pi * rng.choice([hz('E7'), hz('G7'), hz('C8'), hz('D8')]) * t) * np.exp(-t / 0.03)
    return x

def place(mix, x, at):
    i = int(SR * at)
    if len(mix) < i + len(x):
        mix = np.pad(mix, (0, i + len(x) - len(mix)))
    mix[i:i + len(x)] += x
    return mix

def render(variant):
    mix = np.zeros(SR)
    if variant == 'hop':
        times = [0.00, 0.09, 0.18, 0.27, 0.45]
        for name, at, amp in (('C6', 0.00, 0.75), ('E6', 0.09, 0.8), ('G6', 0.18, 0.85), ('C7', 0.27, 0.9)):
            mix = place(mix, mallet(hz(name), amp=amp), at)
        chord = (('C6', 0.45), ('G6', 0.5), ('E7', 0.8), ('C7', 0.6))
    elif variant == 'salut':
        times = [0.00, 0.07, 0.24, 0.31, 0.50]
        for name, at, amp in (('G5', 0.00, 0.6), ('E6', 0.07, 0.9), ('C6', 0.24, 0.6), ('G6', 0.31, 0.9)):
            mix = place(mix, mallet(hz(name), amp=amp), at)
        chord = (('C6', 0.4), ('E6', 0.45), ('G6', 0.5), ('C7', 0.85))
    else:  # tril
        times = [0.00, 0.06, 0.12, 0.18, 0.30]
        for name, at, amp in (('G5', 0.00, 0.6), ('C6', 0.06, 0.7), ('E6', 0.12, 0.8), ('G6', 0.18, 0.9)):
            mix = place(mix, mallet(hz(name), dur=0.4, amp=amp), at)
        chord = (('C7', 0.9), ('E7', 0.55), ('G6', 0.45))
    last = times[-1]
    for name, amp in chord:
        mix = place(mix, 0.5 * bell(hz(name), amp=amp) + 0.6 * mallet(hz(name), dur=1.5, amp=amp), last)
    mix = place(mix, sparkle(last + 0.05), 0)
    # A small bright room, different in each ear.
    out = []
    for _ in range(2):
        n = int(SR * 0.9)
        t = np.arange(n) / SR
        room = rng.normal(0, 1, n) * np.exp(-t / 0.22)
        room = sosfilt(butter(2, [500, 7000], 'bandpass', fs=SR, output='sos'), room)
        wet = fftconvolve(mix, room)[:len(mix) + n]
        dry = np.pad(mix, (0, n))[:len(wet)]
        out.append(dry + 0.035 * wet / (np.abs(room).sum() ** 0.5 / 12))
    y = np.stack(out, 1)
    y = y[:int(SR * (last + 1.25))]
    fade = int(SR * 0.25)
    y[-fade:] *= np.linspace(1, 0, fade)[:, None]
    y = np.tanh(1.4 * y / np.abs(y).max()) / np.tanh(1.4) * 0.89
    return y, times

if __name__ == '__main__':
    import wave
    y, times = render(sys.argv[1])
    w = wave.open(sys.argv[2], 'wb')
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((y * 32767).astype('<i2').tobytes())
    w.close()
    print(sys.argv[1], 'notes', times, 'seconds', round(len(y) / SR, 2))
