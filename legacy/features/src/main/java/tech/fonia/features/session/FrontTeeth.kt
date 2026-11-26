package tech.fonia.features.session

import tech.fonia.domain.SoundID
import tech.fonia.features.app.FoniaPreferences
import tech.fonia.features.app.isChild
import tech.fonia.store.Profile
import java.util.UUID

/**
 * An adult's note that the child's front teeth are out. While the gap lasts, the hissing sounds come out
 * differently with no change in the speech, so every take said then is marked ([CaptureLog]) and the report
 * says how many there were. Nothing about the lesson or the answers changes.
 */
object FrontTeeth {
    const val PREFIX = "fonia.teethOut."

    /** The sounds a gap in the front teeth changes. */
    val affects: Set<SoundID> = setOf(SoundID.S, SoundID.Z, SoundID.Ts, SoundID.Sh, SoundID.J, SoundID.Ce, SoundID.Ge)

    const val TITLE = "Îi cad dinții din față"
    const val DETAIL = "Cât lipsesc dinții, S, Z, Ț, Ș și J ies altfel o vreme, fără ca vorbirea să se fi schimbat. Pornit, încercările din zilele astea sunt notate, iar raportul pentru logoped spune câte au fost. Oprește-l când au crescut dinții la loc."

    fun key(profile: UUID): String = "$PREFIX$profile"

    fun isOffered(profile: Profile): Boolean = profile.isChild

    fun areOut(profile: Profile, preferences: FoniaPreferences?): Boolean =
        areOut(profile) { preferences?.bool(it) == true }

    /** [isSet] reads a stored switch by key; the preferences need an Android context, tests pass a map. */
    fun areOut(profile: Profile, isSet: (String) -> Boolean): Boolean = isOffered(profile) && isSet(key(profile.id))

    /** When the profile is deleted. */
    fun forget(profile: UUID, preferences: FoniaPreferences?) {
        preferences?.remove(key(profile))
    }
}
