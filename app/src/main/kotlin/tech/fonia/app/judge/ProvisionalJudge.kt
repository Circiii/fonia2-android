package tech.fonia.app.judge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.live.Clip
import tech.fonia.live.LiveConfig
import tech.fonia.live.LiveTarget
import tech.fonia.live.SibilantSide
import tech.fonia.model.ErrorKind
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** The provisional judge's numbers (data, saved by version; candidates for `spec/thresholds`). */
data class ProvisionalTuning(
    /** The hold goal of the `Hold` step (also the ring of the lesson). */
    val holdGoalSeconds: Float = 2.5f,
    /** Below this much speech (or this peak level) the take is a [Verdict.Retake]: too short or too quiet. */
    val minSpeechSeconds: Double = 0.25,
    val minPeakLevel: Float = 0.12f,
    /** Friction needed to judge S vs Ș: the `Hold` step and the `Words` step. */
    val minFricativeHold: Double = 0.35,
    val minFricativeWord: Double = 0.06,
    /** sNess at or above = the S side; at or below = the Ș side; in between the phone is unsure. */
    val sSide: Float = 0.6f,
    val shSide: Float = 0.4f,
    /** Share of the hold goal that turns a right-side `Hold` take into Correct (else Almost). */
    val correctHoldShare: Float = 0.9f,
    val version: String = "provisional-0.1",
)

/**
 * A stand-in judge built only on what exists today: the live pipeline re-run over the take ([TakeAnalysis]).
 * - too short / too quiet → [Verdict.Retake];
 * - sibilants: mean sNess on the right side → [Verdict.Correct] (`Hold`: [Verdict.Almost] when held too briefly),
 *   on the other side → [Verdict.NamedError] (`PlaceSwap`, or `AffricatePlace` for Ț / CE / GE) with what was heard,
 *   in between → [Verdict.Unsure];
 * - R: a trill held to the goal → Correct, some trill → Almost; L and anything else spoken → Unsure (the adult decides).
 *
 * Kept as a test double only: the app uses [RealJudge] (`:engine:judge`).
 */
class ProvisionalJudge(
    private val tuning: ProvisionalTuning = ProvisionalTuning(),
    private val config: LiveConfig = LiveConfig(),
) : TakeJudging {
    override val version: String get() = tuning.version

    override suspend fun judge(request: TakeRequest, clip: Clip): Judged = Judged(verdict(request.sound, request.level, clip))

    suspend fun verdict(sound: SoundId, level: Rung, clip: Clip): Verdict =
        withContext(Dispatchers.Default) { decide(sound, level, TakeAnalysis.summarise(clip, LiveTarget.of(sound), config)) }

    /** The decision alone, for tests. */
    fun decide(sound: SoundId, level: Rung, s: TakeSummary): Verdict {
        val hold = level == Rung.Isolated
        if (s.speakingSeconds < tuning.minSpeechSeconds || s.peakLevel < tuning.minPeakLevel) return Verdict.Retake
        return when (val target = LiveTarget.of(sound)) {
            is LiveTarget.Sibilant -> sibilant(sound, target.side, hold, s)
            LiveTarget.Trill -> when {
                hold && s.bestHoldSeconds >= tuning.holdGoalSeconds * tuning.correctHoldShare -> Verdict.Correct
                !hold && s.trillSeconds >= 0.08 -> Verdict.Correct
                s.trillSeconds > 0.05 -> Verdict.Almost
                else -> Verdict.Unsure
            }
            else -> Verdict.Unsure
        }
    }

    private fun sibilant(sound: SoundId, side: SibilantSide, hold: Boolean, s: TakeSummary): Verdict {
        val enough = if (hold) tuning.minFricativeHold else tuning.minFricativeWord
        if (s.fricativeSeconds < enough || s.meanSNess.isNaN()) return Verdict.Unsure
        val heardS = s.meanSNess >= tuning.sSide
        val heardSh = s.meanSNess <= tuning.shSide
        val right = if (side == SibilantSide.S) heardS else heardSh
        val other = if (side == SibilantSide.S) heardSh else heardS
        return when {
            right && hold && s.bestHoldSeconds < tuning.holdGoalSeconds * tuning.correctHoldShare -> Verdict.Almost
            right -> Verdict.Correct
            other -> Verdict.NamedError(swapKind(sound), heardAs(sound))
            else -> Verdict.Unsure
        }
    }

    companion object {
        fun swapKind(sound: SoundId): ErrorKind = when (sound) {
            SoundId.Ts, SoundId.Ce, SoundId.Ge -> ErrorKind.AffricatePlace
            else -> ErrorKind.PlaceSwap
        }

        /** The sound on the other side of the S/Ș scale, when Romanian has one. */
        fun heardAs(sound: SoundId): SoundId? = when (sound) {
            SoundId.S -> SoundId.Sh
            SoundId.Sh -> SoundId.S
            SoundId.Z -> SoundId.J
            SoundId.J -> SoundId.Z
            SoundId.Ts -> SoundId.Ce
            SoundId.Ce -> SoundId.Ts
            else -> null
        }
    }
}
