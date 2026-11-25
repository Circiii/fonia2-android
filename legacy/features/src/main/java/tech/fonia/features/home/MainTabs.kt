package tech.fonia.features.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tech.fonia.design.FoniaTabIcon
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.StartButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.Destination
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.app.LocalNavigator
import tech.fonia.features.app.NavStack
import tech.fonia.features.app.NavigationHost
import tech.fonia.features.grownups.AdaptiveScreen
import tech.fonia.features.grownups.CalibrationScreen
import tech.fonia.features.grownups.HowItGoesScreen
import tech.fonia.features.grownups.WeekScreen
import tech.fonia.features.grownups.NotesScreen
import tech.fonia.features.grownups.ParentsScreen
import tech.fonia.features.grownups.ProfileDetail
import tech.fonia.features.grownups.SoundChoiceScreen
import tech.fonia.features.grownups.WhoScreen
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.guide.AdultCardGate
import tech.fonia.features.guide.FirstTimeCard
import tech.fonia.features.guide.MouthCoach
import tech.fonia.features.guide.SoundGuide
import tech.fonia.features.journal.JournalScreen
import tech.fonia.features.lessons.EnglishScreen
import tech.fonia.features.lessons.GuidesScreen
import tech.fonia.features.lessons.PlayScreen
import tech.fonia.features.lessons.SoundsScreen
import tech.fonia.features.lessons.StoriesScreen
import tech.fonia.features.lessons.UnitScreen
import tech.fonia.features.lessons.WarmupScreen
import tech.fonia.features.progress.ProgressScreen
import tech.fonia.features.reading.ReadingHome
import tech.fonia.features.speaking.SpeakingHome
import tech.fonia.store.Profile

@Suppress("EnumEntryName")
enum class MainTab { home, progress }

/** What the pages under the bar leave free at their bottom: the bar, and the system's navigation area under it. */
private val barHeight = 64.dp

/**
 * The app once someone is chosen. Two pages side by side under one bar, with the big button between them. Everything
 * else opens over the bar. It always starts on the home page, and nothing starts by itself: a lesson needs a tap.
 */
@Composable
fun MainTabs(profile: Profile, app: AppModel, showGrownups: Boolean, onShowGrownups: (Boolean) -> Unit) {
    val path = remember { PathModel(profile, app.content) }
    val stack = remember { NavStack() }
    var showStart by rememberSaveable { mutableStateOf(false) }
    var startAnimatesOut by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<NextStep?>(null) }
    val pager = rememberPagerState(initialPage = MainTab.home.ordinal) { MainTab.entries.size }

    // Anything that can change where someone stands: a session just ended, or an adult edited the profile.
    val isIdle = app.ticket == null && !showGrownups
    LaunchedEffect(isIdle, profile, app.checksVersion) {
        if (!isIdle) return@LaunchedEffect
        path.profile = profile
        path.reload(app.repository, app.checks)
    }

    // Once, when the first run ends: the quick check, which sets what to practise.
    val offering = app.offerCheck && !app.calibrating && profile.track == Profile.Track.Ro && app.ticket == null
    val calibrating = app.calibrating && app.ticket == null
    // A debug `foniaRoute` page opens once the first run's covers have gone, as a page someone opened would: under the
    // calibration it would speak and listen while the calibration's microphone is open.
    LaunchedEffect(offering, calibrating) {
        if (offering || calibrating) return@LaunchedEffect
        app.takePendingRoute()?.let { AppRoute.parse(it) }?.let { stack.push(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (showStart) Modifier.clearAndSetSemantics {} else Modifier)) {
            // Under a cover the pages stand still.
            tech.fonia.design.AmbientMotion(running = !showStart && !offering && !calibrating) {
                NavigationHost(stack) { destination ->
                    if (destination == Destination.Root) {
                        // His yellow button: one tap continues today's sound. The sheet only shows when nothing is left.
                        TabPages(profile, app, path, pager, onStart = {
                            val step = path.continuing
                            if (step != null) app.start(step.lesson, step.unit) else showStart = true
                        })
                    } else {
                        DestinationScreen(destination, app, path, onShowGrownups)
                    }
                }
            }
        }
        FullScreenCover(
            isPresented = showStart,
            animatesOut = startAnimatesOut,
            onDismissed = {
                startAnimatesOut = true
                // The lesson can only open once the start screen has gone, because it's a full-screen cover too.
                val step = picked
                if (step != null) {
                    picked = null
                    app.start(step.lesson, step.unit)
                }
            },
        ) {
            BackHandler { showStart = false }
            StartSheet(
                path,
                onClose = { showStart = false },
                onPick = { step ->
                    picked = step
                    // The lesson rises right away, instead of waiting for this screen to slide down first.
                    startAnimatesOut = false
                    showStart = false
                },
            )
        }
        FullScreenCover(isPresented = offering) {
            BackHandler { app.offerCheck = false }
            tech.fonia.features.check.CheckOffer(onStart = { app.startCheck() }, onLater = { app.offerCheck = false })
        }
        // „Calibrare”: offered once when the first run ends, and from Setări.
        FullScreenCover(isPresented = calibrating) {
            tech.fonia.features.session.VoiceCalibrationScreen(profile, app, onDone = { app.calibrating = false })
        }
    }
}

@Composable
private fun TabPages(profile: Profile, app: AppModel, path: PathModel, pager: PagerState, onStart: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    var tab by rememberSaveable { mutableStateOf(MainTab.entries[pager.currentPage]) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            val now = MainTab.entries[page]
            if (now != tab) tab = now
        }
    }
    // The big button stands above the bar. Its height counts in the inset, so a page scrolled to the end stops above
    // the button instead of under it.
    val bottom = barHeight + startRise + with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(Modifier.fillMaxSize().background(Palette.paper)) {
        // A real paged row: it follows the finger, and the page that isn't shown is hidden from accessibility.
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val value = MainTab.entries[page]
            Box(Modifier.fillMaxSize().then(if (value != tab) Modifier.clearAndSetSemantics {} else Modifier)) {
                // The page next door stays composed, but still.
                tech.fonia.design.AmbientMotion(running = value == tab) {
                    when (value) {
                        MainTab.home -> HomeScreen(profile, app, path, bottomInset = bottom)
                        MainTab.progress -> ProgressScreen(profile, app, path, bottomInset = bottom)
                    }
                }
            }
        }
        MainBar(
            tab = tab,
            onSelect = { value ->
                tab = value
                scope.launch {
                    if (reduceMotion) pager.scrollToPage(value.ordinal) else pager.animateScrollToPage(value.ordinal, animationSpec = swiftSpring(0.34, 0.0))
                }
            },
            onStart = onStart,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * White, with no line on top. The two tabs sit at a quarter and three quarters of the width, and the big button
 * stands in the middle, rising out of the bar.
 */
/** How far the big button stands above the bar. */
private val startRise = 25.dp

@Composable
private fun MainBar(tab: MainTab, onSelect: (MainTab) -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(barHeight),
        ) {
            TabItem(MainTab.home, "Acasă", "tab-home", tab, onSelect, Modifier.weight(1f))
            Spacer(Modifier.width(96.dp))
            TabItem(MainTab.progress, "Progres", "tab-progress", tab, onSelect, Modifier.weight(1f))
        }
        // The yellow button invites: a soft ring breathes out from under it and it swells a little, while the bar
        // is in view. It steps on the ambient beat with the clouds and the bird, so they repaint the screen together;
        // an infinite transition would repaint at the screen's full 120 Hz forever.
        val reduceStart = LocalReduceMotion.current
        val breathes = tech.fonia.design.LocalAmbientMotion.current
        val startPulse = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        androidx.compose.runtime.LaunchedEffect(reduceStart, breathes) {
            if (reduceStart || !breathes) return@LaunchedEffect
            tech.fonia.design.ambientBeats { now -> startPulse.floatValue = ((now / 1_000_000L) % 2000L) / 2000f }
        }
        StyledButton(
            onClick = onStart,
            style = StartButtonStyle(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = -startRise)
                .drawBehind {
                    if (!reduceStart) {
                        val t = startPulse.value
                        drawCircle(
                            Palette.startHalo.copy(alpha = 0.32f * (1f - t)),
                            radius = size.minDimension / 2f * (0.85f + 0.6f * t),
                            center = center.copy(y = center.y - size.height * 0.08f),
                        )
                    }
                }
                .graphicsLayer {
                    if (!reduceStart) {
                        val k = 1f + 0.03f * kotlin.math.sin(startPulse.value * 2f * Math.PI.toFloat())
                        scaleX = k
                        scaleY = k
                    }
                }
                .semantics { contentDescription = "Începe" },
            onClickLabel = "Alegi cum începi lecția",
        ) {}
    }
}

@Composable
private fun TabItem(value: MainTab, title: String, icon: String, tab: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier) {
    val isActive = tab == value
    val ink = if (isActive) Palette.ink else Palette.inkSoft
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!isActive) onSelect(value) }
            .semantics {
                role = Role.Tab
                selected = isActive
            },
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val lift by androidx.compose.animation.core.animateFloatAsState(
            if (isActive) 1f else 0f,
            androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 420f),
            label = "tab",
        )
        FoniaTabIcon(
            if (isActive) "$icon-on" else icon,
            Modifier.size(26.dp).graphicsLayer {
                val k = 1f + 0.1f * lift
                scaleX = k
                scaleY = k
                translationY = -1.5.dp.toPx() * lift
            },
            tint = ink,
        )
        FoniaText(
            title,
            style = ui(14, if (isActive) UIWeight.semibold else UIWeight.regular, TextRole.footnote),
            tracking = 0.8.sp,
            color = ink,
            maxLines = 1,
            minimumScaleFactor = 0.8f,
        )
    }
}

/** `appRoutes`: the page each destination opens. Pages whose data is missing stay empty, as the iOS `if let`s do. */
@Composable
fun DestinationScreen(destination: Destination, app: AppModel, path: PathModel?, onShowGrownups: (Boolean) -> Unit) {
    when (destination) {
        Destination.Root -> Unit
        is Destination.Route -> if (path != null) RouteScreen(destination.route, app, path, onShowGrownups) else EmptyPage()
        is Destination.ProfileDetail -> ProfileDetail(destination.profileID, app)
        is Destination.HowItGoes -> app.profiles.firstOrNull { it.id == destination.profileID }?.let { HowItGoesScreen(it, app) } ?: EmptyPage()
        is Destination.Week -> app.profiles.firstOrNull { it.id == destination.profileID }?.let { WeekScreen(it, app) } ?: EmptyPage()
        is Destination.Notes -> NotesScreen(destination.profileID, app)
        is Destination.SoundChoice -> SoundChoiceScreen(destination.profileID, app)
        is Destination.Calibration -> CalibrationScreen(destination.profileID, app)
        Destination.Who -> WhoScreen(app)
        is Destination.Reading -> if (app.arguments.drafts) ReadingHome(destination.profileID, app) else EmptyPage()
        is Destination.Adaptive -> AdaptiveScreen(destination.profileID, app)
        Destination.Licences -> tech.fonia.features.grownups.LicencesScreen()
        is Destination.LicenceText -> tech.fonia.features.grownups.LicenceTextScreen(destination.id)
        Destination.AdultCards -> tech.fonia.features.guide.AdultCardsScreen()
        is Destination.AdultCard -> tech.fonia.features.guide.AdultCardKind.fromRawValue(destination.kind)
            ?.let { tech.fonia.features.guide.AdultCardPage(tech.fonia.features.guide.AdultCards.card(it)) } ?: EmptyPage()
    }
}

@Composable
private fun RouteScreen(route: AppRoute, app: AppModel, path: PathModel, onShowGrownups: (Boolean) -> Unit) {
    when (route) {
        is AppRoute.Guide -> SoundGuide(app.content.sound(route.sound), app)
        is AppRoute.Mirror -> app.content.coachPlan(route.sound.rawValue)?.let { plan ->
            // A child's first time at the mirror opens with the card for the adult beside them.
            val dismiss = LocalDismiss.current
            AdultCardGate(path.profile, FirstTimeCard.mirror(path.profile, app), app, closes = false, onClose = dismiss) {
                MouthCoach(plan, path.profile, app)
            }
        } ?: EmptyPage()
        is AppRoute.Coach -> app.content.coachPlan(route.key)?.let { MouthCoach(it, path.profile, app) } ?: EmptyPage()
        is AppRoute.Unit -> path.unit(route.id)?.let { UnitScreen(it, path, app) } ?: EmptyPage()
        AppRoute.AllSounds -> SoundsScreen(path)
        AppRoute.Warmup -> WarmupScreen(path, app)
        AppRoute.Play -> PlayScreen(path, app)
        AppRoute.Journal -> JournalScreen(path, app)
        AppRoute.Guides -> GuidesScreen(path)
        AppRoute.Stories -> StoriesScreen(path, app)
        AppRoute.English -> EnglishScreen(path)
        AppRoute.Speaking -> SpeakingHome(path.profile, app, path)
        is AppRoute.SpeechCoach -> tech.fonia.features.speaking.coach.SpeechCoachScreen(path.profile, app, route.launch)
        AppRoute.Settings -> ParentsScreen(path.profile, app, path, onShowGrownups)
        is AppRoute.Training -> tech.fonia.features.diction.TrainingScreen(route.sound, app, path.profile.id)
    }
}

/** What an `if let` with no `else` leaves: the page colour. */
@Composable
private fun EmptyPage() {
    Box(Modifier.fillMaxSize().background(Palette.paper))
}

/** `NavigationLink(value:)`: pushes onto the stack the page sits in. */
@Composable
fun rememberPush(): (AppRoute) -> Unit {
    val stack = LocalNavigator.current
    return remember(stack) { { route: AppRoute -> stack?.push(route) } }
}

/** `NavigationLink { Page() }` for the adult pages. */
@Composable
fun rememberPushDestination(): (Destination) -> Unit {
    val stack = LocalNavigator.current
    return remember(stack) { { destination: Destination -> stack?.push(destination) } }
}
