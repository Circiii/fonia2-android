package tech.fonia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import tech.fonia.features.FoniaRoot

/** The whole app is one Compose tree, like `WindowGroup { RootView() }` in `App/FoniaApp.swift`. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FoniaRoot(launchArguments = intent.extras) }
    }
}
