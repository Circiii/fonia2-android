package tech.fonia.app.adult

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.fonia.app.AppGraph
import tech.fonia.app.data.Household
import tech.fonia.app.judge.Talker
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace

/**
 * ADULT-SETTINGS (chapter 01 §9): who practises, the voice the phone compares with (an adult tester picks an adult
 * voice, so the adult-voice gate does not send them back), reduced motion, „Vorbit în public” (teens and adults),
 * licences, and „Șterge tot” behind a second number. The line „Nimic nu pleacă de pe telefon” stays visible on top.
 */
@Composable
fun AdultSettingsScreen(
    graph: AppGraph,
    onLicenses: () -> Unit,
    onSpeaking: () -> Unit,
    onWiped: () -> Unit,
    onBack: () -> Unit,
) {
    val s = graph.settings
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var number by remember { mutableStateOf(graph.gate.number) }
    var busy by remember { mutableStateOf(false) }

    AdultPage(T.setTitle, onBack, s.reduceMotion) {
        val c = FoniaTheme.colors
        AdultCard { AdultText(T.setPrivacy) }

        AdultHeading(T.setWho)
        AdultChoice(
            listOf(Household.ChildWithAdult to T.setWhoChild, Household.Self to T.setWhoSelf),
            s.household,
        ) { h ->
            s.updateHousehold(h)
            if (h == Household.ChildWithAdult) s.updateTalker(Talker.Child) else if (s.talker == Talker.Child) s.updateTalker(Talker.AdultHigh)
            scope.launch { graph.calibration.refresh() }
        }

        AdultHeading(T.setVoice)
        AdultCaption(T.setVoiceHelp)
        AdultChoice(
            listOf(Talker.Child to T.voiceChild, Talker.AdultHigh to T.voiceAdultHigh, Talker.AdultLow to T.voiceAdultLow),
            s.talker,
        ) { t ->
            s.updateTalker(t)
            scope.launch { graph.calibration.refresh() }
        }

        AdultToggle(T.setMotion, T.setMotionHelp, s.reduceMotion) { s.updateReduceMotion(it) }

        AdultRow(T.setSpeaking, T.setSpeakingHelp, onClick = onSpeaking)
        AdultRow(T.setLicenses, onClick = onLicenses)

        if (!confirming) {
            AdultRow(T.setWipe, T.setWipeHelp, onClick = { confirming = true; input = ""; number = graph.gate.number })
        } else {
            AdultCard {
                AdultHeading(T.wipeTitle)
                AdultText(T.wipeBody, secondary = true)
                AdultText(T.gateWrite, secondary = true)
                BasicText(RomanianNumbers.words(number), style = FoniaTheme.type.title2.copy(color = c.ink))
                Box(
                    Modifier.fillMaxWidth().height(56.dp).background(c.fill, RoundedCornerShape(FoniaCorner.field)).padding(horizontal = FoniaSpace.m),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(input.ifEmpty { "··" }, style = FoniaTheme.type.counter.copy(color = if (input.isEmpty()) c.inkSecondary else c.ink))
                }
            }
            Keypad(
                onDigit = { if (input.length < 2) input += it },
                onDelete = { input = input.dropLast(1) },
                onOk = {
                    if (busy) return@Keypad
                    if (input.toIntOrNull() == number) {
                        busy = true
                        scope.launch {
                            runCatching { graph.wiper.wipe() }
                            busy = false
                            onWiped()
                        }
                    } else {
                        // a wrong number draws another (as the gate does)
                        graph.gate.check(input)
                        number = graph.gate.number
                        input = ""
                    }
                },
                okLabel = T.wipeConfirm,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                QuietButton(T.wipeCancel, onClick = { confirming = false }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The licences (§9 „Licențe”): every bundled component and its licence; no "metoda" claims. */
@Composable
fun AdultLicensesScreen(reduceMotion: Boolean, onBack: () -> Unit) {
    AdultPage(T.licTitle, onBack, reduceMotion) {
        for ((what, lic) in T.licenses) {
            AdultCard {
                AdultHeading(what)
                AdultCaption(lic)
            }
        }
        // the model notices shipped in the APK (ZIPA is CC BY 4.0: attribution is a licence condition; the 3D mouth's GNM line)
        val context = androidx.compose.ui.platform.LocalContext.current
        val notices = androidx.compose.runtime.remember {
            listOf("notices/models/NOTICE", "notices/mouth3d/NOTICE").map { path ->
                runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
            }
        }
        AdultCard {
            AdultHeading(T.licZipa)
            AdultCaption(T.licZipaText)
            if (notices[0].isNotBlank()) AdultCaption(notices[0].trim())
        }
        AdultCard {
            AdultHeading(T.licMouth)
            AdultCaption(notices[1].trim().lines().lastOrNull { it.isNotBlank() } ?: T.licMouthText)
        }
        AdultText(T.licVoice, secondary = true)
    }
}
