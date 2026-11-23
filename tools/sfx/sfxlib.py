"""Shared DSP for Fonia's sound-effect set: decode, measure (BS.1770 momentary / true peak), clean up, key-fit,
warm (saturation), room (computed reverb), synthesise layers.

Signals are float64 at 48 kHz, mono (n,) or stereo (n, 2). Run with any Python that has numpy + scipy + soundfile
(earshot's uv-tool venv has them; or `uv run --with numpy,scipy,soundfile`).
"""
from __future__ import annotations

import subprocess
from pathlib import Path

import numpy as np
from scipy import signal

SR = 48_000

# ---------------------------------------------------------------- I/O


def load(path: str | Path, stereo: bool = False) -> np.ndarray:
    """Any ffmpeg-readable file -> float64 at 48 kHz, mono (n,) or stereo (n, 2)."""
    ch = 2 if stereo else 1
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", str(ch), "-ar", str(SR), "-f", "f32le", "-"],
        check=True, capture_output=True,
    ).stdout
    x = np.frombuffer(raw, dtype=np.float32).astype(np.float64)
    return x.reshape(-1, 2) if stereo else x


def save_wav(path: str | Path, x: np.ndarray) -> None:
    import soundfile as sf
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), x.astype(np.float32), SR, subtype="PCM_24")


def encode_ogg(wav: Path, ogg: Path, quality: float = 4.0) -> None:
    ogg.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-ar", str(SR), "-c:a", "libvorbis",
                    "-q:a", str(quality), "-map_metadata", "-1", str(ogg)], check=True)


def mono(x: np.ndarray) -> np.ndarray:
    return x.mean(axis=1) if x.ndim == 2 else x


def stereo(x: np.ndarray) -> np.ndarray:
    return x if x.ndim == 2 else np.stack([x, x], axis=1)

# ---------------------------------------------------------------- notes


NOTE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
NAMES = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
WHITE = {0, 2, 4, 5, 7, 9, 11}  # C major


def hz(name: str) -> float:
    letter, rest = name[0], name[1:]
    shift = 0
    while rest and rest[0] in "#b":
        shift += 1 if rest[0] == "#" else -1
        rest = rest[1:]
    midi = 12 * (int(rest) + 1) + NOTE[letter] + shift
    return 440.0 * 2 ** ((midi - 69) / 12)


def nearest_note(f: float) -> tuple[str, float]:
    m = 69 + 12 * np.log2(f / 440.0)
    r = int(round(m))
    return f"{NAMES[r % 12]}{r // 12 - 1}", float(100 * (m - r))

# ---------------------------------------------------------------- measures (stereo-aware)


_K1 = ([1.53512485958697, -2.69169618940638, 1.19839281085285], [1.0, -1.69065929318241, 0.73248077421585])
_K2 = ([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621])


def momentary_max(x: np.ndarray) -> float:
    """Max BS.1770 momentary loudness (400 ms windows, 10 ms hop, channels summed), LUFS."""
    x = stereo(x) if x.ndim == 2 else x[:, None]
    pad = np.zeros((int(0.4 * SR), x.shape[1]))
    y = np.concatenate([pad, x, pad])
    y = signal.lfilter(*_K1, y, axis=0)
    y = signal.lfilter(*_K2, y, axis=0)
    p = (y * y).sum(axis=1)
    win, hop = int(0.4 * SR), int(0.01 * SR)
    c = np.concatenate([[0.0], np.cumsum(p)])
    starts = np.arange(0, len(p) - win, hop)
    m = ((c[starts + win] - c[starts]) / win).max()
    return float(-0.691 + 10 * np.log10(m)) if m > 0 else -120.0


def true_peak_db(x: np.ndarray) -> float:
    up = signal.resample_poly(x, 4, 1, axis=0)
    p = np.max(np.abs(up))
    return float(20 * np.log10(p)) if p > 0 else -120.0


def sample_peak_db(x: np.ndarray) -> float:
    p = np.max(np.abs(x))
    return float(20 * np.log10(p)) if p > 0 else -120.0


def lead_silence_ms(x: np.ndarray, rel_db: float = -40.0) -> float:
    a = np.abs(mono(x)) if x.ndim == 1 else np.abs(x).max(axis=1)
    idx = np.argmax(a >= a.max() * 10 ** (rel_db / 20))
    return 1000.0 * idx / SR


def tail_db(x: np.ndarray, ms: float = 10.0) -> float:
    m = mono(x)
    n = int(ms * SR / 1000)
    r = np.sqrt(np.mean(m[-n:] ** 2)) + 1e-12
    return float(20 * np.log10(r / (np.max(np.abs(m)) + 1e-12)))


def band_share(x: np.ndarray, lo: float = 0.0, hi: float = 300.0) -> float:
    m = mono(x)
    f, p = signal.welch(m, SR, nperseg=min(4096, len(m)))
    return float(p[(f >= lo) & (f < hi)].sum() / (p.sum() + 1e-20))


def centroid_hz(x: np.ndarray) -> float:
    m = mono(x)
    f, p = signal.welch(m, SR, nperseg=min(4096, len(m)))
    return float((f * p).sum() / (p.sum() + 1e-20))


def width_db(x: np.ndarray) -> float | None:
    """Side / mid energy (dB); None for mono. Under about -6 dB folds down to mono safely."""
    if x.ndim == 1:
        return None
    mid, side = x.mean(axis=1), (x[:, 0] - x[:, 1]) / 2
    return float(10 * np.log10((side ** 2).sum() / ((mid ** 2).sum() + 1e-20) + 1e-20))


def _peaks(x: np.ndarray, n: int, fmin: float):
    m = mono(x)
    N = max(len(m), 1 << 16)
    spec = np.abs(np.fft.rfft(m * np.hanning(len(m)), n=N))
    freqs = np.fft.rfftfreq(N, 1 / SR)
    spec[(freqs < fmin) | (freqs > 6000)] = 0
    peaks, _ = signal.find_peaks(spec, distance=40)
    top = peaks[np.argsort(spec[peaks])[::-1][:n]]
    return freqs[top], spec[top]


def pitches(x: np.ndarray, n: int = 3, fmin: float = 200.0) -> list[tuple[float, str, float]]:
    f, _ = _peaks(x, n, fmin)
    return [(float(v), *nearest_note(float(v))) for v in f]


def key_offset_cents(x: np.ndarray, max_shift: float = 150.0, n: int = 10) -> tuple[float, float]:
    """The shift (cents) that best lands the strongest partials on C major, and the remaining weighted error
    (cents). A small penalty prefers small shifts."""
    f, a = _peaks(x, n, 250.0)
    if len(f) == 0:
        return 0.0, 0.0
    w = a / a.sum()
    midi = 69 + 12 * np.log2(f / 440.0)
    best = (0.0, 1e9, 1e9)
    for sh in np.arange(-max_shift, max_shift + 1, 5.0):
        m = midi + sh / 100
        r = np.round(m)
        err = np.abs(m - r) * 100
        off = np.array([0.0 if int(k) % 12 in WHITE else 100.0 for k in r])
        score = float((w * (err + off)).sum()) + abs(sh) * 0.04
        if score < best[1]:
            best = (float(sh), score, float((w * (err + off)).sum()))
    return best[0], best[2]

# ---------------------------------------------------------------- processing


def shift(x: np.ndarray, cents: float) -> np.ndarray:
    """Pitch by resampling (tape-style: shorter when higher)."""
    if abs(cents) < 1:
        return x
    n = int(round(len(x) / 2 ** (cents / 1200)))
    return signal.resample(x, n, axis=0)


def highpass(x: np.ndarray, fc: float = 250.0, order: int = 4) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, fc, "highpass", fs=SR, output="sos"), x, axis=0)


def lowpass(x: np.ndarray, fc: float = 14_000.0, order: int = 4) -> np.ndarray:
    return signal.sosfilt(signal.butter(order, fc, "lowpass", fs=SR, output="sos"), x, axis=0)


def shelf_db(x: np.ndarray, fc: float, gain_db: float, high: bool = True) -> np.ndarray:
    """A gentle first-order shelf (band split and re-mix)."""
    sos = signal.butter(1, fc, "highpass" if high else "lowpass", fs=SR, output="sos")
    band = signal.sosfilt(sos, x, axis=0)
    return x + band * (10 ** (gain_db / 20) - 1)


def saturate(x: np.ndarray, drive: float = 2.0, mix: float = 0.3) -> np.ndarray:
    """Warmth: tanh soft saturation on a peak-normalised copy, blended in; level kept."""
    p = np.max(np.abs(x)) + 1e-12
    y = np.tanh(drive * x / p) / np.tanh(drive) * p
    return (1 - mix) * x + mix * y


def room_ir(rt60: float, seed: int = 7, predelay_ms: float = 8.0, damp_hz: float = 6000.0) -> np.ndarray:
    """A computed stereo room: decorrelated exponentially decaying noise, darker as it decays (no recording)."""
    rng = np.random.default_rng(seed)
    n = int(rt60 * 1.2 * SR)
    t = np.arange(n) / SR
    env = np.exp(-6.91 * t / rt60)
    ir = rng.standard_normal((n, 2)) * env[:, None]
    # Darken: blend a low-passed copy in more over time.
    dark = signal.sosfilt(signal.butter(2, damp_hz, "lowpass", fs=SR, output="sos"), ir, axis=0)
    k = np.clip(t / rt60, 0, 1)[:, None]
    ir = (1 - k) * ir + k * dark
    ir = signal.sosfilt(signal.butter(2, 300, "highpass", fs=SR, output="sos"), ir, axis=0)
    pre = np.zeros((int(predelay_ms * SR / 1000), 2))
    ir = np.concatenate([pre, ir])
    return ir / np.sqrt((ir ** 2).sum(axis=0).mean())


def reverb(x: np.ndarray, rt60: float, wet: float, seed: int = 7, width: float = 1.0) -> np.ndarray:
    """Dry centred (or the source's own stereo) + a stereo room at `wet` (energy ratio); `width` scales the room's
    side signal. Always returns stereo, with the tail appended."""
    d = stereo(x)
    ir = room_ir(rt60, seed)
    src = mono(x)
    w = np.stack([signal.fftconvolve(src, ir[:, 0]), signal.fftconvolve(src, ir[:, 1])], axis=1)
    mid, side = w.mean(axis=1, keepdims=True), (w[:, :1] - w[:, 1:]) / 2
    w = np.concatenate([mid + width * side, mid - width * side], axis=1)
    out = np.zeros((max(len(d), len(w)), 2))
    out[: len(d)] += d
    out[: len(w)] += w * np.sqrt(wet) * np.sqrt((src ** 2).sum() / ((w ** 2).sum() / 2 + 1e-20))
    return out


def narrow(x: np.ndarray, width: float) -> np.ndarray:
    """Scale the side signal of a stereo source (phone speakers fold to mono)."""
    if x.ndim == 1:
        return x
    mid, side = x.mean(axis=1, keepdims=True), (x[:, :1] - x[:, 1:]) / 2
    return np.concatenate([mid + width * side, mid - width * side], axis=1)


def trim(x: np.ndarray, head_db: float = -45.0, tail_floor_db: float = -60.0, pre_ms: float = 1.0,
         fade_in_ms: float = 2.0, fade_out_ms: float = 15.0, max_s: float | None = None,
         start_s: float = 0.0) -> np.ndarray:
    """Tight head (onset - pre_ms, searched from start_s), tail cut where the 5 ms envelope stays under
    tail_floor_db, raised-cosine fades."""
    a = np.abs(x) if x.ndim == 1 else np.abs(x).max(axis=1)
    a0 = a.copy()
    a0[: int(start_s * SR)] = 0
    peak = a0.max()
    start = max(0, int(np.argmax(a0 >= peak * 10 ** (head_db / 20))) - int(pre_ms * SR / 1000))
    hop = int(0.005 * SR)
    env = np.sqrt(np.convolve(a * a, np.ones(hop) / hop, mode="same"))
    above = np.nonzero(env >= peak * 10 ** (tail_floor_db / 20))[0]
    end = int(above[-1]) + 1 if len(above) else len(x)
    if max_s is not None:
        end = min(end, start + int(max_s * SR))
    y = x[start:end].copy()
    fi = int(fade_in_ms * SR / 1000)
    fo = min(int(fade_out_ms * SR / 1000), len(y) // 2)
    sh = (-1, 1) if y.ndim == 2 else (-1,)
    if fi:
        y[:fi] *= (0.5 - 0.5 * np.cos(np.linspace(0, np.pi, fi))).reshape(sh)
    if fo:
        y[-fo:] *= (0.5 + 0.5 * np.cos(np.linspace(0, np.pi, fo))).reshape(sh)
    return y


def limit(x: np.ndarray, ceiling_db: float, max_gr_db: float = 4.0, release_ms: float = 40.0) -> np.ndarray:
    """Look-ahead peak limiter (2 ms), at most max_gr_db of gain reduction, linked channels."""
    from scipy.ndimage import minimum_filter1d
    ceil = 10 ** (ceiling_db / 20)
    floor = 10 ** (-max_gr_db / 20)
    up = np.abs(signal.resample_poly(x, 4, 1, axis=0))
    a = (up if up.ndim == 1 else up.max(axis=1))[::4][: len(x)]
    need = np.clip(ceil / np.maximum(a, 1e-12), floor, 1.0)
    look = int(0.002 * SR)
    need = minimum_filter1d(need, size=2 * look + 1, mode="nearest")
    rel = np.exp(-1.0 / (release_ms * SR / 1000))
    g = np.empty_like(need)
    cur = 1.0
    for i, n in enumerate(need):
        cur = n if n < cur else n + (cur - n) * rel
        g[i] = cur
    return x * (g[:, None] if x.ndim == 2 else g)


def normalise(x: np.ndarray, target_m: float, tp_ceiling: float = -3.0) -> tuple[np.ndarray, float, bool]:
    """Gain to target max-momentary loudness; the limiter (<= 4 dB) first when the peak would pass the ceiling,
    then lower gain for what is left. Returns (signal, gain_db, peak_limited)."""
    g = target_m - momentary_max(x)
    y = x * 10 ** (g / 20)
    if true_peak_db(y) > tp_ceiling:
        y = limit(y, tp_ceiling - 0.3)
        g2 = target_m - momentary_max(y)
        y *= 10 ** (g2 / 20)
        g += g2
    tp = true_peak_db(y)
    limited = tp > tp_ceiling
    if limited:
        y *= 10 ** ((tp_ceiling - tp) / 20)
        g += tp_ceiling - tp
    return y, g, limited

# ---------------------------------------------------------------- computed layers (never shipped alone)


def _t(dur: float) -> np.ndarray:
    return np.arange(int(dur * SR)) / SR


def thump(note: str = "C3", dur: float = 0.12) -> np.ndarray:
    """A soft low body under a hit: a sine with a falling pitch blip and its octave (so a phone hears it)."""
    t = _t(dur)
    f = hz(note) * (1 + 0.6 * np.exp(-t / 0.012))
    ph = 2 * np.pi * np.cumsum(f) / SR
    env = (1 - np.exp(-t / 0.002)) * np.exp(-t / 0.035)
    return (np.sin(ph) + 0.35 * np.sin(2 * ph)) * env


def glock(note: str, dur: float = 0.6, vel: float = 1.0, damp: float = 1.0) -> np.ndarray:
    f = hz(note)
    t = _t(dur)
    y = np.zeros_like(t)
    for r, a, tau in zip([1.0, 2.76, 5.40], [1.0, 0.22, 0.08], [0.5 * damp, 0.15 * damp, 0.06]):
        y += a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / tau)
    return y * np.clip(t / 0.001, 0, 1) * vel


def place(events, length: float | None = None, channels: int = 1) -> np.ndarray:
    """events: [(time_s, array)] -> one mix (mono, or stereo when any event is)."""
    st = channels == 2 or any(a.ndim == 2 for _, a in events)
    end = max(int(t * SR) + len(a) for t, a in events)
    if length:
        end = max(end, int(length * SR))
    y = np.zeros((end, 2)) if st else np.zeros(end)
    for t, a in events:
        i = int(t * SR)
        y[i:i + len(a)] += stereo(a) if st else a
    return y
