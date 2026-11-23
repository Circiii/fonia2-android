# spec/glyphs: Allo outlines for the living letter (C1)

Derived data from **Allo 1.0.3** (`AlloVariable.ttf`, SIL Open Font License 1.1, see `OFL.txt`).
The font itself is not in the repo.

Font SHA-256: `cdc994466d3a2dc77d5f72bd4f60c1bbf4d65dad5901ac064c7cc04e182c4fe4`

## Regenerate

```bash
uv run spec/glyphs/export_allo.py --font C:/Users/circi/Desktop/HSIC/vendor_fonts/allo/fonts/variable/AlloVariable.ttf
uv run spec/glyphs/export_allo.py --font <same> --check   # re-export in memory, compare bytes
```

fontTools is pinned (4.68.0) in the script header; the run takes ~3 s and is deterministic. The
script refuses a font with another SHA-256. `:core:glyphs:test` checks `MANIFEST.sha256` and, when
`uv` and the font are present (`ALLO_FONT` overrides the default path), runs `--check`.

## Files

| File | What |
|---|---|
| `allo-hero.json` | outlines, opsz pinned to 32 (axes left: wght 100–400–900, ROND 0–50–100), 133 glyphs, 15 regions |
| `golden/w{wght}_r{ROND}.json` | fontTools `instancer` output (integers) at wght 100 180 250 400 600 750 900 × ROND 0 5 10 20 30 50 75 100 for 20 glyphs + their components |
| `golden/index.json` | golden glyph list, positions, tolerances (0.75 units per coordinate, 1 unit advance) |
| `golden/noise.json` | reference values of `OutlineNoise` (Python implementation) for the Swift port |
| `MANIFEST.sha256` | SHA-256 of every file above |

## Format of `allo-hero.json` (format 1)

- `axes[]`: `tag, min, def, max, avar[[from, to]...]` (normalised, piecewise linear).
- `regions[r][axis] = [lo, peak, hi]`, axes in order wght, ROND; peak 0 = axis ignored.
- `cmap[[codePoint, glyph]...]` (Ş ş Ţ ţ with cedilla map to the comma glyphs), `alt{feature:{glyph:alt}}`
  for ss01, ss02, tnum, zero; `kern[[left, right, value]...]` measured at `kernAt` (wght 900, ROND 100).
- `glyphs{name}`: `adv`; simple: `ends`, `on` (bitset, LSB first, base64), `xy` (x0, y0, ...);
  composite: `comps[[glyph, dx, dy]...]`; both: `tuples[{r, d}]`.
- `d` is dense with IUP already applied: `2n` point deltas then **1** advance delta (simple, n points),
  or `2c` component-offset deltas then the advance delta (composite, c components).
- Defaults are not integers: the opsz factor of every original tuple was folded in at opsz = 32.
- Instance: `xy = xy + Σ scalar(region) · d`, scalar = product of tents (fontTools `supportScalar`).
