package tech.fonia.features.session

/**
 * How fast the practice screens move and how calm the live letter is: data in `spec/pacing.json` (copied into the APK
 * by `specAssets`), with these defaults equal to it (PacingTest). Nothing moves too fast or trembles:
 * verdicts stay long enough to read, live values are smoothed with a dead zone, and the full
 * judge gets time in proportion to the take.
 */
data class Pacing(
    /** A good take's outcome stays this long before the next one comes by itself. */
    val verdictGoodMs: Long = 1900,
    /** A take that is not yet right (the letter shows what to do) stays this long. */
    val verdictAlmostMs: Long = 2600,
    /** Extra time when the strip under the outcome draws what the take looked like. */
    val stripExtraMs: Long = 900,
    /** Extra time when there is a hint with "Vezi cum". */
    val hintExtraMs: Long = 3000,
    /** Each step of the 3-2-1 before the microphone opens again after "Mai spune o dată". */
    val retakeCountStepMs: Long = 800,
    /** "Aproape! Mergem mai departe" after the retakes ran out. */
    val almostMoveOnMs: Long = 2400,
    /** "Nu te aud" in the quick check before the next word. */
    val noMicCheckMs: Long = 2600,
    /** The held sound: the POP stays, and a partial hold stays. */
    val sustainDoneMs: Long = 2100,
    val sustainPartialMs: Long = 1900,
    /** The warm-up's two mouth poses alternate this slowly. */
    val warmupPoseMs: Long = 1400,

    /** The live loudness: on in this time constant, off in that one; changes under the dead zone are ignored. */
    val liveAttackMs: Float = 70f,
    val liveReleaseMs: Float = 320f,
    val liveDeadZone: Float = 0.03f,
    /** Where the hiss sits (S ↔ Ș): smoothed (both ways), held this long after the hiss stops, dead zone. */
    val placeAttackMs: Float = 90f,
    val placeHoldMs: Float = 260f,
    val placeDeadZone: Float = 0.04f,
    /** The target sound must be heard this long before the letter lights, and gone this long before it goes out. */
    val onTargetOnMs: Float = 120f,
    val onTargetOffMs: Float = 420f,
    /** The glow behind the letter when the target sound is heard. */
    val glowAttackMs: Float = 90f,
    val glowReleaseMs: Float = 380f,
    /** Share of the loudness passed to the letter's fast 11 Hz energy buzz (1 = as designed, 0 = none). */
    val letterBuzzShare: Float = 0.3f,
    /** The letter's inflation a loud voice reaches; the rest (to 1) is kept for the target sound itself. */
    val voiceInflationTop: Float = 0.82f,

    /** The full judge (phoneme model) budget: base, plus this per second of take over one second, capped. */
    val judgeBudgetMs: Long = 600,
    val judgeBudgetPerSecondMs: Long = 300,
    val judgeBudgetMaxMs: Long = 1500,
    /** An x86 emulator runs the phoneme model this many times slower. */
    val judgeEmulatorFactor: Float = 2f,
) {
    /** The full judge's budget for a take of [seconds] (a long sentence gets more time than a syllable). */
    fun judgeBudget(seconds: Double, emulator: Boolean): Long {
        val extra = (judgeBudgetPerSecondMs * (seconds - 1.0).coerceAtLeast(0.0)).toLong()
        val phone = (judgeBudgetMs + extra).coerceAtMost(judgeBudgetMaxMs)
        return if (emulator) (phone * judgeEmulatorFactor).toLong() else phone
    }

    companion object {
        /** The pacing in use: the APK's `spec/pacing.json` once the app has read it, the defaults before (and in tests). */
        @Volatile
        var current: Pacing = Pacing()

        private val NUMBER = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")

        /** Reads the flat numbers of `spec/pacing.json`; a missing key keeps its default, unknown keys are ignored. */
        fun parse(text: String): Pacing {
            val v = NUMBER.findAll(text).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val d = Pacing()
            fun l(name: String, def: Long) = v[name]?.toLong() ?: def
            fun f(name: String, def: Float) = v[name]?.toFloat() ?: def
            return Pacing(
                verdictGoodMs = l("verdictGoodMs", d.verdictGoodMs),
                verdictAlmostMs = l("verdictAlmostMs", d.verdictAlmostMs),
                stripExtraMs = l("stripExtraMs", d.stripExtraMs),
                hintExtraMs = l("hintExtraMs", d.hintExtraMs),
                retakeCountStepMs = l("retakeCountStepMs", d.retakeCountStepMs),
                almostMoveOnMs = l("almostMoveOnMs", d.almostMoveOnMs),
                noMicCheckMs = l("noMicCheckMs", d.noMicCheckMs),
                sustainDoneMs = l("sustainDoneMs", d.sustainDoneMs),
                sustainPartialMs = l("sustainPartialMs", d.sustainPartialMs),
                warmupPoseMs = l("warmupPoseMs", d.warmupPoseMs),
                liveAttackMs = f("liveAttackMs", d.liveAttackMs),
                liveReleaseMs = f("liveReleaseMs", d.liveReleaseMs),
                liveDeadZone = f("liveDeadZone", d.liveDeadZone),
                placeAttackMs = f("placeAttackMs", d.placeAttackMs),
                placeHoldMs = f("placeHoldMs", d.placeHoldMs),
                placeDeadZone = f("placeDeadZone", d.placeDeadZone),
                onTargetOnMs = f("onTargetOnMs", d.onTargetOnMs),
                onTargetOffMs = f("onTargetOffMs", d.onTargetOffMs),
                glowAttackMs = f("glowAttackMs", d.glowAttackMs),
                glowReleaseMs = f("glowReleaseMs", d.glowReleaseMs),
                letterBuzzShare = f("letterBuzzShare", d.letterBuzzShare),
                voiceInflationTop = f("voiceInflationTop", d.voiceInflationTop),
                judgeBudgetMs = l("judgeBudgetMs", d.judgeBudgetMs),
                judgeBudgetPerSecondMs = l("judgeBudgetPerSecondMs", d.judgeBudgetPerSecondMs),
                judgeBudgetMaxMs = l("judgeBudgetMaxMs", d.judgeBudgetMaxMs),
                judgeEmulatorFactor = f("judgeEmulatorFactor", d.judgeEmulatorFactor),
            )
        }
    }
}
