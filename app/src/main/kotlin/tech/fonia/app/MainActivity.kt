package tech.fonia.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.fonia.app.shell.FoniaShell
import tech.fonia.app.shell.Navigator
import tech.fonia.app.shell.startStack
import tech.fonia.designsystem.letter.AlloFont
import tech.fonia.designsystem.theme.FoniaTheme

/** Holds the process-wide [AppGraph] and the kid shell's [Navigator] (both survive activity re-creation). */
class FoniaApp : Application() {
    var graph: AppGraph? = null
        private set
    var navigator: Navigator? = null
        private set

    override fun onCreate() {
        super.onCreate()
        AlloFont.preload(this)
    }

    /** The graph for this launch: reused while the flags are the same, rebuilt (old audio released) when they change. */
    fun attach(flags: LaunchFlags, fresh: Boolean): Pair<AppGraph, Navigator> {
        val current = graph
        val nav = navigator
        if (current != null && nav != null && (!fresh || current.flags == flags)) return current to nav
        current?.release()
        val g = AppGraph(this, flags)
        val n = Navigator(startStack(g))
        graph = g
        navigator = n
        return g to n
    }

    fun detach() {
        graph?.release()
        graph = null
        navigator = null
    }
}

/**
 * The Fonia 2 shell: edge-to-edge, [FoniaTheme], the kid shell on PATH. Audio follows the activity: suspended in the
 * background, resumed in front, released when the activity finishes. Debug launch flags: see [LaunchFlags].
 */
class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val flags = LaunchFlags.parse(intent, VariantHooks.debug)
        val (g, nav) = (application as FoniaApp).attach(flags, fresh = savedInstanceState == null)
        graph = g
        setContent {
            FoniaTheme(reduceMotion = if (g.flags.reduceMotion || g.settings.reduceMotion) true else null) {
                FoniaShell(g, nav)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.permissions.refresh()
        graph.onForeground()
    }

    override fun onStop() {
        graph.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) (application as FoniaApp).detach()
        super.onDestroy()
    }
}
