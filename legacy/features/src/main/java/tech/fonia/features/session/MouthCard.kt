package tech.fonia.features.session

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FacePatch
import tech.fonia.design.MouthDriver
import tech.fonia.design.MouthShape
import tech.fonia.design.MouthView
import tech.fonia.design.PracticeSheet
import tech.fonia.design.SagittalPose
import tech.fonia.design.SagittalView
import tech.fonia.design.easeInOut
import tech.fonia.domain.Viseme
import tech.fonia.features.app.SegmentedPicker

/**
 * `MouthCard` from iOS `Session/ExerciseHost.swift` (lines 213-250): the lesson in pictures, the mouth from the front
 * and, for a sound, the inside from the side. The first half needs it already (an English contrast's pose on its unit
 * page), so it lives here; the port of `ExerciseHost` and `SoundGuide` uses this one.
 */
@Composable
fun MouthCard(
    shape: MouthShape,
    viseme: Viseme? = null,
    driver: MouthDriver? = null,
    isLive: Boolean = false,
    height: Dp = 220.dp,
) {
    var showSide by rememberSaveable { mutableStateOf(false) }
    PracticeSheet {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Crossfade(showSide && viseme != null, animationSpec = easeInOut(0.18), label = "mouth") { side ->
                if (side && viseme != null) {
                    SagittalView(
                        SagittalPose.named(viseme.rawValue),
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .semantics { contentDescription = "Gura văzută din lateral, cu limba la locul ei" },
                        isLive = true,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .clip(ContinuousRoundedRectangle(18.dp))
                            .semantics { contentDescription = "Gura văzută din față" },
                        contentAlignment = Alignment.Center,
                    ) {
                        FacePatch(Modifier.matchParentSize())
                        MouthView(shape, Modifier.fillMaxHeight(), driver = driver, isLive = isLive, zoom = 1.5)
                    }
                }
            }
            if (viseme != null) {
                SegmentedPicker(listOf("Din față", "Din lateral"), if (showSide) 1 else 0) { showSide = it == 1 }
            }
        }
    }
}
