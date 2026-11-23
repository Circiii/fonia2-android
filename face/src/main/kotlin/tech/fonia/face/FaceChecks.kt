package tech.fonia.face

import kotlin.time.Duration.Companion.milliseconds

/**
 * The mouth coach's face checks: `CoachFaceJudge` (FoniaDomain/CoachPlan.swift) case for case, with
 * the same raw values, and the rules of `MouthCoach.matches` (FoniaFeatures/Guide/MouthCoach.swift).
 * `:domain` keeps `CoachFaceJudge` for reading coach_plans.json; `:face` has no dependencies, so the
 * coach maps one onto the other by raw value with [fromRawValue].
 *
 * Starting values, tried by eye and not yet measured on many faces. That's why the person can
 * always say "Am făcut" themselves.
 */
enum class FaceCheck(val rawValue: String) {
    SMILE("smile"),
    TEETH("teeth"),
    PUCKER("pucker"),
    ROUND_CLOSE("roundClose"),
    ROUND_LIGHT("roundLight"),
    OPEN("open"),
    TONGUE_OUT("tongueOut"),
    ROUND_W("roundW"),
    LIP_BITE("lipBite"),
    RELAXED("relaxed"),
    WIDE_OPEN("wideOpen"),
    ;

    /** The rule alone; [FaceHold] also asks for a visible face. Float literals, as in Swift. */
    fun matches(frame: FaceFrame): Boolean = when (this) {
        SMILE -> frame.smile > 0.42f
        TEETH -> frame.smile > 0.18f && frame.jawOpen < 0.16f
        PUCKER -> frame.pucker > 0.42f
        ROUND_CLOSE -> frame.round > 0.38f && frame.jawOpen < 0.25f
        ROUND_LIGHT -> frame.round > 0.24f
        ROUND_W -> frame.round > 0.45f
        OPEN -> frame.jawOpen > 0.22f
        WIDE_OPEN -> frame.jawOpen > 0.42f
        TONGUE_OUT -> frame.tongueOut > 0.3f
        RELAXED -> frame.smile < 0.2f && frame.round < 0.2f && frame.jawOpen < 0.2f
        LIP_BITE -> frame.jawOpen < 0.12f && frame.round < 0.25f
    }

    companion object {
        fun fromRawValue(rawValue: String): FaceCheck? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * The pose has to hold for a moment, so a passing smile doesn't count. `MouthCoach.watch` without
 * its clock: the coach polls every [POLL_INTERVAL] and calls [poll]. Time is counted per poll, not
 * read from a clock, so the polling period decides the real hold time, as on iOS.
 */
class FaceHold(holdMs: Int) {
    /** `max(Double(step.holdMs) / 1000, 0.6)` seconds. */
    val needed: Double = SwiftMath.max(holdMs.toDouble() / 1000, 0.6)

    var seconds: Double = 0.0
        private set

    /** The progress bar, 0...1. */
    var held: Double = 0.0
        private set

    val isDone: Boolean
        get() = seconds >= needed

    /** One poll of the latest frame; true once the pose has been held long enough. */
    fun poll(check: FaceCheck, frame: FaceFrame): Boolean {
        val matches = frame.isPresent && check.matches(frame)
        seconds = if (matches) seconds + GAIN_SECONDS else SwiftMath.max(0.0, seconds - LOSS_SECONDS)
        held = SwiftMath.min(1.0, seconds / needed)
        return seconds >= needed
    }

    companion object {
        val POLL_INTERVAL = 60.milliseconds
        const val GAIN_SECONDS = 0.06
        const val LOSS_SECONDS = 0.03
    }
}
