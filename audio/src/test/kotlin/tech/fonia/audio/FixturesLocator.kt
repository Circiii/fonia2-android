package tech.fonia.audio

import java.io.File

/** On the JVM the fixtures are read in place; build.gradle.kts passes the folder. */
object FixturesLocator {
    val directory: File by lazy {
        File(checkNotNull(System.getProperty("fonia.audio.fixtures")) { "fonia.audio.fixtures is not set" })
    }
}
