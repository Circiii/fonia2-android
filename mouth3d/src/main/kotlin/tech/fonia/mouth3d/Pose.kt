package tech.fonia.mouth3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.fonia.model.SoundId

/**
 * The control parameters of the mouth (spec/poses/mouth3d/\*.json, key = [key]). The four lip/jaw channels drive the
 * lips, jaw and teeth of [tech.fonia.mouth3d.style.HeadModel]; the tongue channels pick and blend the side-view tongue
 * shapes ([tech.fonia.mouth3d.style.TongueBlend]) the 3D tongue is swept along.
 */
enum class PoseChannel(val key: String, val min: Float, val max: Float) {
    JawOpen("jawOpen", 0f, 1f), // 1 = incisors 14 mm apart
    LipRound("lipRound", 0f, 1f), // rounded and pushed forward
    LipSpread("lipSpread", 0f, 1f), // smile, corners out
    LipPart("lipPart", 0f, 1f), // lips apart, teeth show, jaw unchanged
    TongueTip("tongueTip", -1f, 1f), // +1 tip curled up (~90°), -1 tip down behind the lower teeth
    TongueForward("tongueForward", -1f, 1f), // +1 forward, -1 pulled back
    TongueRaise("tongueRaise", -1f, 1f), // body (dorsum and blade) up
    TongueGroove("tongueGroove", 0f, 1f), // narrow central groove (S)
    TongueCup("tongueCup", 0f, 1f), // sides up, wide "little cup" (Ș)
    TongueSidesDown("tongueSidesDown", 0f, 1f), // lateral edges lowered (L)
    ;

    companion object {
        fun of(key: String): PoseChannel? = entries.firstOrNull { it.key == key }
    }
}

/** A value per [PoseChannel]. Immutable from the outside. */
class Pose internal constructor(internal val v: FloatArray) {
    operator fun get(c: PoseChannel): Float = v[c.ordinal]

    fun with(c: PoseChannel, value: Float): Pose = Pose(v.copyOf().also { it[c.ordinal] = value.coerceIn(c.min, c.max) })

    fun lerp(to: Pose, t: Float): Pose = Pose(FloatArray(v.size) { v[it] + (to.v[it] - v[it]) * t })

    fun toMap(): Map<String, Float> = PoseChannel.entries.associate { it.key to v[it.ordinal] }

    override fun equals(other: Any?): Boolean = other is Pose && other.v.contentEquals(v)
    override fun hashCode(): Int = v.contentHashCode()
    override fun toString(): String = toMap().filterValues { it != 0f }.toString()

    companion object {
        val Zero = Pose(FloatArray(PoseChannel.entries.size))

        fun of(values: Map<PoseChannel, Float>): Pose =
            Pose(FloatArray(PoseChannel.entries.size) { i -> PoseChannel.entries[i].let { (values[it] ?: 0f).coerceIn(it.min, it.max) } })
    }
}

/** How fast the springs move toward a key (ω in rad/s, docs/v2/04 §9: normal 18, soft 10). */
enum class SpringSpeed(val omega: Float) { Soft(10f), Normal(18f), Fast(34f) }

/** One step of a pose's short animation: from [t] (seconds into the loop) the target is [pose]. */
data class PoseKey(val t: Float, val pose: Pose, val speed: SpringSpeed)

/** A looping key animation (Ț, CE/CI, GE/GI: closure → release; L: contact → release). */
data class PoseAnimation(val loopSec: Float, val keys: List<PoseKey>) {
    init {
        require(keys.isNotEmpty() && keys.first().t == 0f) { "an animation starts with a key at t = 0" }
        require(keys.zipWithNext().all { (a, b) -> b.t > a.t } && keys.last().t < loopSec) { "keys must be increasing and inside the loop" }
    }

    /** The key in force at [timeSec] (looping). */
    fun keyAt(timeSec: Float): PoseKey {
        val t = ((timeSec % loopSec) + loopSec) % loopSec
        return keys.last { it.t <= t }
    }
}

/** A small fast oscillation added after the springs (R: the tip's flutter, shown slowed down). */
data class Flutter(val channel: PoseChannel, val amplitude: Float, val hz: Float) {
    fun offset(timeSec: Float): Float = amplitude * kotlin.math.sin(2.0 * Math.PI * hz * timeSec).toFloat()
}

data class CameraPreset(val yawDeg: Float, val pitchDeg: Float, val zoom: Float)

/** How the air leaves the mouth in the inside view's airflow. */
enum class AirStream {
    /** S, Z, Ț: a narrow, fast stream along the middle of the tongue, out between the teeth. */
    Narrow,
    /** Ș, J, CE, GE: a wide, slower stream through the cup of the tongue and the funnel of the lips. */
    Wide,
    /** R: the air goes out in pulses, in time with the tip's flutter. */
    Pulse,
    /** L: the air leaves the middle before the tip and goes round the sides of the tongue. */
    Lateral,
}

/** The air of a sound: stream, temperature (cold = S-like, warm = Ș-like) and whether a closure holds it first. */
data class AirSpec(val stream: AirStream, val warm: Boolean, val stop: Boolean)

/**
 * The parts the guide's step chips name (`steps[].icon`: lips, teeth, tongue, air). Passing one to [Mouth3D]'s
 * `highlight` makes it glow softly in both views.
 */
enum class Articulator {
    Lips, Teeth, Tongue, Air;

    companion object {
        /** The articulator of a step icon (`lips`, `teeth`, `tongue`, `air`), or null. */
        fun of(icon: String): Articulator? = entries.firstOrNull { it.name.equals(icon, ignoreCase = true) }
    }
}

/** One file of spec/poses/mouth3d. */
data class PoseSpec(
    val key: String,
    val sound: SoundId?,
    val pose: Pose,
    val animation: PoseAnimation?,
    val flutter: Flutter?,
    val front: CameraPreset,
    val inside: CameraPreset,
    /** N17: the tongue cut-away is shown to children only once a speech therapist approved this pose. */
    val insideApproved: Boolean,
    val reviewStatus: String,
    val captionKey: String,
    val caption: String,
    /** TalkBack text (Romanian). */
    val description: String,
    val therapistNote: String,
    /** The guided sequence of the sound guide (2–3 steps, icon + at most two words); empty when the file has none. */
    val steps: List<PoseStep> = emptyList(),
    /** The air shown in the inside view; null = none drawn (rest). */
    val air: AirSpec? = null,
    /** The voice is on (Z, J, GE, R, L): a vibrating mark at the throat. */
    val voiced: Boolean = false,
)

/**
 * One step of the guided sequence: [icon] (`lips`, `teeth`, `tongue`, `air`), a label of at most two words, and the
 * parts it moves ([PoseGroup]); a step that moves nothing new (`air`) starts the pose's own animation or flutter.
 */
data class PoseStep(val icon: String, val label: String, val reveals: Set<PoseGroup>) {
    /** The part this step is about, for [Mouth3D]'s `highlight`. */
    val articulator: Articulator? get() = Articulator.of(icon)
}

/** The pose file name of each sound (`spec/poses/mouth3d/<poseKey>.json`). */
val SoundId.poseKey: String
    get() = when (this) {
        SoundId.S -> "s"
        SoundId.Sh -> "sh"
        SoundId.Z -> "z"
        SoundId.J -> "j"
        SoundId.Ts -> "ts"
        SoundId.Ce -> "ce"
        SoundId.Ge -> "ge"
        SoundId.R -> "r"
        SoundId.L -> "l"
    }

/** All poses: `rest` plus one per [SoundId]. */
class PoseLibrary(val specs: Map<String, PoseSpec>) {
    val rest: PoseSpec = specs[REST] ?: error("pose library lacks $REST")

    init {
        SoundId.entries.forEach { require(it.poseKey in specs) { "pose library lacks ${it.poseKey}" } }
    }

    fun forSound(sound: SoundId?): PoseSpec = if (sound == null) rest else specs.getValue(sound.poseKey)

    companion object {
        const val REST = "rest"
        val FILES: List<String> = listOf(REST) + SoundId.entries.map { it.poseKey }

        /** [read] gives the text of `<key>.json` for every key of [FILES]. */
        fun load(read: (String) -> String): PoseLibrary = PoseLibrary(FILES.associateWith { parse(it, read(it)) })

        fun parse(key: String, text: String): PoseSpec {
            val o = Json.parseToJsonElement(text).jsonObject
            val soundRaw = o.getValue("sound").jsonPrimitive.content
            require(soundRaw == key) { "$key.json says sound=$soundRaw" }
            val base = params(o.getValue("params").jsonObject, Pose.Zero)
            val anim = o["animation"]?.takeUnless { it is JsonNull }?.jsonObject?.let { a ->
                PoseAnimation(
                    loopSec = a.getValue("loopSec").jsonPrimitive.float,
                    keys = a.getValue("keys").jsonArray.map { k ->
                        val ko = k.jsonObject
                        PoseKey(
                            t = ko.getValue("t").jsonPrimitive.float,
                            pose = params(ko["params"]?.jsonObject ?: JsonObject(emptyMap()), base),
                            speed = ko["spring"]?.jsonPrimitive?.content?.let { s -> SpringSpeed.entries.first { it.name.equals(s, true) } } ?: SpringSpeed.Normal,
                        )
                    },
                )
            }
            val flutter = o["flutter"]?.takeUnless { it is JsonNull }?.jsonObject?.let { f ->
                Flutter(
                    channel = PoseChannel.of(f.getValue("channel").jsonPrimitive.content) ?: error("$key: unknown flutter channel"),
                    amplitude = f.getValue("amplitude").jsonPrimitive.float,
                    hz = f.getValue("hz").jsonPrimitive.float,
                )
            }
            val cam = o.getValue("camera").jsonObject
            fun preset(name: String) = cam.getValue(name).jsonObject.let {
                CameraPreset(it.getValue("yawDeg").jsonPrimitive.float, it.getValue("pitchDeg").jsonPrimitive.float, it["zoom"]?.jsonPrimitive?.float ?: 1f)
            }
            return PoseSpec(
                key = key,
                sound = SoundId.entries.firstOrNull { it.poseKey == key },
                pose = base,
                animation = anim,
                flutter = flutter,
                front = preset("front"),
                inside = preset("inside"),
                insideApproved = o["insideApproved"]?.jsonPrimitive?.boolean ?: false,
                reviewStatus = o["review"]?.jsonObject?.get("status")?.jsonPrimitive?.content ?: "draft",
                captionKey = o.getValue("captionKey").jsonPrimitive.content,
                caption = o.getValue("caption").jsonPrimitive.content,
                description = o.getValue("description").jsonPrimitive.content,
                therapistNote = o.getValue("therapistNote").jsonPrimitive.content,
                steps = o["steps"]?.takeUnless { it is JsonNull }?.jsonArray?.map { st ->
                    val so = st.jsonObject
                    PoseStep(
                        icon = so.getValue("icon").jsonPrimitive.content,
                        label = so.getValue("label").jsonPrimitive.content,
                        reveals = so["reveals"]?.jsonArray?.map { r -> PoseGroup.of(r.jsonPrimitive.content) ?: error("$key: unknown group $r") }?.toSet() ?: emptySet(),
                    )
                } ?: emptyList(),
                air = o["air"]?.takeUnless { it is JsonNull }?.jsonObject?.let { a ->
                    val name = a.getValue("stream").jsonPrimitive.content
                    AirSpec(
                        stream = AirStream.entries.firstOrNull { it.name.equals(name, true) } ?: error("$key: unknown air stream $name"),
                        warm = a["warm"]?.jsonPrimitive?.boolean ?: false,
                        stop = a["stop"]?.jsonPrimitive?.boolean ?: false,
                    )
                },
                voiced = o["voiced"]?.jsonPrimitive?.boolean ?: false,
            )
        }

        private fun params(o: JsonObject, base: Pose): Pose {
            val v = base.v.copyOf()
            for ((k, value) in o) {
                val c = PoseChannel.of(k) ?: error("unknown pose channel $k")
                val f = value.jsonPrimitive.float
                require(f in c.min..c.max) { "$k = $f outside ${c.min}..${c.max}" }
                v[c.ordinal] = f
            }
            return Pose(v)
        }
    }
}
