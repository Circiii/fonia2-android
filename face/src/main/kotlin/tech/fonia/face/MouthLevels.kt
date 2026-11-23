package tech.fonia.face

import tech.fonia.face.FaceMeshIndices as I

/**
 * What the mirror draws and the session reads, one frame: the mouth state, the evidence for the current target, the
 * child's lip rings and the target rings (normalised, unmirrored image coordinates, 20 × (x, y) each).
 */
class MirrorSnapshot {
    var seq: Long = 0
    var imageW: Int = 0
    var imageH: Int = 0
    var hasFace: Boolean = false
    var hasTarget: Boolean = false
    val state = MouthState()
    val evidence = CameraEvidence()
    val lipsOuter = FloatArray(I.RING * 2)
    val lipsInner = FloatArray(I.RING * 2)
    val targetOuter = FloatArray(I.RING * 2)
    val targetInner = FloatArray(I.RING * 2)

    /** Rest calibration progress 0…1. */
    var restProgress: Float = 0f

    fun copyFrom(o: MirrorSnapshot) {
        seq = o.seq; imageW = o.imageW; imageH = o.imageH; hasFace = o.hasFace; hasTarget = o.hasTarget
        state.copyFrom(o.state); evidence.copyFrom(o.evidence)
        o.lipsOuter.copyInto(lipsOuter); o.lipsInner.copyInto(lipsInner)
        o.targetOuter.copyInto(targetOuter); o.targetInner.copyInto(targetInner)
        restProgress = o.restProgress
    }

    /** The child's rings from a frame. */
    fun setLips(f: FaceMeshFrame) {
        for (k in 0 until I.RING) {
            lipsOuter[k * 2] = f.lipsOuter[k * 3]
            lipsOuter[k * 2 + 1] = f.lipsOuter[k * 3 + 1]
            lipsInner[k * 2] = f.lipsInner[k * 3]
            lipsInner[k * 2 + 1] = f.lipsInner[k * 3 + 1]
        }
    }
}

/**
 * The latest [MirrorSnapshot], written by the camera thread and read from any thread (the draw phase, the audio
 * analysis thread), without locks or allocation: a sequence lock around one shared snapshot. A reader copies into
 * its own snapshot and retries if a write overlapped.
 */
class MouthLevels {
    @Volatile private var seq = 0L
    private val shared = MirrorSnapshot()

    /** Writer side (one thread): fill [MirrorSnapshot] in [block]. */
    inline fun publish(block: (MirrorSnapshot) -> Unit) {
        beginWrite().also(block)
        endWrite()
    }

    @PublishedApi
    internal fun beginWrite(): MirrorSnapshot {
        seq++ // odd: writing
        return shared
    }

    @PublishedApi
    internal fun endWrite() {
        shared.seq = seq + 1
        seq++ // even: stable
    }

    /** Copies the latest snapshot into [into]; false if nothing was ever published. */
    fun read(into: MirrorSnapshot): Boolean {
        while (true) {
            val before = seq
            if (before and 1L == 1L) {
                Thread.onSpinWait()
                continue
            }
            if (before == 0L) return false
            into.copyFrom(shared)
            if (seq == before) return true
        }
    }

    /** Sequence number of the last complete publish (0 = none); cheap change detection for the draw loop. */
    val version: Long get() = seq and 1L.inv()
}
