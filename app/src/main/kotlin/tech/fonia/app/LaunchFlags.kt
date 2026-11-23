package tech.fonia.app

import android.content.Intent
import tech.fonia.model.SoundId

/**
 * Launch extras (docs/v2/02 §10). Honoured in debug builds only ([VariantHooks.debug]); a release build ignores them
 * and always starts on PATH with the microphone.
 *
 * ```
 * adb shell am start -S -n tech.fonia.app/.MainActivity --es fixture s_hold --es screen lesson --es sound s
 * ```
 * - `fixture`: a WAV in the debug assets (`s_hold`, `sh_hold`, `sarah_s`, `sarah_sh`) used for every take, or a voice
 *   family (`s`, `sh`): `<family>_hold` for `Hold`, `sarah_<family>` for `Words`.
 * - `screen`: `path` (default), `lesson`, `end`, `lab`, `onboarding`, `adult` („Cum merge”), `calibrate`, `settings`,
 *   `teen`; `sound`: `s`, `ș` / `sh`, `z`, `j`, `ț` / `ts`, `ce`, `ge`, `r`, `l`.
 * - `gate`: put the AdultGate in front of `adult` / `calibrate` / `settings` (they open directly otherwise).
 * - `step`: `words` starts the lesson at the `Words` step.
 * - `talker`: `child`, `adultHigh`, `adultLow` — the voice the judge compares with, set before the screen opens.
 * - `demo`: the scripted, hands-free demo for filming (implies `fixture s` when none is given; starts at the onboarding,
 *   with settings kept in memory only).
 * - `lab`: open the letter lab. `reduceMotion`: force reduced motion.
 * - `face`: `fixture` feeds MIRROR from still portraits in the debug assets through the real landmark pipeline
 *   instead of the camera (`--es screen mirror --es sound sh --es face fixture`).
 * - `screen=house` opens the sound house of `sound`; `screen=exercise --es exercise <card>` one exercise card of it.
 * - `screen` also takes `mirror`, `noum`, `minute`; `fixture=fillers` feeds the speaking screens a take with fillers
 *   (they then start and play by themselves).
 */
data class LaunchFlags(
    val fixture: String? = null,
    val screen: String? = null,
    val sound: SoundId? = null,
    val demo: Boolean = false,
    val lab: Boolean = false,
    val reduceMotion: Boolean = false,
    val gate: Boolean = false,
    val talker: String? = null,
    /** `words`: the lesson starts at the `Words` step (fixture checks of the word-level judge). */
    val step: String? = null,
    /** `fixture`: MIRROR's face comes from still images through the real landmark pipeline (debug only). */
    val face: String? = null,
    /** false (`--ez whisper false`): the speaking screens run acoustic-only even when the model is on the phone. */
    val whisper: Boolean = true,
    /** `--es screen exercise --es exercise hold|listen|syllables|words|pairs|sentence|twister|retell|mission|mirror|mouth3d`. */
    val exercise: String? = null,
) {
    val fixtureMode: Boolean get() = fixture != null
    val faceFixture: Boolean get() = face == "fixture"

    companion object {
        fun parse(intent: Intent?, debug: Boolean): LaunchFlags {
            if (intent == null || !debug) return LaunchFlags()
            val demo = intent.getBooleanExtra("demo", false)
            val fixture = intent.getStringExtra("fixture") ?: if (demo) "s" else null
            return LaunchFlags(
                fixture = fixture?.removeSuffix(".wav"),
                screen = intent.getStringExtra("screen"),
                sound = intent.getStringExtra("sound")?.let(::soundOf),
                demo = demo,
                lab = intent.getBooleanExtra("lab", false),
                reduceMotion = intent.getBooleanExtra("reduceMotion", false),
                gate = intent.getBooleanExtra("gate", false),
                // the fixture voices are an adult woman: the film declares an adult talker so the adult-voice gate lets it through
                step = intent.getStringExtra("step"),
                talker = intent.getStringExtra("talker") ?: if (demo) "adultHigh" else null,
                face = intent.getStringExtra("face") ?: if (demo) "fixture" else null,
                whisper = intent.getBooleanExtra("whisper", true),
                exercise = intent.getStringExtra("exercise"),
            )
        }

        fun soundOf(raw: String): SoundId? = when (raw.lowercase()) {
            "sh", "ș", "ş" -> SoundId.Sh
            "ts", "ț", "ţ" -> SoundId.Ts
            else -> SoundId.of(raw.lowercase())
        }
    }
}
