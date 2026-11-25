"""Reference pipeline for the Kotlin port: features (NeMo-compatible), ONNX CTC, greedy decode with times."""
import json, re, time
import numpy as np
import librosa
import onnxruntime as ort

SR, NFFT, WIN, HOP, MELS = 16000, 512, 400, 160, 80
_mel = librosa.filters.mel(sr=SR, n_fft=NFFT, n_mels=MELS, fmin=0, fmax=SR / 2, norm='slaney', htk=False).astype(np.float64)
_win = np.zeros(NFFT); _win[(NFFT - WIN) // 2:(NFFT - WIN) // 2 + WIN] = np.hanning(WIN)  # torch.hann_window(periodic=False), centred in the FFT frame

def features(x):
    x = np.asarray(x, np.float64)
    x = np.concatenate([x[:1], x[1:] - 0.97 * x[:-1]])
    n = len(x) // HOP
    p = np.pad(x, NFFT // 2)
    frames = np.lib.stride_tricks.sliding_window_view(p, NFFT)[::HOP][:n]
    power = np.abs(np.fft.rfft(frames * _win, axis=1)) ** 2
    mel = np.log(power @ _mel.T + 2.0 ** -24)          # (T, 80)
    mean = mel.mean(0)
    std = np.sqrt(((mel - mean) ** 2).sum(0) / max(1, n - 1)) + 1e-5
    return ((mel - mean) / std).T.astype(np.float32)   # (80, T)

class RoAsr:
    def __init__(self, path, threads=4):
        o = ort.SessionOptions(); o.intra_op_num_threads = threads
        self.s = ort.InferenceSession(path, o, providers=['CPUExecutionProvider'])
        d = json.load(open('model/roasr.json', encoding='utf8'))
        self.tokens, self.blank = d['tokens'], d['blank']

    def logprobs(self, x):
        f = features(x)
        return self.s.run(None, {'audio_signal': f[None], 'length': np.array([f.shape[1]], np.int64)})[0][0]

    def decode(self, lp):
        """Greedy CTC. Returns text, words [(word, start_s, end_s, confidence)], blank share, mean confidence."""
        ids = lp.argmax(1); conf = np.exp(lp.max(1))
        pieces, prev = [], self.blank
        for t, i in enumerate(ids):
            if i != self.blank and i != prev:
                pieces.append([self.tokens[i], t, t, conf[t]])
            elif i != self.blank and pieces:
                pieces[-1][2] = t
            prev = i
        words = []
        for tok, a, b, c in pieces:
            if tok.startswith('▁') or not words:
                words.append([tok.lstrip('▁'), a, b, [c]])
            else:
                words[-1][0] += tok; words[-1][2] = b; words[-1][3].append(c)
        step = 0.08
        words = [(w, round(a * step, 2), round((b + 1) * step, 2), float(np.min(c))) for w, a, b, c in words if w]
        text = ' '.join(w[0] for w in words)
        nonblank = conf[ids != self.blank]
        return text, words, float((ids == self.blank).mean()), float(nonblank.mean()) if len(nonblank) else 0.0

    def transcribe(self, x):
        t = time.perf_counter(); lp = self.logprobs(x); dt = time.perf_counter() - t
        return self.decode(lp) + (dt,)

def norm(s):
    s = s.lower().replace('ş', 'ș').replace('ţ', 'ț')
    s = re.sub(r"[^a-zăâîșț0-9\- ]", ' ', s).replace('-', ' ')
    return re.sub(r'\s+', ' ', s).strip()
