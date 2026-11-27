package tech.fonia.designsystem.letter

import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.motion.Spring1D
import tech.fonia.designsystem.tokens.FoniaLetterTokens as L
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.glyphs.AlloOutlines
import tech.fonia.glyphs.GlyphFeatures
import tech.fonia.glyphs.GlyphOutline
import tech.fonia.glyphs.OutlineOps
import tech.fonia.glyphs.QuadOutline
import tech.fonia.glyphs.VariationInterpolator
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The brain of [LiveWord]: 2–50 letters on fixed slots (nothing jumps while a target inflates), target letters as
 * stickers that follow the drive and play the cues (each with its own [LetterPose], 40 ms apart), plain letters in
 * ink that stay still while the child reads (they move only for an entrance, a syllable beat, slot-drop or Unsure).
 */
class LiveWordEngine(val font: AlloOutlines) {
    private val upm = font.upm.toFloat()
    private val vi = VariationInterpolator(font)
    private val viPlain = VariationInterpolator(font)
    private val features = GlyphFeatures.SS01 or GlyphFeatures.SS02

    var text = ""
        private set
    var count = 0
        private set
    private var glyph = IntArray(0)
    private var target = BooleanArray(0)
    private var outlines = emptyArray<GlyphOutline>()
    private var poses = emptyArray<LetterPose>()
    /** Slot left edge and width, font units at scale 1. */
    private var slotX = FloatArray(0)
    private var slotW = FloatArray(0)
    var width = 0f
        private set
    private val reservedVi = VariationInterpolator(font)
    private val commaAccentId = font.glyphId("commaaccent")

    private val ctx = CueContext()
    private val q0 = QuadOutline(2048, 16)
    private val q1 = QuadOutline(2048, 16)
    private val q2 = QuadOutline(2048, 16)

    // cues
    private val active = arrayOfNulls<LetterCue>(MAX_CUES)
    private val started = FloatArray(MAX_CUES)
    private var activeCount = 0
    private val pending = ArrayDeque<LetterCue>()

    private val sInfl = Spring1D(FoniaMotion.follow, 0.55f)
    private val sLevel = Spring1D(0.9f, 900f, 0f)
    var reduceMotion = false

    var baseSet: StickerSet = FoniaSticker.Voice
        set(value) {
            // A letter at rest follows its base set at once (it does not wait for the first cue).
            val atBase = curSet == field
            field = value
            if (atBase) { curSet = value; prevSet = value }
        }
    var flatSet: StickerSet = FoniaSticker.FlatOnPaper
    /** The sets Correct and Almost switch to. */
    var verdicts: tech.fonia.designsystem.tokens.VerdictStickers = tech.fonia.designsystem.tokens.VerdictStickers.Default
    var prevSet: StickerSet = FoniaSticker.Voice
        private set
    var curSet: StickerSet = FoniaSticker.Voice
        private set
    private var setChangedAt = -10f
    var setMix = 1f
        private set
    /** Per-letter weight for plain letters (karaoke, stress), mode B of §3.4; null = 300. */
    var weightAt: ((Int) -> Float)? = null

    private var startNanos = 0L
    private var lastNanos = 0L
    var now = 0f
        private set

    // results per frame
    var inflation = 0.55f; private set
    var targetWght = 576f; private set
    var targetRond = 55f; private set
    var targetScale = 1f; private set
    var rimEm = 0.15f; private set
    var edgeEm = 0.08f; private set
    /** Slot-drop pulse: index and 0–1 strength (drawn as a Sun blob in the slot). */
    var pulseIndex = -1; private set
    var pulse = 0f; private set

    // layout (pixels)
    var emPx = 100f
    var originX = 0f
    var baselineY = 0f

    fun setText(t: String, targets: List<IntRange>) {
        val s = Normalizer.normalize(t, Normalizer.Form.NFC)
        val key = s + targets.joinToString()
        if (key == text) return
        text = key
        val cps = s.codePoints().toArray()
        val ids = ArrayList<Int>(cps.size)
        val tg = ArrayList<Boolean>(cps.size)
        for ((i, cp) in cps.withIndex()) {
            val g = font.glyphFor(cp, features)
            if (g < 0) continue
            ids += g
            tg += targets.any { i in it }
        }
        count = min(ids.size, MAX_LETTERS)
        glyph = IntArray(count) { ids[it] }
        target = BooleanArray(count) { tg[it] }
        outlines = Array(count) { GlyphOutline(font) }
        poses = Array(count) { LetterPose() }
        slotX = FloatArray(count)
        slotW = FloatArray(count)
        layout()
    }

    /** Fixed slots: targets reserve wght 900 / ROND 100 plus the rim at the largest scale; plain letters their own width. */
    private fun layout() {
        reservedVi.setAxes(900f, 100f, true)
        viPlain.setAxes(if (weightAt != null) 900f else PLAIN_WGHT, PLAIN_ROND, true)
        var x = 0f
        var prev = -1
        for (i in 0 until count) {
            val g = glyph[i]
            if (prev >= 0) x += font.kerning(prev, g) * 0.6f
            val w = if (target[i]) (reservedVi.advance(g) + L.RIM_MAX_EM * 0.8f * upm) * TARGET_MAX_SCALE * 0.94f else viPlain.advance(g)
            slotX[i] = x; slotW[i] = w
            x += w
            prev = g
        }
        width = x
    }

    fun cue(c: LetterCue) { pending.addLast(c) }

    val busy: Boolean get() {
        for (k in 0 until activeCount) {
            val c = active[k] ?: continue
            if (now - started[k] < CueMotion.duration(c) + STAGGER * count) return true
        }
        return false
    }

    val activeCueNames: String get() = buildString { for (k in 0 until activeCount) { if (k > 0) append(' '); append(active[k]?.name) } }

    fun idle(d: LetterDrive): Boolean = !busy && pending.isEmpty() && sInfl.settled && sLevel.settled && d.level < 0.01f &&
        !d.speaking && kotlin.math.abs(sInfl.target - d.inflation) < 1e-3f && setMix >= 1f && now - lastCueAt > 0.4f
    private var lastCueAt = 0f

    fun advance(frameNanos: Long, d: LetterDrive) {
        if (startNanos == 0L) { startNanos = frameNanos; lastNanos = frameNanos }
        val dt = min((frameNanos - lastNanos) / 1e9f, 0.1f)
        lastNanos = frameNanos
        now = (frameNanos - startNanos) / 1e9f
        while (pending.isNotEmpty()) { lastCueAt = now; start(pending.removeFirst()) }

        if (reduceMotion) { sInfl.snap(d.inflation); sLevel.snap(0f) } else {
            sInfl.target = d.inflation.coerceIn(0f, 1f); sInfl.update(dt)
            sLevel.target = d.level.coerceIn(0f, 1f); sLevel.update(dt)
        }
        inflation = sInfl.value
        targetWght = Motion.lerp(L.WGHT_MIN + 220f, L.WGHT_MAX, inflation)
        targetRond = 100f * inflation
        targetScale = Motion.lerp(0.92f, 1.12f, inflation) * (1f + if (reduceMotion) 0f else L.ENERGY_SCALE * sLevel.value * sin(Motion.TAU * 11f * now))
        rimEm = Motion.lerp(L.RIM_MIN_EM + 0.04f, L.RIM_MAX_EM * 0.8f, inflation)
        edgeEm = rimEm * 0.5f
        setMix = 1f // instant, see LetterEngine

        // poses
        for (i in 0 until count) poses[i].reset()
        pulseIndex = -1; pulse = 0f
        ctx.capEm = font.xHeight / upm
        var k = 0
        while (k < activeCount) {
            val c = active[k]!!
            val dur = CueMotion.duration(c)
            val t = now - started[k]
            val total = dur + STAGGER * count
            if (t >= total && !CueMotion.holds(c)) { removeAt(k); continue }
            applyToLetters(c, t)
            k++
        }
    }

    private fun applyToLetters(c: LetterCue, t: Float) {
        when (c) {
            LetterCue.DropIn -> for (i in 0 until count) {
                val ti = if (reduceMotion) 10f else t - i * STAGGER
                if (ti < 0f) { poses[i].alpha = 0f; continue }
                CueMotion.apply(c, min(ti, CueMotion.duration(c)), poses[i], ctx)
            }
            is LetterCue.SlotDrop -> {
                val i = c.index.coerceIn(0, max(0, count - 1))
                if (count == 0) return
                val ti = if (reduceMotion) 10f else t
                pulseIndex = i
                pulse = Motion.bell(Motion.progress(ti, 0f, 0.42f))
                val drop = ti - 0.3f
                if (drop < 0f) poses[i].alpha = 0f else CueMotion.apply(LetterCue.DropIn, min(drop, 0.95f), poses[i], ctx)
                // the neighbours feel the landing
                val k = Motion.kick(drop - 0.34f, FoniaMotion.bounce)
                if (i > 0) { poses[i - 1].sy *= 1f - 0.06f * k; poses[i - 1].rot -= 3f * k }
                if (i < count - 1) { poses[i + 1].sy *= 1f - 0.06f * k; poses[i + 1].rot += 3f * k }
            }
            is LetterCue.Syllable -> {
                var n = 0
                for (i in c.range) {
                    if (i !in 0 until count) continue
                    val ti = if (reduceMotion) 10f else t - n * STAGGER
                    n++
                    if (ti < 0f) continue
                    val s = if (c.stressed) 1.5f else 1f
                    if (reduceMotion) { poses[i].wghtAdd += 150f * s; continue }
                    CueMotion.apply(LetterCue.Beat(s), min(ti, 0.36f), poses[i], ctx)
                    poses[i].badgeKind = if (ti < 0.33f) 9 else 0 // marks "Voice for the beat"
                    if (c.stressed) poses[i].scale *= 1f + 0.12f * Motion.step(ti, FoniaMotion.bounce)
                }
            }
            LetterCue.Unsure -> {
                var n = 0
                for (i in 0 until count) {
                    if (target[i]) {
                        CueMotion.apply(c, min(if (reduceMotion) 10f else t, CueMotion.duration(c)), poses[i], ctx)
                    } else if (!reduceMotion && t < 1.2f) {
                        // plain letters: alternate ±3° at 8 fps and 1 px of drift (§5.1, mode B)
                        val f = (t * FoniaMotion.TREMBLE_FPS).toInt()
                        poses[i].rot += if ((f + i) % 2 == 0) 3f else -3f
                        poses[i].tx += 0.01f * if ((f + i * 3) % 3 == 0) 1f else -1f
                    }
                    n++
                }
            }
            else -> {
                // everything else plays on the target letters, 40 ms apart
                var n = 0
                for (i in 0 until count) {
                    if (!target[i]) continue
                    val ti = if (reduceMotion && CueMotion.holds(c)) 100f else t - n * STAGGER
                    n++
                    if (ti < 0f) continue
                    if (reduceMotion && !CueMotion.holds(c)) CueMotion.applyReduced(c, ti, poses[i])
                    else CueMotion.apply(c, min(ti, CueMotion.duration(c)), poses[i], ctx)
                }
            }
        }
    }

    private fun start(c: LetterCue) {
        if (c == LetterCue.Rest) {
            var k = 0
            while (k < activeCount) { if (CueMotion.holds(active[k]!!)) removeAt(k) else k++ }
            changeSet(null)
            return
        }
        if (c is LetterCue.Instruct) { start(LetterCue.resolve(c.kind, firstTargetText(), c.heard)); return }
        if (c == LetterCue.Listen) return
        if (CueMotion.exclusive(c)) { var k = 0; while (k < activeCount) { if (CueMotion.exclusive(active[k]!!)) removeAt(k) else k++ } }
        when (c) {
            LetterCue.Correct -> changeSet(verdicts.correct)
            LetterCue.Almost -> changeSet(verdicts.almost)
            LetterCue.Unsure -> changeSet(flatSet)
            LetterCue.Retake -> changeSet(null)
            else -> if (CueMotion.holds(c)) changeSet(null)
        }
        if (activeCount == MAX_CUES) removeAt(0)
        active[activeCount] = c
        started[activeCount] = now
        activeCount++
    }

    private fun firstTargetText(): String {
        for (i in 0 until count) if (target[i]) return font.glyphName(glyph[i]).take(1)
        return ""
    }

    private fun changeSet(v: StickerSet?) {
        val next = v ?: baseSet
        if (next != curSet) { prevSet = if (setMix >= 0.5f) curSet else prevSet; curSet = next; setChangedAt = now }
    }

    private fun removeAt(k: Int) {
        for (j in k until activeCount - 1) { active[j] = active[j + 1]; started[j] = started[j + 1] }
        activeCount--
        active[activeCount] = null
    }

    // ---------------------------------------------------------------- per letter geometry

    fun isTarget(i: Int) = target[i]
    fun pose(i: Int) = poses[i]

    /** Interpolates letter [i] at its axes and emits it with its pose into [sink]. */
    fun emitLetter(i: Int, sink: AffinePathSink) {
        val p = poses[i]
        val isT = target[i]
        val w: Float
        val r: Float
        val sc: Float
        if (isT) {
            w = (targetWght + p.wghtAdd).coerceIn(100f, 900f)
            r = Motion.lerp(targetRond, p.rond, p.rondMix).coerceIn(0f, 100f)
            sc = targetScale * p.scale
        } else {
            w = ((weightAt?.invoke(i) ?: PLAIN_WGHT) + p.wghtAdd).coerceIn(100f, 900f)
            r = PLAIN_ROND
            sc = p.scale
        }
        vi.setAxes(w, r)
        val o = outlines[i]
        vi.interpolate(glyph[i], o)
        val k = emPx / upm
        val xh = font.xHeight * 0.5f
        val cx = slotX[i] + slotW[i] * 0.5f
        val m = sink.matrix
        m.reset()
            .postScale(k, -k)
            .postTranslate(-o.advance * 0.5f * k, 0f)
            .postScale(p.sx, p.sy, 0f, 0f)
            .postShear(p.shear, 0f)
            .postScale(sc, sc, 0f, -xh * k)
            .postRotate(p.rot, 0f, 0f)
            .postTranslate(originX + cx * k + p.tx * emPx, baselineY + p.ty * emPx)
        if (p.noise > 0.001f) {
            QuadOutline.from(o, q0)
            OutlineOps.subdivide(q0, q1, 0.06f * upm)
            OutlineOps.noiseDisplace(q1, q2, p.noiseSeed + i, 0.035f * upm * p.noise, 0.35f * upm)
            q2.emit(sink)
        } else {
            o.emit(sink)
        }
    }

    /** Pixel centre x and width of slot [i]. */
    fun slotCenterPx(i: Int) = originX + (slotX[i] + slotW[i] * 0.5f) * emPx / upm
    fun slotWidthPx(i: Int) = slotW[i] * emPx / upm
    fun hasCommaAt(i: Int) = commaAccentId >= 0 && font.isComposite(glyph[i])

    companion object {
        const val MAX_LETTERS = 50
        const val MAX_CUES = 8
        const val STAGGER = FoniaMotion.STAGGER_MS / 1000f
        const val PLAIN_WGHT = 300f
        const val PLAIN_ROND = 85f
        const val TARGET_MAX_SCALE = 1.2f
    }
}
