import glob, subprocess, numpy as np, sys
from roasr import RoAsr
def load(path):
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', path, '-ac', '1', '-ar', '16000', '-f', 'f32le', '-'], capture_output=True).stdout
    return np.frombuffer(raw, np.float32).copy()
asr = RoAsr('model/roasr_ctc.int8.onnx')
for p in sorted(glob.glob('mis/*/*.mp3')):
    t, w, blank, conf, dt = asr.transcribe(load(p)); print(p[4:5], '|', t, '| blank', round(blank, 2), 'conf', round(conf, 2), [(a, round(c, 2)) for a, _, _, c in w])
rng = np.random.default_rng(1)
x = load(glob.glob('mis/1/*.mp3')[0])
for name, sig in (('silence 3 s', np.zeros(48000, np.float32)), ('white noise -30 dB', rng.normal(0, 0.03, 48000).astype(np.float32)), ('hum 100 Hz', (0.1 * np.sin(2 * np.pi * 100 * np.arange(48000) / 16000)).astype(np.float32)),
                  ('speech at -40 dB', x * 0.01), ('speech + noise SNR 5 dB', x + rng.normal(0, np.sqrt((x ** 2).mean() / 3.16), len(x)).astype(np.float32)), ('speech + noise SNR 0 dB', x + rng.normal(0, np.sqrt((x ** 2).mean()), len(x)).astype(np.float32))):
    t, w, blank, conf, dt = asr.transcribe(sig); print(name, '|', repr(t), '| blank', round(blank, 2), 'conf', round(conf, 2))
# short clips: timing of a 1 s word and a 10 s sentence
import time
for n in (1, 3, 10, 30):
    s = np.tile(x, 20)[:16000 * n]; t0 = time.perf_counter(); asr.logprobs(s); print(n, 's audio ->', round((time.perf_counter() - t0) * 1000), 'ms')
