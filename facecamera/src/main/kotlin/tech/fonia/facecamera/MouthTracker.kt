package tech.fonia.facecamera

import tech.fonia.face.CameraEvidence
import tech.fonia.face.CameraSpec
import tech.fonia.face.FaceMeshFrame
import tech.fonia.face.MirrorSnapshot
import tech.fonia.face.MouthLevels
import tech.fonia.face.MouthState
import tech.fonia.face.MouthStateEngine
import tech.fonia.face.PoseSpec
import tech.fonia.face.TargetMatcher
import tech.fonia.model.SoundId

/**
 * The mouth on the camera thread: [CameraFaceTracker]'s frames → [MouthStateEngine] (H2) → [TargetMatcher] for the
 * chosen [sound] → [levels] (read by `LipTargetOverlay` in the draw phase and by anyone else, lock-free) and
 * [onMouth] (the live band's `LivePipeline.postMouth(open, round, nanos)`).
 *
 * Numbers only leave a frame; the crop and the landmarks are overwritten by the next frame and never stored.
 * [attach] / [detach] are idempotent; `MirrorView` calls them with its own lifetime.
 */
class MouthTracker(
    private val tracker: CameraFaceTracker,
    spec: CameraSpec = CameraSpec.BUNDLED,
) {
    constructor(source: CameraFaceSource) : this(source.tracker)

    val levels = MouthLevels()
    val engine = MouthStateEngine(spec)
    private val matchSpec = spec.match

    /** The target sound; null shows no target ring and gives no evidence. Any thread. */
    @Volatile var sound: SoundId? = null

    /**
     * Called on the camera thread for every frame the quality gate passes: open (0…1), round (0…1) and
     * `System.nanoTime()` at publication. Wire `LivePipeline::postMouth` here. Nothing is posted while the camera
     * abstains, so the band's value ages and the band treats it as stale.
     */
    @Volatile var onMouth: ((open: Float, round: Float, nanos: Long) -> Unit)? = null

    /** Every frame's state and evidence, on the camera thread (objects reused: copy what you keep). */
    @Volatile var onState: ((MouthState, CameraEvidence) -> Unit)? = null

    /** Feed the microphone's "speaking" flag so that the automatic rest avoids speech frames. Any thread. */
    var speaking: Boolean
        get() = engine.speaking
        set(value) {
            engine.speaking = value
        }

    // Camera thread only.
    private val state = MouthState()
    private val evidence = CameraEvidence()
    private var matcher: TargetMatcher? = null
    private var resetRequested = true

    private val listener: (FaceMeshFrame) -> Unit = { frame -> process(frame) }

    fun attach() {
        resetRequested = true
        tracker.meshListener = listener
    }

    fun detach() {
        if (tracker.meshListener === listener) tracker.meshListener = null
    }

    /** Takes the rest again ("Fă o față liniștită"). Any thread. */
    fun recalibrate() = engine.recalibrate()

    /** Reads the latest snapshot into [into]; false before the first frame. Any thread. */
    fun read(into: MirrorSnapshot): Boolean = levels.read(into)

    internal fun process(frame: FaceMeshFrame) {
        if (resetRequested) {
            resetRequested = false
            engine.reset()
        }
        engine.process(frame, state)
        val target = sound
        var m = matcher
        if (target == null) {
            m = null
        } else if (m == null || m.pose.sound != target) {
            m = TargetMatcher(PoseSpec.bundled(target), matchSpec)
        }
        matcher = m
        if (m != null) {
            m.update(state, evidence)
        } else {
            evidence.sound = null
            evidence.status = tech.fonia.face.EvidenceStatus.Abstain
            evidence.abstain = tech.fonia.face.AbstainReason.NothingVisible
            evidence.matched = false
            evidence.advice = null
        }
        levels.publish { s ->
            s.imageW = frame.imageW
            s.imageH = frame.imageH
            s.hasFace = frame.isPresent && engine.measures.valid
            s.state.copyFrom(state)
            s.evidence.copyFrom(evidence)
            if (s.hasFace) s.setLips(frame)
            s.hasTarget = m != null && s.hasFace && engine.targetRings(frame, m.pose.lips, s.targetOuter, s.targetInner)
            s.restProgress = engine.calibrator.progress
        }
        if (state.isGood) onMouth?.invoke(state.open, state.round, System.nanoTime())
        onState?.invoke(state, evidence)
    }
}
