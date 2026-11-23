package tech.fonia.collector.core

import java.text.Normalizer

/**
 * File and id names (docs/v2/03 §9): `<rundă>/<vorbitor>/<sunet>/<promptId>__<etichetă>__t<n>.wav`, for example
 * `r01/A07/s/s_w_ini_sare__Interdental__t1.wav`. Folder and id parts are ASCII (`ș` → `sh`, `ț` → `ts`) so the
 * archive unpacks the same on Windows, macOS and Linux; `clips.jsonl` keeps the real sound (`"sound": "ș"`).
 */
object Naming {
    val SPEAKER = Regex("^[ATC][0-9]{2,3}$")
    val ROUND = Regex("^r[0-9]{2}$")
    val FORM_CODE = Regex("^[A-Za-z0-9-]{2,12}$")
    val INITIALS = Regex("^[\\p{L}]{2,4}$")

    const val ROOM_LABEL = "room"

    fun roleOf(speaker: String): Role? = if (!SPEAKER.matches(speaker)) null else Role.entries.firstOrNull { it.prefix == speaker[0] }

    /** Lower-case ASCII letters and digits only: "mașină" → "masina", "Țap" → "tap". */
    fun slug(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return folded.filter { it in 'a'..'z' || it in '0'..'9' }
    }

    fun promptId(soundKey: String, level: Level, position: Position?, text: String): String = buildString {
        append(soundKey).append('_').append(level.code)
        if (position != null) append('_').append(position.code)
        append('_').append(slug(text))
    }

    /** The speaker's folder, relative to the data root. */
    fun speakerDir(round: String, speaker: String): String = "$round/$speaker"

    fun clipPath(round: String, speaker: String, soundKey: String, promptId: String, label: String, take: Int): String {
        require(take >= 1)
        return "$round/$speaker/$soundKey/${promptId}__${label}__t$take.wav"
    }

    /** The room-noise take of a session check: `r01/A07/room/room__t1.wav`. */
    fun roomPath(round: String, speaker: String, take: Int): String = "$round/$speaker/$ROOM_LABEL/${ROOM_LABEL}__t$take.wav"

    fun exportName(round: String, speaker: String, stamp: String): String = "fonia_${round}_${speaker}_$stamp.zip"

    fun ageBandsFor(role: Role): List<String> = when (role) {
        Role.Child -> listOf("4-5", "6-7", "8-10", "11-17")
        Role.Adult -> listOf("11-17", "adult")
        Role.Therapist -> listOf("adult")
    }
}
