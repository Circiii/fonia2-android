package tech.fonia.mouth3d

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import tech.fonia.model.SoundId
import tech.fonia.mouth3d.style.StylePalette

/**
 * The colours of the mouth. They come from the app's tokens (docs/v2/05); the defaults follow docs/v2/04 §7: a pale
 * blue "sticker" instead of skin, dark pink lips, pink tongue, pale pink gums, warm white teeth, a blue-violet cavity
 * (never black), a pale yellow rim light. [patch] is the skin around the lips in the front view (match it to the
 * picture's background: only its rim fades). [section] is the tissue of the side cut, [lining] the inside of the mouth
 * there. [airCold] / [airWarm] colour the airflow (S-like / Ș-like), [voice] the throat mark of voiced sounds,
 * [highlight] the glow of the step's part (`highlight`).
 */
@Immutable
data class Mouth3DColors(
    val patch: Color = Color(0xFFB4D8FF),
    val lips: Color = Color(0xFFE2577C),
    val teeth: Color = Color(0xFFFFF9EE),
    val gums: Color = Color(0xFFF7B5C3),
    val tongue: Color = Color(0xFFF2788F),
    val lining: Color = Color(0xFF5A4791),
    val section: Color = Color(0xFFFFE2CF),
    val rim: Color = Color(0xFFFFF1B8),
    val airCold: Color = Color(0xFF6EC3FF),
    val airWarm: Color = Color(0xFFFF9A3D),
    val voice: Color = Color(0xFFFFB020),
    val highlight: Color = Color(0xFFFFE066),
) {
    internal fun byPart(): Array<Color> = arrayOf(patch, lips, teeth, gums, tongue, lining)

    companion object {
        val Default = Mouth3DColors()
    }
}

/** The poses, loaded once per process (the mouth itself is procedural). */
class Mouth3DData(val library: PoseLibrary) {

    /**
     * N17: may the child see the tongue cut-away for this sound? Only once a speech therapist approved the pose
     * (`"insideApproved": true`), unless [allowUnapproved] — by default the owner/debug switch
     * [Mouth3DReview.showUnapprovedInside].
     */
    fun insideAvailable(sound: SoundId?, allowUnapproved: Boolean = Mouth3DReview.showUnapprovedInside): Boolean =
        allowUnapproved || library.forSound(sound).insideApproved

    fun spec(sound: SoundId?): PoseSpec = library.forSound(sound)
}

internal fun Mouth3DColors.toStylePalette(): StylePalette {
    fun Color.f() = floatArrayOf(red, green, blue)
    return StylePalette(patch.f(), lips.f(), teeth.f(), gums.f(), tongue.f(), lining.f(), section.f(), rim.f(), airCold.f(), airWarm.f(), voice.f(), highlight.f())
}

/**
 * The review override for the inside view (N17). Set [showUnapprovedInside] from an owner or debug switch (e.g. the
 * parent area's hidden review menu) to see the cut-away of poses a speech therapist has not approved yet; every
 * `insideAvailable` / `Mouth3D` call then honours it without further code. Off by default: children never see an
 * unapproved cut-away.
 */
object Mouth3DReview {
    @Volatile
    var showUnapprovedInside: Boolean = false
}

object Mouth3DAssets {
    const val DIR = "mouth3d"

    @Volatile
    private var cached: Mouth3DData? = null

    /** Reads `assets/mouth3d/` (bundled from spec/ by the module's build). Call off the main thread the first time. */
    fun load(context: Context): Mouth3DData = cached ?: synchronized(this) {
        cached ?: run {
            val assets = context.applicationContext.assets
            val library = PoseLibrary.load { key -> assets.open("$DIR/poses/$key.json").use { it.readBytes().toString(Charsets.UTF_8) } }
            Mouth3DData(library).also { cached = it }
        }
    }
}

object Mouth3DSupport {
    /** OpenGL ES 3.0 and not a low-RAM phone; otherwise [Mouth3D] draws the 2D side view. */
    fun canRender3D(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000 && !am.isLowRamDevice
    }
}

/** True when the system's animations are off (Settings › Accessibility › Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
