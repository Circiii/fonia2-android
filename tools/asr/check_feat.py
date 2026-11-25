import numpy as np, torch, soundfile as sf, sys
import nemo.collections.asr as nemo_asr
from roasr import features, RoAsr
m = nemo_asr.models.ASRModel.restore_from('model/SpeD-ParakeetRo_110M_TDT-CTC.nemo', map_location='cpu'); m.eval()
m.preprocessor.featurizer.dither = 0.0
x = np.random.default_rng(0).normal(0, 0.05, 16000 * 3).astype(np.float32)
with torch.no_grad():
    f, n = m.preprocessor(input_signal=torch.tensor(x)[None], length=torch.tensor([len(x)]))
mine = features(x)
print('nemo', tuple(f.shape), int(n), 'mine', mine.shape, 'max abs diff', float(np.abs(f[0].numpy()[:, :mine.shape[1]] - mine).max()), float(np.abs(f[0].numpy()[:, :mine.shape[1]] - mine).mean()))
d = np.abs(f[0].numpy()[:, :300] - mine)
print('per-frame max first/last', d[:, :3].max(0), d[:, -3:].max(0), 'middle max', d[:, 5:-5].max(), 'worst mel', int(d.max(1).argmax()))
print('featurizer', m.preprocessor.featurizer.pad_to, m.preprocessor.featurizer.mag_power, m.preprocessor.featurizer.log_zero_guard_value, m.preprocessor.featurizer.preemph, m.preprocessor.featurizer.win_length, m.preprocessor.featurizer.window.shape, getattr(m.preprocessor.featurizer,'exact_pad',None), m.preprocessor.featurizer.fb.shape, float(m.preprocessor.featurizer.fb.sum()), float(__import__('roasr')._mel.sum()))
