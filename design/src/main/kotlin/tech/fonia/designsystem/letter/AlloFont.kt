package tech.fonia.designsystem.letter

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.fonia.glyphs.AlloOutlines

/**
 * The Allo outline export (`assets/glyphs/allo-hero.json`, bundled from `spec/glyphs` at build time), loaded once per
 * process off the main thread. [AlloOutlines] is thread-safe; every letter makes its own interpolator.
 */
object AlloFont {
    const val ASSET = "glyphs/allo-hero.json"

    @Volatile private var loaded: AlloOutlines? = null
    private val lock = Mutex()

    /** The outlines if already loaded (null until [load] finished once). */
    val current: AlloOutlines? get() = loaded

    suspend fun load(context: Context): AlloOutlines {
        loaded?.let { return it }
        return lock.withLock {
            loaded ?: withContext(Dispatchers.IO) {
                context.applicationContext.assets.open(ASSET).use { AlloOutlines.load(it) }
            }.also { loaded = it }
        }
    }

    /** Call early (e.g. Application.onCreate) so the first letter draws in its first frame. */
    fun preload(context: Context) {
        if (loaded != null) return
        Thread({ runCatching { context.applicationContext.assets.open(ASSET).use { loaded = loaded ?: AlloOutlines.load(it) } } }, "allo-preload").start()
    }
}

/** The outlines as Compose state: null for the first frame(s) if not preloaded. */
@Composable
fun rememberAlloOutlines(): State<AlloOutlines?> {
    val context = LocalContext.current
    return produceState(initialValue = AlloFont.current, context) {
        if (value == null) value = AlloFont.load(context)
    }
}
