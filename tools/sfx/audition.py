"""Listens to every candidate (ElevenLabs generations in raw/elevenlabs/v2, bank renders in work/bank) with earshot
and our meters, so a pick is made by measurement. Writes audition.json (read by REPORT).

    <earshot python> -I tools/sfx/audition.py [files...]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import sfxlib as s  # noqa: E402

# Role of a candidate = the part of its file name before the first "_"; one query per role (describing the sound).
QUERIES = {
    "good": "a bright happy marimba arpeggio with a sparkling chime",
    "pop": "a juicy cartoon bubble pop",
    "levelup": "a rising magical harp glissando with chimes",
    "star": "a twinkling magical glockenspiel sparkle",
    "end": "a joyful short orchestral fanfare with mallets and strings",
    "almost": "two soft rising marimba notes",
    "retake": "two soft gentle wooden plucks",
    "mic": "a short cute bubbly blip",
    "tap": "a soft rubbery button tap",
    "page": "a soft airy whoosh",
    "navfwd": "a soft airy whoosh",
    "navback": "a soft airy whoosh",
    "unlock": "a magical unlock chime",
    "mirror": "a soft magical shimmer chime",
    "step": "a soft wooden block tap",
    "foni": "a cute small parrot chirping",
    "tick": "a tiny glockenspiel tick",
}
BAD = ["an error buzz", "a harsh alarm beep", "a sad descending tone", "a cheap synthesizer beep"]


def main() -> None:
    from earshot import api
    files = [Path(f) for f in sys.argv[1:]] or sorted((HERE / "raw/elevenlabs/v2").glob("*.mp3"))
    out_path = HERE / "audition.json"
    done = json.loads(out_path.read_text(encoding="utf-8")) if out_path.exists() else {}
    for f in files:
        role = f.stem.split("_")[0]
        x = s.load(f)
        d = api.describe(str(f), top=4)
        m = api.match(str(f), QUERIES[role], alts=BAD)
        meas = d["measurements"]
        r = {
            "role": role, "dur": round(len(x) / s.SR, 3), "M": round(s.momentary_max(x), 1),
            "tp": round(s.true_peak_db(x), 1), "z": round(m["z"], 1), "p_vs_bad": round(m.get("p_vs_alts", 0), 2),
            "tags": [(t["term"], round(t["z"], 1)) for t in d["top_tags"][:4]], "shape": d["shape"],
            "bright_hz": meas.get("brightness_hz"), "flatness": meas.get("flatness"),
            "attack_ms": meas.get("attack_ms"), "decay_ms": meas.get("decay_ms"),
            "partials": [f"{n}{c:+.0f}" for _, n, c in s.pitches(x, 4, fmin=250)],
            "low300_pct": round(100 * s.band_share(x), 1),
        }
        if role == "foni":
            b = api.match(str(f), "a small bird chirping", alts=["a balloon squeak", "a kettle whistling", "a squeaky toy"])
            rr = api.realness(str(f))
            r["bird_z"] = round(b["z"], 1)
            r["bird_vs_toy"] = round(b.get("p_vs_alts", 0), 2)
            r["p_real"] = round(rr.get("p_real", 0), 2)
            print(f"   bird z {r['bird_z']} vs toy {r['bird_vs_toy']} p_real {r['p_real']}")
        done[f.name] = r
        print(f"{f.name:22s} z {r['z']:4.1f} pBad {r['p_vs_bad']:.2f} {r['dur']:.2f}s bright {r['bright_hz']} "
              f"flat {r['flatness']} | {', '.join(f'{t} {z}' for t, z in r['tags'][:3])} | {' '.join(r['partials'])}",
              flush=True)
        out_path.write_text(json.dumps(done, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
