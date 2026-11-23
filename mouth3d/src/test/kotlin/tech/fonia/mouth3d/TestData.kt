package tech.fonia.mouth3d

import java.io.File

/** The committed spec files, read straight from the repo (system property `fonia.root`). */
object TestData {
    val root: File = File(System.getProperty("fonia.root") ?: "..")
    val posesDir: File get() = File(root, "spec/poses/mouth3d")

    val library: PoseLibrary by lazy { PoseLibrary.load { File(posesDir, "$it.json").readText() } }
}
