package tech.fonia.app.data

import tech.fonia.app.judge.SoundCalibration
import tech.fonia.dsp.SibilantAnchor
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId

/**
 * Calibration on the child (plan 03 §6, E4; the adult's thumb of §7), kept as bookkeeping over the attempt rows — the
 * measurements, never the sound. Pure: rebuilt from the attempts after every adult answer.
 *
 * - **Confirmed good** = a judged take (not a retake) the adult marked 👍, in the lesson or in ADULT-CALIBRATE. At
 *   [CALIBRATED_AT] on a sound the sound is „calibrat pe copil” and the judge runs at calibration tier 1.
 * - **Anchors**: the S/Ș measurements of confirmed takes go into the child's `TalkerCalibration` (oldest first; it
 *   keeps at most 30 per place). Its own boundary only takes over at ≥ 10 anchors with ≥ 3 per place (`:engine:dsp`).
 * - **Child F0**: the median F0 of confirmed takes once there are [MIN_F0_TAKES] (the adult-voice gate).
 * - **Disagreements**: phone `correct` + adult "încă nu" in the last [WINDOW] answered takes of a sound: at
 *   [DISAGREE_AT] the praise threshold rises by [STEP] (`childOkOffset`).
 * - **Errors the adult did not hear**: phone `namedError` / `almost` with a kind (named or withheld) + adult 👍 raises
 *   that kind's threshold by [STEP] each time (`childErrOffset`; the judge adapter caps `tauErr + offset` at 0.97).
 */
class CalibrationBook private constructor(
    private val perSound: Map<SoundId, SoundCalibration>,
) {
    fun forSound(sound: SoundId): SoundCalibration = perSound[sound] ?: SoundCalibration()

    fun calibrated(sound: SoundId): Boolean = forSound(sound).tier >= 1

    companion object {
        const val CALIBRATED_AT = 5
        const val MIN_F0_TAKES = 3
        const val WINDOW = 20
        const val DISAGREE_AT = 3
        const val STEP = 0.05f

        val Empty = CalibrationBook(emptyMap())

        fun build(attempts: List<AttemptView>, childTalker: Boolean = true): CalibrationBook {
            val ordered = attempts.filter { it.sound != null && it.judged }.sortedBy { it.ts }
            val confirmed = ordered.filter { it.adultGood == true }

            val talker = TalkerCalibration()
            confirmed.mapNotNull { it.anchor }.forEach { a ->
                val place = SibilantPlace.fromRawValue(a.place) ?: return@forEach
                talker.add(SibilantAnchor(place, a.centroidHz, a.peakHz, a.bandRatioDb, a.tiltDb))
            }
            val anchorsJson = if (talker.anchors.isEmpty()) null else talker.toJson()

            val f0s = confirmed.mapNotNull { it.f0Hz?.takeIf { f -> f.isFinite() && f > 50f } }.sorted()
            val childF0 = if (childTalker && f0s.size >= MIN_F0_TAKES) median(f0s) else null

            val map = SoundId.entries.associateWith { sound ->
                val mine = ordered.filter { it.sound == sound }
                val good = mine.count { it.adultGood == true }
                val answered = mine.filter { it.adultGood != null }.takeLast(WINDOW)
                val disagreements = answered.count { it.verdict == "correct" && it.adultGood == false }
                val errOffsets = HashMap<ErrorKind, Float>()
                for (a in mine) {
                    if (a.adultGood != true) continue
                    val kind = when (a.verdict) {
                        "namedError" -> a.errorKind
                        "almost" -> a.withheldKind ?: a.trialKind
                        else -> null
                    } ?: continue
                    errOffsets[kind] = (errOffsets[kind] ?: 0f) + STEP
                }
                SoundCalibration(
                    confirmedGood = good,
                    tier = if (good >= CALIBRATED_AT) 1 else 0,
                    anchorsJson = anchorsJson,
                    childF0Hz = childF0,
                    childOkOffset = if (disagreements >= DISAGREE_AT) STEP else 0f,
                    childErrOffset = errOffsets,
                )
            }
            return CalibrationBook(map)
        }

        private fun median(sorted: List<Float>): Float =
            if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }
}
