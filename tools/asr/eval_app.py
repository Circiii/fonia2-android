import csv, sys, json, subprocess, numpy as np, jiwer, io
from roasr import RoAsr, norm
ROOT = 'C:/f/asr2/'
def load(path):
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', path, '-ac', '1', '-ar', '16000', '-f', 'f32le', '-'], capture_output=True).stdout
    return np.frombuffer(raw, np.float32)
asr = RoAsr('model/roasr_ctc.int8.onnx')
rows = []
for f in ('tools/voice/voice-list.csv', 'tools/voice/generated-list.csv'):
    rows += [r for r in csv.DictReader(open(ROOT + f, encoding='utf-8-sig')) if r['limba'] == 'ro']
import os
words, sents, bad = [], [], []
for r in rows:
    p = ROOT + 'content/voice/' + r['fisier']
    if not os.path.exists(p): continue
    ref = norm(r['text'])
    if not ref: continue
    text, ws, blank, conf, dt = asr.transcribe(load(p))
    hyp = norm(text)
    (words if len(ref.split()) == 1 else sents).append((ref, hyp, conf, blank))
    if ref != hyp: bad.append((ref, hyp, round(conf, 2)))
print('single words', len(words), 'exact', sum(a == b for a, b, *_ in words))
print('sentences', len(sents), 'exact', sum(a == b for a, b, *_ in sents), 'WER', round(100 * jiwer.wer([a for a, *_ in sents], [b for _, b, *_ in sents]), 2))
for b in bad[:45]: print(b)
