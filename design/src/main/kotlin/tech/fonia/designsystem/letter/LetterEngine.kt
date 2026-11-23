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
import tech.fonia.glyphs.PathSink
import tech.fonia.glyphs.QuadOutline
import tech.fonia.glyphs.VariationInterpolator
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A [PathSink] that applies an [Affine] (the Compose adapter is one; tests use a recording one). */
interface AffinePathSink : PathSink {
    val matrix: Affine
}

/**
 * The living letter's brain, pure Kotlin: it integrates the [LetterDrive] with springs, plays [LetterCue]s as
 * closed-form functions of time, interpolates the Allo outlines at the resulting axes, and emits the paths through an
 * [Affine] that carries the whole pose. Owns its buffers; nothing is allocated per frame once warmed up.
 *
 * Threading: everything runs on the UI thread (the frame loop advances, the draw phase emits). Only [LetterDrive] is
 * written from other threads.
 */
class LetterEngine(val font: AlloOutlines) {
    val pose = LetterPose()
    val particles = Particles()
    private val ctx = CueContext()

    private val vi = VariationInterpolator(font)
    private val viFixed = VariationInterpolator(font)
    private val upm = font.upm.toFloat()

    // ---- text
    var text: String = ""
        private set
    private val glyphIds = IntArray(MAX_GLYPHS) { -1 }
    private var glyphCount = 0
    private val outlines = Array(MAX_GLYPHS) { GlyphOutline(font) }
    private val commaComp = IntArray(MAX_GLYPHS) { -1 }
    private val pen = FloatArray(MAX_GLYPHS)
    private var commaGlyph = -1
    private var lowercase = false
    private val commaAccentId = font.glyphId("commaaccent")
    private var features = GlyphFeatures.SS01 or GlyphFeatures.SS02

    // comma-lift: the comma of the comma version of glyph 0 (S → Ș, T → Ț)
    private var phantomId = -1
    private val phantom = GlyphOutline(font)
    private var phantomComp = -1

    // the second glyph (ghost / small T) and the badge
    private var ghostId = -1
    private val ghost = GlyphOutline(font)
    private val badgeOutline = GlyphOutline(font)
    private var badgeId = -1

    // effects
    private val q0 = QuadOutline(4096, 32)
    private val q1 = QuadOutline(4096, 32)
    private val q2 = QuadOutline(4096, 32)

    // ---- springs on the drive
    private val sInfl = Spring1D(FoniaMotion.follow, 0.55f)
    private val sRond = Spring1D(FoniaMotion.follow, 55f)
    private val sLevel = Spring1D(0.9f, 900f, 0f)
    private val sLean = Spring1D(FoniaMotion.bounce, 0f)
    private val sTremble = Spring1D(FoniaMotion.follow, 0f)
    private val sTrill = Spring1D(0.8f, 300f, 0f)
    private val sBlow = Spring1D(0.7f, 160f, 0f)
    private val sComma = Spring1D(FoniaMotion.bounce, 1f)
    private var integrated = 0.0f
    /** The integrated inflation stays full until the POP bursts, then lets the air out. */
    private var popArmed = false
    private var listenCue = false

    // reduced motion: sample-and-hold in 100 ms steps
    var reduceMotion = false
    private var rmNext = 0f
    private var rmInfl = 0.55f
    private var rmRond = 55f

    // ---- cues
    private val active = arrayOfNulls<LetterCue>(MAX_CUES)
    private val started = FloatArray(MAX_CUES)
    private val prevT = FloatArray(MAX_CUES)
    private var activeCount = 0
    private val pending = ArrayDeque<LetterCue>()
    private var lastCueAt = 0f
    private var breathedForText = false
    private var tiltSign = 1f

    // ---- sticker sets
    var baseSet: StickerSet = FoniaSticker.Voice
        set(value) {
            // A letter at rest follows its base set at once (it used to keep showing Voice until the first cue).
            val atBase = verdictSet == null && curSet == field
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
    private var verdictSet: StickerSet? = null

    /** Called (on the UI thread, from the frame loop) when a POP fires. */
    var onPop: (() -> Unit)? = null

    // ---- time
    private var startNanos = 0L
    private var lastNanos = 0L
    var now = 0f
        private set

    // ---- results of advance() for the painter
    var wght = 576f; private set
    var rond = 55f; private set
    var inflation = 0.55f; private set
    var scale = 1f; private set
    var rimEm = 0.15f; private set
    var edgeEm = 0.08f; private set
    /** 0..1 blend from [prevSet] to [curSet]. */
    var setMix = 1f; private set

    // ---- layout (pixels), set by the painter each draw
    var emPx = 100f
    var anchorX = 0f
    var anchorY = 0f

    // geometry of the current frame, font units
    private val bounds = FloatArray(4)
    private var centerX = 0f
    private var vCenter = 0f
    private var bodyHalfWidth = 0f
    private var commaCx = 0f
    private var commaCy = 0f
    private var commaTop = 0f

    init {
        setText("S")
    }

    // ================================================================= input

    fun setText(grapheme: String, practiceFeatures: Boolean = true) {
        val f = if (practiceFeatures) GlyphFeatures.SS01 or GlyphFeatures.SS02 else GlyphFeatures.NONE
        if (grapheme == text && f == features) return
        features = f
        text = grapheme
        val s = Normalizer.normalize(grapheme, Normalizer.Form.NFC)
        glyphCount = 0
        commaGlyph = -1
        var i = 0
        var lower = true
        while (i < s.length && glyphCount < MAX_GLYPHS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val g = font.glyphFor(cp, features)
            if (g < 0) continue
            if (Character.isUpperCase(cp)) lower = false
            glyphIds[glyphCount] = g
            glyphCount++
        }
        lowercase = lower && glyphCount > 0
        phantomId = -1
        if (glyphCount > 0) {
            val base = String(Character.toChars(s.codePointAt(0)))
            val cv = LetterCue.commaVersionOf(base)
            if (cv != base) phantomId = font.glyphFor(cv.codePointAt(0), features)
        }
        val first = if (s.isNotEmpty()) s.codePointAt(0) else 0
        ctx.targetIsR = first == 'R'.code || first == 'r'.code
        ctx.targetIsL = first == 'L'.code || first == 'l'.code
        breathedForText = false
        lastCueAt = now
        interpolateAll(force = true)
    }

    /** Queue a cue; it starts on the next frame (stamped with that frame's time). */
    fun cue(c: LetterCue) { pending.addLast(c) }

    /** True while an animation is running (held final frames do not count). */
    val busy: Boolean get() {
        for (k in 0 until activeCount) {
            val c = active[k] ?: continue
            if (now - started[k] < CueMotion.duration(c)) return true
        }
        return particles.alive
    }

    /** Nothing moves and nothing will until the drive or a cue changes: the frame loop may sleep. */
    fun idle(drive: LetterDrive): Boolean =
        !busy && pending.isEmpty() && sInfl.settled && sRond.settled && sLevel.settled && sLean.settled &&
            sTremble.settled && sTrill.settled && sBlow.settled && sComma.settled &&
            drive.level < 0.01f && !drive.speaking && drive.trembling <= 0f && drive.trill <= 0f && drive.blow <= 0f &&
            abs(sInfl.target - targetInflation(drive)) < 1e-3f && (breathedForText || reduceMotion) &&
            setMix >= 1f && now - lastCueAt > 0.4f

    private fun targetInflation(d: LetterDrive) = if (d.inflateWithVoice) integrated else d.inflation.coerceIn(0f, 1f)

    // ================================================================= frame

    fun advance(frameNanos: Long, drive: LetterDrive) {
        if (startNanos == 0L) { startNanos = frameNanos; lastNanos = frameNanos }
        if (frameNanos <= lastNanos && now > 0f) return
        val dt = min((frameNanos - lastNanos) / 1e9f, 0.1f)
        lastNanos = frameNanos
        now = (frameNanos - startNanos) / 1e9f

        startPending()
        integrateDrive(drive, dt)
        evaluatePose(drive)
        particles.update(dt)
        interpolateAll(force = false)
        fireEvents()
    }

    private fun startPending() {
        while (pending.isNotEmpty()) {
            val c = pending.removeFirst()
            lastCueAt = now
            if (c !is LetterCue.Breathe) removeWhere { it is LetterCue.Breathe }
            when (c) {
                LetterCue.Listen -> { listenCue = true; continue }
                LetterCue.Rest -> {
                    listenCue = false
                    removeWhere { CueMotion.holds(it) }
                    changeSet(null)
                    continue
                }
                is LetterCue.Instruct -> { cueNow(LetterCue.resolve(c.kind, text, c.heard)); continue }
                is LetterCue.SlotDrop -> { cueNow(LetterCue.DropIn); continue }
                is LetterCue.Syllable -> { cueNow(LetterCue.Beat(if (c.stressed) 1.4f else 1f)); continue }
                else -> cueNow(c)
            }
        }
    }

    private fun cueNow(c: LetterCue) {
        if (CueMotion.exclusive(c)) removeWhere { CueMotion.exclusive(it) }
        if (c == LetterCue.Pop) removeWhere { it == LetterCue.Pop }
        if (c == LetterCue.Unsure) { tiltSign = -tiltSign; ctx.tiltSign = tiltSign }
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
        prevT[activeCount] = -1f
        activeCount++
        // reduced motion: a holding cue jumps to its last frame; the others run their reduced form in real time
        if (reduceMotion && CueMotion.holds(c)) started[activeCount - 1] = now - CueMotion.duration(c)
    }

    private fun changeSet(verdict: StickerSet?) {
        verdictSet = verdict
        val next = verdict ?: baseSet
        if (next != curSet) {
            prevSet = blendedSnapshotSet()
            curSet = next
            setChangedAt = now
        }
    }

    /** When a set changes mid-blend we restart from the target of the previous blend (good enough at 220 ms). */
    private fun blendedSnapshotSet(): StickerSet = if (setMix >= 0.5f) curSet else prevSet

    private inline fun removeWhere(pred: (LetterCue) -> Boolean) {
        var k = 0
        while (k < activeCount) { val c = active[k]; if (c != null && pred(c)) removeAt(k) else k++ }
    }

    private fun removeAt(k: Int) {
        for (j in k until activeCount - 1) { active[j] = active[j + 1]; started[j] = started[j + 1]; prevT[j] = prevT[j + 1] }
        activeCount--
        active[activeCount] = null
    }

    private fun integrateDrive(d: LetterDrive, dt: Float) {
        if (d.inflateWithVoice) {
            val goal = max(0.3f, d.holdGoalSeconds)
            integrated = if (d.speaking) integrated + dt / goal else integrated - L.DEFLATE_RATE * dt / goal
            integrated = integrated.coerceIn(0f, 1f)
            if (popArmed) integrated = 1f
            if (integrated >= 1f && d.autoPop && !popArmed) {
                popArmed = true
                cueNow(LetterCue.Pop)
            }
        }
        val inflT = targetInflation(d)
        val sharp = d.sharpness
        val rondT = if (sharp.isNaN()) 100f * inflT else Motion.lerp(L.ROND_ROUND, L.ROND_SHARP, sharp.coerceIn(0f, 1f))
        if (reduceMotion) {
            if (now >= rmNext) { rmInfl = inflT; rmRond = rondT; rmNext = now + FoniaMotion.REDUCED_STEP_MS / 1000f }
            sInfl.snap(rmInfl); sRond.snap(rmRond)
            sLevel.snap(0f); sTremble.snap(0f); sTrill.snap(0f); sBlow.snap(0f)
        } else {
            sInfl.target = inflT; sInfl.update(dt)
            sRond.target = rondT; sRond.update(dt)
            sLevel.target = if (d.speaking || d.level > 0f) d.level.coerceIn(0f, 1f) else 0f; sLevel.update(dt)
            sTremble.target = d.trembling.coerceIn(0f, 1f); sTremble.update(dt)
            sTrill.target = d.trill.coerceIn(0f, 1f); sTrill.update(dt)
            sBlow.target = d.blow.coerceIn(0f, 1f); sBlow.update(dt)
        }
        sLean.target = if (d.listening || listenCue) 1f else 0f
        if (reduceMotion) sLean.snap(sLean.target) else sLean.update(dt)
        // the comma of a comma letter grows with the Ș-ness of the voice when sharpness is live
        sComma.target = if (sharp.isNaN()) 1f else 1f - Motion.easeInOut(Motion.progress(sharp, 0.35f, 0.8f))
        if (reduceMotion) sComma.snap(sComma.target) else sComma.update(dt)

        // breathe once, 1 s after the letter appeared and nothing happened
        if (!breathedForText && !reduceMotion && now - lastCueAt > 1f && activeCount == 0 && !d.speaking && !d.listening) {
            breathedForText = true
            cueNow(LetterCue.Breathe)
        }
    }

    private fun evaluatePose(d: LetterDrive) {
        val p = pose
        p.reset()
        ctx.capEm = (if (lowercase) font.xHeight else font.capHeight) / upm
        ctx.halfWidthEm = bodyHalfWidth / upm
        ctx.liveInflation = sInfl.value
        ctx.popPeak = (L.SCALE_POP / max(Motion.lerp(L.SCALE_MIN, L.SCALE_MAX, sInfl.value), 0.5f) - 1f).coerceIn(0.2f, 1.2f)

        var k = 0
        while (k < activeCount) {
            val c = active[k]!!
            val dur = CueMotion.duration(c)
            val t = now - started[k]
            if (t >= dur && !CueMotion.holds(c)) { removeAt(k); continue }
            if (reduceMotion && !CueMotion.holds(c)) CueMotion.applyReduced(c, t, p) else CueMotion.apply(c, min(t, dur), p, ctx)
            k++
        }

        // listening lean (§5.1): 4° toward the child, 0.96, rim −15 %
        val lean = sLean.value
        p.rot += -4f * lean
        p.scale *= 1f - 0.04f * lean
        p.rimMul *= 1f - 0.15f * lean

        // live trembling (no verdict) and the trill ripple
        if (sTremble.value > 0.01f) {
            p.noise = max(p.noise, sTremble.value)
            p.noiseSeed = (now * FoniaMotion.TREMBLE_FPS).toInt() and 3
        }
        if (sTrill.value > 0.01f) {
            p.ripple += 0.022f * sTrill.value
            p.ripplePhase = Motion.TAU * 18f * now
        }
        // blowing leans the letter away
        p.shear += -0.22f * sBlow.value
        p.sx *= 1f - 0.05f * sBlow.value

        val infl = Motion.lerp(sInfl.value, p.inflation, p.inflationMix)
        inflation = infl
        val voicedExtra = 60f * d.voicing.coerceIn(0f, 1f) * (if (d.speaking) 1f else 0f)
        wght = (Motion.lerp(L.WGHT_MIN, L.WGHT_MAX, infl) + p.wghtAdd + voicedExtra).coerceIn(100f, 900f)
        var r = sRond.value
        if (d.sharpness.isNaN()) r = 100f * infl
        r += -20f * d.fricative.coerceIn(0f, 1f) * (if (d.speaking) 1f else 0f)
        rond = Motion.lerp(r, p.rond, p.rondMix).coerceIn(0f, 100f)

        val buzz = if (reduceMotion) 0f else L.ENERGY_SCALE * sLevel.value * sin(Motion.TAU * 11f * now)
        val baseScale = Motion.lerp(L.SCALE_MIN, L.SCALE_MAX, infl)
        scale = baseScale * p.scale * (1f + buzz)
        rimEm = Motion.lerp(L.RIM_MIN_EM, L.RIM_MAX_EM, infl) * p.rimMul
        edgeEm = Motion.lerp(L.EDGE_MIN_EM, L.EDGE_MAX_EM, infl) * p.rimMul
        // sets switch at once, inside the cue's crouch / squash: any RGB blend of complementary sets (pink ↔ green,
        // flat ↔ sticker) shows muddy brown frames, worst on slow devices
        setMix = 1f
    }

    private fun fireEvents() {
        for (k in 0 until activeCount) {
            val c = active[k] ?: continue
            val t = now - started[k]
            val before = prevT[k]
            prevT[k] = t
            if (reduceMotion) {
                if (c == LetterCue.Pop && before < 0f) { onPop?.invoke(); if (popArmed) { popArmed = false; integrated = 0.1f } }
                continue
            }
            if (c == LetterCue.Pop && before < CueMotion.POP_BURST_T && t >= CueMotion.POP_BURST_T) {
                spawnPop(); onPop?.invoke()
                if (popArmed) { popArmed = false; integrated = 0.1f }
            }
            if (c == LetterCue.Correct && before < CueMotion.CORRECT_STARS_T && t >= CueMotion.CORRECT_STARS_T) spawnStars()
        }
    }

    // ================================================================= geometry

    private fun interpolateAll(force: Boolean) {
        val changed = vi.setAxes(wght, rond, force)
        if (!changed && !force && glyphCount > 0 && outlines[0].glyphId == glyphIds[0]) {
            // axes did not move: outlines are still valid, only refresh the ghost/phantom if needed
        } else {
            var x = 0f
            var prev = -1
            for (i in 0 until glyphCount) {
                val g = glyphIds[i]
                if (prev >= 0) x += font.kerning(prev, g)
                vi.interpolate(g, outlines[i])
                pen[i] = x
                x += outlines[i].advance
                prev = g
                commaComp[i] = if (commaAccentId >= 0 && font.isComposite(g)) outlines[i].componentIndexOf(commaAccentId) else -1
            }
            commaGlyph = -1
            for (i in 0 until glyphCount) if (commaComp[i] >= 0) { commaGlyph = i; break }
            if (phantomId >= 0) {
                vi.interpolate(phantomId, phantom)
                phantomComp = phantom.componentIndexOf(commaAccentId)
            }
            measure()
        }
        if (pose.ghostMode != 0 && ghostId >= 0) vi.interpolate(ghostId, ghost)
    }

    /** Bounds of the body (all glyphs, commas excluded) and of the comma, font units. */
    private fun measure() {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until glyphCount) {
            val o = outlines[i]
            val n = if (o.componentCount == 0) 1 else o.componentCount
            for (ci in 0 until n) {
                if (ci == commaComp[i]) continue
                val s = if (o.componentCount == 0) 0 else o.componentPointStart(ci)
                val e = if (o.componentCount == 0) o.pointCount else o.componentPointEnd(ci)
                for (p in s until e) {
                    val x = o.xy[2 * p] + pen[i]; val y = o.xy[2 * p + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
        }
        if (x0 > x1) { x0 = 0f; x1 = 0f; y0 = 0f; y1 = 0f }
        bounds[0] = x0; bounds[1] = y0; bounds[2] = x1; bounds[3] = y1
        centerX = (x0 + x1) * 0.5f
        bodyHalfWidth = (x1 - x0) * 0.5f
        vCenter = (if (lowercase) font.xHeight else font.capHeight) * 0.5f
        // the comma (own or phantom)
        val o: GlyphOutline? = when {
            commaGlyph >= 0 -> outlines[commaGlyph]
            phantomId >= 0 && phantomComp >= 0 -> phantom
            else -> null
        }
        val ci = if (commaGlyph >= 0) commaComp[commaGlyph] else phantomComp
        if (o != null && ci >= 0) {
            var cx0 = Float.MAX_VALUE; var cx1 = -Float.MAX_VALUE; var cy0 = Float.MAX_VALUE; var cy1 = -Float.MAX_VALUE
            for (p in o.componentPointStart(ci) until o.componentPointEnd(ci)) {
                val x = o.xy[2 * p]; val y = o.xy[2 * p + 1]
                if (x < cx0) cx0 = x; if (x > cx1) cx1 = x; if (y < cy0) cy0 = y; if (y > cy1) cy1 = y
            }
            val px = if (commaGlyph >= 0) pen[commaGlyph] else 0f
            commaCx = (cx0 + cx1) * 0.5f + px
            commaCy = (cy0 + cy1) * 0.5f
            commaTop = cy1
        }
    }

    /** font units → pixels with the body pose; [penX] the glyph's pen position. */
    private fun bodyMatrix(m: Affine, penX: Float, extraDx: Float = 0f) {
        val p = pose
        val k = emPx / upm
        val baseY = vCenter * k
        m.reset()
            .postScale(k, -k)
            .postTranslate((penX - centerX) * k, vCenter * k)
            .postScale(p.sx, p.sy, 0f, baseY)
            .postShear(p.shear, baseY)
            .postScale(scale, scale)
            .postRotate(p.rot, 0f, baseY * scale)
            .postTranslate(anchorX + (p.tx + p.targetDx + extraDx) * emPx, anchorY + p.ty * emPx)
    }

    /** True when the body should use the quad route (noise / ripple). */
    private val needsQuad: Boolean get() = pose.noise > 0.001f || pose.ripple > 0.0005f

    /** Emits every glyph of the body. The comma goes in only when it is attached and visible. */
    fun emitBody(sink: AffinePathSink) {
        val p = pose
        val commaInBody = p.commaMode == 0 && sComma.value > 0.98f
        for (i in 0 until glyphCount) {
            val o = outlines[i]
            bodyMatrix(sink.matrix, pen[i])
            if (needsQuad) {
                emitEffected(o, sink, if (commaInBody) -1 else commaComp[i])
                continue
            }
            if (o.componentCount == 0) { o.emit(sink); continue }
            for (ci in 0 until o.componentCount) {
                if (ci == commaComp[i] && !commaInBody) continue
                o.emitComponent(ci, sink)
            }
        }
    }

    private fun emitEffected(o: GlyphOutline, sink: AffinePathSink, skipComp: Int) {
        QuadOutline.from(o, q0)
        OutlineOps.subdivide(q0, q1, 0.06f * upm)
        var src = q1
        if (pose.noise > 0.001f) {
            OutlineOps.noiseDisplace(q1, q2, pose.noiseSeed, 0.035f * upm * pose.noise, 0.35f * upm)
            src = q2
        }
        if (pose.ripple > 0.0005f) ripple(src)
        // skipping the comma in the quad route: drop the contours that belong to it
        if (skipComp >= 0 && o.componentCount > 0) {
            // contours of the comma component are the last ones of that component; emit contour by contour
            val c0 = o.componentContourStart(skipComp)
            val c1 = o.componentContourEnd(skipComp)
            emitQuadContours(src, sink, c0, c1)
        } else {
            src.emit(sink)
        }
    }

    /** Emits all contours of [q] except those in [skipFrom, skipTo). */
    private fun emitQuadContours(q: QuadOutline, sink: PathSink, skipFrom: Int, skipTo: Int) {
        for (c in 0 until q.contourCount) {
            if (c in skipFrom until skipTo) continue
            val s = q.contourStart(c)
            val e = q.contourEnd[c]
            val sg = q.seg
            sink.moveTo(sg[4 * s], sg[4 * s + 1])
            for (j in s until e) {
                val n = if (j + 1 == e) s else j + 1
                if (q.line[j]) { if (j + 1 != e) sink.lineTo(sg[4 * n], sg[4 * n + 1]) }
                else sink.quadTo(sg[4 * j + 2], sg[4 * j + 3], sg[4 * n], sg[4 * n + 1])
            }
            sink.close()
        }
    }

    /** Transverse wave on the lower right (R's leg; the lower half of other letters), in place. */
    private fun ripple(q: QuadOutline) {
        val amp = pose.ripple * upm
        val cap = vCenter * 2f
        val wl = 0.16f * upm
        val legX0 = if (ctx.targetIsR) centerX - 0.12f * upm else -1e9f
        val s = q.seg
        for (j in 0 until q.segmentCount) {
            for (o in 0..2 step 2) {
                val x = s[4 * j + o]; val y = s[4 * j + o + 1]
                val my = Motion.clamp01((cap * 0.52f - y) / (cap * 0.22f))
                val mx = if (legX0 < -1e8f) 1f else Motion.clamp01((x - legX0) / (0.1f * upm))
                val m = my * mx
                if (m <= 0f) continue
                val bottom = Motion.clamp01(1f - y / (cap * 0.1f + 1f)) // keep the foot planted
                s[4 * j + o] = x + amp * m * (1f - 0.7f * bottom) * sin(Motion.TAU * y / wl + pose.ripplePhase)
            }
        }
    }

    /** Emits the separate comma (falling in, coming loose, or growing with the voice). False when there is none. */
    fun emitComma(sink: AffinePathSink): Boolean {
        val p = pose
        val growing = p.commaMode == 0 && sComma.value <= 0.98f
        if (p.commaMode != 1 && !growing) return false
        val o: GlyphOutline
        val ci: Int
        val penX: Float
        when {
            p.phantomComma && phantomId >= 0 && phantomComp >= 0 && commaGlyph < 0 -> { o = phantom; ci = phantomComp; penX = 0f }
            commaGlyph >= 0 && !p.phantomComma -> { o = outlines[commaGlyph]; ci = commaComp[commaGlyph]; penX = pen[commaGlyph] }
            else -> return false
        }
        if (p.commaAlpha <= 0.01f) return false
        val cs = if (growing) sComma.value else p.commaScale
        if (cs <= 0.01f) return false
        val m = sink.matrix
        // comma-local ops in font units (y up): scale about its top (growing) or centre, turn about its centre, move
        val pivotX = commaCx - penX
        bodyMatrix(TMP, penX)
        m.reset().postScale(cs, cs, pivotX, if (growing) commaTop else commaCy)
        rotateYUp(m, p.commaRot, pivotX, commaCy)
        m.postTranslate(p.commaDx * upm, -p.commaDy * upm)
        concat(m, TMP)
        o.emitComponent(ci, sink)
        return true
    }

    private val TMP = Affine()
    private val TMP_B = FloatArray(4)

    /** Clockwise-on-screen rotation in the font's y-up space. */
    private fun rotateYUp(m: Affine, deg: Float, px: Float, py: Float) {
        m.postRotate(-deg, px, py)
    }

    /** m = outer ∘ m. */
    private fun concat(m: Affine, outer: Affine) {
        val a = outer.a * m.a + outer.c * m.b
        val b = outer.b * m.a + outer.d * m.b
        val c = outer.a * m.c + outer.c * m.d
        val d = outer.b * m.c + outer.d * m.d
        val e = outer.a * m.e + outer.c * m.f + outer.e
        val f = outer.b * m.e + outer.d * m.f + outer.f
        m.a = a; m.b = b; m.c = c; m.d = d; m.e = e; m.f = f
    }

    /** The ghost of swap-glide (flat, heard letter) or closure-snap's small T. False when there is none. */
    fun emitGhost(sink: AffinePathSink, heard: String?): Boolean {
        val p = pose
        if (p.ghostMode == 0 || p.ghostAlpha <= 0.01f) return false
        val want = when (p.ghostMode) {
            1 -> heard?.takeIf { it.isNotEmpty() }?.let { font.glyphFor(Normalizer.normalize(it, Normalizer.Form.NFC).codePointAt(0), features) } ?: -1
            else -> font.glyphFor((if (text.firstOrNull()?.uppercaseChar() in setOf('G', 'J')) 'D' else 'T').code, features)
        }
        if (want < 0) return false
        if (want != ghostId) { ghostId = want; vi.interpolate(ghostId, ghost) }
        val k = emPx / upm
        val baseY = vCenter * k
        // centre the ghost on its own advance
        val gx = ghost.advance * 0.5f
        val m = sink.matrix
        val sc = (if (p.ghostMode == 1) scale else scale * 0.92f) * p.ghostScale
        m.reset()
            .postScale(k, -k)
            .postTranslate(-gx * k, vCenter * k)
            .postScale(p.ghostSx, 1f, 0f, baseY)
            .postScale(sc, sc)
            .postTranslate(anchorX + p.ghostDx * emPx, anchorY + p.ghostDy * emPx + baseY * (scale - sc))
        ghost.emit(sink)
        return true
    }

    /** The badge glyph (✓ ? ↻) at the letter's top right, as a small sticker. */
    fun emitBadge(sink: AffinePathSink): Boolean {
        val p = pose
        if (p.badge == 0 || p.badgeScale <= 0.01f) return false
        val g = font.glyphFor(p.badge)
        if (g < 0) return false
        if (g != badgeId) { badgeId = g; viFixed.setAxes(850f, 100f, true); viFixed.interpolate(g, badgeOutline) }
        val k = emPx / upm
        val size = 0.36f * p.badgeScale
        val m = sink.matrix
        val cx = bodyRightPx() + 0.10f * emPx
        val cy = bodyTopPx() + 0.02f * emPx
        m.reset()
            .postScale(k * size, -k * size)
            .postTranslate(cx - badgeOutline.advance * 0.5f * k * size, cy + font.capHeight * 0.5f * k * size)
        badgeOutline.emit(sink)
        return true
    }

    /** A glyph at fixed heavy axes (stars, hearts for particles), font units → caller's matrix. */
    fun emitFixedGlyph(codePoint: Int, sink: AffinePathSink, out: GlyphOutline): Boolean {
        val g = font.glyphFor(codePoint)
        if (g < 0) return false
        viFixed.setAxes(900f, 100f, true)
        viFixed.interpolate(g, out)
        out.bounds(TMP_B)
        val cx = (TMP_B[0] + TMP_B[2]) * 0.5f
        val cy = (TMP_B[1] + TMP_B[3]) * 0.5f
        val m = sink.matrix
        m.e += -(m.a * cx + m.c * cy)
        m.f += -(m.b * cx + m.d * cy)
        out.emit(sink)
        return true
    }

    fun newOutline() = GlyphOutline(font)

    // ---- pixel geometry for the painter (approximate, from the body bounds through the pose)

    fun bodyLeftPx(): Float = mapBodyX(bounds[0], (bounds[1] + bounds[3]) * 0.5f)
    fun bodyRightPx(): Float = mapBodyX(bounds[2], (bounds[1] + bounds[3]) * 0.5f)
    fun bodyTopPx(): Float = mapBodyY(centerX, bounds[3])
    fun baselinePx(): Float = mapBodyY(centerX, 0f)
    fun capCenterYPx(): Float = mapBodyY(centerX, vCenter)
    fun bodyCenterXPx(): Float = mapBodyX(centerX, vCenter)

    /** Pixel size of one em at the current scale. */
    val emNowPx: Float get() = emPx * scale

    /** Height of the cap (or x-height) in em. */
    val capEm: Float get() = vCenter * 2f / upm

    private fun mapBodyX(x: Float, y: Float): Float { bodyMatrix(TMP, 0f); return TMP.mapX(x, y) }
    private fun mapBodyY(x: Float, y: Float): Float { bodyMatrix(TMP, 0f); return TMP.mapY(x, y) }

    /** Body without pose rotation/jump: where the baseline sits at rest (for props that stay on the ground). */
    fun restBaselinePx(): Float = anchorY + vCenter / upm * emPx * scale

    // ================================================================= particles

    private val sampleXY = FloatArray(2 * 96)
    private val sampleN = FloatArray(2 * 96)

    private fun spawnPop() {
        if (glyphCount == 0) return
        QuadOutline.from(outlines[0], q0)
        val n = OutlineOps.sampleEvenly(q0, 80, sampleXY, sampleN)
        bodyMatrix(TMP, pen[0])
        val sp = emPx * scale
        for (i in 0 until n) {
            val fx = sampleXY[2 * i]; val fy = sampleXY[2 * i + 1]
            val px = TMP.mapX(fx, fy); val py = TMP.mapY(fx, fy)
            // normal in screen space (y flipped)
            var nx = TMP.a * sampleN[2 * i] + TMP.c * sampleN[2 * i + 1]
            var ny = TMP.b * sampleN[2 * i] + TMP.d * sampleN[2 * i + 1]
            val l = kotlin.math.sqrt(nx * nx + ny * ny).coerceAtLeast(1e-4f)
            nx /= l; ny /= l
            val speed = sp * (1.3f + 1.9f * particles.rnd())
            val kind = when (i % 7) { 0, 3 -> Particles.KIND_STAR; 5 -> Particles.KIND_HEART; 6 -> Particles.KIND_CONFETTI; else -> Particles.KIND_DOT }
            val color = when (i % 4) { 0 -> 0; 1 -> 1; 2 -> 3; else -> if (i % 8 == 3) 2 else 0 }
            val size = sp * (if (kind == Particles.KIND_DOT) 0.035f + 0.03f * particles.rnd() else 0.10f + 0.05f * particles.rnd())
            particles.spawn(px, py, nx * speed, ny * speed - sp * 0.5f, size, 0.75f + 0.25f * particles.rnd(), kind, color, sp * 3.2f, 2.2f, (particles.rnd() - 0.5f) * 720f)
        }
    }

    private fun spawnStars() {
        val sp = emPx * scale
        val cx = bodyCenterXPx()
        val cy = capCenterYPx()
        for (i in 0 until 24) {
            val a = -Math.PI.toFloat() * (0.08f + 0.84f * (i + particles.rnd() * 0.6f) / 24f)
            val r = sp * 0.42f
            val speed = sp * (1.1f + 1.0f * particles.rnd())
            val kind = if (i % 5 == 4) Particles.KIND_DOT else Particles.KIND_STAR
            val color = if (i % 6 == 2) 3 else 0
            particles.spawn(
                cx + kotlin.math.cos(a) * r, cy + kotlin.math.sin(a) * r * 1.2f,
                kotlin.math.cos(a) * speed, kotlin.math.sin(a) * speed,
                sp * (if (kind == Particles.KIND_DOT) 0.04f else 0.11f + 0.05f * particles.rnd()),
                0.8f + 0.25f * particles.rnd(), kind, color, sp * 2.4f, 1.8f, (particles.rnd() - 0.5f) * 300f,
            )
        }
    }

    // ---- info for tests and the lab
    val activeCueNames: String get() = buildString { for (k in 0 until activeCount) { if (k > 0) append(' '); append(active[k]?.name) } }
    val glyphs: Int get() = glyphCount
    fun hasComma(): Boolean = commaGlyph >= 0

    companion object {
        const val MAX_GLYPHS = 3
        const val MAX_CUES = 8
    }
}
