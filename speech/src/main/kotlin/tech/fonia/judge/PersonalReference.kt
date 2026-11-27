package tech.fonia.judge

import kotlinx.serialization.Serializable
import tech.fonia.dsp.SibilantAnchor
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/**
 * The person's own reference (the app fits HIM): after takes that BOTH heads accept with high
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
        /** The guided calibration ("Calibrare"), null before it. */
        val room: Room? = null,
    )

    /**
     * What the guided calibration measured (about 20 s: 2 s of quiet, a held "aaa", "sss", "șșș"): his room's noise
     * floor and his speaking level (frame RMS, dBFS), his voice's F0, and when. The live meters set their floor and
     * range from it; the judge uses his group from [f0Hz] until his accepted takes know it better.
     */
    @Serializable
    data class Room(
        val floorDb: Float,
        val speechDb: Float,
        val f0Hz: Float? = null,
        /** Median live place of his held S and Ș once measured against his own anchors (0 = Ș … 1 = S), for the picture. */
        val sPlace: Float? = null,
        val shPlace: Float? = null,
        val atMillis: Long = 0L,
    )

    @Serializable
    data class AnchorJson(val place: String, val centroidHz: Float, val peakHz: Float, val bandRatioDb: Float, val tiltDb: Float)

    private val calibration = TalkerCalibration()
    private val f0 = ArrayList<Float>()
    private val stats = LinkedHashMap<SoundId, SoundStats>()

    /** The guided calibration, null before it. */
    var room: Room? = null
        private set

    /** Takes that joined the reference. */
    var takes: Int = 0
        private set

    /**
     * True once the person's own S/Ș boundary is in use: enough anchors of each place, and the two places really are
     * two different hisses ([separated]). A reference taken from noise (a dead or very quiet microphone: S and Ș come
     * out the same) is never used, also when it is already saved.
     */
    val active: Boolean
        get() = calibration.anchors.count { it.place == SibilantPlace.S } >= thresholds.minPerPlace &&
            calibration.anchors.count { it.place == SibilantPlace.SH } >= thresholds.minPerPlace &&
            separated(calibration.anchors)

    /** The calibrated room when it can be trusted: a voice was really heard ([audible]); else null. */
    val usableRoom: Room?
        get() = room?.takeIf { audible(it) }

    /** The person's anchors for the DSP, or null before [active]. */
    fun calibration(): TalkerCalibration? = if (active) calibration.copy() else null

    /** His median F0 (NaN before any voiced accepted take). */
    val f0Hz: Float get() = median(f0)

    /** His talker group from his accepted takes, or from the calibration's "aaa" before 3 voiced ones (null: neither). */
    fun talker(): TalkerGroup? = when {
        f0.size >= 3 -> TalkerVoice.groupOf(f0Hz)
        else -> room?.f0Hz?.let { TalkerVoice.groupOf(it) }
    }

    fun stats(sound: SoundId): SoundStats? = stats[sound]

    /** The request measured against his references first: his anchors, his group (when known), his F0. */
    fun apply(request: JudgeRequest): JudgeRequest = request.copy(
        calibration = calibration() ?: request.calibration,
        talker = talker() ?: request.talker,
        childF0Hz = when {
            f0.size >= 3 -> f0Hz
            room?.f0Hz != null -> room!!.f0Hz!!
            else -> request.childF0Hz
        },
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

    /**
     * The guided calibration: his held S and Ș become anchors at once (he was asked for exactly those sounds, so no
     * judge has to accept them first; a pair the wrong way round is still refused by the DSP's fit), and [room] is kept.
     * The newest anchors replace the oldest past the DSP's cap per place. Returns true when his own S/Ș boundary is
     * now [active].
     */
    fun calibrate(room: Room, sAnchors: List<SibilantAnchor>, shAnchors: List<SibilantAnchor>): Boolean {
        // A calibration that heard no voice, or whose S and Ș are the same hiss, measured the room, not the person:
        // nothing of it is kept, and what was there before stays.
        val fresh = sAnchors.map { it.copy(place = SibilantPlace.S) } + shAnchors.map { it.copy(place = SibilantPlace.SH) }
        if (!audible(room) || !separated(fresh)) return false
        // An older reference that cannot be used (saved before this check) makes room for the new one.
        if (!separated(calibration.anchors)) calibration.anchors = emptyList()
        this.room = room
        for (a in sAnchors) calibration.add(a.copy(place = SibilantPlace.S))
        for (a in shAnchors) calibration.add(a.copy(place = SibilantPlace.SH))
        return active
    }

    /** Keeps the places measured after the anchors were added (the result picture). */
    fun placed(sPlace: Float, shPlace: Float) {
        room = room?.copy(sPlace = sPlace.takeIf { !it.isNaN() }, shPlace = shPlace.takeIf { !it.isNaN() })
    }

    fun toJson(): String = JudgeSpec.json.encodeToString(
        State.serializer(),
        State(
            anchors = calibration.anchors.map { AnchorJson(it.place.rawValue, it.centroidHz, it.peakHz, it.bandRatioDb, it.tiltDb) },
            f0Hz = f0.toList(),
            sounds = stats.values.toList(),
            takes = takes,
            room = room,
        ),
    )

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
            ref.room = state.room
            return ref
        }

        /** S anchors sit clearly above Ș anchors: at least [MIN_CENTROID_GAP_HZ] in centroid or [MIN_TILT_GAP_DB] in tilt. */
        fun separated(anchors: List<SibilantAnchor>): Boolean {
            val s = anchors.filter { it.place == SibilantPlace.S }
            val sh = anchors.filter { it.place == SibilantPlace.SH }
            if (s.isEmpty() || sh.isEmpty()) return false
            return median(s.map { it.centroidHz }) - median(sh.map { it.centroidHz }) >= MIN_CENTROID_GAP_HZ ||
                median(s.map { it.tiltDb }) - median(sh.map { it.tiltDb }) >= MIN_TILT_GAP_DB
        }

        /** A voice was heard: not near digital silence, and well above the room. */
        fun audible(room: Room): Boolean = room.speechDb >= MIN_SPEECH_DB && room.speechDb - room.floorDb >= MIN_ABOVE_ROOM_DB

        const val MIN_CENTROID_GAP_HZ = 700f
        const val MIN_TILT_GAP_DB = 5f
        const val MIN_SPEECH_DB = -50f
        const val MIN_ABOVE_ROOM_DB = 15f

        private fun median(v: List<Float>): Float {
            if (v.isEmpty()) return Float.NaN
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }
    }
}
