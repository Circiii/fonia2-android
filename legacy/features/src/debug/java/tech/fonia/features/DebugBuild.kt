package tech.fonia.features

import androidx.compose.runtime.Composable
import tech.fonia.design.DesignGallery
import tech.fonia.design.GallerySection

// Debug builds only, like `#if DEBUG` on iOS: the launch arguments that change data or skip screens, and the design
// gallery that `-foniaMouths` shows in place of the app.

internal const val IS_DEBUG_BUILD: Boolean = true

/** `MouthGallery()` on iOS: here the whole design gallery from :design, opened at [section] (`guri` for the mouths). */
@Composable
internal fun DebugGallery(section: String?) {
    DesignGallery(GallerySection.from(section))
}
