package tech.fonia.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import tech.fonia.design.SceneName
import tech.fonia.design.scene
import tech.fonia.domain.Catalog
import tech.fonia.domain.CourseUnit
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Lesson
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundDef
import tech.fonia.domain.SoundID
import tech.fonia.domain.Track
import tech.fonia.features.app.field
import tech.fonia.features.check.CheckRecord
import tech.fonia.features.check.CheckStore
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.features.app.orderedSounds
import tech.fonia.features.app.progressionState
import tech.fonia.features.session.key
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import tech.fonia.store.localDay
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

@Suppress("EnumEntryName")
enum class LessonState { done, current, later, locked }

/** A lesson to pick up, and the unit it belongs to. */
data class NextStep(val unit: CourseUnit, val lesson: Lesson)

/** One day of the current week, Monday first. */
data class DayActivity(
    val id: String,
    val letter: String,
    val attempts: Int,
    val minutes: Int,
    val isToday: Boolean,
    val hasPractice: Boolean,
)

/**
 * Where a person stands in the course. Read from what they've actually finished, so nothing here is stored twice and
 * a wiped profile simply starts over.
 *
 * `@Observable` on iOS: the values live in snapshot state, so a screen that reads them recomposes when they change.
 */
class PathModel(profile: Profile, val content: FoniaContent) {
    var profile: Profile by mutableStateOf(profile)
    val catalog = Catalog(content)

    var progress: List<SoundProgress> by mutableStateOf(emptyList())
        private set

    var sessions: List<SessionRecord> by mutableStateOf(emptyList())
        private set

    private var finished: Set<String> by mutableStateOf(emptySet())

    /** The quick checks, oldest first (empty until the first one). */
    var checks: List<CheckRecord> by mutableStateOf(emptyList())
        private set

    val lastCheck: CheckRecord? get() = checks.lastOrNull()

    suspend fun reload(repository: FoniaRepository, checkStore: CheckStore? = null) {
        if (checkStore != null) checks = orEmpty { checkStore.history(profile.id) }
        progress = orEmpty { repository.soundProgress(profile.id) }
        sessions = orEmpty { repository.sessions(profile.id) }
        val ended = sessions.filter { it.endedAt != null }
        finished = ended.mapNotNull { it.focus }.toSet()
    }

    // The course

    val soundOrder: List<SoundID> get() = profile.orderedSounds(content).map { it.id }

    /** The sounds an adult picked, in the order they picked them. */
    val chosenSounds: List<SoundDef>
        get() = profile.targetSounds.mapNotNull { SoundID.fromRawValue(it) }.map { content.sound(it) }

    /** What this person came for: their sounds, English or diction. */
    val trackUnits: List<CourseUnit>
        get() = when (profile.track) {
            Profile.Track.En -> catalog.englishUnits
            Profile.Track.Dictie -> catalog.dictionUnits
            Profile.Track.Ro -> soundOrder.map { catalog.unit(content.sound(it)) }
        }

    fun unit(id: String): CourseUnit? = catalog.allUnits(soundOrder).firstOrNull { it.id == id }

    fun saved(sound: SoundID): SoundProgress? = progress.firstOrNull { it.sound == sound.rawValue }

    fun ladder(sound: SoundID): ProgressionState = profile.progressionState(sound, saved(sound))

    // Lessons

    private fun isDone(lesson: Lesson, unit: CourseUnit): Boolean {
        val rung = lesson.rung
        val sound = unit.sound
        if (rung != null && sound != null) return rung.index < ladder(sound).rung.index
        return lesson.focus.key in finished
    }

    /** The rung a lesson stands on: its own, or for a lesson off the ladder, the rung just before it. */
    private fun rung(lesson: Lesson, unit: CourseUnit): Rung? {
        val index = unit.lessons.indexOf(lesson)
        if (index < 0) return null
        return unit.lessons.subList(0, index + 1).lastOrNull { it.rung != null }?.rung
    }

    private fun isLocked(lesson: Lesson, unit: CourseUnit): Boolean {
        val sound = unit.sound ?: return false
        val rung = rung(lesson, unit) ?: return false
        return Progression.isLocked(rung, ladder(sound))
    }

    /** A rung further up than the learner has reached stays shut unless an adult opened the path. */
    fun canOpen(lesson: Lesson, unit: CourseUnit): Boolean {
        val sound = unit.sound ?: return true
        val rung = rung(lesson, unit) ?: return true
        if (isLocked(lesson, unit)) return false
        return rung.index <= ladder(sound).rung.index || profile.openPath
    }

    /** The first lesson that isn't finished and can be opened. */
    fun next(unit: CourseUnit): Lesson? = unit.lessons.firstOrNull { !isDone(it, unit) && canOpen(it, unit) }

    fun state(lesson: Lesson, unit: CourseUnit): LessonState {
        if (isDone(lesson, unit)) return LessonState.done
        if (lesson.id == next(unit)?.id) return LessonState.current
        return if (isLocked(lesson, unit)) LessonState.locked else LessonState.later
    }

    fun doneCount(unit: CourseUnit): Int = unit.lessons.count { isDone(it, unit) }

    /** How many of the plain sound's good takes that open the next lessons are done (the dots of [OwnSuccess]). */
    fun ownSuccessDots(sound: SoundID): Int = tech.fonia.features.session.OwnSuccess.dots(ladder(sound).establishCorrect)

    /** The plain sound's good takes so far, for the line under a shut lesson. */
    fun ownSuccessEstablished(sound: SoundID): Int = ladder(sound).establishCorrect

    // Picking up

    val hasStarted: Boolean get() = sessions.any { it.focus != null }

    /**
     * What the big button continues with. With sounds chosen (by hand or by the quick check), today's sound: the first
     * of them that still has a lesson to do, one sound at a time. Otherwise the next thing in the unit worked on last,
     * or for someone new, the first thing on their track. Warming up is never the place to pick up from.
     */
    val continuing: NextStep?
        get() {
            val units = catalog.allUnits(soundOrder)
            val last = sessions.lastOrNull { it.focus != null }?.focus
            val lastUnit = units.firstOrNull { unit -> unit.lessons.any { it.focus.key == last } }
                .takeUnless { profile.track == Profile.Track.Ro && profile.targetSounds.isNotEmpty() }
            for (unit in listOfNotNull(lastUnit) + trackUnits) {
                val lesson = unit.lessons.firstOrNull { !it.isWarmup && !isDone(it, unit) && canOpen(it, unit) }
                if (lesson != null) return NextStep(unit, lesson)
            }
            return null
        }

    /** What "warm up first" opens: the warm-up of the sound being worked on, or a general one. */
    val warmingUp: NextStep
        get() {
            val unit = continuing?.unit
            val lesson = unit?.lessons?.firstOrNull { it.isWarmup }
            if (unit != null && lesson != null) return NextStep(unit, lesson)
            if (!profile.isChild) {
                val diction = catalog.dictionUnits.firstOrNull { it.id == "d-articulation" }
                val first = diction?.lessons?.firstOrNull()
                if (diction != null && first != null) return NextStep(diction, first)
            }
            val warmup = catalog.warmup
            return NextStep(warmup, warmup.lessons[0])
        }

    /** The painted place a unit opens on. It's always an empty landscape, so what stands for the unit fits in it. */
    fun scene(unit: CourseUnit): SceneName {
        unit.sound?.let { return content.sound(it).family.field.scene }
        return when (unit.track) {
            Track.English -> SceneName.start
            Track.Diction -> SceneName.lesson
            Track.Sounds -> SceneName.start
        }
    }

    // History

    /** Monday first, in the phone's time zone (`Calendar(identifier: .iso8601)` with `timeZone = .current`). */
    val week: List<DayActivity>
        get() {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val monday = now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val today = localDay(now, zone)
            return listOf("L", "M", "M", "J", "V", "S", "D").mapIndexed { index, letter ->
                val day = localDay(monday.plusDays(index.toLong()).atStartOfDay(zone).toInstant(), zone)
                val ofDay = sessions.filter { it.localDay == day }
                DayActivity(
                    id = day,
                    letter = letter,
                    attempts = ofDay.sumOf { it.attempts },
                    minutes = minutes(ofDay),
                    isToday = day == today,
                    hasPractice = ofDay.any { it.endedAt != null || it.attempts > 0 },
                )
            }
        }

    val totalMinutes: Int get() = minutes(sessions)
    val totalAttempts: Int get() = sessions.sumOf { it.attempts }
    val totalLessons: Int get() = sessions.count { it.endedAt != null }
    val totalDays: Int get() = sessions.filter { it.endedAt != null || it.attempts > 0 }.map { it.localDay }.toSet().size

    private companion object {
        fun minutes(sessions: List<SessionRecord>): Int {
            val seconds = sessions.fold(0.0) { sum, session ->
                val end = session.endedAt ?: return@fold sum
                sum + Duration.between(session.startedAt, end).toNanos() / 1e9
            }
            return ceil(seconds / 60).toInt()
        }

        /** `(try? await …) ?? []`. */
        suspend fun <T> orEmpty(fetch: suspend () -> List<T>): List<T> = try {
            fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}

val Lesson.isWarmup: Boolean get() = focus is SessionFocus.Warmups
