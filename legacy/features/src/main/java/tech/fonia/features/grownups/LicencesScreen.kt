package tech.fonia.features.grownups

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.features.app.Destination
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.SystemNavigationBar
import tech.fonia.features.home.rememberPushDestination

/**
 * One entry of the licences page (iOS `Licence`): the name, the notice each licence asks to travel with the app,
 * and the asset files that hold the full texts.
 */
data class Licence(val id: String, val name: String, val notice: String, val files: List<String>) {
    companion object {
        private const val APACHE = "licenses/apache-2.0.txt"

        /**
         * Everything third-party in the Android app. The fonts' and the mouth's files travel with their own modules
         * (`design`, `legacy/design`); the rest sit in this module's `assets/licenses`.
         */
        val all: List<Licence> = listOf(
            Licence("baloo2", "Baloo 2", "© 2019 The Baloo 2 Project Authors (Ek Type). Licență SIL Open Font 1.1.", listOf("type/Baloo2-OFL.txt")),
            Licence("allo", "Allo", "© 2026 Josh Puckett, pornind de la Inter (© 2016 The Inter Project Authors). Licență SIL Open Font 1.1.", listOf("licenses/Allo-OFL.txt")),
            Licence("andika", "Andika", "© 2004-2025 SIL Global. Licență SIL Open Font 1.1.", listOf("licenses/OFL-Andika.txt")),
            Licence("inter", "Inter", "© 2016 The Inter Project Authors. Licență SIL Open Font 1.1.", listOf("licenses/OFL-Inter.txt")),
            Licence("phosphor", "Phosphor Icons", "© 2023 Phosphor Icons. Licență MIT.", listOf("licenses/LICENSE-phosphor.txt")),
            Licence(
                "fluent",
                "Fluent Emoji",
                "© Microsoft Corporation. Licență MIT. Foni pornește de la acest desen, modificat.",
                listOf("fonia/licenses/fluent-emoji.txt"),
            ),
            Licence("noto", "Noto Emoji", "© Google. Imaginile sunt sub licența Apache 2.0.", listOf(APACHE)),
            // CC BY 3.0 asks for the author, the licence, the source and the changes (mouth3d's CREDITS.txt).
            Licence(
                "head-scan",
                "Scanarea 3D „Infinite”",
                "Lee Perry-Smith / Infinite-Realities (triplegangers.com). Licență CC BY 3.0. Fonia a păstrat partea de jos a feței, a refăcut interiorul gurii și a pregătit buzele și bărbia pentru vorbire.",
                listOf("licenses/head-scan-cc-by-3.0.txt"),
            ),
            Licence("makehuman", "Dinții MakeHuman", "Dinții gurii 3D pornesc de la MakeHuman. Domeniu public (CC0 1.0).", listOf("licenses/makehuman-cc0.txt")),
            Licence(
                "mediapipe",
                "MediaPipe Face Landmarker",
                "© Google. Modelele care găsesc fața și buzele. Licență Apache 2.0.",
                listOf(APACHE),
            ),
            Licence("litert", "LiteRT", "© Google. Rulează modelele feței pe telefon. Licență Apache 2.0.", listOf(APACHE)),
            Licence("camerax", "CameraX", "© The Android Open Source Project. Licență Apache 2.0.", listOf(APACHE)),
            Licence("oboe", "Oboe", "© Google. Microfonul și sunetul fără întârziere. Licență Apache 2.0.", listOf(APACHE)),
            Licence("media3", "Media3", "© The Android Open Source Project. Redă clipurile. Licență Apache 2.0.", listOf(APACHE)),
            Licence(
                "androidx",
                "AndroidX, Jetpack Compose și Kotlin",
                "© The Android Open Source Project și JetBrains. Licență Apache 2.0. SQLite e în domeniul public.",
                listOf(APACHE),
            ),
            Licence(
                "onnxruntime",
                "ONNX Runtime 1.31.1",
                "© Microsoft Corporation. Licență MIT, cu notițele componentelor terțe.",
                listOf("licenses/onnxruntime-license.txt", "licenses/onnxruntime-third-party-notices.txt"),
            ),
            // CC BY 4.0 asks for the authors, the licence, the source and whether it was changed.
            Licence(
                "zipa",
                "ZIPA-small CR-CTC (500k)",
                "© autorii ZIPA (Jian Zhu și alții). Licență CC BY 4.0, creativecommons.org/licenses/by/4.0/. Sursa: huggingface.co/anyspeech/zipa-small-crctc-500k. Folosit fără schimbări.",
                listOf("licenses/zipa-cc-by-4.0.txt"),
            ),
            Licence("whisper-cpp", "whisper.cpp", "© 2023-2026 The ggml authors. Licență MIT.", listOf("licenses/whisper-cpp-mit.txt")),
            Licence("whisper", "Whisper", "© 2022 OpenAI. Modelul de recunoaștere a vorbirii. Licență MIT.", listOf("licenses/whisper-mit.txt")),
        )

        fun withID(id: String): Licence? = all.firstOrNull { it.id == id }

        /** The full texts split into paragraphs; some run to thousands, so this is read off the main thread. */
        fun paragraphs(texts: List<String>): List<String> =
            texts.flatMap { text -> text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() } }
    }
}

/** What Fonia is built from (iOS `LicencesScreen`). Every row opens the full texts. */
@Composable
fun LicencesScreen() {
    val push = rememberPushDestination()
    val dismiss = LocalDismiss.current
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar("Licențe", onBack = dismiss)
        InsetGroupedList(Modifier.weight(1f)) {
            ListSection {
                for (licence in Licence.all) {
                    row {
                        ListRow(onClick = { push(Destination.LicenceText(licence.id)) }, chevron = true) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                FoniaText(licence.name, style = fBodyStrong, color = Palette.ink)
                                FoniaText(licence.notice, style = fCaption, color = Palette.inkSoft)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One licence: its notice, then its full texts, which can be selected and copied. */
@Composable
fun LicenceTextScreen(id: String) {
    val licence = Licence.withID(id) ?: return
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(id) {
        paragraphs = withContext(Dispatchers.IO) { Licence.paragraphs(licence.files.mapNotNull { readAsset(context, it) }) }
    }
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar(licence.name, onBack = dismiss)
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { FoniaText(licence.notice, style = fBody, color = Palette.ink) }
                itemsIndexed(paragraphs) { _, paragraph ->
                    FoniaText(paragraph, Modifier.fillMaxWidth(), style = fCaption, color = Palette.inkSoft)
                }
            }
        }
    }
}

private fun readAsset(context: Context, path: String): String? =
    runCatching { context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
