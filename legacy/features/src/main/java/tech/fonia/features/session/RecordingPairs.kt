package tech.fonia.features.session

import tech.fonia.store.Recording

/**
 * The first take of a word, kept next to the newest so someone can hear the difference later (iOS `RecordingPairs`
 * and `SessionRun.store`). The store keeps only the newest takes of each sound; a pinned take is never pruned.
 */
object RecordingPairs {
    /** How many first takes stay pinned for each sound. */
    const val FIRST_TAKES_KEPT = 5

    /**
     * Whether a new take of [label] at [sound] is pinned. It is when no take of the profile still on the phone has the
     * same word and sound ([earlier] is what the store holds now), and the sound has fewer than [FIRST_TAKES_KEPT]
     * pinned takes.
     */
    fun pinsNewTake(earlier: List<Recording>, label: String, sound: String?): Boolean {
        val isFirst = earlier.none { it.label == label && it.sound == sound }
        val kept = earlier.count { it.pinned && it.sound == sound }
        return isFirst && kept < FIRST_TAKES_KEPT
    }
}
