package tech.fonia.speaking.coach

/**
 * His own gaze, measured once in six seconds (the lens, a line at the bottom of the screen, a line at the top) and
 * kept on the phone: where his reading sits when he looks into the lens, and how far down or up counts as "not the
 * lens" for him. Degrees, on the same sum of head and eyes the coach uses ([FaceCoach.rawGaze]).
 */
data class GazeCalibration(val centreX: Double, val centreY: Double, val upDeg: Double, val downDeg: Double) {
    fun encode(): String = "$centreX;$centreY;$upDeg;$downDeg"

    companion object {
        fun decode(text: String?): GazeCalibration? {
            val p = text?.split(';')?.mapNotNull { it.toDoubleOrNull() } ?: return null
            return if (p.size == 4 && p.all { it.isFinite() }) GazeCalibration(p[0], p[1], p[2], p[3]) else null
        }
    }
}

/** Collects the three steps; [result] is null when the steps cannot be told apart (then the defaults stay). */
class GazeCalibrator(private val spec: CoachSpec = CoachSpec.v1) {
    enum class Step { Lens, Bottom, Top }

    private val xs = Array(3) { ArrayList<Double>() }
    private val ys = Array(3) { ArrayList<Double>() }

    /** One measured frame during [step]; frames that fail the quality gate are left out by the caller. Camera thread. */
    @Synchronized
    fun add(step: Step, sample: FaceSample) {
        if (!sample.present) return
        xs[step.ordinal] += FaceCoach.rawGazeX(sample, spec)
        ys[step.ordinal] += FaceCoach.rawGazeY(sample, spec)
    }

    @Synchronized
    fun frames(step: Step): Int = xs[step.ordinal].size

    @Synchronized
    fun result(): GazeCalibration? {
        val c = spec.calibration
        if (xs[0].size < c.minFrames || xs[1].size < c.minFrames) return null
        val lensX = median(xs[0])
        val lensY = median(ys[0])
        val drop = lensY - median(ys[1])
        // The bottom line must read clearly lower than the lens; otherwise nothing was learned.
        if (drop < c.minSeparationDeg) return null
        val down = (drop * c.downShare).coerceIn(c.minToleranceDeg, c.maxToleranceDeg)
        val rise = if (xs[2].size >= c.minFrames) median(ys[2]) - lensY else Double.NaN
        val up = if (!rise.isNaN() && rise >= c.minSeparationDeg) (rise * c.upShare).coerceIn(c.minToleranceDeg, c.maxToleranceDeg) else spec.gaze.tolerancePitchUpDeg
        return GazeCalibration(
            centreX = lensX.coerceIn(-c.maxCentreDeg, c.maxCentreDeg),
            centreY = lensY.coerceIn(-c.maxCentreDeg, c.maxCentreDeg),
            upDeg = up,
            downDeg = down,
        )
    }

    private fun median(v: List<Double>): Double = v.sorted()[v.size / 2]
}
