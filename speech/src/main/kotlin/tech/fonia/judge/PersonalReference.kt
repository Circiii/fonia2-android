package tech.fonia.judge

import kotlinx.serialization.Serializable
import tech.fonia.dsp.SibilantAnchor
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/**
 * The person's own reference (owner 2026-11-24, "make the app fit HIM"): after takes that BOTH heads accept with high
 * confidence, his own S and Ș spectra become the anchors of the S↔Ș boundary (the DSP's [TalkerCalibration] fit), his
 * F0 fixes his talker group, and his own fricative loudness sets the "more air" floor. Later takes are measured
 * against HIS references first ([apply]); until [PersonalThresholds.minPerPlace] anchors per place exist, the group
 * prior answers as before.
 *
 * Safety: a take joins only when the full head (phoneme model) and the fast head (measurements) both said correct
 * with high p, and its place evidence is clear of the uncertain middle, so one habit cannot slowly redefine "S". The
 * anchors per place are capped (the DSP keeps the newest `maxAnchorsPerPlace`).
 *
 * Per profile; not thread-safe (the session calls it from one coroutine). [toJson] / [fromJson] for the app to keep it.
 *
 * ```kotlin
 * val ref = PersonalReference.fromJson(prefs.getString(key, null))      // or PersonalReference()
 * val request = ref.apply(baseRequest)                                    // his anchors, his group, his loudness
 * val result = judge.decide(judge.measure(request, clip), phones)
 * if (ref.update(result)) prefs.putString(key, ref.toJson())
 * ```
 */
class PersonalReference(
    val thresholds: PersonalThresholds = PersonalThresholds(),
) {
    @Serializable
    data class SoundStats(
        val sound: String,
        val accepted: Int = 0,
        val sNess: List<Float> = emptyList(),
        val centroidHz: List<Float> = emptyList(),
        val relLevelDb: List<Float> = emptyList(),
        val fricDurMs: List<Float> = emptyList(),
    )

    @Serializable
    data class State(
        val schema: Int = 1,
        val anchors: List<AnchorJson> = emptyList(),
        val f0Hz: List<Float> = emptyList(),
        val sounds: List<SoundStats> = emptyList(),
        val takes: Int = 0,
    )

    @Serializable
    data class AnchorJson(val place: String, val centroidHz: Float, val peakHz: Float, val bandRatioDb: Float, val tiltDb: Float)

    private val calibration = TalkerCalibration()
    private val f0 = ArrayList<Float>()
    private val stats = LinkedHashMap<SoundId, SoundStats>()

    /** Takes that joined the reference. */
    var takes: Int = 0
        private set

    /** True once the person's own S/Ș boundary is in use. */
    val active: Boolean
        get() = calibration.anchors.count { it.place == SibilantPlace.S } >= thresholds.minPerPlace &&
            calibration.anchors.count { it.place == SibilantPlace.SH } >= thresholds.minPerPlace

    /** The person's anchors for the DSP, or null before [active]. */
    fun calibration(): TalkerCalibration? = if (active) calibration.copy() else null

    /** His median F0 (NaN before any voiced accepted take). */
    val f0Hz: Float get() = median(f0)

    /** His talker group from his accepted takes (null before 3 voiced ones). */
    fun talker(): TalkerGroup? = if (f0.size >= 3) TalkerVoice.groupOf(f0Hz) else null

    fun stats(sound: SoundId): SoundStats? = stats[sound]

    /** The request measured against his references first: his anchors, his group (when known), his F0. */
    fun apply(request: JudgeRequest): JudgeRequest = request.copy(
        calibration = calibration() ?: request.calibration,
        talker = talker() ?: request.talker,
        childF0Hz = if (f0.size >= 3) f0Hz else request.childF0Hz,
        reference = this,
    )

    /** The "more air" floor: his median relative level minus a margin, once he has ≥ 5 accepted takes of [sound]. */
    fun loudnessFloor(sound: SoundId, t: VisualThresholds): Float? {
        val s = stats[sound] ?: return null
        if (s.relLevelDb.size < 5) return null
        return median(s.relLevelDb) - t.personalLevelMarginDb
    }

    /**
     * Offers one decided take; returns true when it joined (the caller then saves [toJson]). Joins only a Correct take
     * decided by the full head with p ≥ `minFull` whose fast-head p(correct) was ≥ `minFast`.
     */
    fun update(result: TakeResult): Boolean {
        val sound = result.sound ?: return false
        if (result.verdict != Verdict.Correct || result.head != Head.Full) return false
        if (result.confidence < thresholds.minFull) return false
        if ((result.fastCorrect ?: 0f) < thresholds.minFast) return false
        val f = result.evidence.features
        fun v(name: String) = f[name] ?: Float.NaN
        takes++
        val pitch = v(Features.F0)
        if (!pitch.isNaN() && TalkerVoice.groupOf(pitch) != null) push(f0, pitch)
        val old = stats[sound] ?: SoundStats(sound.raw)
        stats[sound] = old.copy(
            accepted = old.accepted + 1,
            sNess = pushed(old.sNess, v(Features.S_NESS)),
            centroidHz = pushed(old.centroidHz, v(Features.CENTROID)),
            relLevelDb = pushed(old.relLevelDb, v(Features.REL_LEVEL)),
            fricDurMs = pushed(old.fricDurMs, v(Features.FRIC_DUR)),
        )
        // S and Ș only: the voiced and affricate spectra differ from the hiss the boundary is about.
        val place = when (sound) {
            SoundId.S -> SibilantPlace.S
            SoundId.Sh -> SibilantPlace.SH
            else -> null
        }
        val sNess = v(Features.S_NESS)
        val clear = !sNess.isNaN() && kotlin.math.abs(sNess - 0.5f) >= thresholds.minPlaceMargin &&
            (place == SibilantPlace.S) == (sNess > 0.5f)
        val c = v(Features.CENTROID)
        val p = v(Features.PEAK)
        val b = v(Features.BAND_RATIO)
        val tilt = v(Features.TILT)
        if (place != null && clear && !c.isNaN() && !p.isNaN() && !b.isNaN()) {
            calibration.add(SibilantAnchor(place, c, p, b, if (tilt.isNaN()) 0f else tilt))
        }
        return true
    }

    fun toJson(): String = JudgeSpec.json.encodeToString(State.serializer(), State(
        anchors = calibration.anchors.map { AnchorJson(it.place.rawValue, it.centroidHz, it.peakHz, it.bandRatioDb, it.tiltDb) },
        f0Hz = f0.toList(),
        sounds = stats.values.toList(),
        takes = takes,
    ))

    private fun push(list: MutableList<Float>, value: Float) {
        list.add(value)
        while (list.size > thresholds.window) list.removeAt(0)
    }

    private fun pushed(list: List<Float>, value: Float): List<Float> =
        if (value.isNaN()) list else (list + value).takeLast(thresholds.window)

    companion object {
        /** Restores a saved reference; null, blank or unreadable text gives an empty one. */
        fun fromJson(text: String?, thresholds: PersonalThresholds = PersonalThresholds()): PersonalReference {
            val ref = PersonalReference(thresholds)
            if (text.isNullOrBlank()) return ref
            val state = runCatching { JudgeSpec.json.decodeFromString(State.serializer(), text) }.getOrNull() ?: return ref
            for (a in state.anchors) {
                val place = SibilantPlace.fromRawValue(a.place) ?: continue
                ref.calibration.add(SibilantAnchor(place, a.centroidHz, a.peakHz, a.bandRatioDb, a.tiltDb))
            }
            state.f0Hz.takeLast(thresholds.window).forEach { ref.f0.add(it) }
            for (s in state.sounds) SoundId.of(s.sound)?.let { ref.stats[it] = s }
            ref.takes = state.takes
            return ref
        }

        private fun median(v: List<Float>): Float {
            if (v.isEmpty()) return Float.NaN
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }
    }
}
