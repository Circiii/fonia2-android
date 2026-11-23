package tech.fonia.app.mirror

import tech.fonia.app.text.T

/** What Foni's line in MIRROR is about (drives the letter's pose and the ring's colour). */
enum class MirrorTone { Rest, Quality, Look, Advice, Matched, Cheer }

data class MirrorLine(val text: String, val tone: MirrorTone)

/**
 * One camera reading, as the coach needs it (copied from `MirrorSnapshot` once per drawn frame; plain fields, so the
 * coach is testable on the JVM without the face engine).
 */
class MirrorReading {
    /** A snapshot exists (the camera or the fixture delivered at least one frame). */
    var hasData = false
    /** The quality gate passed (`FaceQuality.Good`). */
    var good = false
    /** `QualityIssue.key` (`noFace`, `tooFar`, `tooDark`…). */
    var issueKey = "noFace"
    /** The rest is not taken yet (`AbstainReason.Calibrating`). */
    var calibrating = true
    /** 0…1, `MirrorSnapshot.restProgress`. */
    var restProgress = 0f
    /** `EvidenceStatus.Matches` (with the matcher's hysteresis). */
    var matches = false
    /** `CameraAdvice.key` while adjusting, null otherwise. */
    var adviceKey: String? = null
}

/**
 * The MIRROR coach (chapter 01 §8, docs/v2/04 §4): turns camera readings into one short line for the child and the
 * ring around the small letter. Pure and JVM-tested.
 *
 * - quality problems speak first, once they lasted [issueSettleNanos] (no flicker): „Vino mai aproape.”;
 * - before the rest is taken: „Fă o față liniștită, cu gura închisă.” and the ring shows the rest's progress;
 * - adjusting: the camera's advice, once it held [adviceSettleNanos], else the target shape in words;
 * - matching: „Așa! Ține-o așa.” and the ring fills; after [holdGoalSeconds] held, one [consumeCheer] (the letter
 *   celebrates) and „Bravo! Exact așa.”; gaps under [graceNanos] are forgiven; it re-arms after [rearmNanos]
 *   without a match.
 *
 * Every line is ≤ 8 words and never says what is wrong, only what to do (docs/REGULI.md, product rules).
 */
class MirrorCoach(
    private val lipClass: String,
    val holdGoalSeconds: Float = 1.0f,
    private val issueSettleNanos: Long = 600_000_000L,
    private val adviceSettleNanos: Long = 500_000_000L,
    private val rearmNanos: Long = 600_000_000L,
    /** HoldTracker's grace: a gap this short does not break the hold. */
    private val graceNanos: Long = 150_000_000L,
    /** The engine takes the rest from the calmest frames after this long at the latest (`spec/poses/camera.json`). */
    private val restFallbackNanos: Long = 4_000_000_000L,
) {
    /** 0…1 for the ring: the rest's progress, or the hold toward the cheer. */
    var ring = 0f
        private set

    /** True once the current hold reached the goal (until it re-arms). */
    var cheered = false
        private set

    var line = MirrorLine(T.mirrorRest, MirrorTone.Rest)
        private set

    private var lastNanos = 0L
    private var held = 0f
    private var cheerPending = false
    private var issue = ""
    private var issueSince = 0L
    private var advice: String? = null
    private var adviceSince = 0L
    private var unmatchedSince = 0L
    private var firstDataNanos = 0L

    /** The shape line for this sound, shown while nothing more precise can be said. */
    val shapeLine: String get() = T.mirrorShape(lipClass)

    /** True exactly once per reached hold. */
    fun consumeCheer(): Boolean = cheerPending.also { cheerPending = false }

    fun update(r: MirrorReading, now: Long): MirrorLine {
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.25f)
        lastNanos = now

        val currentIssue = if (!r.hasData) "noFace" else if (r.good) "none" else r.issueKey
        if (currentIssue != issue) {
            issue = currentIssue
            issueSince = now
        }
        val adviceNow = if (r.matches) null else r.adviceKey
        if (adviceNow != advice) {
            advice = adviceNow
            adviceSince = now
        }

        // the hold toward the cheer
        if (r.good && r.matches && !r.calibrating) {
            held += dt
            unmatchedSince = 0L
            if (held >= holdGoalSeconds && !cheered) {
                cheered = true
                cheerPending = true
            }
        } else {
            if (unmatchedSince == 0L) unmatchedSince = now
            // a break shorter than the grace (one unsteady camera frame) pauses the hold; a longer one ends it
            if (now - unmatchedSince >= graceNanos) held = 0f
            if (cheered && now - unmatchedSince >= rearmNanos) cheered = false
        }

        val qualityText = if (issue != "none" && now - issueSince >= issueSettleNanos) T.mirrorQuality(issue) else null
        line = when {
            qualityText != null -> MirrorLine(qualityText, MirrorTone.Quality)
            issue != "none" && line.tone == MirrorTone.Quality -> line // settling: keep the last hint, no flicker
            r.calibrating -> MirrorLine(T.mirrorRest, MirrorTone.Rest)
            r.matches && cheered -> MirrorLine(T.mirrorCheer, MirrorTone.Cheer)
            r.matches -> MirrorLine(T.mirrorMatched, MirrorTone.Matched)
            advice != null && now - adviceSince >= adviceSettleNanos ->
                T.mirrorAdvice(advice!!)?.let { MirrorLine(it, MirrorTone.Advice) } ?: MirrorLine(shapeLine, MirrorTone.Look)
            line.tone == MirrorTone.Advice && advice != null -> line // the next advice is settling
            else -> MirrorLine(shapeLine, MirrorTone.Look)
        }
        if (r.hasData && firstDataNanos == 0L) firstDataNanos = now
        ring = when {
            // the engine's own progress counts steady closed-mouth frames; its fall-back takes the rest after
            // [restFallbackNanos] anyway, so the ring also fills with time (never quite full before the rest is in)
            r.calibrating && r.hasData ->
                maxOf(r.restProgress, 0.95f * ((now - firstDataNanos).toFloat() / restFallbackNanos).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            cheered -> 1f
            else -> (held / holdGoalSeconds).coerceIn(0f, 1f)
        }
        return line
    }
}
