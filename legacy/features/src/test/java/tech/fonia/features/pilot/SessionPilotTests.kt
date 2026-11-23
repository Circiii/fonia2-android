package tech.fonia.features.pilot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AudioHub
import tech.fonia.features.app.NoAudioBackend
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.session.SessionRun
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** A whole session through the app's own hooks: every settled attempt reaches the pilot log, and the dose matches. */
class SessionPilotTests {
    private val pilot = PilotLog.inMemory()

    @After
    fun close() = pilot.close()

    private fun makeApp(): AppModel {
        val folder = Files.createTempDirectory("fonia-pilot-run").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return AppModel(
            content = content,
            repository = FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings"))),
            audio = AudioHub(voices = null, backend = NoAudioBackend(null), usesFixtures = true),
            speech = SpeechHub(model = null, scope = scope),
            scope = scope,
            pilot = pilot,
        )
    }

    private val child = Profile(
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.SixToSeven,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        targetSounds = listOf("s"),
        confirmedSounds = listOf("s"),
        track = Profile.Track.Ro,
    )

    @Test
    fun everySettledAttemptIsLoggedAndTheDoseIsTheSessions() = runBlocking {
        val app = makeApp()
        app.repository.saveProfile(child)
        pilot.profiles(listOf(child))
        val run = SessionRun(
            SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial), title = "test", sound = SoundID.S),
            child,
            app,
            progress = null,
            history = AttemptsSummary(),
        )
        run.start()
        var settled = 0
        var retried = false
        var guard = 0
        while (run.phase == SessionRun.Phase.Trial && guard++ < 500) {
            val trial = run.current ?: break
            if (trial.countsAsAttempt || trial.exercise.isIdentification) {
                // One retry, one take the phone couldn't hear, the rest good.
                if (!retried && trial.exercise.isProduction) {
                    retried = true
                    run.finishAttempt(trial.exercise, machine = Attempt.Outcome.Almost, adult = null, take = null, retry = true)
                    settled += 1
                }
                val machine = if (settled == 3) Attempt.Outcome.Unclear else Attempt.Outcome.Good
                run.finishAttempt(trial.exercise, machine = machine, adult = null, take = null)
                settled += 1
            } else {
                run.advance()
            }
        }
        assertEquals(SessionRun.Phase.Finished, run.phase)
        pilot.flush()

        val snapshot = pilot.snapshot()
        val rows = snapshot.attempts.getValue(child.id.toString())
        assertEquals(settled, rows.size)
        // Each take has its own key, `<trial>.<retry>`, and the retried trial shows twice.
        assertEquals(rows.size, rows.map { it["take"] }.toSet().size)
        assertTrue(rows.any { (it["take"] as String).endsWith(".1") })
        // The dose is what the session itself counted: productions with a verdict.
        assertEquals(run.attempts, rows.count { it["dose"] == true })
        assertTrue(rows.any { it["machine"] == "unclear" && it["dose"] == false })
        val productions = rows.filter { it["mode"] == "production" }
        assertTrue(productions.all { it["sound"] == "s" })
        assertTrue(productions.any { it["position"] == "initial" })
        assertEquals(1, snapshot.rows.getValue("session"))
        val day = pilot.read { sessionDays(child.id.toString()) }.single()
        assertEquals(1, day.finished)
        assertEquals(run.attempts, pilot.dose(child.id, LocalDate.parse(day.day)).sounds.single().total)
    }

    @Test
    fun leavingEarlyEndsTheSessionUnfinished() = runBlocking {
        val app = makeApp()
        app.repository.saveProfile(child)
        pilot.profiles(listOf(child))
        val run = SessionRun(
            SessionTicket(focus = SessionFocus.Sound(SoundID.S, Rung.Syllables), title = "test", sound = SoundID.S),
            child,
            app,
            progress = null,
            history = AttemptsSummary(),
        )
        run.start()
        run.leaveEarly()
        pilot.flush()
        val day = pilot.read { sessionDays(child.id.toString()) }.single()
        assertEquals(1, day.sessions)
        assertEquals(0, day.finished)
    }

    companion object {
        val content: FoniaContent by lazy {
            FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content") ?: "../content")))
        }
    }
}
