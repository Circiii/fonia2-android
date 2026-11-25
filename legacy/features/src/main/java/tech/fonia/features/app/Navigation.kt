package tech.fonia.features.app

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Radius
import java.util.UUID

/**
 * A page on a navigation stack: an app route, or one of the pages the adult area pushes directly (`NavigationLink {
 * Screen() }` on iOS). [Root] is what the stack starts on: the tabs, or the settings list in its sheet.
 */
sealed interface Destination {
    data object Root : Destination

    data class Route(val route: AppRoute) : Destination

    data class ProfileDetail(val profileID: UUID) : Destination

    data class HowItGoes(val profileID: UUID) : Destination

    data class Notes(val profileID: UUID) : Destination

    data class SoundChoice(val profileID: UUID) : Destination

    data class Calibration(val profileID: UUID) : Destination

    data object Who : Destination

    /** The reading probe and its report (not on iOS). */
    data class Reading(val profileID: UUID) : Destination

    /** The adaptive pilot's switch, modes and estimates (not on iOS, docs/PLAN.md §7.4). */
    data class Adaptive(val profileID: UUID) : Destination

    /** What the app is built from, and each licence's full text. */
    data object Licences : Destination

    data class LicenceText(val id: String) : Destination

    /** The cards for the adult (iOS `AdultCardsScreen`), and one of them read again. */
    data object AdultCards : Destination

    data class AdultCard(val kind: String) : Destination
}

/** One `NavigationStack` path. The root is always there; [push] and [pop] change what's on top. */
class NavStack {
    class Entry internal constructor(val id: Long, val destination: Destination, val depth: Int)

    private var nextID = 1L
    internal val entries = mutableStateListOf(Entry(0, Destination.Root, 0))

    val top: Entry get() = entries.last()

    val canPop: Boolean get() = entries.size > 1

    fun push(destination: Destination) {
        tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.NavForward)
        entries.add(Entry(nextID++, destination, entries.size))
    }

    fun push(route: AppRoute) = push(Destination.Route(route))

    fun pop() {
        if (!canPop) return
        tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.NavBack)
        entries.removeAt(entries.lastIndex)
    }

    /** Removes [entry] and everything above it: what `dismiss()` does from inside a pushed page. */
    fun pop(entry: Entry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index > 0) entries.removeRange(index, entries.size)
    }

    fun below(entry: Entry): Entry? = entries.getOrNull(entries.indexOfFirst { it.id == entry.id } - 1)
}

/** `NavigationLink(value:)` and friends: the stack the current page sits in. */
val LocalNavigator = compositionLocalOf<NavStack?> { null }

/** `@Environment(\.dismiss)`: closes the page, or the sheet a root page sits in. */
val LocalDismiss = compositionLocalOf<() -> kotlin.Unit> { {} }

/** The scopes a door needs to zoom into its page. */
internal val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
internal val LocalNavigationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The routes that grow out of their door on Home (`opensByZoom`, MainTabs.swift:160-167). */
internal val AppRoute.opensByZoom: Boolean
    get() = when (this) {
        AppRoute.AllSounds, AppRoute.Guides, AppRoute.Warmup, AppRoute.Play, AppRoute.Stories, AppRoute.English, AppRoute.Speaking, is AppRoute.SpeechCoach -> true
        else -> false
    }

private val Destination.zoomRoute: AppRoute? get() = (this as? Destination.Route)?.route?.takeIf { it.opensByZoom }

/** The door's corner while it grows (`clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))`). */
private val doorClip = ContinuousRoundedRectangle(Radius.card)

/**
 * `NavigationStack` with `navigationDestination`: one page at a time, the ones below kept with their saved state.
 * A push slides the page in from the right; a zoom route grows out of its door (`navigationTransition(.zoom)`); the
 * Android back gesture pops with the page following the finger, the way the edge swipe does on iOS.
 */
@Composable
fun NavigationHost(stack: NavStack, modifier: Modifier = Modifier, content: @Composable (Destination) -> kotlin.Unit) {
    val saveable = rememberSaveableStateHolder()
    val reduceMotion = LocalReduceMotion.current
    val outerDismiss = LocalDismiss.current
    val current = stack.top
    val seekable = remember { SeekableTransitionState(current) }
    val scope = rememberCoroutineScope()
    val kept = remember { mutableSetOf<Long>() }
    // How far the back gesture has gone, for the zoom pages: shared bounds don't follow a seek, so the page itself
    // shrinks under the finger, as a zoomed page does when it's dragged on iOS.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEntry by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(current) {
        if (seekable.currentState != current || seekable.targetState != current) seekable.animateTo(current)
        backProgress = 0f
        // Pages that left the stack don't come back: drop what they kept.
        val alive = stack.entries.map { it.id }.toSet()
        kept.filter { it !in alive }.forEach { saveable.removeState(it) }
        kept.retainAll(alive)
        kept.addAll(alive)
    }

    PredictiveBackHandler(enabled = stack.canPop) { events ->
        val from = stack.top
        val to = stack.below(from) ?: return@PredictiveBackHandler
        backEntry = from.id
        try {
            events.collect { event ->
                backProgress = event.progress
                seekable.seekTo(event.progress, targetState = to)
            }
            stack.pop(from)
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            scope.launch { seekable.animateTo(from) }
            throw cancelled
        }
    }

    val transition = rememberTransition(seekable, label = "navigation")
    SharedTransitionLayout(modifier) {
        transition.AnimatedContent(
            transitionSpec = { navigationTransform(reduceMotion) },
            contentKey = { it.id },
        ) { entry ->
            saveable.SaveableStateProvider(entry.id) {
                val dismiss: () -> kotlin.Unit = if (entry.depth == 0) outerDismiss else ({ stack.pop(entry) })
                CompositionLocalProvider(
                    LocalNavigator provides stack,
                    LocalDismiss provides dismiss,
                    LocalSharedTransition provides this@SharedTransitionLayout,
                    LocalNavigationScope provides this@AnimatedContent,
                ) {
                    // Depth 1 sits right on the root, where the doors are. (Read from the entry, not the stack: a
                    // page being popped is no longer on the stack while it zooms back into its door.)
                    val zoom = entry.destination.zoomRoute
                    if (zoom != null && entry.depth == 1 && !reduceMotion) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .sharedBounds(
                                    rememberSharedContentState(zoom),
                                    this@AnimatedContent,
                                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth),
                                    clipInOverlayDuringTransition = OverlayClip(doorClip),
                                )
                                .graphicsLayer {
                                    val progress = if (backEntry == entry.id) backProgress else 0f
                                    val shrink = 1f - 0.12f * progress
                                    scaleX = shrink
                                    scaleY = shrink
                                    shape = doorClip
                                    clip = progress > 0f
                                },
                        ) { content(entry.destination) }
                    } else {
                        content(entry.destination)
                    }
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavStack.Entry>.navigationTransform(reduceMotion: Boolean): ContentTransform {
    val pushing = targetState.depth > initialState.depth
    val page = if (pushing) targetState else initialState
    val zoomsFromHome = page.destination.zoomRoute != null && page.depth == 1
    if (reduceMotion) {
        return (fadeIn(tween(200)) togetherWith fadeOut(tween(200))).apply { targetContentZIndex = if (pushing) 1f else -1f }
    }
    if (zoomsFromHome) {
        // The shared bounds do the zoom; the page underneath stays as it is until the transition ends.
        return (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished).apply {
            targetContentZIndex = if (pushing) 1f else -1f
        }
    }
    if (page.destination.zoomRoute != null) {
        // A zoom route opened from a page with no door for it (Vorbit în public from Engleză): iOS zooms from the
        // middle of the screen.
        val spec = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
        return if (pushing) {
            (scaleIn(spec, initialScale = 0.85f) + fadeIn(spec)) togetherWith ExitTransition.KeepUntilTransitionsFinished
        } else {
            EnterTransition.None togetherWith (scaleOut(spec, targetScale = 0.85f) + fadeOut(spec))
        }.apply { targetContentZIndex = if (pushing) 1f else -1f }
    }
    // A push glides in on a soft spring (v2 polish): it lands with the weight of a card rather than the curve of a timer.
    val slide = androidx.compose.animation.core.spring(
        dampingRatio = 0.88f,
        stiffness = 420f,
        visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
    )
    return if (pushing) {
        slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it / 3 }
    } else {
        (slideInHorizontally(slide) { -it / 3 } togetherWith slideOutHorizontally(slide) { it }).apply { targetContentZIndex = -1f }
    }
}

/**
 * The door side of the zoom (`matchedTransitionSource(id: route, in: doorSpace)`): the card a route grows out of.
 * Outside a navigation host it does nothing.
 */
@Composable
fun Modifier.zoomSource(route: AppRoute): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val scope = LocalNavigationScope.current ?: return this
    if (LocalReduceMotion.current) return this
    return with(shared) {
        this@zoomSource.sharedBounds(
            rememberSharedContentState(route),
            scope,
            clipInOverlayDuringTransition = OverlayClip(doorClip),
        )
    }
}
