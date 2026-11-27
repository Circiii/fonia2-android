package tech.fonia.features.app

import tech.fonia.domain.SoundID

/** Which of the two counts [AppRoute.Tally] opens on. */
enum class TallyFocus { Days, Stars }

/** Where a tap can lead: the pages pushed on the one navigation stack of the tabs. */
sealed interface AppRoute {
    data class Guide(val sound: SoundID) : AppRoute

    data class Mirror(val sound: SoundID) : AppRoute

    data class Coach(val key: String) : AppRoute

    // `.unit(String)` on iOS. Inside this interface `Unit` names the route, not kotlin.Unit.
    data class Unit(val id: String) : AppRoute

    data object AllSounds : AppRoute

    data object Warmup : AppRoute

    data object Play : AppRoute

    data object Journal : AppRoute

    data object Guides : AppRoute

    data object Stories : AppRoute

    data object English : AppRoute

    data object Speaking : AppRoute

    data object Settings : AppRoute

    /** The days and stars behind the two counts on Home. */
    data class Tally(val focus: TallyFocus) : AppRoute

    /** „La cameră”: the speech coach. [launch] (debug) = `<seconds>:<promptId>[:go]`. */
    data class SpeechCoach(val launch: String? = null) : AppRoute

    /** „Antrenament de dicție” of a sound: the ladder of drills. */
    data class Training(val sound: SoundID) : AppRoute

    companion object {
        /**
         * Parses `foniaRoute`: `journal`, `settings`, `sounds`, `warmup`, `play`, `guides`, `stories`, `english`,
         * `speaking`, `speech[:<seconds>:<promptId>[:go]]`, `unit:<id>`, `guide:<sound>`, `mirror:<sound>`, `coach:<key>`.
         */
        fun parse(text: String): AppRoute? {
            val parts = text.split(":", limit = 2)
            return when (parts[0]) {
                "journal" -> Journal
                "settings" -> Settings
                "sounds" -> AllSounds
                "warmup" -> Warmup
                "play" -> Play
                "guides" -> Guides
                "stories" -> Stories
                "english" -> English
                "speaking" -> Speaking
                "speech" -> SpeechCoach(parts.getOrNull(1))
                "unit" -> parts.getOrNull(1)?.let { Unit(it) }
                "guide" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Guide(it) }
                "mirror" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Mirror(it) }
                "coach" -> parts.getOrNull(1)?.let { Coach(it) }
                "training" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Training(it) }
                else -> null
            }
        }
    }
}
