package tech.fonia.features.grownups

import tech.fonia.audio.RecordingWriter
import tech.fonia.domain.Language
import tech.fonia.domain.Rung
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ClipDecoder
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.store.Attempt
import tech.fonia.store.Profile
import tech.fonia.store.Recording
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * `foniaWeekSample` (iOS `-foniaWeekSample`) gives a child with no practice yet two weeks on their first sound, with
 * kept takes made from the app's own voice clips, so the week page can be seen without saying the words to the
 * emulator. Only the takes the store would still keep here are written: the three newest of the sound.
 */
internal suspend fun AppModel.addSampleWeek() {
    val profile = activeProfile ?: return
    if (!profile.isChild) return
    val sound = profile.targetSounds.firstOrNull() ?: return
    if (repository.sessions(profile.id).isNotEmpty()) return

    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val thisMonday = PracticeReport.monday(LocalDate.now(zone))
    fun at(day: LocalDate, hour: Int, minute: Int): Instant =
        minOf(day.atTime(hour, minute).atZone(zone).toInstant(), now.minusSeconds(3_600))

    class Say(val text: String, val machine: Attempt.Outcome, val adult: Attempt.Outcome? = null, val kept: Boolean = false)

    suspend fun practise(start: Instant, says: List<Say>) {
        val session = SessionRecord(profileId = profile.id, track = Profile.Track.Ro, sound = sound, startedAt = start)
        repository.addSession(session)
        says.forEachIndexed { index, say ->
            val ts = start.plusSeconds((index + 1) * 40L)
            var recordingID: UUID? = null
            val clipAt = if (say.kept) audio.clipURL(say.text, Language.Ro) else null
            if (clipAt != null) {
                runCatching {
                    val clip = ClipDecoder.decode(clipAt)
                    val recording = Recording(profileId = profile.id, ts = ts, label = say.text, sound = sound, duration = clip.duration)
                    RecordingWriter.writeM4A(clip, repository.recordingFileURL(recording))
                    repository.addRecording(recording, now = ts)
                    recordingID = recording.id
                }
            }
            repository.addAttempt(
                Attempt(
                    sessionId = session.id,
                    profileId = profile.id,
                    ts = ts,
                    exerciseKind = "repeat",
                    sound = sound,
                    target = say.text,
                    machineOutcome = say.machine,
                    adultOutcome = say.adult,
                    recordingId = recordingID,
                ),
            )
        }
        repository.finishSession(session.id, endedAt = start.plusSeconds(9 * 60))
    }

    // The week before: pairs. Its takes are gone by now: only the three newest of a sound stay here.
    val lastMonday = thisMonday.minusDays(7)
    practise(at(lastMonday.plusDays(1), 17, 30), listOf(
        Say("cos", Attempt.Outcome.NotYet),
        Say("sută", Attempt.Outcome.Almost),
        Say("sare", Attempt.Outcome.Good),
    ))
    practise(at(lastMonday.plusDays(3), 18, 0), listOf(
        Say("cos", Attempt.Outcome.Good),
        Say("sută", Attempt.Outcome.Good),
    ))

    // This week: pairs on Monday, then sentences.
    val monday = at(thisMonday, 17, 10)
    practise(monday, listOf(
        Say("Sara sare sus.", Attempt.Outcome.NotYet),
        Say("Supa e sărată.", Attempt.Outcome.Good, adult = Attempt.Outcome.Good),
        Say("cos", Attempt.Outcome.Good),
        Say("Pisica stă pe sanie.", Attempt.Outcome.Almost),
    ))
    if (!thisMonday.plusDays(1).isAfter(LocalDate.now(zone))) {
        practise(at(thisMonday.plusDays(1), 17, 0), listOf(
            Say("Pisica stă pe sanie.", Attempt.Outcome.Almost),
            Say("Supa e sărată.", Attempt.Outcome.NotJudged),
        ))
    }
    val today = now.minusSeconds(20 * 60)
    practise(today, listOf(
        Say("Pisica stă pe sanie.", Attempt.Outcome.NotJudged, kept = true),
        Say("Supa e sărată.", Attempt.Outcome.Good, kept = true),
        Say("Sara sare sus.", Attempt.Outcome.Good, kept = true),
    ))

    val progress = repository.soundProgress(profile.id).firstOrNull { it.sound == sound } ?: SoundProgress(profileId = profile.id, sound = sound)
    repository.saveSoundProgress(
        progress.copy(
            rung = Rung.Sentences.index,
            blockHistory = listOf(
                SoundProgress.Block(rung = Rung.Pairs.index, good = 3, total = 6, endedAt = at(lastMonday.plusDays(1), 17, 40)),
                SoundProgress.Block(rung = Rung.Pairs.index, good = 5, total = 6, endedAt = at(lastMonday.plusDays(3), 18, 10)),
                SoundProgress.Block(rung = Rung.Pairs.index, good = 5, total = 6, endedAt = monday.plusSeconds(9 * 60)),
                SoundProgress.Block(rung = Rung.Sentences.index, good = 4, total = 6, endedAt = today.plusSeconds(9 * 60)),
            ),
        ),
    )
}
