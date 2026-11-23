package tech.fonia.app.content

import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import tech.fonia.lessons.LessonContent
import tech.fonia.lessons.VoiceIndex
import tech.fonia.live.PromptRef

/** The v2 content and Foni's voice index, loaded together. */
class LoadedContent(val content: LessonContent, val voices: VoiceIndex) {
    val contentVersion: String get() = content.contentVersion

    /** The asset of a voice key (`content/voice/clips/<key>.m4a`), or null for "human-needed" / missing clips (quiet rule: text only). */
    fun clip(key: String?): PromptRef? = voices.file(key)?.let { PromptRef.Asset("$VOICE_DIR/$it") }

    /** Foni's text for a line key: a foni_lines.json line, a hero chapter (`open.<slug>.<rung>`), or a hold prompt. */
    fun lineText(key: String): String? {
        content.line(key)?.let { return it.text }
        for (s in content.sounds.values) {
            s.hero.chapters.values.firstOrNull { it.key == key }?.let { return it.text }
            s.items.firstOrNull { it.promptVoice == key }?.prompt?.let { return it }
        }
        return voices.clips[key]?.text
    }

    companion object {
        const val CONTENT_DIR = "content/v2"
        const val VOICE_DIR = "content/voice"
    }
}

/**
 * Loads `assets/content/v2` (LessonContent) and `assets/content/voice/index.json` (VoiceIndex) ONCE, on IO, when the
 * graph is built (the `contentAssets` task of app/build.gradle.kts copies them from `spec/content`). Screens await
 * [ready]; nothing reads the files on the main thread.
 */
class ContentRepo(scope: CoroutineScope, load: suspend () -> LoadedContent) {
    val ready: Deferred<LoadedContent> = scope.async(Dispatchers.IO) { load() }

    suspend fun get(): LoadedContent = ready.await()

    /** The content if it already finished loading (composition reads this; it never blocks). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun now(): LoadedContent? = if (ready.isCompleted && !ready.isCancelled) runCatching { ready.getCompleted() }.getOrNull() else null

    companion object {
        fun fromAssets(assets: AssetManager): LoadedContent {
            fun read(path: String) = assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = LessonContent.load { path -> read("${LoadedContent.CONTENT_DIR}/$path") }
            val voices = VoiceIndex.parse(read("${LoadedContent.VOICE_DIR}/index.json"))
            return LoadedContent(content, voices)
        }
    }
}
