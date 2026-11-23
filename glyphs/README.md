# :core:glyphs (C2): Allo outlines and the living-letter interpolator

Pure Kotlin/JVM, no Android types, no dependencies. Reads `spec/glyphs/allo-hero.json` (made by
`spec/glyphs/export_allo.py`, see `spec/glyphs/README.md`) and produces Allo outlines at any
wght (100–900) × ROND (0–100), opsz fixed at 32.

## Public API

```kotlin
val font = AlloOutlines.load(stream)              // once, off the main thread (~3 ms on a PC JVM)
font.glyphFor(codePoint, GlyphFeatures.SS01)      // glyph id or -1; also glyphId(name), kerning(l, r)
font.axisMap.normalize("wght", 650f)              // default normalisation + avar
val vi = VariationInterpolator(font)              // one per drawing thread; owns the region scalars
vi.setAxes(wght, rond): Boolean                   // recomputes only past 0.25 wght / 0.1 ROND
vi.interpolate(glyphId, outline)                  // into a reused GlyphOutline; vi.advance(glyphId)
outline.emit(sink, sx, sy, tx, ty)                // TrueType quads -> PathSink (moveTo/lineTo/quadTo/cubicTo/close)
outline.translateComponent(i, dx, dy)             // comma of Ș/ș/Ț/ț: componentIndexOf(font.glyphId("commaaccent"))
LetterLayout(font).layout(text, line)             // fixed slots at the reserved instance (wght 900, ROND 100)
QuadOutline.from(outline, quad); OutlineOps.*     // subdivide, shear, lerp, offsetAlongNormals, noiseDisplace, sampleEvenly
```

## Per frame in LiveLetter

```kotlin
// once per letter: val out = GlyphOutline(font); val quad = QuadOutline(4096, 32); val path = Path()
if (vi.setAxes(wghtFrom(inflation), rond) || dirty) {   // nothing to do when the axes did not move
    vi.interpolate(glyph, out)
    commaIdx.takeIf { it >= 0 }?.let { out.translateComponent(it, 0f, commaDropY) }
    path.rewind(); out.emit(composeSink(path), s, -s, x0, baseline)   // font y is up: flip with a negative sy
}
// scale / rotate / translate with the canvas, never by re-interpolating
```

The Compose adapter (`PathSink` over `androidx.compose.ui.graphics.Path`) belongs in
`:core:designsystem`. Effects: `QuadOutline.from` then the `OutlineOps` you need (e.g. Unsure:
`subdivide(0.08 em)` + `noiseDisplace(seed 0..3, 0.035 em, 0.35 em)` precomputed into 4 buffers;
Almost: `shear(tan 9°)`; POP: `sampleEvenly(80)` for particles and their outward normals).

"Sharpen / round" (S↔Ș) is the ROND axis; "inflate while held" is wght + ROND + scale + stroke
width; there is no outline op for them. The inflation → axes mapping (§4 table) belongs to C3.

## Measured (PC JVM 21, i7-class laptop)

| What | Value |
|---|---|
| interpolate + emit | 0.20 µs per glyph (56 glyphs: 0.011 ms per frame); via `QuadOutline`: 0.27 µs |
| allocation | 0 bytes over 10,000 interpolate + emit calls and 1,000 op chains (`ThreadMXBean`) |
| load | 2.8 ms for 362 KB |
| vs fontTools instancer | 1,120 cases, max coordinate error 0.51 units (limit 0.75), advance 0.5 (limit 1) |

The phone target (≤ 0.05 ms per glyph, plan §3.3) still has to be measured in `:benchmark`.

## Regenerate the data

```bash
uv run spec/glyphs/export_allo.py --font <AlloVariable.ttf>   # then ./gradlew :core:glyphs:test
```

## Pitfalls

- Font units, **y up**, origin on the baseline; flip when emitting to a canvas.
- `GlyphOutline` must be sized for the font (`GlyphOutline(font)`); `QuadOutline` grows on demand,
  so give it room up front (subdivision multiplies segments) or the first frames allocate.
- `VariationInterpolator` is not thread-safe (it owns `scalars`); `AlloOutlines` is.
- `setAxes` ignores tiny moves; pass `force = true` when you need the exact value (tests).
- No hinting, no rounding: outlines move continuously, and normalised coordinates are not
  quantised to F2Dot14 as fontTools does (difference < 0.06 units).
- Nested or transformed composites are rejected by the export; Allo has none in the set.
- Text goes through NFC in `LetterLayout` (S + U+0326 → Ș); cedilla Ş/Ţ draw the comma glyphs.
- `OutlineOps` that read neighbours (subdivide, offset, noise) need distinct src/dst buffers.
