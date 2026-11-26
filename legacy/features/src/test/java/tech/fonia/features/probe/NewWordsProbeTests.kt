package tech.fonia.features.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.CourseUnit
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Lesson
import tech.fonia.domain.NewWordsRecord
import tech.fonia.domain.ProbeItem
import tech.fonia.domain.ProbeLevel
import tech.fonia.domain.ProbeLists
import tech.fonia.domain.ProbeMark
import tech.fonia.domain.ProbeRules
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.Track
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.CustomRun
import tech.fonia.features.app.LaunchArguments
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.index
import tech.fonia.features.grownups.PracticeReport
import tech.fonia.features.reading.repositoryRoot
import tech.fonia.features.session.SessionRun
import tech.fonia.features.session.key
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import tech.fonia.store.practiceData
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Port of FoniaFeaturesTests/NewWordsProbeTests.swift, test for test, plus the bundled-lists test of
// FoniaDomainTests/ProbeTests.swift (the lists are an asset of this module) and the draft gate on this side.

private val content: FoniaContent by lazy {
    FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
}

/** The lists as `assets/drafts/probes.json` ships them. */
private val bundledLists: ProbeLists by lazy {
    ProbeLists.parse(File(repositoryRoot, "legacy/features/src/main/assets/$PROBE_LISTS_ASSET").readText(Charsets.UTF_8))
}

private fun folder(): File = Files.createTempDirectory("fonia-probe").toFile()

private fun probe(marks: List<ProbeMark?>, level: ProbeLevel = ProbeLevel.Words, finished: Boolean = true): NewWordsRecord =
    NewWordsRecord(
        date = Instant.ofEpochSecond(1_790_000_000),
        sound = "s",
        level = level,
        items = marks.mapIndexed { index, mark -> ProbeItem(text = "w$index", phone = "good", adult = mark) },
        finished = finished,
    )

private fun child(band: Profile.AgeBand): Profile = Profile(
    kind = Profile.Kind.Child,
    ageBand = band,
    voiceGroup = Profile.VoiceGroup.Child,
    color = "cold",
    targetSounds = listOf("s"),
    track = Profile.Track.Ro,
)

/** Suite "Cuvinte noi: store, copy and report". */
class NewWordsProbeStoreTests {
    /** a probe is kept, its marks replace it, and sentences stay chosen */
    @Test
    fun roundTrip() = runBlocking {
        val store = ProbeStore(folder())
        val profile = UUID.randomUUID()
        val first = probe(listOf(null, null))
        store.save(first, profile)
        store.save(first.withMarks(listOf(ProbeMark.Good, ProbeMark.KeepWorking)), profile)
        store.moveToSentences("s", profile)
        store.moveToSentences("s", profile)
        val stored = store.stored(profile)
        assertEquals(1, stored.probes.size)
        assertEquals(listOf(ProbeMark.Good, ProbeMark.KeepWorking), stored.probes[0].items.map { it.adult })
        assertEquals(listOf("s"), stored.sentences)
        assertTrue(store.records(UUID.randomUUID()).isEmpty())
    }

    /** a deleted profile and a wipe take the probes too */
    @Test
    fun keepOnly() = runBlocking {
        val store = ProbeStore(folder())
        val kept = UUID.randomUUID()
        val gone = UUID.randomUUID()
        store.save(probe(listOf(ProbeMark.Good)), kept)
        store.save(probe(listOf(ProbeMark.Good)), gone)
        store.keepOnly(setOf(kept))
        assertEquals(1, store.records(kept).size)
        assertTrue(store.records(gone).isEmpty())
        store.removeAll()
        assertTrue(store.records(kept).isEmpty())
    }

    /** only the last 120 probes of a profile are kept */
    @Test
    fun keepsTheLast() = runBlocking {
        val store = ProbeStore(folder())
        val profile = UUID.randomUUID()
        val all = List(ProbeStore.kept + 3) { probe(listOf(ProbeMark.Good)) }
        for (record in all) store.save(record, profile)
        assertEquals(all.takeLast(ProbeStore.kept).map { it.id }, store.records(profile).map { it.id })
    }

    /** dates are kept in milliseconds */
    @Test
    fun encoding() {
        val text = ProbeStore.encode(ProbeStore.Stored(probes = listOf(probe(listOf(ProbeMark.Good)))))
        assertTrue(text, text.contains("\"date\":1790000000000"))
        assertEquals(1, ProbeStore.decode(text).probes.size)
        // The same record back, the unmarked item too.
        val record = probe(listOf(ProbeMark.Skip, null))
        assertEquals(record, ProbeStore.decode(ProbeStore.encode(ProbeStore.Stored(probes = listOf(record)))).probes.single())
    }

    /** the report line names only what there is */
    @Test
    fun reportLine() {
        val line = ProbeCopy.reportLine(probe(listOf(ProbeMark.Good, ProbeMark.Good, ProbeMark.KeepWorking, null)), letter = "S", day = "12 noiembrie")
        assertEquals("Cuvinte noi la S, 12 noiembrie: 2 din 4 „Sună bine”, 1 „Mai lucrăm”, 1 nenotat.", line)
        val sentences = ProbeCopy.reportLine(probe(listOf(ProbeMark.Good, ProbeMark.Skip, ProbeMark.Skip), level = ProbeLevel.Sentences), letter = "S", day = "1 decembrie")
        assertEquals("Propoziții noi la S, 1 decembrie: 1 din 3 „Sună bine”, 0 „Mai lucrăm”, 2 sărite.", sentences)
        assertEquals("20 de nenotate", ProbeCopy.counted(20, "nenotat", "nenotate"))
        assertEquals("5 nenotate", ProbeCopy.counted(5, "nenotat", "nenotate"))
    }

    /** the phone line waits for five items both judged */
    @Test
    fun phoneLine() {
        assertNull(ProbeCopy.phoneLine(probe(List(4) { ProbeMark.Good })))
        val line = ProbeCopy.phoneLine(probe(List(4) { ProbeMark.Good } + ProbeMark.KeepWorking))
        assertEquals("Pe 5 notate și auzite de telefon, telefonul a spus la fel la 4.", line)
    }

    /** the report: the latest probes, the ones left early, and what the marks suggest */
    @Test
    fun reportLines() {
        val good = List<ProbeMark?>(25) { ProbeMark.Good }
        val lines = PracticeReport.probeLines(listOf(probe(good), probe(listOf(null), finished = false), probe(good)), letter = "S")
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("Cuvinte noi la S"))
        assertTrue("Probe făcute până la capăt: 2 din 3." in lines)
        assertEquals(ProbeCopy.suggestSentences, lines.last())
        assertTrue(PracticeReport.probeLines(emptyList(), letter = "S").isEmpty())
        val told = PracticeReport.probeLines(listOf(probe(List(10) { ProbeMark.Good }, level = ProbeLevel.Sentences)), letter = "S")
        assertEquals(ProbeCopy.tellTherapist, told.last())
    }

    /** the PDF's text has the lines under their sound */
    @Test
    fun reportHasTheLinesUnderTheSound() = runBlocking {
        val repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder(), "Recordings")))
        val profile = child(Profile.AgeBand.EightToTen)
        repository.saveProfile(profile)
        repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "s", rung = Rung.WordsInitial.index))
        val records = listOf(probe(List(25) { ProbeMark.Good }), probe(List(25) { ProbeMark.Good }))
        val report = PracticeReport.build(repository.practiceData(profile.id), content, LocalDate.of(2026, 11, 26), probes = records)
        val part = report.sounds.single { it.letter == "S" }
        assertEquals(PracticeReport.probeLines(records, "S"), part.probeLines)
        assertTrue(report.text.contains(part.probeLines.first()))
        val plain = PracticeReport.build(repository.practiceData(profile.id), content, LocalDate.of(2026, 11, 26))
        assertTrue(plain.sounds.all { it.probeLines.isEmpty() })
    }

    /** no banned word in the probe's copy */
    @Test
    fun copy() {
        for (line in ProbeCopy.everyLine) {
            for (word in listOf("diagnostic", "terapie", "tratează", "evaluare", "scor")) {
                assertFalse(line, line.lowercase().contains(word))
            }
        }
    }

    /** the bundled lists are drafts, 25 words and 10 sentences a sound, each holding its sound */
    @Test
    fun bundled() {
        val lists = bundledLists
        assertFalse(lists.isApproved)
        assertEquals(SoundID.entries.map { it.rawValue }.toSet(), lists.sounds.keys)
        for ((sound, list) in lists.sounds) {
            assertEquals(sound, 25, list.words.size)
            assertEquals(sound, 25, list.words.toSet().size)
            assertEquals(sound, 10, list.sentences.size)
            assertTrue(list.words.size >= ProbeRules.fewestItems)
            val spellings = when (sound) {
                "ce" -> listOf("ce", "ci")
                "ge" -> listOf("ge", "gi")
                else -> listOf(sound)
            }
            for (word in list.words) {
                assertTrue("$sound: $word", spellings.any { word.lowercase().contains(it) })
            }
        }
        assertEquals(10, lists.items("s", ProbeLevel.Sentences).size)
        assertTrue(lists.items("x", ProbeLevel.Words).isEmpty())
    }
}

/** Suite "Cuvinte noi: before a lesson", and the draft gate. */
class NewWordsProbeRunTests {
    private fun app(
        folder: File,
        store: ProbeStore?,
        drafts: Boolean = true,
        lists: ProbeLists? = bundledLists,
        repository: FoniaRepository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
    ): AppModel {
        // No main thread on the JVM: the app's own scope runs where it's called, saves included.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = repository,
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            arguments = LaunchArguments(drafts = drafts),
            probes = store,
            probeLists = lists,
        )
    }

    private suspend fun finishedSessions(app: AppModel, profile: Profile, focus: SessionFocus, count: Int = 3) {
        repeat(count) {
            val session = SessionRecord(profileId = profile.id, track = Profile.Track.Ro, sound = "s", focus = focus.key)
            app.repository.addSession(session)
            app.repository.finishSession(session.id)
        }
    }

    private fun ticket(plan: ProbePlan): SessionTicket = SessionTicket(
        focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial),
        title = ProbeCopy.title,
        sound = SoundID.S,
        custom = CustomRun(CustomRun.Kind.Probe(plan), plan.trials),
    )

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(condition())
    }

    /** due on the fourth lesson of a sound from words on, never at 4–5, never without the lists */
    @Test
    fun due() = runBlocking {
        val folder = folder()
        val app = app(folder, ProbeStore(File(folder, "probes")))
        val profile = child(Profile.AgeBand.EightToTen)
        app.repository.saveProfile(profile)
        val focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial)
        assertNull(app.probe(focus, profile))
        finishedSessions(app, profile, focus)
        val plan = app.probe(focus, profile)
        assertNotNull(plan)
        assertEquals(ProbeLevel.Words, plan!!.level)
        assertTrue(plan.reads)
        assertEquals(25, plan.items.size)
        assertNull(app.probe(SessionFocus.Sound(SoundID.S, Rung.Syllables), profile))
        assertNull(app.probe(SessionFocus.Sound(SoundID.R, Rung.WordsInitial), profile))
        assertNull(app.probe(focus, profile.copy(ageBand = Profile.AgeBand.FourToFive)))
        val hidden = app(folder, app.probes, drafts = false, repository = app.repository)
        assertNull(hidden.probeLists)
        assertNull(hidden.probe(focus, profile))
    }

    /** the probe stores nothing as practice and keeps the phone's answers */
    @Test
    fun run() = runBlocking {
        val folder = folder()
        val store = ProbeStore(File(folder, "probes"))
        val app = app(folder, store)
        val profile = child(Profile.AgeBand.SixToSeven)
        app.repository.saveProfile(profile)
        val plan = ProbePlan(SoundID.S, ProbeLevel.Words, reads = false, items = listOf("sită", "sumă", "sacou", "seră", "sirop", "vesel", "dans", "plus"))
        val run = SessionRun(ticket(plan), profile, app, progress = null, history = AttemptsSummary())
        run.start()
        assertTrue(run.isProbe)
        assertTrue(run.isAside)
        assertTrue(run.isTellingChapter)
        assertEquals(ProbeCopy.opening, run.chapter)
        run.finishChapter()
        val outcomes = ArrayDeque(listOf(Attempt.Outcome.Good, Attempt.Outcome.NotYet))
        while (run.phase == SessionRun.Phase.Trial) {
            val trial = run.current ?: break
            run.finishAttempt(trial.exercise, machine = outcomes.removeFirstOrNull() ?: Attempt.Outcome.Good, adult = null, take = null)
        }
        assertEquals(SessionRun.Phase.Finished, run.phase)
        assertNull(run.blockNote)
        assertEquals(8, run.probeTakes.size)
        assertTrue(run.takes.isEmpty())
        delay(50)
        run.awaitSaves()
        assertTrue(app.repository.sessions(profile.id).isEmpty())
        assertTrue(app.repository.practiceData(profile.id).attempts.isEmpty())
        assertTrue(app.repository.soundProgress(profile.id).isEmpty())
        val kept = store.records(profile.id).firstOrNull()
        assertNotNull(kept)
        assertEquals(plan.id, kept!!.id)
        assertTrue(kept.finished)
        assertEquals(listOf("good", "notYet"), kept.items.map { it.phone }.take(2))
        assertTrue(kept.items.all { it.adult == null })
    }

    /** a probe left early is kept as offered, not finished */
    @Test
    fun leftEarly() = runBlocking {
        val folder = folder()
        val store = ProbeStore(File(folder, "probes"))
        val app = app(folder, store)
        val profile = child(Profile.AgeBand.SixToSeven)
        app.repository.saveProfile(profile)
        val plan = ProbePlan(SoundID.S, ProbeLevel.Words, reads = true, items = List(8) { "sită" })
        val run = SessionRun(ticket(plan), profile, app, progress = null, history = AttemptsSummary())
        run.start()
        run.current?.let { run.finishAttempt(it.exercise, machine = Attempt.Outcome.Good, adult = null, take = null) }
        run.leaveEarly()
        delay(50)
        run.awaitSaves()
        val kept = store.records(profile.id).firstOrNull()
        assertNotNull(kept)
        assertFalse(kept!!.finished)
        assertEquals(1, kept.items.mapNotNull { it.phone }.size)
    }

    /**
     * The draft gate: without drafts (a release build), draft lists give no probe and a lesson starts as it always
     * did; approved lists would show anywhere. With drafts, the fourth lesson opens on the probe, then the lesson.
     */
    @Test
    fun hiddenWhereDraftsAreHidden() = runBlocking {
        val focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial)
        val lesson = Lesson(id = "s-words", title = "Cuvinte cu S", detail = "", focus = focus, art = "")
        val unit = CourseUnit(id = "s", title = "S", subtitle = "", track = Track.Sounds, sound = SoundID.S, mark = "S", lessons = listOf(lesson))
        assertNotNull(app(folder(), null, drafts = false, lists = bundledLists.copy(isApproved = true)).probeLists)
        for (drafts in listOf(false, true)) {
            val folder = folder()
            val app = app(folder, ProbeStore(File(folder, "probes")), drafts = drafts)
            assertEquals(drafts, app.probeLists != null)
            val watching = app.scope.launch { app.start() }
            val profile = child(Profile.AgeBand.EightToTen)
            app.repository.saveProfile(profile)
            waitUntil { app.activeProfile != null }
            finishedSessions(app, profile, focus)
            app.start(lesson, unit)
            waitUntil { app.ticket != null }
            val shown = app.ticket!!
            if (drafts) {
                assertNotNull(shown.probe)
                assertEquals(ProbeCopy.title, shown.title)
                app.startAfterProbe()
                assertNull(app.ticket?.probe)
                assertEquals(lesson.title, app.ticket?.title)
            } else {
                assertNull(shown.probe)
                assertNull(shown.custom)
                assertEquals(lesson.title, shown.title)
            }
            watching.cancel()
        }
    }

    /** a deleted profile and „Șterge tot” take the probes too */
    @Test
    fun deletionAndWipe() = runBlocking {
        val folder = folder()
        val store = ProbeStore(File(folder, "probes"))
        val app = app(folder, store)
        val watching = app.scope.launch { app.start() }
        val gone = child(Profile.AgeBand.EightToTen)
        val kept = child(Profile.AgeBand.SixToSeven)
        app.repository.saveProfile(gone)
        app.repository.saveProfile(kept)
        waitUntil { app.profiles.size == 2 }
        store.save(probe(listOf(ProbeMark.Good)), gone.id)
        store.save(probe(listOf(ProbeMark.Good)), kept.id)
        app.repository.deleteProfile(gone.id)
        waitUntil { store.records(gone.id).isEmpty() }
        assertEquals(1, store.records(kept.id).size)
        app.wipeEverything()
        assertTrue(store.records(kept.id).isEmpty())
        watching.cancel()
    }
}
