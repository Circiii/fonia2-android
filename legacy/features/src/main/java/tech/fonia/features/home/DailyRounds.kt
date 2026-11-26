package tech.fonia.features.home

import tech.fonia.features.app.FoniaPreferences
import tech.fonia.features.app.isChild
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A second short round on the same day, for the youngest. At 4–5 a lesson holds few tries, and more sessions
 * are the lever there, not longer ones. An adult turns it on for the child; the home page then offers one more
 * round once a pause has passed since the first lesson of the day. Nothing is lost when the child says no.
 */
object DailyRounds {
    const val PREFIX = "fonia.rounds."

    /** How long after the day's first lesson the second one is offered (an estimate, to be checked in the pilot). */
    val pause: Duration = Duration.ofHours(2)

    /** Where a day stands, when a second round is on. */
    sealed interface Round {
        /** No lesson finished today yet. */
        data object First : Round

        /** One lesson finished; the next round is offered from [until]. */
        data class Resting(val until: Instant) : Round

        /** One lesson finished and the pause is over. */
        data object Second : Round

        /** Two or more lessons finished today. */
        data object Done : Round
    }

    const val TITLE = "Încă o rundă mai târziu"
    const val DETAIL = "La 4–5 ani o lecție are puține încercări. Pornit, acasă apare „Încă o rundă mică?” la cel puțin două ore după prima lecție a zilei. Copilul o poate lăsa pentru altă dată."

    fun key(profile: UUID): String = "$PREFIX$profile"

    /** Only for a child of 4–5. Older children already get longer sessions. */
    fun isOffered(profile: Profile): Boolean = profile.isChild && profile.ageBand == Profile.AgeBand.FourToFive

    fun isOn(profile: Profile, preferences: FoniaPreferences?): Boolean =
        isOn(profile) { preferences?.bool(it) == true }

    /** [isSet] reads a stored switch by key; the preferences need an Android context, tests pass a map. */
    fun isOn(profile: Profile, isSet: (String) -> Boolean): Boolean = isOffered(profile) && isSet(key(profile.id))

    /** When the profile is deleted. */
    fun forget(profile: UUID, preferences: FoniaPreferences?) {
        preferences?.remove(key(profile))
    }

    /** Today's round, from the lessons finished on [day] ("yyyy-MM-dd"). */
    fun round(sessions: List<SessionRecord>, day: String, now: Instant): Round {
        val ended = sessions.mapNotNull { if (it.localDay == day) it.endedAt else null }
        val last = ended.maxOrNull() ?: return Round.First
        if (ended.size != 1) return Round.Done
        val from = last.plus(pause)
        return if (now >= from) Round.Second else Round.Resting(from)
    }
}
