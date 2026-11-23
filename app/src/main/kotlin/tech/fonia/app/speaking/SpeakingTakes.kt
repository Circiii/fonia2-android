package tech.fonia.app.speaking

import tech.fonia.app.data.Wav
import tech.fonia.live.Clip
import tech.fonia.speaking.CleanResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The speaking takes a person chose to keep („Păstrează”, chapter 01 §10: otherwise they vanish on exit). Each kept
 * take is two WAVs (as said, and „fără ăăă”) plus a small JSON with the thresholds version and the cut numbers, in
 * the app's no-backup folder ([dir]), which „Șterge tot” empties. Nothing else is written, nothing leaves the phone.
 */
class SpeakingTakes(private val dir: File) {
    /** Writes one kept take; returns its base name (`noum_20261121_101530`). */
    fun keep(kind: String, original: Clip, clean: CleanResult, version: String, now: LocalDateTime = LocalDateTime.now()): String {
        val base = "${kind}_${now.format(STAMP)}"
        dir.mkdirs()
        Wav.write(File(dir, "$base.wav"), original)
        Wav.write(File(dir, "${base}_clean.wav"), Clip(clean.samples, clean.sampleRate, audioSource = "clean"))
        val s = clean.stats
        File(dir, "$base.json").writeText(
            """{"kind":"$kind","thresholds":"$version","seconds":${"%.2f".format(java.util.Locale.ROOT, original.duration)},""" +
                """"cleanSeconds":${"%.2f".format(java.util.Locale.ROOT, clean.samples.size / clean.sampleRate)},"cuts":${clean.cutIds.size},"stats":"${s.toString().replace("\"", "'")}"}""",
        )
        return base
    }

    /** How many takes are kept. */
    fun count(): Int = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
