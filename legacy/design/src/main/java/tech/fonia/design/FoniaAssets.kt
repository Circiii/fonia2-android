package tech.fonia.design

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import org.json.JSONObject
import java.util.concurrent.Executors

/** One image of `images/catalog.json`: where it is and how iOS drew it. */
data class CatalogEntry(
    val folder: String,
    val file: String,
    /** `"3x"` for the scenes, null for single-scale images. */
    val scale: String?,
    /** Drawn in the colour of the text around it, like an iOS template image. */
    val template: Boolean,
    /** `"lossy"` or `"lossless"` on iOS. */
    val compression: String?,
)

/**
 * Reads the pictures the content submodule puts in the APK's assets (`images/…`) and keeps the decoded ones,
 * like `SceneCache` and the parrot layer dictionary do on iOS.
 *
 * Bitmaps live in an LRU of 180 MiB, the cost limit of the iOS `NSCache`, and the cache is emptied when the
 * system asks for memory back, as `NSCache` is. Icons are SVG and are drawn once per pixel size.
 */
class FoniaAssets private constructor(context: Context) {
    private val assets: AssetManager = context.applicationContext.assets

    val catalog: Map<String, CatalogEntry> by lazy { readCatalog() }

    private val bitmaps = object : LruCache<String, ImageBitmap>(BITMAP_BUDGET) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmapSafe()?.allocationByteCount ?: 1
    }
    private val icons = LruCache<String, ImageBitmap>(256)
    private val svgs = HashMap<String, SVG>()

    init {
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

    fun has(name: String) = catalog.containsKey(name)

    /** The decoded image if it is already in the cache; never decodes. */
    fun cached(name: String): ImageBitmap? = bitmaps.get(name)

    private val boundsCache = java.util.concurrent.ConcurrentHashMap<String, androidx.compose.ui.unit.IntSize>()

    /**
     * The pixel size of an image from its header only (no pixels decoded), so a picture still decoding off the main
     * thread keeps its place in the layout. Null when the catalog has no such image or it is a vector.
     */
    fun bounds(name: String): androidx.compose.ui.unit.IntSize? {
        boundsCache[name]?.let { return it }
        val entry = catalog[name] ?: return null
        if (entry.file.endsWith(".svg")) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        runCatching { assets.open("images/${entry.folder}/${entry.file}").use { BitmapFactory.decodeStream(it, null, options) } }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return androidx.compose.ui.unit.IntSize(options.outWidth, options.outHeight).also { boundsCache[name] = it }
    }

    /** The decoded image, from the cache when it's there. Null when the catalog has no such image. */
    fun bitmap(name: String): ImageBitmap? {
        bitmaps.get(name)?.let { return it }
        val entry = catalog[name] ?: return null
        if (entry.file.endsWith(".svg")) return null
        val decoded = assets.open("images/${entry.folder}/${entry.file}").use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            })
        } ?: return null
        // Mipmaps keep a big painting sharp when it's drawn a lot smaller, as iOS does when it downsamples.
        decoded.setHasMipMap(true)
        decoded.prepareToDraw()
        val image = decoded.asImageBitmap()
        bitmaps.put(name, image)
        return image
    }

    /** A vector icon drawn at [sizePx] × [sizePx] in black; the caller tints it, as iOS tints a template. */
    fun icon(name: String, sizePx: Int): ImageBitmap? {
        val side = sizePx.coerceAtLeast(1)
        val key = "$name@$side"
        icons.get(key)?.let { return it }
        val svg = synchronized(svgs) {
            svgs.getOrPut(name) {
                val entry = catalog[name]
                val path = if (entry != null) "images/${entry.folder}/${entry.file}" else "images/icons/$name.svg"
                assets.open(path).use { SVG.getFromInputStream(it) }.apply {
                    // The files say 24 × 24; let the viewBox fill whatever square the icon is drawn in.
                    setDocumentWidth("100%")
                    setDocumentHeight("100%")
                }
            }
        }
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        synchronized(svg) {
            svg.renderToCanvas(android.graphics.Canvas(bitmap), RenderOptions().viewPort(0f, 0f, side.toFloat(), side.toFloat()))
        }
        bitmap.prepareToDraw()
        val image = bitmap.asImageBitmap()
        icons.put(key, image)
        return image
    }

    /** Decodes [names] on a background thread, so the first frame that shows them doesn't wait. */
    fun preload(names: List<String>) {
        loader.execute {
            for (name in names) {
                if (bitmaps.get(name) == null) runCatching { bitmap(name) }
            }
        }
    }

    fun trim() {
        bitmaps.evictAll()
        icons.evictAll()
    }

    private fun readCatalog(): Map<String, CatalogEntry> {
        val text = assets.open("images/catalog.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val out = HashMap<String, CatalogEntry>(json.length())
        for (key in json.keys()) {
            val item = json.getJSONObject(key)
            out[key] = CatalogEntry(
                folder = item.getString("folder"),
                file = item.getString("file"),
                scale = if (item.isNull("scale")) null else item.optString("scale"),
                template = item.optString("rendering") == "template",
                compression = if (item.isNull("compression")) null else item.optString("compression"),
            )
        }
        return out
    }

    companion object {
        private const val BITMAP_BUDGET = 180 * 1024 * 1024
        private val loader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fonia-assets").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        @Volatile
        private var shared: FoniaAssets? = null

        fun get(context: Context): FoniaAssets =
            shared ?: synchronized(this) { shared ?: FoniaAssets(context).also { shared = it } }
    }
}

private fun ImageBitmap.asAndroidBitmapSafe(): Bitmap? = runCatching { asAndroidBitmap() }.getOrNull()

/** The asset provider for this composition; [FoniaTheme] provides it, and anything outside a theme gets the shared one. */
val LocalFoniaAssets = staticCompositionLocalOf<FoniaAssets?> { null }

@Composable
fun foniaAssets(): FoniaAssets = LocalFoniaAssets.current ?: FoniaAssets.get(LocalContext.current)
