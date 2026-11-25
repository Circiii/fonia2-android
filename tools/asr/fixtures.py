import numpy as np, json, subprocess, shutil, os, soundfile as sf, hashlib
from roasr import features, RoAsr
N = 24000
i = np.arange(N, dtype=np.float64)
seed = 12345; noise = np.empty(N)
for k in range(N):
    seed = (seed * 1103515245 + 12345) % 2147483648; noise[k] = seed / 2147483648 - 0.5
x = (0.3 * np.sin(2 * np.pi * (200 + 3000 * i / N) * i / 16000) + 0.01 * noise).astype(np.float32)
f = features(x)
pts = {f"{b},{t}": float(f[b, t]) for b in (0, 7, 40, 79) for t in (0, 1, 50, 149)}
json.dump({'samples': N, 'frames': f.shape[1], 'points': pts}, open('C:/f/asr2/words/src/test/resources/features.json', 'w'), indent=1)
cache = os.path.expanduser('~/.gradle/fonia-models/roasr-parakeet-ro-110m'); os.makedirs(cache, exist_ok=True)
shutil.copy('model/roasr_ctc.int8.onnx', cache + '/ctc.int8.onnx')
import glob
raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', glob.glob('mis/2/*.mp3')[0], '-ac', '1', '-ar', '16000', '-f', 'f32le', '-'], capture_output=True).stdout
y = np.frombuffer(raw, np.float32); sf.write(cache + '/reference.wav', y, 16000, subtype='PCM_16')
y16, _ = sf.read(cache + '/reference.wav', dtype='float32')
asr = RoAsr('model/roasr_ctc.int8.onnx'); text, words, blank, conf, dt = asr.transcribe(y16)
json.dump({'text': text, 'words': [[w, a, b] for w, a, b, c in words], 'blank': blank, 'confidence': conf}, open(cache + '/reference.json', 'w', encoding='utf8'), ensure_ascii=False)
h = hashlib.sha256(open(cache + '/ctc.int8.onnx', 'rb').read()).hexdigest()
print(text, words, 'sha', h, os.path.getsize(cache + '/ctc.int8.onnx'))
