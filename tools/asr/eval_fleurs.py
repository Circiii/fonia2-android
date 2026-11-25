import sys, io, json, time, numpy as np, soundfile as sf, librosa, jiwer
from huggingface_hub import hf_hub_download, list_repo_files
import pyarrow.parquet as pq
from roasr import RoAsr, norm
files = [f for f in list_repo_files('google/fleurs', repo_type='dataset', revision='refs/convert/parquet') if f.startswith('ro_ro/test')]
print(files)
tab = pq.read_table(hf_hub_download('google/fleurs', files[0], repo_type='dataset', revision='refs/convert/parquet'))
rows = tab.slice(0, int(sys.argv[2]) if len(sys.argv) > 2 else 300).to_pylist()
asr = RoAsr(sys.argv[1])
refs, hyps, audio_s, proc_s, out = [], [], 0.0, 0.0, []
for r in rows:
    x, sr = sf.read(io.BytesIO(r['audio']['bytes']), dtype='float32')
    if sr != 16000: x = librosa.resample(x, orig_sr=sr, target_sr=16000)
    text, words, blank, conf, dt = asr.transcribe(x)
    refs.append(norm(r['transcription'])); hyps.append(norm(text)); audio_s += len(x) / 16000; proc_s += dt
    out.append({'ref': refs[-1], 'hyp': hyps[-1], 'blank': blank, 'conf': conf})
print(sys.argv[1], 'utts', len(rows), 'audio min', round(audio_s / 60, 1), 'WER', round(100 * jiwer.wer(refs, hyps), 2), 'CER', round(100 * jiwer.cer(refs, hyps), 2), 'RTF', round(proc_s / audio_s, 4))
json.dump(out, open('out_fleurs_%s.json' % ('int8' if 'int8' in sys.argv[1] else 'f32'), 'w', encoding='utf8'), ensure_ascii=False, indent=0)
for o in out[:4]: print(o['ref'], '|', o['hyp'])
