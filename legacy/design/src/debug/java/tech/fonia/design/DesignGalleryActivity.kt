package tech.fonia.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Debug builds only: the design gallery on its own screen, the way `-foniaMouths` replaces the iOS app with
 * `MouthGallery`. `adb shell am start -n tech.fonia.app/tech.fonia.design.DesignGalleryActivity --es section guri`
 * opens one page directly (`componente`, `acasa`, `foni`, `guri`, `scene`).
 */
class DesignGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val section = GallerySection.from(intent.getStringExtra("section"))
        setContent {
            FoniaTheme {
                DesignGallery(section)
            }
        }
    }
}
