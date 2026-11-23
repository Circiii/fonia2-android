package tech.fonia.collector.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.fonia.dsp.decodeWAV
import tech.fonia.dsp.encodeWAV
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.sin

fun session(id: String = "r01-A07-A-1", speaker: String = "A07") = SessionRecord(
    sessionId = id, round = "r01", speaker = speaker, role = "adult", ageBand = "adult", mode = "adult", pack = "A",
    sounds = listOf("s", "ș", "z", "j"),
    consent = ConsentRecord("adult", Records.CONSENT_ADULT, initials = "AB", at = "2026-11-21T10:00:00+02:00"),
    device = DeviceInfo("Google", "Pixel", "15", 35), startedAt = "2026-11-21T10:00:00+02:00", updatedAt = "2026-11-21T10:00:00+02:00",
    promptCount = 170, appVersion = "1.0.0", promptsVersion = "colector-rostiri-1", thresholdsVersion = 1,
)

fun clip(file: String?, take: Int, outcome: String = "accepted") = ClipRecord(
    file = file, sessionId = "r01-A07-A-1", round = "r01", speaker = "A07", role = "adult", ageBand = "adult", mode = "adult",
    device = "Google Pixel", android = "15", sdk = 35, audioSource = "UNPROCESSED", sampleRate = 48_000, narrowBand = false,
    bandwidthHz = 20_000f, promptId = "s_w_ini_sare", sound = "s", soundKey = "s", level = "word", position = "initial",
    text = "sare", imitation = "șare", hint = "cu Ș în loc de S", intendedLabel = "PlaceSwap:ș", label = "PlaceSwap",
    errorKind = "PlaceSwap", heardAs = "ș", wave = 1, take = take, outcome = outcome, speechDetected = true,
    speechStartSec = 0.41, speechEndSec = 1.02, endedBy = "auto", durationSec = 1.95, samples = 93_600, dropped = 0,
    roomFloorDb = -61f, quality = null, recordedAt = "2026-11-21T10:01:00+02:00", appVersion = "1.0.0",
    promptsVersion = "colector-rostiri-1", thresholdsVersion = 1, consentVersion = Records.CONSENT_ADULT, fixture = false,
)

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = Store(tmp.newFolder("data"), File(tmp.root, "active.json"))

    @Test
    fun takesNumberOnFromTheFilesOnDisk() {
        val store = store()
        assertEquals(1, store.nextTake("r01", "A07", "s", "s_w_ini_sare", "PlaceSwap"))
        val first = store.file(Naming.clipPath("r01", "A07", "s", "s_w_ini_sare", "PlaceSwap", 1))
        first.parentFile!!.mkdirs()
        first.writeBytes(ByteArray(10))
        assertEquals(2, store.nextTake("r01", "A07", "s", "s_w_ini_sare", "PlaceSwap"))
        assertEquals(1, store.nextTake("r01", "A07", "s", "s_w_ini_sare", "correct"))
        assertEquals(1, store.nextRoomTake("r01", "A07"))
    }

    @Test
    fun clipsAppendOneLineEach() {
        val store = store()
        store.appendClip(clip("r01/A07/s/a.wav", 1, "redo"))
        store.appendClip(clip("r01/A07/s/b.wav", 2))
        val lines = File(store.speakerDir("r01", "A07"), Store.CLIPS).readLines()
        assertEquals(2, lines.size)
        val back = store.readClips("r01", "A07")
        assertEquals(listOf("redo", "accepted"), back.map { it.outcome })
        assertEquals(clip("r01/A07/s/b.wav", 2), back[1])
    }

    @Test
    fun sessionsAreUpsertedById() {
        val store = store()
        store.upsertSession(session())
        store.upsertSession(session().copy(promptsDone = 12))
        store.upsertSession(session(id = "r01-A07-B-2").copy(pack = "B"))
        val file = store.readSpeakerFile("r01", "A07")!!
        assertEquals(2, file.sessions.size)
        assertEquals(12, file.sessions.first { it.sessionId == "r01-A07-A-1" }.promptsDone)
        assertEquals(Records.SCHEMA, file.schema)
    }

    @Test
    fun activeSessionSurvivesARestart() {
        val store = store()
        assertNull(store.loadActive())
        val pending = clip("r01/A07/s/x.wav", 1, "pending")
        store.saveActive(ActiveSession(session(), index = 37, autoFlow = false, pendingTake = pending))
        val again = Store(store.dataRoot, File(tmp.root, "active.json")).loadActive()
        assertNotNull(again)
        assertEquals(37, again!!.index)
        assertEquals(pending, again.pendingTake)
        assertFalse(again.autoFlow)
        store.clearActive()
        assertNull(store.loadActive())
    }

    @Test
    fun speakersAndDeletion() {
        val store = store()
        store.upsertSession(session())
        store.appendClip(clip("r01/A07/s/a.wav", 1))
        store.appendClip(clip("r01/A07/s/b.wav", 2, "redo"))
        store.upsertSession(session(id = "s2", speaker = "T02"))
        val all = store.speakers()
        assertEquals(listOf("A07", "T02"), all.map { it.speaker })
        assertEquals(1, all[0].clips)
        store.saveActive(ActiveSession(session()))
        assertTrue(store.deleteSpeaker("r01", "A07"))
        assertNull(store.loadActive())
        assertEquals(listOf("T02"), store.speakers().map { it.speaker })
    }
}

class ExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tone(seconds: Double, rate: Int = 48_000) = FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * 440 * it / rate)).toFloat() }

    private fun fixture(): Store {
        val store = Store(tmp.newFolder("data"), File(tmp.root, "active.json"))
        store.upsertSession(session())
        val rel = Naming.clipPath("r01", "A07", "s", "s_w_ini_sare", "PlaceSwap", 1)
        store.file(rel).apply { parentFile!!.mkdirs() }.writeBytes(encodeWAV(tone(1.2), 48_000))
        store.file(Naming.roomPath("r01", "A07", 1)).apply { parentFile!!.mkdirs() }.writeBytes(encodeWAV(FloatArray(48_000), 48_000))
        store.appendClip(clip(rel, 1))
        return store
    }

    @Test
    fun zipCarriesEveryFileAndAMatchingManifest() {
        val store = fixture()
        val out = File(tmp.root, "export/fonia_r01_A07_x.zip")
        val result = Export.writeZip(store.dataRoot, "r01/A07", out)
        assertEquals(4, result.fileCount)
        assertTrue(Export.verifyZip(out).isEmpty())
        val manifest = Export.parseManifest(result.manifest)
        assertEquals(
            setOf("r01/A07/clips.jsonl", "r01/A07/session.json", "r01/A07/room/room__t1.wav", "r01/A07/s/s_w_ini_sare__PlaceSwap__t1.wav"),
            manifest.keys,
        )
        ZipFile(out).use { zip ->
            val wav = zip.getEntry("r01/A07/s/s_w_ini_sare__PlaceSwap__t1.wav")
            assertEquals(ZipEntry.STORED, wav.method)
            val decoded = decodeWAV(zip.getInputStream(wav).readBytes())
            assertEquals(48_000.0, decoded.pcm.size / 1.2, 1.0)
            assertEquals(48_000.0, decoded.sampleRate, 0.0)
            assertNotNull(zip.getEntry(Export.MANIFEST))
        }
        val side = File(out.path + ".sha256").readText()
        assertTrue(side.startsWith(result.zipSha256))
        assertEquals(Export.sha256(out), result.zipSha256)
    }

    @Test
    fun wavHeaderIs48kMono16Bit() {
        val bytes = encodeWAV(tone(0.1), 48_000)
        fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
        assertEquals(1, u16(20)) // PCM
        assertEquals(1, u16(22)) // mono
        assertEquals(48_000L, u32(24))
        assertEquals(16, u16(34))
    }

    @Test
    fun aChangedByteIsCaught() {
        val store = fixture()
        val out = File(tmp.root, "x.zip")
        Export.writeZip(store.dataRoot, "r01/A07", out)
        // Flip one byte inside the stored WAV data, far from the zip headers.
        val raf = RandomAccessFile(out, "rw")
        val at = 4_000L
        raf.seek(at)
        val b = raf.read()
        raf.seek(at)
        raf.write(b xor 0x55)
        raf.close()
        val problems = runCatching { Export.verifyZip(out) }.getOrElse { listOf(it.toString()) }
        assertFalse(problems.isEmpty())
    }
}

class RecordsTest {
    @Test
    fun clipLineHasEveryFieldOfTheSpec() {
        val line = Records.clipLine(clip("r01/A07/s/a.wav", 1))
        assertFalse(line.contains('\n'))
        val keys = Json.parseToJsonElement(line).jsonObject.keys
        val required = listOf(
            "speaker", "ageBand", "role", "device", "android", "audioSource", "sampleRate", "bandwidthHz", "promptId", "sound",
            "level", "position", "text", "intendedLabel", "take", "roomFloorDb", "quality", "appVersion", "thresholdsVersion",
            "promptsVersion", "consentVersion", "narrowBand", "file", "outcome",
        )
        for (k in required) assertTrue(k, k in keys)
    }
}

class LevelsTest {
    @Test
    fun dbfsOfAFullScaleSine() {
        val s = FloatArray(4800) { sin(2 * PI * 1000 * it / 48_000).toFloat() }
        assertEquals(-3.01f, Levels.dbfs(s, 0, s.size), 0.05f)
        assertEquals(Levels.SILENT_DB, Levels.dbfs(FloatArray(480), 0, 480), 0f)
    }

    @Test
    fun floorIgnoresAShortLoudBit() {
        val hops = List(80) { -62f } + List(20) { -20f }
        assertEquals(-62f, Levels.floor(hops), 0f)
        assertFalse(Levels.isNoisy(-62f))
        assertTrue(Levels.isNoisy(-40f))
    }

    @Test
    fun thresholdStaysInItsBand() {
        assertEquals(-50f, Levels.speechThreshold(-90f), 0f)
        assertEquals(-48f, Levels.speechThreshold(-60f), 0f)
        assertEquals(-32f, Levels.speechThreshold(-30f), 0f)
    }

    @Test
    fun hopStatsFindTheSpeech() {
        val stats = HopStats(-40f)
        repeat(30) { stats.add(-70f, 480) }
        repeat(50) { stats.add(-25f, 480) }
        repeat(90) { stats.add(-70f, 480) }
        assertEquals(30 * 480, stats.firstSpeech)
        assertEquals(80 * 480, stats.lastSpeechEnd)
        assertEquals(170 * 480, stats.total)
    }
}

class TextRulesTest {
    private val forbidden = listOf("greșit", "gresit", "diagnostic", "terapie", "tratează", "trateaza")
    private val cedilla = charArrayOf('\u015E', '\u015F', '\u0162', '\u0163')

    private fun files(): List<File> {
        val src = File(System.getProperty("fonia.collector.src") ?: "src/main")
        val spec = File(System.getProperty("fonia.collector.spec") ?: "../spec/collector")
        return src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() +
            spec.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
    }

    @Test
    fun noCedillaAndNoForbiddenWords() {
        val all = files()
        assertTrue(all.size > 5)
        for (f in all) {
            val text = f.readText()
            for (c in cedilla) assertFalse("${f.name} has a cedilla letter ${c.code.toString(16)}", text.contains(c))
            for (w in forbidden) assertFalse("${f.name} contains '$w'", text.contains(w, ignoreCase = true))
        }
    }

    @Test
    fun manifestAsksOnlyForTheMicrophone() {
        val manifest = File(System.getProperty("fonia.collector.src") ?: "src/main", "AndroidManifest.xml").readText()
        val asked = Regex("<uses-permission android:name=\"([^\"]+)\"\\s*/>").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.RECORD_AUDIO"), asked)
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
    }
}
