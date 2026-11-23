package tech.fonia.designsystem.art

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fonia's bundled art (`assets/fonia/…`, copied from fonia-content 66c53b7; licences in `assets/fonia/licenses`):
 * Foni's 14 layers (`foni/`), the word pictures (`pictures/<emoji code>.png` + `pictures/words.json`), the scenes
 * (`scenes/scene-<name>[-tall|-wide|-page].jpg`).
 *
 * Everything decodes off the main thread (2 IO workers), downsampled to the size it is shown at, into one LRU
 * (48 MiB, emptied when the system asks for memory). Composables use [WordPicture], [SceneBackdrop] and
 * [tech.fonia.designsystem.foni.FoniMascot]; call [preload] ahead of a screen to have its art ready on the first frame.
 */
object FoniaArtAssets {
    private const val ROOT = "fonia"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val rigs = HashMap<Int, FoniArt>()

    @Volatile private var words: Map<String, String>? = null
    @Volatile private var pictureCodes: Set<String>? = null
    @Volatile private var callbacksRegistered = false

    // ---------------------------------------------------------------------------------------------- generic

    /** A bitmap of `assets/fonia/[path]`, decoded at least [minSidePx] on its shorter side (0 = full size). */
    suspend fun bitmap(context: Context, path: String, minSidePx: Int = 0): Bitmap? = withContext(io) { decode(context, path, minSidePx) }

    /** The cached bitmap, or null when it has not been decoded yet (never decodes). */
    fun cached(path: String, minSidePx: Int = 0): Bitmap? = cache.get(key(path, sampleFor(path, minSidePx)))

    /** Decodes [paths] in the background (relative to `assets/fonia`), e.g. the scene and pictures of the next screen. */
    fun preload(context: Context, paths: List<String>, minSidePx: Int = 0) {
        val app = context.applicationContext
        scope.launch { for (p in paths) runCatching { decode(app, p, minSidePx) } }
    }

    fun trim() {
        cache.evictAll()
        synchronized(rigs) { rigs.clear() }
    }

    // ---------------------------------------------------------------------------------------------- Foni

    /** Foni's layers, cropped and downsampled for a mascot [sizePx] wide. */
    suspend fun foni(context: Context, sizePx: Int): FoniArt? = withContext(io) {
        val sample = foniSample(sizePx)
        synchronized(rigs) { rigs[sample] }?.let { return@withContext it }
        register(context)
        val assets = context.applicationContext.assets
        val layers = arrayOfNulls<Bitmap>(FoniArt.LAYERS.size)
        val rects = Array(FoniArt.LAYERS.size) { i -> FoniArt.RECTS[i] }
        for (i in FoniArt.LAYERS.indices) {
            val name = FoniArt.LAYERS[i]
            val r = rects[i]
            layers[i] = runCatching {
                assets.open("$ROOT/foni/$name.png").use { input ->
                    @Suppress("DEPRECATION")
                    val dec = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
                    try {
                        dec.decodeRegion(Rect(r[0], r[1], r[2], r[3]), BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })?.also { it.prepareToDraw() }
                    } finally { dec.recycle() }
                }
            }.getOrNull()
        }
        val zzz = runCatching { decode(context, "foni/icon-zzz.png", 0, sampleOverride = sample * 2) }.getOrNull()
        if (layers.any { it == null }) return@withContext null
        val art = FoniArt(layers.requireNoNulls(), rects, zzz)
        synchronized(rigs) { rigs[sample] = art }
        art
    }

    /** The already-loaded rig for [sizePx], if any (lets a second mascot draw on its first frame). */
    fun foniCached(sizePx: Int): FoniArt? = synchronized(rigs) { rigs[foniSample(sizePx)] }

    private fun foniSample(sizePx: Int): Int = if (sizePx in 1..380) 2 else 1

    // ---------------------------------------------------------------------------------------------- pictures

    /** Normalises a word the way `words.json` keys are written: trimmed, lower case, comma-below Ș/Ț. */
    fun normalizeWord(word: String): String =
        word.trim().lowercase().replace('ş', 'ș').replace('ţ', 'ț')

    /** The emoji code of a string of emoji (`"🍎"` → `"1f34e"`), variation selectors dropped. */
    fun emojiCode(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFE0F || Character.isWhitespace(cp)) continue
            if (sb.isNotEmpty()) sb.append('-')
            sb.append(Integer.toHexString(cp))
        }
        return sb.toString()
    }

    /**
     * The picture code for [key]: a code (`"1f34e"`), an emoji (`"🍎"`) or a Romanian word (`"măr"`). Null when no
     * picture exists (then [WordPicture] shows its letter tile).
     */
    suspend fun pictureCode(context: Context, key: String): String? = withContext(io) { resolve(context, key) }

    internal fun resolve(context: Context, key: String): String? {
        ensureIndex(context)
        val codes = pictureCodes ?: return null
        val k = key.trim()
        if (k.isEmpty()) return null
        if (k in codes) return k
        val asEmoji = emojiCode(k)
        if (asEmoji in codes) return asEmoji
        return words?.get(normalizeWord(k))
    }

    suspend fun picture(context: Context, code: String, minSidePx: Int): Bitmap? = bitmap(context, "pictures/$code.png", minSidePx)

    private fun ensureIndex(context: Context) {
        if (pictureCodes != null) return
        synchronized(this) {
            if (pictureCodes != null) return
            val assets = context.applicationContext.assets
            val files = assets.list("$ROOT/pictures").orEmpty()
            val map = HashMap<String, String>()
            runCatching {
                val json = JSONObject(assets.open("$ROOT/pictures/words.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                for (w in json.keys()) map[w] = json.getString(w)
            }
            words = map
            pictureCodes = files.filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.toHashSet()
        }
    }

    // ---------------------------------------------------------------------------------------------- scenes

    /** The scene names that exist (`lesson`, `cold`, `warm`, `tip`, `night`, `start`, `sounds`, …). */
    val scenes: List<String> = listOf(
        "lesson", "start", "cold", "warm", "tip", "night", "sounds", "warmup", "play", "journal", "guide", "stories",
        "english", "diction", "speaking", "melody",
    )

    /**
     * The file of [scene] for a box of [aspect] (width / height): `-page` for a tall page, `-tall` for a squarish box,
     * the plain one for a card, `-wide` for a strip. [scene] may also name a file (`scene-cold-wide`).
     */
    fun sceneFile(scene: String, aspect: Float): String {
        val s = scene.removePrefix("scene-").removeSuffix(".jpg")
        if (s.endsWith("-page") || s.endsWith("-tall") || s.endsWith("-wide")) return "scenes/scene-$s.jpg"
        val shape = when {
            aspect < 0.75f -> "-page"
            aspect < 1.3f -> "-tall"
            aspect < 2.2f -> ""
            else -> "-wide"
        }
        return "scenes/scene-$s$shape.jpg"
    }

    // ---------------------------------------------------------------------------------------------- decoding

    private fun key(path: String, sample: Int) = "$path@$sample"

    private val sizes = HashMap<String, IntArray>()

    private fun sampleFor(path: String, minSidePx: Int): Int {
        if (minSidePx <= 0) return 1
        val wh = synchronized(sizes) { sizes[path] } ?: return 1
        var s = 1
        while (minOf(wh[0], wh[1]) / (s * 2) >= minSidePx) s *= 2
        return s
    }

    private fun decode(context: Context, path: String, minSidePx: Int, sampleOverride: Int = 0): Bitmap? {
        register(context)
        val assets = context.applicationContext.assets
        val full = "$ROOT/$path"
        if (synchronized(sizes) { sizes[path] } == null) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { assets.open(full).use { BitmapFactory.decodeStream(it, null, o) } }
            if (o.outWidth <= 0) return null
            synchronized(sizes) { sizes[path] = intArrayOf(o.outWidth, o.outHeight) }
        }
        val sample = if (sampleOverride > 0) sampleOverride else sampleFor(path, minSidePx)
        val k = key(path, sample)
        cache.get(k)?.let { return it }
        val bmp = runCatching {
            assets.open(full).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        }.getOrNull() ?: return null
        bmp.prepareToDraw()
        cache.put(k, bmp)
        return bmp
    }

    private fun register(context: Context) {
        if (callbacksRegistered) return
        synchronized(this) {
            if (callbacksRegistered) return
            callbacksRegistered = true
            context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    @Suppress("DEPRECATION")
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim()
                }
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = trim()
            })
        }
    }
}

/**
 * Foni's layers as loaded: one cropped bitmap per layer and where it sits in the 720 px square the art was drawn in
 * (the legacy rig's 35 × 35 grid: 1 grid unit = 720 / 35 px).
 */
class FoniArt internal constructor(
    internal val layers: Array<Bitmap>,
    /** l, t, r, b in the 720 px square, per layer. */
    internal val rects: Array<IntArray>,
    internal val zzz: Bitmap?,
) {
    companion object {
        internal val LAYERS = arrayOf(
            "parrot-leg-far", "parrot-tail", "parrot-leg-near", "parrot-body", "parrot-wing-shadow", "parrot-wing",
            "parrot-head", "parrot-eye-open", "parrot-eye-calm", "parrot-eye-smile", "parrot-mouth", "parrot-beak-low",
            "parrot-beak-up",
        )
        // Alpha bounding boxes (+2 px) of the 720 × 720 layers, measured once from fonia-content 66c53b7.
        internal val RECTS = arrayOf(
            intArrayOf(323, 530, 430, 692), intArrayOf(453, 486, 638, 620), intArrayOf(253, 533, 360, 692),
            intArrayOf(131, 260, 476, 620), intArrayOf(225, 295, 470, 626), intArrayOf(249, 270, 494, 620),
            intArrayOf(137, 100, 428, 371), intArrayOf(254, 204, 298, 248), intArrayOf(240, 210, 311, 241),
            intArrayOf(240, 208, 311, 240), intArrayOf(152, 223, 229, 275), intArrayOf(159, 185, 236, 263),
            intArrayOf(133, 175, 236, 270),
        )
        internal const val LEG_FAR = 0
        internal const val TAIL = 1
        internal const val LEG_NEAR = 2
        internal const val BODY = 3
        internal const val WING_SHADOW = 4
        internal const val WING = 5
        internal const val HEAD = 6
        internal const val EYE_OPEN = 7
        internal const val EYE_CALM = 8
        internal const val EYE_SMILE = 9
        internal const val MOUTH = 10
        internal const val BEAK_LOW = 11
        internal const val BEAK_UP = 12
    }
}
