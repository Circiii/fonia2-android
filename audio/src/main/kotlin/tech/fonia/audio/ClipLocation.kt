package tech.fonia.audio

import android.content.res.AssetManager
import java.io.FileInputStream
import java.io.InputStream

/**
 * Where a clip lives. iOS passes file `URL`s; Android's voice clips are app assets, which have no file
 * path, so a location is either a file or an asset entry (`VoiceIndex.clipUrl` gives the asset path).
 */
sealed interface ClipLocation {
    /** The last path component, for logs. */
    val name: String

    /** The location of [child] inside this folder. */
    fun resolve(child: String): ClipLocation

    fun open(): InputStream

    data class File(val file: java.io.File) : ClipLocation {
        override val name: String get() = file.name

        override fun resolve(child: String): ClipLocation = File(java.io.File(file, child))

        override fun open(): InputStream = FileInputStream(file)
    }

    data class Asset(val assets: AssetManager, val path: String) : ClipLocation {
        override val name: String get() = path.substringAfterLast('/')

        override fun resolve(child: String): ClipLocation = Asset(assets, if (path.isEmpty()) child else "${path.trimEnd('/')}/$child")

        override fun open(): InputStream = assets.open(path)
    }
}
