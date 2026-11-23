package tech.fonia.app.shell

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import tech.fonia.navigation.EndKey
import tech.fonia.navigation.FoniaKey
import tech.fonia.navigation.LabKey
import tech.fonia.navigation.LessonKey
import tech.fonia.navigation.OnboardingKey
import tech.fonia.navigation.PathKey
import tech.fonia.navigation.SoundHouseKey
import tech.fonia.navigation.TeenHomeKey

/**
 * The kid shell's back stack (docs/v2/02 §8): a snapshot list of keys, PATH at the root. Predictable back:
 * - LESSON: the screen intercepts back and asks „Ne oprim?” (it calls [finishLesson] itself), never a silent pop;
 * - END: back to PATH, never into the finished lesson;
 * - PATH: nothing left, the activity closes ([back] returns false).
 */
class Navigator(start: List<FoniaKey> = listOf(PathKey)) {
    val stack: SnapshotStateList<FoniaKey> = mutableStateListOf<FoniaKey>().apply { addAll(normalise(start)) }

    val top: FoniaKey get() = stack.last()

    /** True when the last change went deeper (for the direction of the transition). */
    var forward = true
        private set

    fun push(key: FoniaKey) {
        forward = true
        if (key is LessonKey) stack.removeAll { it is LessonKey || it is EndKey }
        stack.add(key)
    }

    /** The lesson is over: END replaces LESSON, so back from END lands on PATH. */
    fun finishLesson(end: EndKey) {
        forward = true
        stack.removeAll { it is LessonKey || it is EndKey }
        stack.add(end)
    }

    /** Back to PATH (the nearest one in the stack), dropping everything above it; PATH becomes the root if none. */
    fun home() {
        forward = false
        val at = stack.lastIndexOf(PathKey)
        if (at < 0) { stack.clear(); stack.add(PathKey); return }
        while (stack.size > at + 1) stack.removeAt(stack.lastIndex)
    }

    /** The top key is replaced (the gate by what it guarded). */
    fun replaceTop(key: FoniaKey) {
        forward = true
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else stack.clear()
        stack.add(key)
    }

    /** A new root (after onboarding, after „Șterge tot”). */
    fun reset(keys: List<FoniaKey>) {
        forward = true
        val n = normalise(keys)
        stack.clear()
        stack.addAll(n)
    }

    /** "Încă una?" on END: a fresh lesson of the same sound replaces END. */
    fun again(lesson: LessonKey) {
        forward = true
        stack.removeAll { it is LessonKey || it is EndKey }
        stack.add(lesson)
    }

    /**
     * „Alege alt exercițiu” / back from END: the sound house of [sound] (the nearest one in the stack, or a new one on top
     * of PATH). The finished lesson and END are dropped.
     */
    fun toHouse(sound: tech.fonia.model.SoundId) {
        forward = false
        stack.removeAll { it is LessonKey || it is EndKey }
        val at = stack.indexOfLast { it == SoundHouseKey(sound) }
        if (at >= 0) {
            while (stack.size > at + 1) stack.removeAt(stack.lastIndex)
        } else {
            if (stack.isEmpty()) stack.add(PathKey)
            stack.add(SoundHouseKey(sound))
        }
    }

    /** System back. Returns false when the stack is at its root and the activity should close. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        when (top) {
            // END goes back to where the lesson was chosen: the sound house if there is one, else PATH
            is EndKey -> if (stack.any { it is SoundHouseKey }) {
                stack.removeAll { it is LessonKey || it is EndKey }
            } else {
                home()
            }
            else -> stack.removeAt(stack.lastIndex)
        }
        return true
    }

    companion object {
        /** Roots: PATH (kid shell), TEEN-HOME („Doar eu”), the onboarding (first start); the lab may stand alone. */
        val ROOTS: Set<FoniaKey> = setOf(PathKey, LabKey, OnboardingKey, TeenHomeKey)

        fun normalise(start: List<FoniaKey>): List<FoniaKey> = when {
            start.isEmpty() -> listOf(PathKey)
            start.first() in ROOTS -> start
            else -> listOf(PathKey) + start
        }
    }
}
