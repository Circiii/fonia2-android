"""Writes spec/fusion/<sound>.json v1: the hand-set v0 fast heads (unchanged) and a full head built from them.

Run from the repo root:  python -I spec/fusion/make_v1.py
The JSON files are the contract (the engine reads them); this script keeps the numbers readable.

Full head (FullHead.build in speech/.../FusionHead.kt, which JudgeSpecTests checks against these files):
    logit_k = alpha * fast_k / T_fast - c * lpr(candidate_k)        (+ g * zipa.gop + correctBias for "correct")
a log-linear pool of the fast head and ZIPA's posterior over {target, candidates}; a class without a candidate
(weakOrShort) is "correct" plus the fast head's own (k - correct) difference. lpr(x) = log P(target) - log P(x)
in nats, z-scored with scale 3 and clipped at +-4 (+-12 nats), so the weight on z is -c * 3.

The four constants PARAMS were chosen by a grid search with leave-one-voice-out (JudgeEvalTest, the synthetic adult
voices content/Sarah/Roger/Lily, spec/fusion/EVAL_v1.md); they are shared by every sound. Not trained on children.
"""
import importlib.util
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("make_v0", os.path.join(HERE, "make_v0.py"))
v0 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v0)

VERSION = "2026-11-21.v1"
SOURCE = ("fast: hand-set v0, not trained; full: fast head pooled with ZIPA lpr (FullHead.build), 4 shared constants "
          "chosen by leave-one-voice-out grid search on synthetic adult voices (EVAL_v1.md), not trained on children")

# Chosen in EVAL_v1.md (leave-one-voice-out grid search, then refit on all four voices).
PARAMS = {"alpha": 0.5, "c": 0.75, "g": 0.5, "correctBias": -0.5}

# ConfusionSets.keys(sound) in :engine:phones, in order (candidates, omission, then groups). J and Z accept [dʒ], [dz]
# (and, word-finally, [ʃ], [s]) as the target, so those are not candidates (ConfusionSets.accepted).
KEYS = {
    "s": ["ʃ", "ts", "z", "t", "θ", "ɬ", "ɕ", "s̪", "∅"],
    "ș": ["s", "tʃ", "ts", "ʒ", "t", "ɬ", "θ", "∅"],
    "z": ["s", "ʒ", "d", "ð", "ɮ", "∅"],
    "j": ["ʃ", "z", "d", "ɮ", "∅"],
    "ț": ["s", "tʃ", "ʃ", "t", "tθ", "∅"],
    "ce": ["ts", "ʃ", "t", "s", "∅"],
    "ge": ["ʒ", "z", "dz", "tʃ", "d", "∅", "dz+z"],
    "r": ["l", "j", "ə", "ʁ", "ɹ", "w", "∅"],
    "l": ["j", "w", "r", "∅", "j+w"],
}

# Class -> the candidate that names it (plan 03 §3, §5). Candidates without a class (S as [ts], R as [ɹ]) only lower
# "correct" through zipa.gop.
PHONE_MAP = {
    "s": {"placeSwap": "ʃ", "stopping": "t", "omission": "∅", "lateral": "ɬ", "interdental": "θ"},
    "ș": {"placeSwap": "s", "stopping": "t", "omission": "∅", "lateral": "ɬ", "interdental": "θ"},
    "z": {"devoicing": "s", "placeSwap": "ʒ", "stopping": "d", "omission": "∅", "lateral": "ɮ", "interdental": "ð"},
    "j": {"devoicing": "ʃ", "placeSwap": "z", "stopping": "d", "omission": "∅", "lateral": "ɮ"},
    "ț": {"deaffrication": "s", "stopping": "t", "affricatePlace": "tʃ", "omission": "∅", "interdental": "tθ"},
    "ce": {"deaffrication": "ʃ", "stopping": "t", "affricatePlace": "ts", "omission": "∅"},
    "ge": {"deaffrication": "ʒ", "stopping": "d", "affricatePlace": "dz+z", "devoicing": "tʃ", "omission": "∅"},
    "r": {"rToL": "l", "rToGlide": "j", "rToVowel": "ə", "rUvular": "ʁ", "omission": "∅"},
    "l": {"lToGlide": "j+w", "lToR": "r", "omission": "∅"},
}

LPR = {"mean": 0.0, "scale": 3.0, "clip": [-4, 4], "mask": "hasZipa"}
GOP = {"name": "zipa.gop", "mean": 0.0, "scale": 3.0, "clip": [-4, 0], "mask": "hasZipa"}


def full_head(spec, params):
    fast = spec["heads"]["fast"]
    t = fast["temperature"]
    names = [i["name"] for i in spec["inputs"]]
    scale = {i["name"]: i["scale"] for i in spec["inputs"]}
    a, c, g = params["alpha"], params["c"], params["g"]
    c_bias = a * fast["bias"][0] / t + params["correctBias"]
    c_row = [a * fast["weights"][0][i] / t + (g * scale[n] if n == "zipa.gop" else 0.0) for i, n in enumerate(names)]
    bias, weights = [], []
    for k, cls in enumerate(spec["classes"]):
        key = spec["phoneMap"].get(cls)
        if k == 0:
            b, row = c_bias, c_row
        elif key is None:
            # A class the phone model cannot see (weakOrShort) rides on "correct" with the fast head's own difference.
            b = c_bias + (fast["bias"][k] - fast["bias"][0]) / t
            row = [c_row[i] + (fast["weights"][k][i] - fast["weights"][0][i]) / t for i in range(len(names))]
        else:
            b = a * fast["bias"][k] / t
            row = [a * fast["weights"][k][i] / t - (c * scale[n] if n == "lpr." + key else 0.0) for i, n in enumerate(names)]
        bias.append(round(b, 4))
        weights.append([round(w, 4) for w in row])
    return {"uses": "dsp+zipa", "bias": bias, "weights": weights, "temperature": 1.0}


def v1(spec):
    sound = spec["sound"]
    keep = [i for i, inp in enumerate(spec["inputs"]) if not inp["name"].startswith("lpr.")]
    inputs = [spec["inputs"][i] for i in keep]
    inputs += [{"name": "lpr." + k, **LPR} for k in KEYS[sound]]
    inputs.append(dict(GOP))
    n_new = len(inputs) - len(keep)
    fast = spec["heads"]["fast"]
    fast = {**fast, "weights": [[row[i] for i in keep] + [0.0] * n_new for row in fast["weights"]]}
    for cls, key in PHONE_MAP[sound].items():
        assert cls in spec["classes"], (sound, cls)
        assert key in KEYS[sound], (sound, key)
    out = {**spec, "version": VERSION, "source": SOURCE, "inputs": inputs, "phoneMap": PHONE_MAP[sound], "fullFrom": PARAMS}
    out["heads"] = {"fast": fast}
    out["heads"]["full"] = full_head(out, PARAMS)
    # Keep the key order readable: decision and metrics last.
    order = ["schema", "sound", "version", "source", "featureVersion", "trainedOn", "classes", "levels", "heardAs",
             "inputs", "phoneMap", "fullFrom", "heads", "decision", "metrics"]
    return {k: out[k] for k in order if k in out}


if __name__ == "__main__":
    for name, spec in v0.FILES.items():
        text = json.dumps(v1(spec), ensure_ascii=False, indent=1)
        text = re.sub(r"\[([^\[\]{}]*)\]", lambda m: "[" + re.sub(r"\s*\n\s*", " ", m.group(1)).strip() + "]", text)
        with open(os.path.join(HERE, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
    print("wrote", ", ".join(v0.FILES), "with", PARAMS)
