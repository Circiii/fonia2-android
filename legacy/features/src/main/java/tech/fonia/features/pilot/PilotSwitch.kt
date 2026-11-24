package tech.fonia.features.pilot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The pilot log's switch (iOS `PilotSwitches`, 007bfcf): off until an adult switches it on in Setări, so a family that
 * never opens the settings keeps no log at all. Switching it off stops the logging and keeps what was logged; wiping
 * it is "Șterge tot".
 */
object PilotSwitch {
    const val KEY = "fonia.pilot.log"

    /** What the switch is before an adult has touched it. */
    const val DEFAULT = false
}

/**
 * The log as every part of the app sees it, the shadow scorer's thread included: it can be opened and closed while
 * they run, and each writes to whichever log is open when its row is ready, or to none. A screen that reads it
 * follows it.
 */
class PilotSlot(initial: PilotLog?) {
    var log: PilotLog? by mutableStateOf(initial)
}
