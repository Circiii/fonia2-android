package tech.fonia.speaking.coach

import org.junit.jupiter.api.Test
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.decodeWAV
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The coach on real videos (opt-in: `-Pfonia.coach.bench=<folder>`; the folder is never committed). The folder's
 * `tracks/<name>.csv` holds what the app's own face model saw in each frame (made by `track.py` on the PC) and
 * `tracks/<name>.wav` the sound. Each pair is replayed through [CoachSession] as the screen does (the first
 * [BASELINE] seconds are the countdown: the lens baseline; ticks every 0.1 s) and the timeline is written to
 * `out/<name>.csv` with the cues and the report in `out/<name>.txt`.
 */
class CoachBenchTest {
    @Test
    fun realVideos() {
        val dir = File(System.getProperty("fonia.coach.bench") ?: return)
        val tracks = File(dir, "tracks").listFiles { f -> f.extension == "csv" }?.sortedBy { it.name } ?: return
        val out = File(dir, "out").apply { mkdirs() }
        val only = System.getProperty("fonia.coach.bench.only")
        for (track in tracks) {
            if (only != null && only !in track.name) continue
            val wav = File(track.parentFile, track.nameWithoutExtension + ".wav")
            if (!wav.exists()) continue
            replay(track, wav, out)
        }
    }

    private fun replay(track: File, wavFile: File, out: File) {
        val lines = track.readLines()
        val head = lines.first().split(',')
        val col = head.withIndex().associate { it.value to it.index }
        val wav = decodeWAV(wavFile.readBytes())
        val pcm = wav.pcm
        val rate = wav.sampleRate
        val session = CoachSession()
        val audio = session.attachAudio(rate)
        val live = LiveAnalyzer(rate, VoiceGroup.FEMALE, null)
        val frame = LiveFrame.silent
        val hop = (rate * 0.01).toInt()
        val sample = FaceSample()
        val rows = StringBuilder("t,speaking,rate,quiet,loud01,looking,deviation,away,motion,faceActivity,pitchSpread,lively,cue\n")
        val cues = ArrayList<String>()
        var lastCue: Cue? = null
        // The phone model's vowels (from `VowelNucleiBenchTest`), handed over in 2 s chunks 0.4 s late, as the app does.
        val vowels = File(out, track.nameWithoutExtension + ".vowels.txt").takeIf { it.exists() && System.getProperty("fonia.coach.bench.envelope") == null }
            ?.readLines()?.mapNotNull { it.toDoubleOrNull() }?.map { it - BASELINE }?.filter { it >= 0 }
        var chunk = 0.0
        var row = 1
        var audioAt = 0
        var nextTick = BASELINE
        session.face.startBaseline()
        var started = false
        while (row < lines.size) {
            val c = lines[row].split(',')
            val t = c[0].toDouble()
            if (!started && t >= BASELINE) {
                session.face.endBaseline()
                session.startTalk(BASELINE)
                started = true
                audioAt = (BASELINE * rate).toInt()
            }
            fill(sample, c, col, t)
            session.face.onFrame(sample)
            row++
            if (!started) continue
            // The sound up to this frame.
            val until = minOf(pcm.size, (t * rate).toInt())
            while (audioAt + hop <= until) {
                val f = live.process(pcm, audioAt, hop, frame)
                audio.process(pcm, audioAt, hop, f.pitchHz)
                audioAt += hop
            }
            while (vowels != null && audio.seconds >= chunk + 2.4) {
                audio.addVowels(chunk, chunk + 2.0, vowels.filter { it >= chunk && it < chunk + 2.0 }.toDoubleArray())
                chunk += 2.0
            }
            while (nextTick <= t) {
                val cue = session.tick(nextTick)
                val tt = nextTick - BASELINE
                if (cue != lastCue) {
                    if (cue != null) cues += "%5.1f %s".format(tt, cue.word)
                    lastCue = cue
                }
                val l = session.levels
                rows.append("%.1f,%d,%.2f,%d,%.2f,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s\n".format(
                    tt, if (l.speaking) 1 else 0, l.paceRate, if (audio.quiet) 1 else 0, l.loudness01, l.looking.toString(),
                    session.face.gazeDeviation, session.face.awayFraction, session.face.motionIndex, session.face.faceActivity01,
                    audio.pitchSpreadSt, l.lively01, cue?.key ?: "",
                ))
                nextTick += 0.1
            }
        }
        File(out, track.nameWithoutExtension + ".csv").writeText(rows.toString())
        val s = audio.summary()
        val text = StringBuilder()
        text.append("seconds=%.1f phonation=%.1f nuclei=%d rate=%.2f pitchSpread=%.2f longPauses=%d baseline=%s\n".format(
            s.seconds, s.phonationSeconds, s.nuclei, s.articulationRate, s.pitchSpreadSt, s.longPauses.size, session.face.baselineTaken,
        ))
        cues.forEach { text.append(it).append('\n') }
        File(out, track.nameWithoutExtension + ".txt").writeText(text.toString())
        println("${track.nameWithoutExtension}: ${text.lines().first()} cues=${cues.size}")
    }

    /** One row of the track, as `FaceSample.fill` reads a `FaceMeshFrame`. */
    private fun fill(s: FaceSample, c: List<String>, col: Map<String, Int>, t: Double) {
        s.tSeconds = t
        s.present = c[col.getValue("present")] == "1"
        if (!s.present) return
        fun v(name: String) = c[col.getValue(name)].toDouble()
        val w = v("w"); val h = v("h")
        fun x(i: Int) = v("x$i") * w
        fun y(i: Int) = v("y$i") * h
        val ercx = (x(33) + x(133)) / 2; val ercy = (y(33) + y(133)) / 2
        val elcx = (x(263) + x(362)) / 2; val elcy = (y(263) + y(362)) / 2
        val eye = hypot(elcx - ercx, elcy - ercy).coerceAtLeast(1.0)
        s.posX = x(168) / eye
        s.posY = y(168) / eye
        s.yawDeg = v("yaw")
        s.pitchDeg = v("pitch")
        s.rollDeg = Math.toDegrees(atan2(elcy - ercy, elcx - ercx))
        val tR = ratio(x(468), x(33), x(133))
        val tL = ratio(x(473), x(362), x(263))
        s.eyeH = when {
            !tR.isNaN() && !tL.isNaN() -> (tR + tL) - 1.0
            !tR.isNaN() -> (tR - 0.5) * 2
            !tL.isNaN() -> (tL - 0.5) * 2
            else -> 0.0
        }
        s.eyeV = ((v("b_eyeLookUpLeft") + v("b_eyeLookUpRight")) - (v("b_eyeLookDownLeft") + v("b_eyeLookDownRight"))) / 2.0
        s.brow = v("b_browInnerUp") * 0.5 + (v("b_browOuterUpLeft") + v("b_browOuterUpRight")) * 0.25
        s.smile = (v("b_mouthSmileLeft") + v("b_mouthSmileRight")) / 2.0
    }

    private fun ratio(x: Double, from: Double, to: Double): Double {
        val span = to - from
        if (kotlin.math.abs(span) < 1e-6) return Double.NaN
        val t = (x - from) / span
        return if (t < -0.2 || t > 1.2) Double.NaN else t
    }

    private companion object {
        const val BASELINE = 3.0
    }
}
