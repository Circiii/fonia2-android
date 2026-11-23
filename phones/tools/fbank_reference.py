"""Reference fbank vectors for ZipaFbankTest, computed with lhotse's own code (ZIPA's front end).

Writes deterministic test signals (16 kHz, float32 little-endian, `<name>.f32`) and the features lhotse computes
for them (`<name>.fbank.f32`, frames x 80, float32 little-endian) into the test resources.

lhotse is the reference: ZIPA's training and its published inference both call `Fbank()` /
`FbankConfig(num_filters=80, dither=0.0, snip_edges=False)`, i.e. `Wav2LogFilterBank` with the defaults below.

    pip install torch numpy lhotse==1.33.0
    python -I phones/tools/fbank_reference.py phones/src/test/resources/fbank

Without lhotse installed, point at `lhotse/features/kaldi/layers.py` from the lhotse 1.33.0 wheel
(sha256 8697bc74a8f3101594fca5661c7318c30899f3fdb132a44c7e99e794be6ac061); only that file and torch are needed:

    python -I phones/tools/fbank_reference.py phones/src/test/resources/fbank --layers <path>/layers.py
"""
import argparse
import importlib.util
import os
import sys
import types

import numpy as np
import torch

RATE = 16000


def load_layers(path):
    if path is None:
        from lhotse.features.kaldi import layers  # noqa: E402
        return layers
    package = types.ModuleType("lhotse")
    package.__path__ = []
    utils = types.ModuleType("lhotse.utils")
    utils.EPSILON = 1e-10  # lhotse/utils.py
    utils.Seconds = float
    sys.modules.setdefault("lhotse", package)
    sys.modules.setdefault("lhotse.utils", utils)
    spec = importlib.util.spec_from_file_location("lhotse_kaldi_layers", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def extractor(layers):
    # lhotse.features.kaldi.extractors.FbankConfig defaults (lhotse 1.33.0), as Fbank() builds it.
    return layers.Wav2LogFilterBank(
        sampling_rate=16000, frame_length=0.025, frame_shift=0.01, round_to_power_of_two=True,
        remove_dc_offset=True, preemph_coeff=0.97, window_type="povey", dither=0.0, snip_edges=False,
        energy_floor=1e-10, raw_energy=True, use_energy=False, use_fft_mag=False, low_freq=20.0,
        high_freq=-400.0, num_filters=80, norm_filters=False, torchaudio_compatible_mel_scale=True,
    ).eval()


def resonate(x, freq, bandwidth):
    """Two-pole resonator, applied sample by sample in float64."""
    r = np.exp(-np.pi * bandwidth / RATE)
    a1, a2 = -2 * r * np.cos(2 * np.pi * freq / RATE), r * r
    y = np.zeros_like(x)
    for n in range(len(x)):
        y[n] = x[n] - a1 * (y[n - 1] if n >= 1 else 0) - a2 * (y[n - 2] if n >= 2 else 0)
    return y * (1 - r)


def signals():
    rng = np.random.default_rng(20261106)
    t = np.arange(20837) / RATE
    tones = 0.3 * np.sin(2 * np.pi * 440 * t) + 0.1 * np.sin(2 * np.pi * 3000 * t)
    chirp = 0.2 * np.sin(2 * np.pi * (100 * t + 0.5 * (7900 - 100) / t[-1] * t * t))
    mix = tones + chirp
    mix[:1600] = 0.0  # 0.1 s of digital silence
    mix[8000:9600] += 0.25 * rng.standard_normal(1600)  # a noise burst
    mix[12000:13000] += 0.2  # a DC step
    mix[15000] = 0.95  # a click
    yield "mix", mix

    yield "short", 0.1 * rng.standard_normal(4000) + 0.2 * np.sin(2 * np.pi * 1000 * np.arange(4000) / RATE)

    pulses = np.zeros(7777)
    pulses[::RATE // 120] = 1.0
    voiced = resonate(resonate(resonate(pulses, 700, 90), 1200, 110), 2600, 160) * 40
    hiss = rng.standard_normal(7777)
    hiss = np.diff(hiss, prepend=0.0) * 0.08  # tilted towards the top of the band, like a sibilant
    envelope = np.zeros(7777)
    envelope[5000:7000] = np.hanning(2000)
    yield "speechlike", np.clip(voiced, -0.9, 0.9) * (1 - envelope) + hiss * envelope

    yield "tiny", 0.05 * rng.standard_normal(250)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out")
    parser.add_argument("--layers", help="lhotse/features/kaldi/layers.py when lhotse is not installed")
    args = parser.parse_args()
    layers = load_layers(args.layers)
    fbank = extractor(layers)
    os.makedirs(args.out, exist_ok=True)
    for name, signal in signals():
        samples = np.asarray(signal, dtype=np.float32)
        with torch.no_grad():
            features = fbank(torch.from_numpy(samples).unsqueeze(0))[0]
        frames = (len(samples) + 80) // 160  # lhotse.utils.compute_num_frames_from_samples
        features = features[:frames].numpy().astype("<f4")
        samples.astype("<f4").tofile(os.path.join(args.out, f"{name}.f32"))
        features.tofile(os.path.join(args.out, f"{name}.fbank.f32"))
        print(f"{name}: {len(samples)} samples, {features.shape[0]} frames, "
              f"min {features.min():.6f}, max {features.max():.6f}")


if __name__ == "__main__":
    main()
