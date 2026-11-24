# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Tables from EvalV2Runner's cases.csv: per voice group and per sound, for one or two runs.

    python -I tools/eval/summarize.py v1 v2          (tags = folders under tools/eval/out/)

Column used: `heldout` from <tag>/lovo.csv when present (each voice judged with what was fitted on the others),
otherwise `full` (the full head, detector view = every kind unlocked; the fast head when ZIPA had no answer).
Writes <last tag>/summary.md and prints it. Every voice is synthetic.
"""
from __future__ import annotations

import csv
import os
import sys
from collections import defaultdict

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def load(tag: str, column: str = "full") -> list[dict]:
    with open(os.path.join(OUT, tag, "cases.csv"), encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    lovo = os.path.join(OUT, tag, "lovo.csv")
    if os.path.isfile(lovo) and column == "full":
        with open(lovo, encoding="utf-8") as f:
            held = {r["id"]: r["heldout"] for r in csv.DictReader(f)}
        for r in rows:
            r["label"] = held.get(r["id"], r["full"])
    else:
        for r in rows:
            r["label"] = r[column]
    # The new voices' "si" is no ground truth (build_manifest.py): dropped from every run alike.
    rows = [r for r in rows if not (r["said"] == "si" and r["voice"] in {"liam", "will", "sally", "jessica", "roger"})]
    return [r for r in rows if scored(r["expected"])]


def scored(e: str) -> bool:
    return not (e.startswith("retake") or e.startswith("n/a") or e.startswith("info"))


def kind(label: str) -> str:
    return label.split("(")[0]


def named(label: str) -> bool:
    return label not in ("correct", "almost", "unsure") and not label.startswith("retake")


def stats(rows: list[dict]) -> dict:
    ok = [r for r in rows if r["expected"] == "correct"]
    err = [r for r in rows if r["expected"] != "correct"]
    s = dict(
        ok=len(ok), err=len(err),
        accepted=sum(r["label"] == "correct" for r in ok),
        almost=sum(r["label"] == "almost" for r in ok),
        unsure_ok=sum(r["label"] == "unsure" for r in ok),
        retake_ok=sum(r["label"].startswith("retake") for r in ok),
        false_alarm=sum(named(r["label"]) for r in ok),
        right=sum(named(r["label"]) and kind(r["label"]) == kind(r["expected"]) for r in err),
        wrong=sum(named(r["label"]) and kind(r["label"]) != kind(r["expected"]) for r in err),
        praised=sum(r["label"] == "correct" for r in err),
        abstained=sum(r["label"] in ("almost", "unsure") or r["label"].startswith("retake") for r in err),
    )
    s["abstain_ok"] = s["almost"] + s["unsure_ok"] + s["retake_ok"]
    return s


def pct(a: int, b: int) -> str:
    return "-" if b == 0 else f"{100 * a / b:.0f} %"


def table(title: str, groups: dict[str, list[dict]]) -> list[str]:
    out = [f"**{title}**", "",
           "| | correct takes | accepted | abstained (almost/unsure/retake) | false alarm | error takes | named right | named wrong | praised (false praise) | abstained |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for name, rows in groups.items():
        s = stats(rows)
        out.append(f"| {name} | {s['ok']} | {s['accepted']} ({pct(s['accepted'], s['ok'])}) | {s['abstain_ok']} ({pct(s['abstain_ok'], s['ok'])}) | "
                   f"{s['false_alarm']} ({pct(s['false_alarm'], s['ok'])}) | {s['err']} | {s['right']} ({pct(s['right'], s['err'])}) | "
                   f"{s['wrong']} ({pct(s['wrong'], s['err'])}) | {s['praised']} ({pct(s['praised'], s['err'])}) | {s['abstained']} ({pct(s['abstained'], s['err'])}) |")
    return out + [""]


def by(rows: list[dict], key: str) -> dict[str, list[dict]]:
    g = defaultdict(list)
    for r in rows:
        g[r[key]].append(r)
    return dict(sorted(g.items())) | {"all": rows}


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    tags = sys.argv[1:] or ["v1"]
    lines = ["Every voice is synthetic (ElevenLabs + the prompt voice); none is a child or the owner.", ""]
    for tag in tags:
        rows = load(tag)
        lines += table(f"{tag}: by voice group", by(rows, "group"))
        lines += table(f"{tag}: by sound", by(rows, "sound"))
    if len(tags) >= 2:
        a, b = load(tags[0]), load(tags[-1])
        lines += ["**Per sound and group, accepted / false alarm / false praise (" + tags[0] + " → " + tags[-1] + ")**", "",
                  "| sound | group | accepted | false alarm | false praise |", "|---|---|---|---|---|"]
        ga = defaultdict(list)
        gb = defaultdict(list)
        for r in a:
            ga[(r["sound"], r["group"])].append(r)
        for r in b:
            gb[(r["sound"], r["group"])].append(r)
        for k in sorted(set(ga) | set(gb)):
            x, y = stats(ga[k]), stats(gb[k])
            lines.append(f"| {k[0]} | {k[1]} | {pct(x['accepted'], x['ok'])} → {pct(y['accepted'], y['ok'])} | "
                         f"{x['false_alarm']} → {y['false_alarm']} | {x['praised']} → {y['praised']} |")
        lines.append("")
    text = "\n".join(lines)
    with open(os.path.join(OUT, tags[-1], "summary.md"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
