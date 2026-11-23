package tech.fonia.app.shell

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import tech.fonia.app.AppGraph
import tech.fonia.app.VariantHooks
import tech.fonia.app.adult.AdultCalibrateScreen
import tech.fonia.app.adult.AdultGateScreen
import tech.fonia.app.adult.AdultHomeScreen
import tech.fonia.app.adult.AdultLicensesScreen
import tech.fonia.app.adult.AdultSettingsScreen
import tech.fonia.app.data.Household
import tech.fonia.app.end.EndScreen
import tech.fonia.app.lab.LetterLab
import tech.fonia.app.lesson.LessonScreen
import tech.fonia.app.onboarding.OnExit
import tech.fonia.app.onboarding.OnboardingScreen
import tech.fonia.app.path.PathModel
import tech.fonia.app.path.PathScreen
import tech.fonia.app.mirror.MirrorScreen
import tech.fonia.app.speaking.SpeakingHost
import tech.fonia.app.speaking.SpeakingKind
import tech.fonia.app.teen.TeenHomeScreen
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.feature.speaking.SpeakingStrings
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import tech.fonia.navigation.AdultCalibrateKey
import tech.fonia.navigation.AdultGateKey
import tech.fonia.navigation.AdultHomeKey
import tech.fonia.navigation.AdultLicensesKey
import tech.fonia.navigation.AdultSettingsKey
import tech.fonia.navigation.EndKey
import tech.fonia.navigation.FoniaKey
import tech.fonia.navigation.LabKey
import tech.fonia.navigation.LessonKey
import tech.fonia.navigation.MinuteKey
import tech.fonia.navigation.MirrorKey
import tech.fonia.navigation.NoUmKey
import tech.fonia.navigation.OnboardingKey
import tech.fonia.navigation.PathKey
import tech.fonia.navigation.TeenHomeKey
import tech.fonia.navigation.SoundHouseKey
import tech.fonia.navigation.Mouth3DKey
import tech.fonia.navigation.AdultSoundKey
import tech.fonia.app.house.Exercise
import tech.fonia.app.house.SoundHouseScreen
import tech.fonia.app.mouth.Mouth3DScreen
import tech.fonia.app.adult.AdultSoundScreen
import java.time.LocalDate
import java.util.UUID

fun newLesson(sound: SoundId, oneMore: Boolean = false) = LessonKey(sound, Rung.Isolated, UUID.randomUUID().toString(), oneMore = oneMore)

/** One exercise card of the sound house: a lesson run of that card, or the screen of a viewing aid. */
fun exerciseKey(sound: SoundId, exercise: Exercise): FoniaKey = when (exercise) {
    Exercise.Mirror -> MirrorKey(sound)
    Exercise.Mouth3D -> Mouth3DKey(sound)
    else -> LessonKey(sound, exercise.rung ?: Rung.Isolated, UUID.randomUUID().toString(), exercise = exercise.raw)
}

/** The home root for the current settings: TEEN-HOME for "Doar eu" without a chosen sound, else PATH. */
fun homeRoot(graph: AppGraph): FoniaKey =
    if (graph.settings.household == Household.Self && graph.settings.chosenSound == null) TeenHomeKey else PathKey

/**
 * The first stack. First start (or `--ez demo true`, `--es screen onboarding`): the onboarding. Debug launch flags:
 * `screen=lesson|end|lab|adult|calibrate|settings|teen|onboarding|mirror|noum|minute` (`--ez gate true` puts the
 * AdultGate in front of the adult screens).
 */
fun startStack(graph: AppGraph): List<FoniaKey> {
    val f = graph.flags
    val sound = f.sound ?: SoundId.S
    fun adult(vararg keys: FoniaKey): List<FoniaKey> =
        if (f.gate) listOf(PathKey, AdultGateKey(keys.first())) else listOf(PathKey) + keys
    return when {
        f.lab || f.screen == "lab" -> listOf(PathKey, LabKey)
        f.screen == "onboarding" || f.demo -> listOf(OnboardingKey)
        f.screen == "lesson" -> listOf(PathKey, newLesson(sound))
        f.screen == "end" -> listOf(PathKey, EndKey(UUID.randomUUID().toString(), sound, attempts = 5, correct = 4, bestHoldMillis = 2800))
        f.screen == "adult" -> adult(AdultHomeKey)
        f.screen == "calibrate" -> if (f.gate) adult(AdultCalibrateKey) else listOf(PathKey, AdultHomeKey, AdultCalibrateKey)
        f.screen == "settings" -> if (f.gate) adult(AdultSettingsKey) else listOf(PathKey, AdultHomeKey, AdultSettingsKey)
        f.screen == "teen" -> listOf(TeenHomeKey)
        f.screen == "mirror" -> listOf(PathKey, MirrorKey(sound))
        f.screen == "noum" -> listOf(TeenHomeKey, NoUmKey())
        f.screen == "minute" -> listOf(TeenHomeKey, MinuteKey)
        f.screen == "path" -> listOf(PathKey)
        f.screen == "house" -> listOf(PathKey, SoundHouseKey(sound))
        f.screen == "adultsound" -> if (f.gate) listOf(PathKey, SoundHouseKey(sound), AdultGateKey(AdultSoundKey(sound))) else listOf(PathKey, SoundHouseKey(sound), AdultSoundKey(sound))
        f.screen == "exercise" -> listOf(PathKey, SoundHouseKey(sound), exerciseKey(sound, Exercise.of(f.exercise) ?: Exercise.Hold))
        !graph.settings.onboarded -> listOf(OnboardingKey)
        else -> listOf(homeRoot(graph))
    }
}

/**
 * MIRROR (chapter 04) from PATH's „Oglinda” (free mirror of the current sound). From a lesson the mirror opens inside
 * LESSON instead (`LessonScreen`: the lesson keeps its item, the interrupted take does not count).
 */
fun openMirror(nav: Navigator, sound: SoundId?) {
    nav.push(MirrorKey(sound))
}

/** „Vorbit în public” (chapter 01 §10) from ADULT-SETTINGS: TEEN-HOME, whose rows open NO-UM and MINUTE. */
fun openSpeaking(nav: Navigator) {
    nav.push(TeenHomeKey)
}

/** The adult corner: through the AdultGate unless it is already open (or nobody to protect: "Doar eu"). */
fun openAdult(graph: AppGraph, nav: Navigator, key: FoniaKey) {
    if (graph.gate.isOpen || graph.settings.household == Household.Self) nav.push(key) else nav.push(AdultGateKey(key))
}

/**
 * The shell: one screen per key of the [Navigator], cross-faded / slid with `AnimatedContent` (a hand-rolled
 * Navigation-3-shaped host): onboarding, PATH, LESSON, END, the adult area, TEEN-HOME.
 */
@Composable
fun FoniaShell(graph: AppGraph, nav: Navigator) {
    var path by remember { mutableStateOf(PathModel()) }
    val top = nav.top
    val reduce = FoniaTheme.reduceMotion

    // back from plain screens; LESSON and END register their own handlers, which win
    BackHandler(enabled = nav.stack.size > 1 && top !is LessonKey && top !is EndKey) { nav.back() }

    LaunchedEffect(top) {
        if (top == PathKey) path = runCatching {
            val log = graph.attempts
            val book = graph.practice.state()
            val current = book.lastSound ?: log.lastSound() ?: graph.settings.chosenSound ?: SoundId.S
            val ladder = book.sounds.mapValues { (_, p) -> ((p.rung.ordinal - Rung.Isolated.ordinal).coerceAtLeast(0) * 3 / (Rung.Everyday.ordinal - Rung.Isolated.ordinal)).coerceIn(0, 3) }
            PathModel(
                today = log.todayCount(), stats = log.soundStats(), current = current, loaded = true,
                totalDays = book.totalDays, streak = book.streak(LocalDate.now()), ladder = ladder,
            )
        }.getOrElse { Log.e("FoniaShell", "path data", it); path.copy(loaded = true) }
    }

    Box(Modifier.fillMaxSize().background(FoniaTheme.colors.bg)) {
        AnimatedContent(
            targetState = top,
            contentKey = { it },
            transitionSpec = {
                if (reduce) {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                } else {
                    val dir = if (nav.forward) 1 else -1
                    (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { dir * it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -dir * it / 4 } + fadeOut(tween(180)))
                }
            },
            label = "shell",
        ) { key ->
            Screen(key, graph, nav, path)
        }
    }

    if (graph.flags.demo) DemoScript(graph, nav)
}

@Composable
private fun Screen(key: FoniaKey, graph: AppGraph, nav: Navigator, path: PathModel) {
    val reduce = FoniaTheme.reduceMotion // one source: the Fonia toggle, the system setting or the debug flag
    when (key) {
        OnboardingKey -> OnboardingScreen(graph, demo = graph.flags.demo) { exit ->
            nav.reset(listOf(if (exit == OnExit.TeenHome) TeenHomeKey else PathKey))
        }
        PathKey -> PathScreen(
            model = path,
            onPick = { nav.push(newLesson(it)) },
            onHouse = { nav.push(SoundHouseKey(it)) },
            onMirror = { openMirror(nav, path.current) },
            onAdult = { openAdult(graph, nav, AdultHomeKey) },
            onLab = if (VariantHooks.debug) ({ nav.push(LabKey) }) else null,
        )
        is LessonKey -> LessonScreen(
            key, graph,
            onFinished = { nav.finishLesson(it) },
            onHome = { nav.home() },
        )
        is EndKey -> EndScreen(
            key,
            onDone = { nav.home() },
            onAgain = {
                val ex = Exercise.of(key.exercise)
                nav.again((if (ex != null) exerciseKey(key.sound, ex) else newLesson(key.sound, oneMore = true)) as LessonKey)
            },
            onPickOther = { nav.toHouse(key.sound) },
        )
        is SoundHouseKey -> SoundHouseScreen(
            key.sound, graph,
            onBack = { nav.back() },
            onGuided = { nav.push(newLesson(key.sound)) },
            onExercise = { nav.push(exerciseKey(key.sound, it)) },
            onAdult = { openAdult(graph, nav, AdultSoundKey(key.sound)) },
        )
        is Mouth3DKey -> Mouth3DScreen(key.sound, onBack = { nav.back() })
        is AdultSoundKey -> AdultSoundScreen(graph, key.sound, onBack = { nav.back() })
        LabKey -> LetterLab(startDemo = false, startOnPaper = false, startLetter = graph.flags.sound?.letter)
        is AdultGateKey -> AdultGateScreen(
            graph.gate, reduce,
            onPass = { nav.replaceTop(key.then) },
            onBack = { nav.back() },
            autoPass = graph.flags.demo,
        )
        AdultHomeKey -> AdultHomeScreen(
            graph,
            onCalibrate = { nav.push(AdultCalibrateKey) },
            onSettings = { nav.push(AdultSettingsKey) },
            onBack = { nav.back() },
            onSound = { nav.push(AdultSoundKey(it)) },
        )
        AdultCalibrateKey -> AdultCalibrateScreen(graph, onBack = { nav.back() })
        AdultSettingsKey -> AdultSettingsScreen(
            graph,
            onLicenses = { nav.push(AdultLicensesKey) },
            onSpeaking = { openSpeaking(nav) },
            onWiped = { nav.reset(listOf(OnboardingKey)) },
            onBack = { nav.back() },
        )
        AdultLicensesKey -> AdultLicensesScreen(reduce, onBack = { nav.back() })
        TeenHomeKey -> {
            LaunchedEffect(Unit) { graph.prewarmTranscriber() }
            TeenHomeScreen(
            reduce,
            minuteStep = graph.settings.minuteRung,
            minuteSteps = SpeakingStrings.TOPICS.size,
            whisperReady = graph.whisperReady,
            root = nav.stack.size == 1,
            onNoUm = { nav.push(NoUmKey()) },
            onMinute = { nav.push(MinuteKey) },
            onSounds = { nav.push(PathKey) },
            onSettings = { openAdult(graph, nav, AdultSettingsKey) },
            onBack = { nav.back() },
        )
        }
        is MirrorKey -> MirrorScreen(graph, key.sound, inLesson = false, onClose = { nav.back() })
        is NoUmKey -> SpeakingHost(graph, SpeakingKind.NoUm, key.topic, onDone = { nav.back() })
        MinuteKey -> SpeakingHost(graph, SpeakingKind.Minute, null, onDone = { nav.back() })
    }
}

/**
 * `--ez demo true`: the hands-free film. Onboarding (who practises → the adult card → microphone → pick S) →
 * PATH → the sound house of S → Sâsâilă's hold game (fixture hiss) → END → „Alege alt exercițiu” (the house again) →
 * PATH → the house of Ș → Șușu's train → END → PATH → MIRROR for Ș (the still-portrait face fixture
 * through the real landmark pipeline: the calm face, advice, a match, too far, too dark) → PATH → the AdultGate (fills
 * itself) → „Cum merge” → ADULT-SETTINGS, where "Doar eu" is chosen → TEEN-HOME → NO-UM on the fillers fixture
 * (analysis, transcript, „fără ăăă” played) → TEEN-HOME. Nothing is tapped.
 */
@Composable
private fun DemoScript(graph: AppGraph, nav: Navigator) {
    var run by remember { mutableIntStateOf(0) }
    val top = nav.top
    LaunchedEffect(top) {
        fun stillOn(): Boolean = nav.top == top
        when (top) {
            PathKey -> {
                delay(if (run == 0) 2200 else 1500)
                if (!stillOn()) return@LaunchedEffect
                when (run) {
                    0 -> { run = 1; nav.push(SoundHouseKey(graph.flags.sound ?: SoundId.S)) }
                    2 -> { run = 3; nav.push(SoundHouseKey(SoundId.Sh)) }
                    4 -> { run = 5; nav.push(MirrorKey(SoundId.Sh)) }
                    5 -> { run = 6; nav.push(AdultGateKey(AdultHomeKey)) }
                }
            }
            is SoundHouseKey -> {
                delay(3200)
                if (!stillOn()) return@LaunchedEffect
                when (run) {
                    1, 3 -> { run++; nav.push(exerciseKey(top.sound, Exercise.Hold)) } // the sound's own hold game
                    else -> nav.back()
                }
            }
            is EndKey -> {
                delay(2800)
                if (stillOn()) { if (run == 2) nav.toHouse(top.sound) else nav.home() }
            }
            is MirrorKey -> {
                delay(DEMO_MIRROR_MILLIS)
                if (stillOn()) nav.back()
            }
            AdultHomeKey -> {
                delay(4000)
                if (stillOn()) nav.push(AdultSettingsKey)
            }
            AdultSettingsKey -> {
                delay(1800)
                if (!stillOn()) return@LaunchedEffect
                graph.settings.updateHousehold(Household.Self)
                graph.settings.updateTalker(tech.fonia.app.judge.Talker.AdultHigh)
                delay(1600)
                if (stillOn()) nav.reset(listOf(TeenHomeKey))
            }
            TeenHomeKey -> {
                if (run >= 7) return@LaunchedEffect
                delay(2400)
                if (stillOn()) { run = 7; nav.push(NoUmKey()) }
            }
            else -> Unit
        }
    }
}

/** How long the film stays in MIRROR: about one pass of the face fixture's script. */
private const val DEMO_MIRROR_MILLIS = 22_000L
