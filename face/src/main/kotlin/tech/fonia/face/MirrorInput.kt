package tech.fonia.face

/**
 * What drives Foni's mouth when it mirrors the person. Tongue direction is not available: ARKit
 * only reports how far the tongue is out, and the colour heuristic of the web app was not ported.
 */
data class MirrorInput(
    val jaw: Float,
    val smile: Float,
    val round: Float,
    val puff: Float,
    val tongueOut: Boolean,
) {
    companion object {
        /**
         * A starting value. No published study measures ARKit's `tongueOut`, so the exercises keep the
         * "Am făcut" button in view.
         */
        internal const val TONGUE_OUT_THRESHOLD = 0.35f

        /** `init?(_ frame:)`: null when no face is visible. */
        operator fun invoke(frame: FaceFrame): MirrorInput? {
            if (!frame.isPresent) return null
            return MirrorInput(
                jaw = frame.jawOpen,
                smile = frame.smile,
                round = frame.round,
                puff = frame.puff,
                tongueOut = frame.tongueOut > TONGUE_OUT_THRESHOLD,
            )
        }

        /** `init?(levels:)`: null when no face is visible. */
        operator fun invoke(levels: FaceLevels): MirrorInput? = invoke(levels.snapshot())
    }
}
