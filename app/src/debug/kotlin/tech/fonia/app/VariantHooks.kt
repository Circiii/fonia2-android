package tech.fonia.app

import android.content.Context
import tech.fonia.app.mirror.FaceFixture
import tech.fonia.audio.CaptureSource
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.FixtureSource
import tech.fonia.live.LiveTarget
import tech.fonia.model.SoundId

/** Debug build: launch flags are honoured, the fixture deck and the lab exist. */
object VariantHooks {
    const val debug = true

    fun takeSources(context: Context, flags: LaunchFlags): TakeSources? =
        flags.fixture?.let { FixtureDeck(context.applicationContext, it) }

    /** `--es face fixture`: MIRROR from still portraits through the real landmark pipeline ([FixtureFace]). */
    fun faceFixture(context: Context, flags: LaunchFlags): FaceFixture? =
        if (flags.faceFixture) FixtureFace(context.applicationContext) else null
}

/**
 * Plays a WAV from the debug assets (`spec/fixtures/audio`) in real time instead of the microphone. A plain name
 * (`s_hold`) serves every take. A voice family (`s`, `sh`) or `auto` serves, per take: `<sound>_hold.wav` for a `hold`
 * item when that sound has one (`s_hold`, `sh_hold`, `z_hold`, `j_hold`, `ts_hold`, `ce_hold`, `ge_hold`, `r_hold`,
 * `l_hold`; synthetic, spec/fixtures/make_fixtures.py), else the family's; `sarah_<sound>.wav` for spoken items when it
 * exists (`sarah_s`, `sarah_sh`), else the family's. Public-speaking takes ([beforeSpeaking]) get `fillers.wav`.
 */
class FixtureDeck(private val context: Context, private val name: String) : TakeSources {
    @Volatile private var type = "hold"
    @Volatile private var sound: SoundId = SoundId.S
    @Volatile private var speaking = false
    private val available: Set<String> by lazy { context.assets.list("")?.toSet().orEmpty() }

    override val label: String get() = "fixture:${clipName()}"

    override fun beforeTake(sound: SoundId, type: String) {
        this.sound = sound
        this.type = type
        speaking = false
    }

    override fun beforeSpeaking() {
        speaking = true
    }

    private fun slug(s: SoundId) = when (s) { SoundId.Sh -> "sh"; SoundId.Ts -> "ts"; else -> s.raw }

    private fun clipName(): String {
        val family = name in setOf("s", "sh", "auto")
        if (speaking && (family || name == "fillers")) return "fillers"
        if (!family) return name
        val fam = if (name == "auto") "s" else name
        return if (type == "hold") {
            "${slug(sound)}_hold".takeIf { "$it.wav" in available } ?: "${fam}_hold"
        } else {
            "sarah_${slug(sound)}".takeIf { "$it.wav" in available } ?: "sarah_$fam"
        }
    }

    override fun source(target: LiveTarget): CaptureSource =
        FixtureSource(ClipLocation.Asset(context.assets, "${clipName()}.wav"), FixtureSource.Pacing.RealTime)
}
