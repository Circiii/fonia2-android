"""Download SpeD Parakeet-Ro 110M, export its CTC branch to ONNX, quantise to int8, dump the vocabulary and feature settings."""
import json, os, sys
import torch
from huggingface_hub import hf_hub_download
import nemo.collections.asr as nemo_asr

path = hf_hub_download('gabrielpirlo/Sped_ParakeetRomanian_110M_TDT-CTC', 'SpeD-ParakeetRo_110M_TDT-CTC.nemo', local_dir='model')
m = nemo_asr.models.ASRModel.restore_from(path, map_location='cpu')
m.eval()
print(type(m).__name__)
cfg = m.cfg.preprocessor
print('preprocessor', dict(cfg))
vocab = list(m.ctc_decoder.vocabulary) if hasattr(m, 'ctc_decoder') else list(m.decoder.vocabulary)
print('vocab', len(vocab), vocab[:20])
json.dump({'tokens': vocab, 'blank': len(vocab), 'preprocessor': {k: (v if isinstance(v, (int, float, str, bool, type(None))) else str(v)) for k, v in dict(cfg).items()},
           'subsampling': int(m.cfg.encoder.subsampling_factor)}, open('model/roasr.json', 'w', encoding='utf8'), ensure_ascii=False, indent=1)
m.set_export_config({'decoder_type': 'ctc'})
m.export('model/roasr_ctc.onnx', check_trace=False)
print('exported', os.path.getsize('model/roasr_ctc.onnx') / 1e6, [f for f in os.listdir('model')])
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic('model/roasr_ctc.onnx', 'model/roasr_ctc.int8.onnx', weight_type=QuantType.QUInt8)
print('int8', os.path.getsize('model/roasr_ctc.int8.onnx') / 1e6)
