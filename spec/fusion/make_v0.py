"""Writes spec/fusion/<sound>.json: the hand-set v0 fusion heads (not trained).

Run from the repo root:  python -I spec/fusion/make_v0.py
The JSON files are the contract (the engine reads them); this script only keeps the hand-set numbers readable.
Each class's logit is  bias + sum(weight * z(input)),  z = clip((x - mean) / scale); a missing input is 0 (masked).
The "full" head is the "fast" head plus the phoneme model's lpr terms: lpr(x) = log P(target) - log P(x), so a
negative lpr.x (x more likely) raises class x.
"""
import json
import os
import re

VERSION = "2026-11-21.v0"
FEATURE_VERSION = "e2.v0"
SOURCE = "hand-set v0, not trained"

LEVELS = {
    "omission": 1, "stopping": 2, "weakOrShort": 2, "deaffrication": 2, "rToGlide": 2, "rToVowel": 2,
    "lToGlide": 2, "lToR": 2, "rToL": 2, "devoicing": 3, "placeSwap": 4, "affricatePlace": 4, "rUvular": 4,
    "lateral": 5, "interdental": 5,
}

DECISION = {
    "tauOk": 0.80, "tauOkFast": 0.90, "tauAlmost": 0.35, "delta": 0.20, "coarseMax": 0.15, "tauErrFastExtra": 0.05,
    "tauErr": {
        "omission": 0.85, "stopping": 0.85, "weakOrShort": 0.75, "devoicing": 0.85, "placeSwap": 0.85,
        "deaffrication": 0.90, "affricatePlace": 0.85, "lateral": 0.90, "interdental": 0.90, "rToL": 0.85,
        "rToGlide": 0.85, "rToVowel": 0.85, "rUvular": 0.90, "lToGlide": 0.85, "lToR": 0.85,
    },
}

# Shared input normalisations.
I = {
    "present": (0.5, 0.5, [-1, 1]),
    "toward": (0.5, 0.2, [-3, 3]),
    "burstDb": (10, 6, [-3, 3]),
    "gapMs": (15, 10, [-2, 3]),
    "relLevelDb": (-20, 6, [-3, 3]),
    "flatness": (0.5, 0.2, [-3, 3]),
    "voicing": (0.5, 0.2, [-3, 3]),
    "riseMs": (30, 15, [-2, 3]),
    "rTap": (0.5, 0.5, [-1, 1]),
    "lTap": (0.5, 0.5, [-1, 1]),
    "dipDb": (16, 4, [-3, 3]),
    "dipMs": (45, 15, [-2, 3]),
    "uvNoiseDb": (-5, 6, [-3, 3]),
}


def lpr(label):
    return ("lpr." + label, (0.0, 3.0, [-3, 3]), "hasZipa")


def sibilant(sound, heard_place, voiced, lprs, dur_mean):
    inputs = ["present", "toward", ("fricDurMs", (dur_mean, 30, [-3, 3])), "burstDb", "gapMs", "relLevelDb", "flatness"]
    if voiced:
        inputs.append("voicing")
    classes = ["correct", "omission", "stopping", "weakOrShort"] + (["devoicing"] if voiced else []) + ["placeSwap", "lateral", "interdental"]
    fast = {
        "correct": {"bias": -1.0, "present": 1.0, "toward": 1.8, "fricDurMs": 1.6, "relLevelDb": 0.6, "flatness": -0.3, "burstDb": -0.3},
        "placeSwap": {"bias": -1.0, "present": 1.0, "toward": -2.4, "fricDurMs": 1.0, "relLevelDb": 0.4},
        "stopping": {"bias": 0.0, "fricDurMs": -1.2, "burstDb": 0.8, "gapMs": 0.8},
        "omission": {"bias": 0.5, "present": -1.5, "fricDurMs": -1.8, "burstDb": -1.0},
        # Weak: place and length can be fine; only the level against the neighbouring vowel is low.
        "weakOrShort": {"bias": -2.5, "present": 0.6, "toward": 1.2, "fricDurMs": 0.8, "relLevelDb": -2.0, "flatness": 0.5},
        "lateral": {"bias": -6.0},
        "interdental": {"bias": -6.0},
    }
    if voiced:
        fast["correct"]["voicing"] = 1.5
        fast["placeSwap"]["voicing"] = 0.5
        fast["weakOrShort"]["voicing"] = 0.5
        # Without a voicing measurement (word-final, or too short) devoicing must not compete with correct.
        fast["devoicing"] = {"bias": -2.0, "present": 1.0, "toward": 1.8, "fricDurMs": 1.6, "relLevelDb": 0.4, "voicing": -2.5}
    full_extra = {"correct": {}, "placeSwap": {}, "stopping": {}, "omission": {}, "lateral": {"bias": 2.0}, "interdental": {"bias": 2.0}}
    for cls, label in lprs.items():
        inputs.append(lpr(label))
        full_extra.setdefault(cls, {})["lpr." + label] = -1.5
        full_extra["correct"]["lpr." + label] = 0.6
    return write(sound, classes, inputs, fast, full_extra, heard_place)


def affricate(sound, heard, voiced, lprs, dur_mean):
    inputs = ["present", "toward", ("fricDurMs", (dur_mean, 30, [-3, 3])), "riseMs", "gapMs", "burstDb", "relLevelDb"]
    if voiced:
        inputs.append("voicing")
    classes = ["correct", "omission", "stopping", "weakOrShort", "deaffrication"] + (["devoicing"] if voiced else []) + ["affricatePlace", "lateral", "interdental"]
    fast = {
        "correct": {"bias": 0.0, "present": 1.0, "toward": 1.2, "fricDurMs": -1.0, "riseMs": -0.8, "gapMs": 0.4, "burstDb": 1.0, "relLevelDb": 0.4},
        # The fricative instead of the affricate: same place, longer, slower rise, no closure or burst.
        # The closure is often invisible on coded audio, so the burst and the length carry more than the gap.
        "deaffrication": {"bias": -1.5, "present": 1.0, "toward": 1.2, "fricDurMs": 1.5, "riseMs": 1.0, "gapMs": -0.5, "burstDb": -1.5},
        "stopping": {"bias": -0.5, "fricDurMs": -1.6, "burstDb": 1.2},
        "affricatePlace": {"bias": -1.0, "present": 1.0, "toward": -2.4},
        "omission": {"bias": 0.0, "present": -1.5, "fricDurMs": -1.5, "burstDb": -1.0},
        "weakOrShort": {"bias": -2.5, "present": 0.6, "toward": 0.8, "relLevelDb": -2.0},
        "lateral": {"bias": -6.0},
        "interdental": {"bias": -6.0},
    }
    if voiced:
        fast["correct"]["voicing"] = 1.5
        fast["devoicing"] = {"bias": -2.0, "present": 1.0, "toward": 1.2, "voicing": -2.5}
    full_extra = {"correct": {}, "lateral": {"bias": 2.0}, "interdental": {"bias": 2.0}}
    for cls, label in lprs.items():
        inputs.append(lpr(label))
        full_extra.setdefault(cls, {})["lpr." + label] = -1.5
        full_extra["correct"]["lpr." + label] = 0.6
    return write(sound, classes, inputs, fast, full_extra, heard)


def rhotic():
    inputs = ["rTap", "dipDb", "dipMs", "uvNoiseDb", lpr("l"), lpr("j"), lpr("ə"), lpr("ʁ"), lpr("∅")]
    classes = ["correct", "omission", "rToL", "rToGlide", "rToVowel", "rUvular"]
    # rTap: a tap or trill was found; dipDb/dipMs: the dip between the nuclei around an intervocalic R. A tap is deep
    # and narrow (about 22 dB, 30 ms on the prompt voices), an L shallower and wider (13 dB, 60 ms), a vowel flat.
    fast = {
        "correct": {"bias": -1.0, "rTap": 3.0, "dipDb": 1.2, "dipMs": -0.8},
        "rToL": {"bias": 0.0, "rTap": -1.5, "dipDb": -0.5, "dipMs": 1.0},
        "rToGlide": {"bias": -4.0, "rTap": -1.2, "dipDb": -1.5},
        "rToVowel": {"bias": -4.0, "rTap": -1.2, "dipDb": -1.5},
        "rUvular": {"bias": -3.5, "uvNoiseDb": 0.5},
        "omission": {"bias": -4.0, "rTap": -1.0, "dipDb": -1.5},
    }
    lprs = {"rToL": "l", "rToGlide": "j", "rToVowel": "ə", "rUvular": "ʁ", "omission": "∅"}
    full_extra = {"correct": {}}
    for cls, label in lprs.items():
        full_extra.setdefault(cls, {})["lpr." + label] = -1.8
        full_extra["correct"]["lpr." + label] = 0.5
    return write("r", classes, inputs, fast, full_extra, {"rToL": "l"})


def lateral():
    inputs = ["lTap", "dipDb", "dipMs", lpr("j"), lpr("w"), lpr("r"), lpr("∅")]
    classes = ["correct", "omission", "lToGlide", "lToR"]
    fast = {
        # No DSP detector for a good L (plan 03 §3.9): without the phoneme model the fast head stays unsure.
        "correct": {"bias": -2.0, "lTap": -1.0},
        "lToR": {"bias": -2.0, "lTap": 2.0, "dipDb": 1.0, "dipMs": -1.0},
        "lToGlide": {"bias": -0.5, "dipDb": -1.0},
        "omission": {"bias": -1.0, "dipDb": -0.8},
    }
    full_extra = {"correct": {}, "lToGlide": {"lpr.j": -1.8, "lpr.w": -1.8}, "lToR": {"lpr.r": -1.8}, "omission": {"lpr.∅": -1.8}}
    for label in ["j", "w", "r", "∅"]:
        full_extra["correct"]["lpr." + label] = 0.5
    return write("l", classes, inputs, fast, full_extra, {"lToR": "r"})


def write(sound, classes, inputs, fast, full_extra, heard):
    specs = []
    for item in inputs:
        if isinstance(item, str):
            mean, scale, clip = I[item]
            specs.append({"name": item, "mean": mean, "scale": scale, "clip": clip})
        elif len(item) == 3:
            name, (mean, scale, clip), mask = item
            specs.append({"name": name, "mean": mean, "scale": scale, "clip": clip, "mask": mask})
        else:
            name, (mean, scale, clip) = item
            specs.append({"name": name, "mean": mean, "scale": scale, "clip": clip})
    names = [s["name"] for s in specs]

    def head(terms, uses, temperature, extra=None):
        bias, weights = [], []
        for cls in classes:
            t = dict(terms.get(cls, {}))
            for k, v in (extra or {}).get(cls, {}).items():
                t[k] = t.get(k, 0.0) + v
            bias.append(round(float(t.pop("bias", -6.0 if cls not in terms else 0.0)), 3))
            unknown = set(t) - set(names)
            assert not unknown, (sound, cls, unknown)
            weights.append([round(float(t.get(n, 0.0)), 3) for n in names])
        return {"uses": uses, "bias": bias, "weights": weights, "temperature": temperature}

    full_terms = {cls: dict(fast.get(cls, {"bias": -6.0})) for cls in classes}
    spec = {
        "schema": 1, "sound": sound, "version": VERSION, "source": SOURCE, "featureVersion": FEATURE_VERSION,
        "trainedOn": None,
        "classes": classes,
        "levels": {c: LEVELS[c] for c in classes[1:]},
        "heardAs": heard,
        "inputs": specs,
        "heads": {"fast": head(fast, "dsp", 1.3), "full": head(full_terms, "dsp+zipa", 1.1, full_extra)},
        "decision": {**DECISION, "tauErr": {c: DECISION["tauErr"][c] for c in classes[1:]}},
        "metrics": None,
    }
    return spec


FILES = {
    "s": sibilant("s", {"placeSwap": "ș"}, False, {"placeSwap": "ʃ", "stopping": "t", "omission": "∅", "lateral": "ɬ", "interdental": "θ"}, 60),
    "sh": sibilant("ș", {"placeSwap": "s"}, False, {"placeSwap": "s", "stopping": "t", "omission": "∅", "lateral": "ɬ", "interdental": "θ"}, 60),
    "z": sibilant("z", {"placeSwap": "j", "devoicing": "s"}, True, {"devoicing": "s", "placeSwap": "ʒ", "stopping": "d", "omission": "∅", "lateral": "ɮ", "interdental": "ð"}, 50),
    "j": sibilant("j", {"placeSwap": "z", "devoicing": "ș"}, True, {"devoicing": "ʃ", "placeSwap": "z", "stopping": "d", "omission": "∅"}, 50),
    "ts": affricate("ț", {"deaffrication": "s", "affricatePlace": "ce"}, False, {"deaffrication": "s", "stopping": "t", "affricatePlace": "tʃ", "omission": "∅"}, 60),
    "ce": affricate("ce", {"deaffrication": "ș", "affricatePlace": "ț"}, False, {"deaffrication": "ʃ", "stopping": "t", "affricatePlace": "ts", "omission": "∅"}, 70),
    "ge": affricate("ge", {"deaffrication": "j", "affricatePlace": "z", "devoicing": "ce"}, True, {"deaffrication": "ʒ", "stopping": "d", "affricatePlace": "z", "devoicing": "tʃ", "omission": "∅"}, 45),
    "r": rhotic(),
    "l": lateral(),
}

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    for name, spec in FILES.items():
        text = json.dumps(spec, ensure_ascii=False, indent=1)
        # Lists without objects on one line, so a weight row reads as a row.
        text = re.sub(r"\[([^\[\]{}]*)\]", lambda m: "[" + re.sub(r"\s*\n\s*", " ", m.group(1)).strip() + "]", text)
        with open(os.path.join(here, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text + "\n")
    print("wrote", ", ".join(FILES))
