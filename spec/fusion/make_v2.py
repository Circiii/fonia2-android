"""Writes spec/fusion/<sound>.json v2: v1's fast heads (unchanged) and a full head per sound from the constants chosen
by leave-one-VOICE-out on the v2 evaluation set (tools/eval, spec/fusion/EVAL_v2.md).

Run from the repo root:  python -I spec/fusion/make_v2.py
FullHead.build (speech/.../FusionHead.kt) and full_head() below are the same formula; JudgeSpecTests checks them.
v2 adds cPlace: the weight of the phone evidence on the place classes (placeSwap, affricatePlace), fitted per sound
because ZIPA hears [s] in good Ș on synthetic voices (EVAL_v1). Not trained on children: every voice is synthetic.
"""
import importlib.util
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("make_v1", os.path.join(HERE, "make_v1.py"))
v1m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v1m)

VERSION = "2026-11-24.v2"
SOURCE = ("fast: hand-set v0, not trained; full: fast head pooled with ZIPA lpr (FullHead.build), constants per sound "
          "chosen by leave-one-voice-out on 8 synthetic voices (EVAL_v2.md), not trained on children")

# Chosen on all voices by EvalV2Fits (tools/eval/out/<tag>/chosen.json); the held-out folds are in EVAL_v2.md.
PARAMS = {
    "s": {"alpha": 0.5, "c": 0.25, "g": 0.5, "correctBias": 0.0},
    "ș": {"alpha": 0.75, "c": 0.5, "g": 0.5, "correctBias": -1.0},
    "z": {"alpha": 1.0, "c": 0.75, "g": 0.5, "correctBias": 1.0},
    "j": {"alpha": 0.5, "c": 1.25, "g": 0.5, "correctBias": 1.0},
    "ț": {"alpha": 0.25, "c": 1.25, "g": 0.5, "correctBias": 0.0, "cPlace": 0.5},
    "ce": {"alpha": 0.25, "c": 0.75, "g": 0.5, "correctBias": 1.0, "cPlace": 0.0},
    "ge": {"alpha": 0.25, "c": 0.5, "g": 0.5, "correctBias": 1.0},
    "r": {"alpha": 1.0, "c": 0.75, "g": 0.5, "correctBias": 1.0},
    "l": {"alpha": 0.25, "c": 0.75, "g": 0.5, "correctBias": 1.0},
}
PLACE = {"placeSwap", "affricatePlace"}


def full_head(spec, params):
    fast = spec["heads"]["fast"]
    t = fast["temperature"]
    names = [i["name"] for i in spec["inputs"]]
    scale = {i["name"]: i["scale"] for i in spec["inputs"]}
    a, c, g = params["alpha"], params["c"], params["g"]
    c_place = params.get("cPlace", c)
    c_bias = a * fast["bias"][0] / t + params["correctBias"]
    c_row = [a * fast["weights"][0][i] / t + (g * scale[n] if n == "zipa.gop" else 0.0) for i, n in enumerate(names)]
    bias, weights = [], []
    for k, cls in enumerate(spec["classes"]):
        key = spec["phoneMap"].get(cls)
        if k == 0:
            b, row = c_bias, c_row
        elif key is None:
            b = c_bias + (fast["bias"][k] - fast["bias"][0]) / t
            row = [c_row[i] + (fast["weights"][k][i] - fast["weights"][0][i]) / t for i in range(len(names))]
        else:
            ck = c_place if cls in PLACE else c
            b = a * fast["bias"][k] / t
            row = [a * fast["weights"][k][i] / t - (ck * scale[n] if n == "lpr." + key else 0.0) for i, n in enumerate(names)]
        bias.append(round(b, 4))
        weights.append([round(w, 4) for w in row])
    return {"uses": "dsp+zipa", "bias": bias, "weights": weights, "temperature": 1.0}


if __name__ == "__main__":
    for name, spec in v1m.v0.FILES.items():
        out = v1m.v1(spec)
        params = PARAMS[out["sound"]]
        out["version"] = VERSION
        out["source"] = SOURCE
        out["fullFrom"] = params
        out["heads"]["full"] = full_head(out, params)
        text = json.dumps(out, ensure_ascii=False, indent=1)
        text = re.sub(r"\[([^\[\]{}]*)\]", lambda m: "[" + re.sub(r"\s*\n\s*", " ", m.group(1)).strip() + "]", text)
        with open(os.path.join(HERE, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
    print("wrote", ", ".join(v1m.v0.FILES), "v2")
