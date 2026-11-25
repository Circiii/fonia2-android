# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Copies what EvalV2Fits chose on all voices (tools/eval/out/<tag>/chosen.json) into the spec data files:
spec/fusion/make_v2.py PARAMS (then regenerates spec/fusion/*.json) and spec/thresholds/population.json -> judge2.

    python -I tools/eval/apply_chosen.py v2b
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    tag = sys.argv[1]
    with open(os.path.join(ROOT, "tools", "eval", "out", tag, "chosen.json"), encoding="utf-8") as f:
        chosen = json.load(f)
    # make_v2.py PARAMS
    mk = os.path.join(ROOT, "spec", "fusion", "make_v2.py")
    with open(mk, encoding="utf-8") as f:
        src = f.read()
    body = "PARAMS = {\n" + "".join(
        f'    "{s}": {json.dumps(p, ensure_ascii=False)},\n' for s, p in chosen["heads"].items()) + "}"
    src = re.sub(r"PARAMS = \{\n.*?\n\}", body, src, count=1, flags=re.S)
    with open(mk, "w", encoding="utf-8", newline="\n") as f:
        f.write(src)
    subprocess.run([sys.executable, "-I", mk], check=True)
    # population.json judge2
    pop = os.path.join(ROOT, "spec", "thresholds", "population.json")
    with open(pop, encoding="utf-8") as f:
        d = json.load(f)
    d["version"] = "2026-11-24.v2"
    d["source"] = (d["source"].split(" | v2:")[0] + " | v2: judge2 fitted by leave-one-voice-out on 8 synthetic voices "
                   "(tools/eval, spec/fusion/EVAL_v2.md), word gate -4.5 from the correct takes' 1st percentile; not children")
    d["word"]["minFitPerLabel"] = -4.5
    old = d.get("judge2", {})
    d["judge2"] = {
        "version": "2026-11-24.v2",
        "contextShift": chosen["contextShift"],
        "placeTrust": chosen["placeTrust"],
        "praise": chosen["praise"],
        "burst": {"minFound": 2},
        "visual": {},
        "personal": {},
    }
    # The opt-in gates (EvalGates, docs/JUDECATOR_AFRICATE.md) are set by hand from gates.json: a refit keeps them.
    for key in ("affricate", "finalL"):
        if key in old:
            d["judge2"][key] = old[key]
    with open(pop, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(d, ensure_ascii=False, indent=1) + "\n")
    print("applied", tag, chosen)


if __name__ == "__main__":
    main()
