# /// script
# requires-python = ">=3.10"
# dependencies = ["fonttools==4.68.0"]
# ///
"""C1: export the Allo outlines the living letter needs, plus golden instances.

    uv run spec/glyphs/export_allo.py --font <AlloVariable.ttf>          # write
    uv run spec/glyphs/export_allo.py --font <AlloVariable.ttf> --check  # re-export in memory, compare bytes

Writes (next to this script):
  allo-hero.json       outlines with opsz pinned to 32: defaults + dense (IUP-resolved) deltas per
                       wght x ROND region, avar, advances, composites, cmap, ss01/ss02/tnum/zero
                       alternates, kerning at the reserved instance (wght 900, ROND 100).
  golden/w*_r*.json    fontTools.varLib.instancer outlines (integers, as a static instance stores
                       them) at 7 x 8 axis positions for the golden glyph set and its components.
  golden/noise.json    reference vectors for OutlineNoise (Kotlin) and its Swift port.
  MANIFEST.sha256      SHA-256 of every file above; the Kotlin tests check it.

Format notes (the Kotlin side, :core:glyphs, relies on them):
  * opsz is pinned by folding every original tuple's opsz factor (exact, in float); tuples whose
    remaining region is the default are folded into "xy"/"adv", so defaults are not integers.
  * deltas are dense: IUP is resolved here (TupleVariation.calcInferredDeltas). For a simple glyph
    with n points "d" has 2n+1 numbers: x0,y0,...,x(n-1),y(n-1), advance delta. For a composite
    with c components: 2c+1 numbers (component offset deltas, advance delta).
  * phantom point 1 never moves in Allo (asserted), so the advance delta is pp2.x - pp1.x.
  * numbers are rounded to 2 decimals (error <= 0.005 per number).
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.varLib.models import normalizeValue, piecewiseLinearMap, supportScalar

FONT_SHA256 = "cdc994466d3a2dc77d5f72bd4f60c1bbf4d65dad5901ac064c7cc04e182c4fe4"
FONT_VERSION = "1.0.3"
PIN_OPSZ = 32.0
AXES = ("wght", "ROND")  # order of the axes in "regions" and in the Kotlin API

UPPER = "AĂÂBCDEFGHIÎJKLMNOPQRSȘTȚUVWXYZ"
LOWER = "aăâbcdefghiîjklmnopqrsștțuvwxyz"
DIGITS = "0123456789"
PUNCT = " .,!?-…„”:;'()"
ICONS = "★♥✓→←↑↓↻✕×⚠⌕⏎⌫●○•▶+−"
# legacy cedilla forms map to the comma glyphs (product rule: Ș and Ț with comma)
CEDILLA_FALLBACK = {"Ş": "Ș", "ş": "ș", "Ţ": "Ț", "ţ": "ț"}
ALTERNATES = ["a.1", "abreve.1", "acircumflex.1", "I.1", "Icircumflex.1", "l.ss02",
              "one.foot", "zero.slash"] + [d + ".tf" for d in
              ("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")]
ALT_FEATURES = ("ss01", "ss02", "tnum", "zero")

GOLDEN_CHARS = ["S", "Ș", "ș", "Ț", "ț", "R", "L", "Z", "J", "C", "G", "a.1", "ă", "â", "î",
                "i", "j", "★", "✓", "→"]
GOLDEN_WGHT = (100, 180, 250, 400, 600, 750, 900)
GOLDEN_ROND = (0, 5, 10, 20, 30, 50, 75, 100)
KERN_AT = {"wght": 900.0, "ROND": 100.0}


def num(v: float) -> float | int:
    r = round(v, 2)
    if r == 0:
        return 0
    return int(r) if r == int(r) else r


def dumps(obj) -> bytes:
    return (json.dumps(obj, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def norm_axis(font: TTFont, tag: str, user: float) -> float:
    ax = next(a for a in font["fvar"].axes if a.axisTag == tag)
    v = normalizeValue(user, (ax.minValue, ax.defaultValue, ax.maxValue))
    seg = font["avar"].segments.get(tag) if "avar" in font else None
    return piecewiseLinearMap(v, seg) if seg else v


def glyph_set(font: TTFont) -> tuple[list[list], list[str]]:
    cmap = font.getBestCmap()
    pairs = []
    for ch in UPPER + LOWER + DIGITS + PUNCT + ICONS:
        name = cmap.get(ord(ch))
        if name is None:
            sys.exit(f"missing glyph for {ch!r}")
        pairs.append([ord(ch), name])
    for legacy, comma in CEDILLA_FALLBACK.items():
        pairs.append([ord(legacy), cmap[ord(comma)]])
    pairs = sorted({p[0]: p for p in pairs}.values())
    names = {p[1] for p in pairs} | set(ALTERNATES)
    glyf = font["glyf"]
    for n in list(names):
        g = glyf[n]
        if g.isComposite():
            for c in g.components:
                if glyf[c.glyphName].isComposite():
                    sys.exit(f"nested composite {n} -> {c.glyphName} (not supported by :core:glyphs)")
                if hasattr(c, "transform") or not hasattr(c, "x"):
                    sys.exit(f"transformed/anchored component in {n} (not supported)")
                names.add(c.glyphName)
    return pairs, sorted(names)


def single_subst(lookup) -> dict[str, str]:
    out: dict[str, str] = {}
    for st in lookup.SubTable:
        if lookup.LookupType == 7:
            if st.ExtensionLookupType != 1:
                continue
            st = st.ExtSubTable
        elif lookup.LookupType != 1:
            continue
        for k, v in st.mapping.items():
            out.setdefault(k, v)
    return out


def alternates(font: TTFont, names: set[str]) -> dict:
    gsub = font["GSUB"].table
    alt = {}
    for tag in ALT_FEATURES:
        m: dict[str, str] = {}
        for fr in gsub.FeatureList.FeatureRecord:
            if fr.FeatureTag != tag:
                continue
            for li in fr.Feature.LookupListIndex:
                for k, v in single_subst(gsub.LookupList.Lookup[li]).items():
                    if k in names and v in ALTERNATES:
                        m.setdefault(k, v)
        if m:
            alt[tag] = dict(sorted(m.items()))
    return alt


def kerning(font_path: str, letters: list[str]) -> list[list]:
    """Pair kerning (first PairPos subtable that covers the pair wins) at KERN_AT, opsz 32."""
    f = TTFont(font_path)
    inst = instancer.instantiateVariableFont(f, {"opsz": PIN_OPSZ, **KERN_AT})
    gpos = inst["GPOS"].table
    lookups = []
    for fr in gpos.FeatureList.FeatureRecord:
        if fr.FeatureTag == "kern":
            for li in fr.Feature.LookupListIndex:
                if li not in lookups:
                    lookups.append(li)
    pending = {(a, b) for a in letters for b in letters}
    result: dict[tuple[str, str], int] = {}
    for li in sorted(lookups):
        lk = gpos.LookupList.Lookup[li]
        for st in lk.SubTable:
            if lk.LookupType == 9:
                if st.ExtensionLookupType != 2:
                    continue
                st = st.ExtSubTable
            elif lk.LookupType != 2:
                continue
            cov = set(st.Coverage.glyphs)
            for a, b in sorted(pending):
                if a not in cov:
                    continue
                if st.Format == 1:
                    ps = st.PairSet[st.Coverage.glyphs.index(a)]
                    rec = next((r for r in ps.PairValueRecord if r.SecondGlyph == b), None)
                    if rec is None:
                        continue
                    v1 = rec.Value1
                else:
                    c1 = st.ClassDef1.classDefs.get(a, 0)
                    c2 = st.ClassDef2.classDefs.get(b, 0)
                    v1 = st.Class1Record[c1].Class2Record[c2].Value1
                pending.discard((a, b))
                x = getattr(v1, "XAdvance", 0) if v1 is not None else 0
                if x:
                    result[(a, b)] = int(round(x))
    return [[a, b, v] for (a, b), v in sorted(result.items())]


def export_outlines(font: TTFont, font_path: str) -> bytes:
    pairs, names = glyph_set(font)
    glyf, hmtx, gvar = font["glyf"], font["hmtx"].metrics, font["gvar"].variations
    vmtx = font["vmtx"].metrics if "vmtx" in font else None
    opsz_n = norm_axis(font, "opsz", PIN_OPSZ)
    regions: dict[tuple, int] = {}
    pending_glyphs = {}
    for name in names:
        g = glyf[name]
        coords, ctrl = glyf._getCoordinatesAndControls(name, hmtx, vmtx)
        coords = [tuple(map(float, p)) for p in coords]
        npts = len(coords) - 4
        if coords[-4][0] != 0:
            sys.exit(f"{name}: phantom point 1 is not at x=0")
        base = [list(p) for p in coords]
        merged: dict[tuple, list[float]] = {}
        for tv in gvar.get(name, []):
            if g.isComposite():
                deltas = [d if d is not None else (0, 0) for d in tv.coordinates]
            else:
                deltas = tv.calcInferredDeltas(coords, ctrl.endPts) if None in tv.coordinates else None
                deltas = deltas if deltas is not None else tv.coordinates
            if deltas[-4] is not None and deltas[-4][0] != 0:
                sys.exit(f"{name}: phantom point 1 moves")
            ax = tv.axes
            f_opsz = supportScalar({"opsz": opsz_n}, {"opsz": ax["opsz"]}) if "opsz" in ax else 1.0
            if f_opsz == 0:
                continue
            key = tuple(ax[t] if t in ax and ax[t][1] != 0 else (0.0, 0.0, 0.0) for t in AXES)
            if all(k == (0.0, 0.0, 0.0) for k in key):
                target = None  # folds into the defaults
            else:
                target = merged.setdefault(key, [0.0] * (2 * npts + 1))
            for i in range(npts):
                dx, dy = deltas[i]
                if target is None:
                    base[i][0] += f_opsz * dx
                    base[i][1] += f_opsz * dy
                else:
                    target[2 * i] += f_opsz * dx
                    target[2 * i + 1] += f_opsz * dy
            dadv = (deltas[-3][0] - deltas[-4][0]) * f_opsz
            if target is None:
                base[-3][0] += dadv
            else:
                target[2 * npts] += dadv
        entry: dict = {"adv": num(base[-3][0] - base[-4][0])}
        if g.isComposite():
            entry["comps"] = [[c.glyphName, num(base[i][0]), num(base[i][1])]
                              for i, c in enumerate(g.components)]
        elif npts:
            ends = list(ctrl.endPts[: g.numberOfContours])
            flags = ctrl.flags
            bits = bytearray((npts + 7) // 8)
            for i in range(npts):
                if flags[i] & 1:
                    bits[i >> 3] |= 1 << (i & 7)
            entry["ends"] = ends
            entry["on"] = base64.b64encode(bytes(bits)).decode("ascii")
            entry["xy"] = [num(v) for i in range(npts) for v in base[i]]
        tuples = []
        for key, d in merged.items():
            rd = [num(v) for v in d]
            if any(rd):
                tuples.append((key, rd))
        pending_glyphs[name] = (entry, tuples)
        for key, _ in tuples:
            regions.setdefault(key, 0)
    region_list = sorted(regions)
    index = {k: i for i, k in enumerate(region_list)}
    glyphs = {}
    for name in names:
        entry, tuples = pending_glyphs[name]
        entry["tuples"] = [{"r": index[k], "d": d} for k, d in sorted(tuples, key=lambda t: index[t[0]])]
        glyphs[name] = entry

    axes = []
    for t in AXES:
        a = next(x for x in font["fvar"].axes if x.axisTag == t)
        seg = font["avar"].segments.get(t, {-1.0: -1.0, 0.0: 0.0, 1.0: 1.0})
        axes.append({"tag": t, "min": num(a.minValue), "def": num(a.defaultValue),
                     "max": num(a.maxValue), "avar": [[k, v] for k, v in sorted(seg.items())]})
    os2 = font["OS/2"]
    cmap_names = sorted({p[1] for p in pairs})
    doc = {
        "format": 1,
        "font": "Allo",
        "version": FONT_VERSION,
        "sha256": FONT_SHA256,
        "upm": font["head"].unitsPerEm,
        "ascent": font["hhea"].ascent,
        "descent": font["hhea"].descent,
        "capHeight": os2.sCapHeight,
        "xHeight": os2.sxHeight,
        "pinned": {"opsz": num(PIN_OPSZ)},
        "axes": axes,
        "regions": [[list(r) for r in key] for key in region_list],
        "cmap": pairs,
        "alt": alternates(font, set(names)),
        "kernAt": {k: num(v) for k, v in KERN_AT.items()},
        "kern": kerning(font_path, cmap_names),
        "glyphs": glyphs,
    }
    return dumps(doc)


def golden(font_path: str) -> dict[str, bytes]:
    font = TTFont(font_path)
    cmap = font.getBestCmap()
    names = [n if "." in n else cmap[ord(n)] for n in GOLDEN_CHARS]
    glyf = font["glyf"]
    allnames = list(names)
    for n in names:
        if glyf[n].isComposite():
            for c in glyf[n].components:
                if c.glyphName not in allnames:
                    allnames.append(c.glyphName)
    opts = subset.Options()
    opts.layout_features = []
    opts.layout_closure = False
    opts.notdef_outline = True
    opts.name_IDs = ["*"]
    opts.glyph_names = True
    sub = TTFont(font_path)
    s = subset.Subsetter(opts)
    s.populate(glyphs=allnames)
    s.subset(sub)
    buf = io.BytesIO()
    sub.save(buf)
    sub_bytes = buf.getvalue()

    out = {}
    for w in GOLDEN_WGHT:
        for r in GOLDEN_ROND:
            f = TTFont(io.BytesIO(sub_bytes))
            inst = instancer.instantiateVariableFont(f, {"opsz": PIN_OPSZ, "wght": w, "ROND": r})
            b = io.BytesIO()
            inst.save(b)
            inst = TTFont(io.BytesIO(b.getvalue()))
            g2, hm = inst["glyf"], inst["hmtx"].metrics
            gl = {}
            for n in sorted(allnames):
                g = g2[n]
                e: dict = {"adv": hm[n][0]}
                if g.isComposite():
                    e["comps"] = [[c.glyphName, c.x, c.y] for c in g.components]
                elif g.numberOfContours > 0:
                    e["xy"] = [int(v) for p in g.coordinates for v in p]
                gl[n] = e
            doc = {"opsz": num(PIN_OPSZ), "wght": w, "ROND": r, "glyphs": gl}
            out[f"golden/w{w:03d}_r{r:03d}.json"] = dumps(doc)
    out["golden/index.json"] = dumps({
        "tolerance": {"coord": 0.75, "adv": 1.0},
        "glyphs": names,
        "positions": [[w, r] for w in GOLDEN_WGHT for r in GOLDEN_ROND],
    })
    return out


def noise_lattice(seed: int, i: int) -> float:
    """Reference for OutlineNoise.lattice (Kotlin) / the Swift port: 32-bit wrapping integer hash."""
    m = 0xFFFFFFFF
    h = (i * 0x9E3779B9 + seed * 0x85EBCA6B) & m
    h ^= h >> 16
    h = (h * 0x7FEB352D) & m
    h ^= h >> 15
    h = (h * 0x846CA68B) & m
    h ^= h >> 16
    return (h >> 8) / 16777216 * 2 - 1


def noise_value(seed: int, x: float, period: int) -> float:
    import math
    i0 = math.floor(x)
    f = x - i0
    u = f * f * (3 - 2 * f)
    a = noise_lattice(seed, i0 % period)
    b = noise_lattice(seed, (i0 + 1) % period)
    return a + (b - a) * u


def noise_vectors() -> bytes:
    seeds = [0, 1, 3, 12345, -7, 0x632BE5AB]
    lattice = [[s, i, noise_lattice(s, i)] for s in seeds for i in range(-3, 21)]
    values = [[s, round(k * 0.37, 2), 7, round(noise_value(s, round(k * 0.37, 2), 7), 6)]
              for s in seeds for k in range(0, 40)]
    return dumps({"lattice": lattice, "value": values, "tolerance": 1e-5})


def build(font_path: str) -> dict[str, bytes]:
    raw = Path(font_path).read_bytes()
    if sha256(raw) != FONT_SHA256:
        sys.exit(f"font SHA-256 {sha256(raw)} != expected {FONT_SHA256}")
    font = TTFont(font_path)
    files = {"allo-hero.json": export_outlines(font, font_path)}
    files.update(golden(font_path))
    files["golden/noise.json"] = noise_vectors()
    manifest = "".join(f"{sha256(files[k])}  {k}\n" for k in sorted(files))
    files["MANIFEST.sha256"] = manifest.encode("ascii")
    return files


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True, help="path to AlloVariable.ttf (Allo 1.0.3)")
    ap.add_argument("--check", action="store_true", help="re-export in memory and compare bytes")
    args = ap.parse_args()
    root = Path(__file__).resolve().parent
    files = build(args.font)
    if args.check:
        bad = [k for k, v in files.items() if not (root / k).is_file() or (root / k).read_bytes() != v]
        expected = {f"golden/{p.name}" for p in (root / "golden").glob("*.json")}
        stale = sorted(expected - set(files))
        if bad or stale:
            sys.exit(f"export differs from disk: {bad + stale}")
        print(f"OK: {len(files)} files identical")
        return
    for k, v in files.items():
        p = root / k
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(v)
    print(f"wrote {len(files)} files; allo-hero.json {len(files['allo-hero.json'])} bytes")


if __name__ == "__main__":
    main()
