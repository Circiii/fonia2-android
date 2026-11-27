package tech.fonia.features.app

import android.os.Bundle
import tech.fonia.features.IS_DEBUG_BUILD

/**
 * The debug and test switches of a launch: the launch arguments of the iOS app, which arrive here as intent extras.
 * They are for UI tests, screenshots and scripts. A release build ignores all of them except `foniaFixtures` and
 * `foniaDrafts`, which are not gated by the build type; every other switch works only in a debug build, as on iOS
 * (`#if DEBUG`). The screens read them from [AppModel.arguments], never from the intent.
 *
 * | iOS | intent extra | |
 * |---|---|---|
 * | `-foniaResetData` | `foniaResetData` (boolean) | wipes everything before the first read |
 * | `-foniaFixtures` | `foniaFixtures` (boolean) | takes come from the recorded clip of the target; on iOS always on in the simulator |
 * | `-foniaStart s:wordsInitial` | `foniaStart` (string) | opens one session once a profile is active |
 * | `-foniaMouths` | `foniaMouths` (boolean) | shows the mouths of the design gallery instead of the app |
 * | | `foniaGallery` (string) | shows the design gallery at a section (`componente`, `acasa`, `lectia`, `foni`, `guri`, `scene`) |
 * | | `foniaRoute` (string) | pushes one page once a profile is active, for tests and screenshots (see [AppRoute.parse]) |
 * | `-foniaSpeakOnly`, `-foniaShort` | `foniaSpeakOnly`, `foniaShort` (boolean) | read by the session (second half) |
 * | | `foniaFakeSpeech` (boolean) | a speech model that downloads in two seconds and answers canned results |
 * | | `foniaReduceMotion` (boolean) | Reduce Motion on whatever the system says, and the voice meters still, for the UI tests (Compose tests wait for idle, and Foni, the sky and a live meter never are) |
 * | | `foniaFace` (string) | the face the mouth coach sees, held still, as `name=value` pairs of `FaceFrame` (`smile=0.8,jawOpen=0.1`) |
 * | `-foniaNoAdultCards` | `foniaNoAdultCards` (boolean) | no first-time card for the adult before a lesson or the mirror |
 * | `-foniaMissionSample` | `foniaMissionSample` (boolean) | a mission answered two days ago and one from yesterday still to ask about |
 * | `-foniaWeekSample` | `foniaWeekSample` (boolean) | a child with no practice gets two weeks on the first sound, with kept takes, for the week page |
 * | | `foniaGate` (int or string) | the adult check always asks the multiplication with this answer |
 * | | `foniaDrafts` (boolean) | shows the drafts in a release build too |
 * | | `foniaCoachVideo` (string) | a video in the app's files folder shown to the speech coach in place of the camera |
 * | | `foniaCoachHint` (string) | one correction of the mouth coach shown whatever the camera says (a `Correction` name) |
 * | | `foniaFaceGuides` (string, any value) | turns the mirror's drawings on, whatever the setting |
 * | | `foniaMicFile` (string) | a WAV on the device read in real time in place of the microphone, then silence, through the same live path (levels, judge, screens) |
 *
 * The same switches can also come as iOS-style strings in a string-array extra `arguments`
 * (`["-foniaResetData", "-foniaStart", "s:wordsInitial"]`), as `ProcessInfo.arguments` has them.
 */
data class LaunchArguments(
    val resetData: Boolean = false,
    val fixtures: Boolean = false,
    val start: String? = null,
    val mouths: Boolean = false,
    val gallery: String? = null,
    val route: String? = null,
    val speakOnly: Boolean = false,
    val short: Boolean = false,
    val fakeSpeech: Boolean = false,
    /** Android only, for the UI tests: Reduce Motion on, so no animation runs forever (Foni, the sky). */
    val reduceMotion: Boolean = false,
    /** Android only: the mouth coach sees this face, held still, instead of the camera (`smile=0.8,jawOpen=0.1`). */
    val face: String? = null,
    /** Android only, debug: every take reads this WAV (a path on the device) instead of the microphone. */
    val micFile: String? = null,
    /** Debug only (iOS `-foniaGate`): the adult check always asks the multiplication with this answer (56: 7 × 8). */
    val gateNumber: Int? = null,
    /** Debug only (iOS `-foniaNoAdultCards`): the first-time cards for the adult stay out of the way of a UI test. */
    val hidesAdultCards: Boolean = false,
    /** Debug only (iOS `-foniaMissionSample`): two missions, one answered and one still to ask about. */
    val missionSample: Boolean = false,
    /** Debug only (iOS `-foniaWeekSample`): two weeks of practice with kept takes, for the week page. */
    val weekSample: Boolean = false,
    /**
     * The drafts (Bac, "Cuvântul important", the reading probes, the diction training): shown in debug builds, or
     * with `foniaDrafts`; a release build without the flag doesn't show them (iOS `LaunchFlags.showsDrafts`).
     */
    val drafts: Boolean = IS_DEBUG_BUILD,
    /** Debug only: a video in the app's files folder shown to the speech coach in place of the camera (`foniaCoachVideo`). */
    val coachVideo: String? = null,
    /** Debug only: the name of one mouth-coach correction shown whatever the camera says (`foniaCoachHint`), for screenshots. */
    val coachHint: String? = null,
    /** Debug only: the mirror's drawings are on whatever the setting says (`foniaFaceGuides`, any string value), for screenshots. */
    val faceGuides: Boolean = false,
) {
    companion object {
        val none = LaunchArguments()

        fun from(extras: Bundle?, debug: Boolean = IS_DEBUG_BUILD): LaunchArguments {
            if (extras == null) return none
            val listed = extras.getStringArray("arguments")?.toList() ?: emptyList()
            fun flag(name: String): Boolean = extras.getBoolean(name, false) || "-$name" in listed
            fun value(name: String): String? = extras.getString(name)
                ?: listed.indexOf("-$name").takeIf { it >= 0 && it + 1 < listed.size }?.let { listed[it + 1] }
            return LaunchArguments(
                resetData = debug && flag("foniaResetData"),
                fixtures = flag("foniaFixtures"),
                start = if (debug) value("foniaStart") else null,
                mouths = debug && flag("foniaMouths"),
                gallery = if (debug) value("foniaGallery") else null,
                route = if (debug) value("foniaRoute") else null,
                speakOnly = debug && flag("foniaSpeakOnly"),
                short = debug && flag("foniaShort"),
                fakeSpeech = debug && flag("foniaFakeSpeech"),
                reduceMotion = debug && flag("foniaReduceMotion"),
                face = if (debug) value("foniaFace") else null,
                micFile = if (debug) value("foniaMicFile") else null,
                gateNumber = if (debug) AdultGate.fixedNumber(extras.getInt("foniaGate", 0).takeIf { it != 0 } ?: value("foniaGate")?.toIntOrNull()) else null,
                hidesAdultCards = debug && flag("foniaNoAdultCards"),
                missionSample = debug && flag("foniaMissionSample"),
                weekSample = debug && flag("foniaWeekSample"),
                drafts = debug || flag("foniaDrafts"),
                coachVideo = if (debug) value("foniaCoachVideo") else null,
                coachHint = if (debug) extras.getString("foniaCoachHint") else null,
                faceGuides = debug && extras.getString("foniaFaceGuides") != null,
            )
        }
    }
}
