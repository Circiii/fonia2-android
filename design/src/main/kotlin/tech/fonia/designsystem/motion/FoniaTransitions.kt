package tech.fonia.designsystem.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaMotion

/** How one screen replaces another. */
enum class FoniaNav {
    /** Deeper: shared X axis, the new screen comes from the right. */
    Forward,
    /** Back up: shared X axis the other way. */
    Back,
    /** A sheet or a modal rises over the screen. */
    ModalOpen,
    /** The sheet sinks away. */
    ModalClose,
    /** Unrelated places (tabs, home ↔ lesson): fade through with a small zoom. */
    Through,
}

/**
 * The shared screen transitions (plan §10): shared axis for depth, rise/sink for modals, fade-through for siblings.
 * Positions move on the `follow` spring (no wobble on a whole screen), modals land on `bounce`, fades are short tweens.
 * With reduced motion every transition is the 150 ms cross-fade of the tokens.
 */
object FoniaTransitions {
    private fun <T> follow(): FiniteAnimationSpec<T> = spring(FoniaMotion.follow.dampingRatio, FoniaMotion.follow.stiffness * 0.7f)
    private fun <T> land(): FiniteAnimationSpec<T> = spring(0.72f, FoniaMotion.bounce.stiffness * 0.8f)
    private val slideSpec: FiniteAnimationSpec<IntOffset> = spring(FoniaMotion.follow.dampingRatio, FoniaMotion.follow.stiffness * 0.7f, IntOffset(1, 1))
    private val landSpec: FiniteAnimationSpec<IntOffset> = spring(0.72f, FoniaMotion.bounce.stiffness * 0.8f, IntOffset(1, 1))
    private const val QUICK = FoniaMotion.QUICK_MS

    fun forward(reduceMotion: Boolean = false): ContentTransform =
        if (reduceMotion) reduced() else
            (slideInHorizontally(slideSpec) { it / 7 } + fadeIn(tween(FoniaMotion.STANDARD_MS, delayMillis = 40)))
                .togetherWith(slideOutHorizontally(slideSpec) { -it / 10 } + fadeOut(tween(QUICK)))

    fun back(reduceMotion: Boolean = false): ContentTransform =
        if (reduceMotion) reduced() else
            ((slideInHorizontally(slideSpec) { -it / 7 } + fadeIn(tween(FoniaMotion.STANDARD_MS, delayMillis = 40)))
                .togetherWith(slideOutHorizontally(slideSpec) { it / 10 } + fadeOut(tween(QUICK)))).apply { targetContentZIndex = -1f }

    fun modalOpen(reduceMotion: Boolean = false): ContentTransform =
        if (reduceMotion) reduced() else
            (slideInVertically(landSpec) { it / 4 } + fadeIn(tween(QUICK)) + scaleIn(land(), initialScale = 0.94f))
                .togetherWith(fadeOut(tween(FoniaMotion.STANDARD_MS), targetAlpha = 0.6f) + scaleOut(follow(), targetScale = 0.97f))

    fun modalClose(reduceMotion: Boolean = false): ContentTransform =
        if (reduceMotion) reduced() else
            ((fadeIn(tween(FoniaMotion.STANDARD_MS), initialAlpha = 0.6f) + scaleIn(follow(), initialScale = 0.97f))
                .togetherWith(slideOutVertically(slideSpec) { it / 3 } + fadeOut(tween(FoniaMotion.STANDARD_MS)))).apply { targetContentZIndex = -1f }

    fun through(reduceMotion: Boolean = false): ContentTransform =
        if (reduceMotion) reduced() else
            (fadeIn(tween(FoniaMotion.STANDARD_MS, delayMillis = 90)) + scaleIn(spring(0.8f, Spring.StiffnessMediumLow), initialScale = 0.92f))
                .togetherWith(fadeOut(tween(90)))

    fun of(nav: FoniaNav, reduceMotion: Boolean = false): ContentTransform = when (nav) {
        FoniaNav.Forward -> forward(reduceMotion)
        FoniaNav.Back -> back(reduceMotion)
        FoniaNav.ModalOpen -> modalOpen(reduceMotion)
        FoniaNav.ModalClose -> modalClose(reduceMotion)
        FoniaNav.Through -> through(reduceMotion)
    }

    private fun reduced(): ContentTransform =
        fadeIn(tween(FoniaMotion.REDUCED_FADE_MS)).togetherWith(fadeOut(tween(FoniaMotion.REDUCED_FADE_MS)))
}

/**
 * [AnimatedContent] with the Fonia transitions: [nav] says, for a change from `initial` to `target`, which kind it is.
 * Reduced motion is taken from the theme. Works for any screen key (the app's Navigation 3 back stack top, a tab, …).
 */
@Composable
fun <S> FoniaAnimatedScreen(
    targetState: S,
    modifier: Modifier = Modifier,
    nav: (initial: S, target: S) -> FoniaNav,
    contentKey: (S) -> Any? = { it },
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val reduce = FoniaTheme.reduceMotion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { FoniaTransitions.of(nav(initialState, this.targetState), reduce) },
        contentAlignment = Alignment.TopStart,
        label = "FoniaScreen",
        contentKey = contentKey,
        content = content,
    )
}

/** Shortcut for transition specs written by hand: `transitionSpec = { foniaTransition(FoniaNav.Forward, reduce) }`. */
fun AnimatedContentTransitionScope<*>.foniaTransition(nav: FoniaNav, reduceMotion: Boolean = false): ContentTransform =
    FoniaTransitions.of(nav, reduceMotion)
