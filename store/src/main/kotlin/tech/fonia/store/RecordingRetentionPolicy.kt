package tech.fonia.store

import java.time.Instant
import java.util.UUID

/**
 * Which audio takes may stay on the device. A child's voice is the most sensitive thing Fonia holds,
 * so the defaults keep very little: the adult pins what is worth more.
 */
data class RecordingRetentionPolicy(
    /** How many of the newest takes stay for each profile and sound, pinned ones included. */
    val keepLatestPerSound: Int,
    /** Unpinned takes older than this many seconds go, even if they are among the newest. Null means no limit. */
    val maxAge: Double?,
) {
    /**
     * The takes to delete. Pinned takes are never in the result. Takes with no sound, such as adult
     * talks, count as one more group for their profile.
     */
    fun takesToDelete(recordings: List<Recording>, now: Instant): List<Recording> {
        val groups = recordings.groupBy { GroupKey(profileId = it.profileId, sound = it.sound) }
        val oldestAllowed = maxAge?.let { now.addingTimeInterval(-it) }

        return groups.values.flatMap { group ->
            // Descending by (ts, id.uuidString), so takes at the same moment rank the same way every time.
            val newestFirst = group.sortedWith(
                compareByDescending<Recording> { it.ts }.thenByDescending { it.id.uuidString },
            )
            newestFirst.withIndex().mapNotNull { (rank, recording) ->
                if (recording.pinned) return@mapNotNull null
                val beyondLimit = rank >= keepLatestPerSound
                val expired = oldestAllowed?.let { recording.ts < it } ?: false
                if (beyondLimit || expired) recording else null
            }
        }
    }

    private data class GroupKey(val profileId: UUID, val sound: String?)

    companion object {
        val default = RecordingRetentionPolicy(keepLatestPerSound = 3, maxAge = 90.0 * 24 * 60 * 60)
    }
}
