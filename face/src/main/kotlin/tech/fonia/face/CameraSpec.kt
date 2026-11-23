package tech.fonia.face

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The camera's thresholds, read from `spec/poses/camera.json` (bundled as the Java resource `poses/camera.json`).
 * The in-code defaults equal the file; `CameraSpecTests` fails if they drift apart. All [Propunere] values of
 * docs/v2/04 §2–§5, some re-tuned on real video (output/camera/RAPORT_CAMERA.md).
 */
data class CameraSpec(
    val version: Int = 1,
    val quality: QualitySpec = QualitySpec(),
    val scale: ScaleSpec = ScaleSpec(),
    val rest: RestSpec = RestSpec(),
    val measure: MeasureSpec = MeasureSpec(),
    val smoothing: SmoothingSpec = SmoothingSpec(),
    val match: MatchSpec = MatchSpec(),
    val tongue: TongueSpec = TongueSpec(),
) {
    companion object {
        /** Parses `camera.json`; keys left out keep their defaults. */
        fun parse(text: String): CameraSpec {
            val o = Json.parseToJsonElement(text).jsonObject
            val d = CameraSpec()
            return CameraSpec(
                version = o.int("version", d.version),
                quality = o.obj("quality")?.let { q ->
                    val x = d.quality
                    QualitySpec(
                        minPresence = q.f("minPresence", x.minPresence), minMouthPx = q.f("minMouthPx", x.minMouthPx),
                        maxMouthShare = q.f("maxMouthShare", x.maxMouthShare), maxYawDeg = q.f("maxYawDeg", x.maxYawDeg),
                        maxPitchDeg = q.f("maxPitchDeg", x.maxPitchDeg), maxRollDeg = q.f("maxRollDeg", x.maxRollDeg),
                        minLumaMouth = q.f("minLumaMouth", x.minLumaMouth), maxLumaMouth = q.f("maxLumaMouth", x.maxLumaMouth),
                        maxClippedShare = q.f("maxClippedShare", x.maxClippedShare), minFps = q.f("minFps", x.minFps),
                        maxFrameGapMs = q.f("maxFrameGapMs", x.maxFrameGapMs), minInFrame = q.f("minInFrame", x.minInFrame),
                        weakAfterMs = q.f("weakAfterMs", x.weakAfterMs), goodAfterMs = q.f("goodAfterMs", x.goodAfterMs),
                        absentAfterMs = q.f("absentAfterMs", x.absentAfterMs),
                    )
                } ?: d.quality,
                scale = o.obj("scale")?.let { s ->
                    val x = d.scale
                    ScaleSpec(s.f("irisMmAdult", x.irisMmAdult), s.f("irisMmChild", x.irisMmChild), s.int("medianFrames", x.medianFrames))
                } ?: d.scale,
                rest = o.obj("rest")?.let { r ->
                    val x = d.rest
                    RestSpec(
                        minFrames = r.int("minFrames", x.minFrames), maxWidthCv = r.f("maxWidthCv", x.maxWidthCv),
                        maxApertureW0 = r.f("maxApertureW0", x.maxApertureW0), autoAfterMs = r.f("autoAfterMs", x.autoAfterMs),
                        autoFrames = r.int("autoFrames", x.autoFrames), defaultWidthRel = r.f("defaultWidthRel", x.defaultWidthRel),
                        defaultJawRel = r.f("defaultJawRel", x.defaultJawRel), defaultMouthDrop = r.f("defaultMouthDrop", x.defaultMouthDrop),
                    )
                } ?: d.rest,
                measure = o.obj("measure")?.let { m ->
                    val x = d.measure
                    MeasureSpec(
                        roundWidthSpan = m.f("roundWidthSpan", x.roundWidthSpan), roundShapeSpan = m.f("roundShapeSpan", x.roundShapeSpan),
                        roundGeomWeight = m.f("roundGeomWeight", x.roundGeomWeight), roundOpenGateStart = m.f("roundOpenGateStart", x.roundOpenGateStart),
                        roundOpenGateSpan = m.f("roundOpenGateSpan", x.roundOpenGateSpan), spreadSpan = m.f("spreadSpan", x.spreadSpan),
                        openFull = m.f("openFull", x.openFull), closedApertureW0 = m.f("closedApertureW0", x.closedApertureW0),
                        closedMm = m.f("closedMm", x.closedMm), closedAreaW0 = m.f("closedAreaW0", x.closedAreaW0),
                        smileSpread = m.f("smileSpread", x.smileSpread), smileShape = m.f("smileShape", x.smileShape),
                        stabilityRef = m.f("stabilityRef", x.stabilityRef),
                    )
                } ?: d.measure,
                smoothing = o.obj("smoothing")?.let { s ->
                    val x = d.smoothing
                    SmoothingSpec(s.f("minCutoffHz", x.minCutoffHz), s.f("beta", x.beta), s.f("dCutoffHz", x.dCutoffHz))
                } ?: d.smoothing,
                match = o.obj("match")?.let { m ->
                    val x = d.match
                    MatchSpec(m.f("onAt", x.onAt), m.f("onHoldMs", x.onHoldMs), m.f("offBelow", x.offBelow), m.f("offHoldMs", x.offHoldMs))
                } ?: d.match,
                tongue = o.obj("tongue")?.let { t ->
                    val x = d.tongue
                    TongueSpec(
                        minDeltaShare = t.f("minDeltaShare", x.minDeltaShare), minAreaW0 = t.f("minAreaW0", x.minAreaW0),
                        minApertureMm = t.f("minApertureMm", x.minApertureMm), minSeparation = t.f("minSeparation", x.minSeparation),
                        maxLipDistance = t.f("maxLipDistance", x.maxLipDistance), teethLuma = t.f("teethLuma", x.teethLuma),
                        teethSaturation = t.f("teethSaturation", x.teethSaturation), darkLuma = t.f("darkLuma", x.darkLuma),
                        voteFrames = t.int("voteFrames", x.voteFrames), onHoldMs = t.f("onHoldMs", x.onHoldMs),
                        offHoldMs = t.f("offHoldMs", x.offHoldMs), modelScoreOn = t.f("modelScoreOn", x.modelScoreOn),
                        useModelScore = t.b("useModelScore", x.useModelScore),
                        bandRatioOut = t.f("bandRatioOut", x.bandRatioOut), colourConfirm = t.f("colourConfirm", x.colourConfirm),
                        colourOnlyZones = t.b("colourOnlyZones", x.colourOnlyZones), bandOnlyRatio = t.f("bandOnlyRatio", x.bandOnlyRatio),
                    )
                } ?: d.tongue,
            )
        }

        /** The bundled `poses/camera.json`. */
        val BUNDLED: CameraSpec by lazy { parse(SpecResources.text("camera.json")) }
    }
}

/** The quality gate (docs/v2/04 §3, "Poarta de calitate"). */
data class QualitySpec(
    val minPresence: Float = 0.5f,
    /** Mouth width in analysis pixels below which the face is "too far" (640×480 analysis). */
    val minMouthPx: Float = 34f,
    /** Mouth width as a share of the image width above which the face is "too close". */
    val maxMouthShare: Float = 0.42f,
    val maxYawDeg: Float = 20f,
    val maxPitchDeg: Float = 20f,
    val maxRollDeg: Float = 25f,
    val minLumaMouth: Float = 50f,
    val maxLumaMouth: Float = 235f,
    val maxClippedShare: Float = 0.05f,
    val minFps: Float = 12f,
    val maxFrameGapMs: Float = 150f,
    /** Share of the lip and anchor points that must lie inside the image. */
    val minInFrame: Float = 0.98f,
    /** A problem must last this long before the gate turns Weak; a clean stretch this long turns it Good again. */
    val weakAfterMs: Float = 200f,
    val goodAfterMs: Float = 300f,
    /** No face for this long → Absent. */
    val absentAfterMs: Float = 150f,
)

/** Millimetre scale from the iris (U §3.4). */
data class ScaleSpec(val irisMmAdult: Float = 11.7f, val irisMmChild: Float = 10.6f, val medianFrames: Int = 15)

/** Calibration to the mouth at rest (docs/v2/04 §3, "Repausul"). */
data class RestSpec(
    val minFrames: Int = 20,
    val maxWidthCv: Float = 0.03f,
    /** Lips must be (nearly) closed at rest: aperture ≤ this share of the mouth width. */
    val maxApertureW0: Float = 0.08f,
    val autoAfterMs: Float = 4000f,
    val autoFrames: Int = 15,
    /** Population fall-backs until a rest is taken: mouth width / eye distance, chin-to-nose / eye distance. */
    val defaultWidthRel: Float = 0.80f,
    val defaultJawRel: Float = 1.05f,
    /** Mouth centre below the nose base, in eye distances. */
    val defaultMouthDrop: Float = 0.30f,
)

/** The mouth-state formulas (docs/v2/04 §3 table). */
data class MeasureSpec(
    val roundWidthSpan: Float = 0.25f,
    val roundShapeSpan: Float = 0.5f,
    val roundGeomWeight: Float = 0.6f,
    /** A wide-open jaw narrows the corners too: the width part of rounding fades out from this aperture (× W₀)… */
    val roundOpenGateStart: Float = 0.35f,
    /** …over this span. */
    val roundOpenGateSpan: Float = 0.30f,
    val spreadSpan: Float = 0.15f,
    /** Aperture (× W₀) that counts as fully open (open = 1). */
    val openFull: Float = 0.5f,
    val closedApertureW0: Float = 0.03f,
    val closedMm: Float = 1.0f,
    val closedAreaW0: Float = 0.01f,
    val smileSpread: Float = 0.5f,
    val smileShape: Float = 0.2f,
    /** Std-dev of the aperture (× W₀) over 5 frames that halves the stability. */
    val stabilityRef: Float = 0.02f,
)

/** One-Euro on the scalars. */
data class SmoothingSpec(val minCutoffHz: Float = 1.5f, val beta: Float = 0.3f, val dCutoffHz: Float = 1.0f)

/** Target match hysteresis (docs/v2/04 §4, "Potrivirea"). */
data class MatchSpec(val onAt: Float = 0.75f, val onHoldMs: Float = 300f, val offBelow: Float = 0.55f, val offHoldMs: Float = 400f)

/** The tongue-out colour heuristic (docs/v2/04 §5.1). */
data class TongueSpec(
    val minDeltaShare: Float = 0.25f,
    val minAreaW0: Float = 0.12f,
    val minApertureMm: Float = 5f,
    /** Lip and skin colours must separate at rest by this d′, else the detector says Unknown for the session. */
    val minSeparation: Float = 1.5f,
    /** A pixel is red tissue only within this Mahalanobis distance of the lip colour. */
    val maxLipDistance: Float = 3.5f,
    val teethLuma: Float = 0.9f,
    val teethSaturation: Float = 0.5f,
    val darkLuma: Float = 0.35f,
    val voteFrames: Int = 5,
    val onHoldMs: Float = 250f,
    val offHoldMs: Float = 200f,
    /** The landmark model's own tongue score that counts as "out", when [useModelScore]. */
    val modelScoreOn: Float = 0.5f,
    val useModelScore: Boolean = true,
    /**
     * Tongue between / over the lips: lower lip band ≥ this × its rest thickness (real video: 2.0× with the tongue
     * out, ≤ 1.35× in speech) together with a red-tissue gain ≥ [colourConfirm] in the "out" zone.
     */
    val bandRatioOut: Float = 1.6f,
    val colourConfirm: Float = 0.15f,
    /** Without a colour model: the band alone must reach this (speech max on real video 1.56×, tongue 2.0–2.1×). */
    val bandOnlyRatio: Float = 1.8f,
    /** Colour alone decides Up/Down/Left/Right (not validated on real video). Out never relies on colour alone. */
    val colourOnlyZones: Boolean = true,
)

/** Reads `spec/poses/…`, bundled as Java resources under `poses/`. */
internal object SpecResources {
    fun text(name: String): String {
        val stream = SpecResources::class.java.getResourceAsStream("/poses/$name")
            ?: error("spec/poses/$name is not bundled")
        return stream.use { it.readBytes().decodeToString() }
    }

    fun exists(name: String): Boolean = SpecResources::class.java.getResource("/poses/$name") != null
}

internal fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

internal fun JsonObject.f(key: String, default: Float): Float = (this[key] as? JsonPrimitive)?.floatOrNull ?: default

internal fun JsonObject.fOrNull(key: String): Float? {
    val e: JsonElement = this[key] ?: return null
    if (e is JsonNull) return null
    return (e as? JsonPrimitive)?.floatOrNull
}

internal fun JsonObject.int(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default

internal fun JsonObject.b(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.arr(key: String) = this[key]?.jsonArray
