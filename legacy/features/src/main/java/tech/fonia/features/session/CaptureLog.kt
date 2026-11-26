package tech.fonia.features.session

import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.ml.label
import tech.fonia.store.JSONValue

/**
 * How a take was heard, kept next to the attempt in `detail["capture"]` (iOS `CaptureLog`): the microphone, the sample
 * rate, the DSP settings the verdict came from and, when its opinion was used, the model. A new microphone or a new
 * judge can move the phone's answers while the speech stays the same, and this is how the two are told apart later.
 * Short names and numbers only, no voice.
 */
object CaptureLog {
    const val KEY = "capture"

    /**
     * The app's version and build, „1.4 (212)”, so a take can be read against the code that judged it. Set once when
     * the app starts ([tech.fonia.features.app.AppModel.live]); null in tests and before that.
     */
    @Volatile
    var appVersion: String? = null

    /**
     * What one take was heard with. [thresholds] are the ones the take was measured with (`AudioHub.analysisThresholds`),
     * so a narrow-band microphone shows as `v1-noSibilants`, the same label as in the pilot log. [model] is the label of
     * the model whose opinion went into the verdict, if any. [teethOut] marks a take said while an adult had noted the
     * front teeth out ([FrontTeeth]).
     */
    fun entry(
        microphone: String,
        sampleRate: Double,
        thresholds: DSPThresholds,
        model: String?,
        app: String?,
        teethOut: Boolean = false,
    ): JSONValue {
        val entry = linkedMapOf<String, JSONValue>(
            "mic" to JSONValue.String(microphone),
            "rateHz" to JSONValue.Number(Math.round(sampleRate).toDouble()),
            "dsp" to JSONValue.String(thresholds.label()),
        )
        if (model != null) entry["model"] = JSONValue.String(model)
        if (app != null) entry["app"] = JSONValue.String(app)
        if (teethOut) entry["teethOut"] = JSONValue.Bool(true)
        return JSONValue.Object(entry)
    }

    /** A take's measures with its capture added under [KEY]. */
    fun adding(capture: JSONValue, to: Map<String, JSONValue>?): Map<String, JSONValue> = (to.orEmpty()) + (KEY to capture)

    /** The microphone a saved attempt came from; null for attempts saved before it was kept. */
    fun microphone(detail: Map<String, JSONValue>?): String? {
        val capture = detail?.get(KEY) as? JSONValue.Object ?: return null
        return (capture.value["mic"] as? JSONValue.String)?.value
    }

    /** Whether a saved attempt was said while the front teeth were out. */
    fun teethOut(detail: Map<String, JSONValue>?): Boolean {
        val capture = detail?.get(KEY) as? JSONValue.Object ?: return false
        return (capture.value["teethOut"] as? JSONValue.Bool)?.value == true
    }
}
