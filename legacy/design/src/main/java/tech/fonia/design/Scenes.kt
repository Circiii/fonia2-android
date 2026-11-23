package tech.fonia.design

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale

/**
 * The painted scene for one activity. Each activity keeps one family of colours, so the place tells you
 * where you are before the words do.
 */
@Suppress("EnumEntryName")
enum class SceneName {
    sounds, warmup, play, journal, guide, stories,
    english, diction, speaking, melody,
    cold, warm, tip,
    start, lesson, night;

    val rawValue: String get() = name

    /** The classroom and the stage are indoors, so nothing drifts across them. */
    val hasSky: Boolean get() = this != sounds && this != speaking
}

/** The empty landscape of a sound's family, for a hero to stand in. */
val Field.scene: SceneName
    get() = when (this) {
        Field.cold -> SceneName.cold
        Field.warm -> SceneName.warm
        Field.tip -> SceneName.tip
        Field.night -> SceneName.night
        Field.paper -> SceneName.lesson
    }

/** Wide cards and tall headers each get their own painting, so the mascot stays a sensible size. */
internal fun sceneImageName(scene: SceneName, aspect: Float): String {
    val shape = if (aspect >= 2) "-wide" else if (aspect < 1.25f) "-tall" else ""
    return "scene-${scene.rawValue}$shape"
}

/**
 * A scene cut to the shape it's shown in. The art stays centred, so a wide card and a tall header can
 * share a painting. Takes the width it's offered and the height from [aspect].
 */
@Composable
fun SceneArt(scene: SceneName, aspect: Float, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(aspect).clipToBounds()) {
        FoniaImage(sceneImageName(scene, aspect), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * A scene that fills the page behind everything, for screens that happen inside one. iOS lets it run under
 * the safe areas; give it the whole window.
 */
@Composable
fun SceneBackdrop(scene: SceneName, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        FoniaImage("scene-${scene.rawValue}-page", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        if (scene.hasSky) SkyLife(isNight = scene == SceneName.night || scene == SceneName.stories, modifier = Modifier.matchParentSize())
    }
}

/**
 * Scenes decoded ahead of time, away from the main thread, so opening a page never waits for a big picture.
 * They sit in the asset cache, which the system empties when memory gets short.
 */
object SceneCache {
    /** What Home shows, then the pages its doors open. */
    fun preloadHome(context: Context) {
        val scenes = listOf(SceneName.sounds, SceneName.warmup, SceneName.play, SceneName.stories, SceneName.guide, SceneName.english, SceneName.speaking)
        val tiles = scenes.flatMap { listOf("scene-${it.rawValue}-wide", "scene-${it.rawValue}") }
        val pages = listOf(SceneName.lesson, SceneName.start, SceneName.cold, SceneName.warm, SceneName.tip, SceneName.night)
            .map { "scene-${it.rawValue}-page" }
        FoniaAssets.get(context).preload(tiles + pages)
    }
}
