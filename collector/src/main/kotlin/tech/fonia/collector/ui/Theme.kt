package tech.fonia.collector.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.collector.R

/** Fonia 2 colour roles (docs/v2/05 §2.1). There is no red anywhere. */
object C {
    val bg = Color(0xFFF4F6FF)
    val surface = Color(0xFFFFFFFF)
    val fill = Color(0xFFE6EEFF)
    val separator = Color(0xFFDFE4F7)
    val inkSecondary = Color(0xFF5A638A)
    val ink = Color(0xFF131A3A)
    val accent = Color(0xFF2F5BFF)
    val accentDeep = Color(0xFF14228A)
    val voice = Color(0xFFFF4F93)
    val voiceDeep = Color(0xFF8A1247)
    val voiceFace = Color(0xFFFFF6E6)
    val parrot = Color(0xFF17B55E)
    val parrotDeep = Color(0xFF0A5A30)
    val parrotFace = Color(0xFFEFFFE0)
    val sun = Color(0xFFFFD23F)
    val sunDeep = Color(0xFF9A6200)
    val sunFace = Color(0xFFFFF3B8)
}

/** Spacing (05 §2.5). */
object S {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val page = 16.dp
}

/**
 * Allo, the only typeface (05 §2.4), in the grown-up shell: ROND 30, opsz = size. The variable file carries every
 * weight; each style asks for its own instance.
 */
@OptIn(ExperimentalTextApi::class)
object T {
    private fun allo(weight: Int, size: Float, rond: Float = 30f) = Font(
        R.font.allo_variable,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.Setting("ROND", rond),
            FontVariation.Setting("opsz", size.coerceIn(9f, 32f)),
        ),
    )

    private fun family(weight: Int, size: Float, rond: Float = 30f) = FontFamily(allo(weight, size, rond))

    val display = TextStyle(fontFamily = family(800, 40f), fontWeight = FontWeight(800), fontSize = 40.sp, lineHeight = 44.sp, color = C.ink)
    val title = TextStyle(fontFamily = family(750, 28f), fontWeight = FontWeight(750), fontSize = 28.sp, lineHeight = 34.sp, color = C.ink)
    val title2 = TextStyle(fontFamily = family(700, 22f), fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 28.sp, color = C.ink)
    val headline = TextStyle(fontFamily = family(650, 18f), fontWeight = FontWeight(650), fontSize = 18.sp, lineHeight = 24.sp, color = C.ink)
    val body = TextStyle(fontFamily = family(500, 17f), fontWeight = FontWeight(500), fontSize = 17.sp, lineHeight = 24.sp, color = C.ink)
    val label = TextStyle(fontFamily = family(600, 15f), fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 20.sp, color = C.ink)
    val caption = TextStyle(fontFamily = family(500, 13f), fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp, color = C.inkSecondary)
    val counter = TextStyle(
        fontFamily = family(800, 28f), fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 32.sp, color = C.ink,
        fontFeatureSettings = "tnum",
    )

    /** What is practised: ss01 (one-storey a) and ss02 (unambiguous I/l) on, contextual alternates off. */
    val practiceWord = TextStyle(
        fontFamily = family(800, 32f, rond = 60f), fontWeight = FontWeight(800), fontSize = 56.sp, lineHeight = 64.sp,
        color = C.ink, fontFeatureSettings = "ss01, ss02, calt 0",
    )
    val practiceLine = TextStyle(
        fontFamily = family(600, 28f, rond = 60f), fontWeight = FontWeight(600), fontSize = 28.sp, lineHeight = 38.sp,
        color = C.ink, fontFeatureSettings = "ss01, ss02, calt 0",
    )
}
