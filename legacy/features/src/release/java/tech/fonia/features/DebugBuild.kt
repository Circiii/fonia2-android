package tech.fonia.features

import androidx.compose.runtime.Composable

// Release builds: no debug launch arguments and no gallery, as `#if DEBUG` leaves them out on iOS.

internal const val IS_DEBUG_BUILD: Boolean = false

@Composable
internal fun DebugGallery(@Suppress("UNUSED_PARAMETER") section: String?) = Unit
