package tech.fonia.design

/**
 * Fonia's sound effects. The app calls [FoniaSfx.play] at each [SfxCue]; every request goes
 * through ONE gate ([SfxGate]) that enforces the quiet rule (nothing while the microphone is open, nor 150 ms around
 * it), the 120 ms anti-stacking rule, the in-app sound switch and the phone's silent / Do Not Disturb state. Only then
 * does the installed [Sfx] sink play it (`SfxPlayer`: a preloaded `SoundPool`, media volume), with its haptic.
 *
 * The set (v2: ElevenLabs mallets / kalimba / harp / bubbles / parrot calls + earshot-bank wood and shimmer, all
 * key-fitted to C major) is built and measured by `tools/sfx/` into `assets/sfx/`; see `tools/sfx/REPORT.md`.
 * The sound is never negative: "almost" and "retake" step up, softly.
 */
enum class SfxCue {
    /** Any button of his controls (`StyledButton`). Its haptic stays the button's own. */
    Tap,

    /** A new card (trial) slides in. */
    Card,

    /** A page is pushed / popped. */
    NavForward,
    NavBack,

    /** "Your turn": just BEFORE the microphone opens; the hub waits until it has ended + 150 ms. */
    TakeStart,

    /** The judge said the take was good (his `Correct`). */
    TakeGood,

    /** The judge said "almost" or named a fix (his `Almost`). */
    TakeAlmost,

    /** The take could not be judged: "Mai spune o dată". */
    Retake,

    /** A step of a held sound's progress; `step` 0..5 climbs C D E G A C'. Never while the microphone is open. */
    HoldTick,

    /** The living letter POPs (also his "I heard you" `Pop`). */
    Pop,

    /** A star lands (his `Star`). */
    Star,

    /** A block of the session is done (his `LevelUp`; Fonia counts no streaks). */
    BlockDone,

    /** The end screen of a session. */
    SessionEnd,

    /** Something new opens (his `Unlock`). */
    Unlock,

    /** Foni reacts (a tap on Foni): six parrot calls in turn. */
    FoniChirp,

    /** The mirror sees the right mouth shape. */
    MirrorMatch,

    /** The 3D mouth guide moves to its next step. */
    GuideStep,
}

/** The haptic paired with a sound, played by the screen's `FoniaHaptics` (its own on / off switch applies). */
enum class SfxHaptic { Tap, Success, Pop }

/**
 * One file of `assets/sfx/`. [durationMs] is the measured length rounded up (tools/sfx/manifest.json; a unit test
 * checks the two agree), used by the gate to know when a sound has ended. Higher [priority] wins a 120 ms clash.
 * A [deferrable] sound asked for in the 150 ms after the microphone closed plays when the window ends; the others drop.
 */
enum class SfxSound(
    val fileName: String,
    val durationMs: Int,
    val priority: Int,
    val deferrable: Boolean,
    val haptic: SfxHaptic? = null,
    /** UI chrome: silent while a screen reader speaks (TalkBack has its own earcons). */
    val isChrome: Boolean = false,
) {
    Tap1("tap_1.ogg", 100, 1, deferrable = false, isChrome = true),
    Tap2("tap_2.ogg", 100, 1, deferrable = false, isChrome = true),
    Tap3("tap_3.ogg", 100, 1, deferrable = false, isChrome = true),
    Card("card.ogg", 345, 3, deferrable = false, isChrome = true),
    NavForward("nav_forward.ogg", 330, 3, deferrable = false, isChrome = true),
    NavBack("nav_back.ogg", 345, 3, deferrable = false, isChrome = true),
    MicOpen("mic_open.ogg", 140, 4, deferrable = false),
    Good("good.ogg", 1070, 8, deferrable = true, haptic = SfxHaptic.Success),
    Almost("almost.ogg", 565, 6, deferrable = true),
    Retake("retake.ogg", 670, 5, deferrable = true),
    HoldTick("hold_tick.ogg", 120, 2, deferrable = false),
    Pop("pop.ogg", 510, 7, deferrable = true, haptic = SfxHaptic.Pop),
    Star("star.ogg", 960, 6, deferrable = true),
    LevelUp("levelup.ogg", 1370, 9, deferrable = true, haptic = SfxHaptic.Success),
    SessionEnd("session_end.ogg", 2355, 10, deferrable = true, haptic = SfxHaptic.Pop),
    Unlock("unlock.ogg", 960, 7, deferrable = true),
    Foni1("foni_1.ogg", 615, 4, deferrable = false),
    Foni2("foni_2.ogg", 695, 4, deferrable = false),
    Foni3("foni_3.ogg", 560, 4, deferrable = false),
    Foni4("foni_4.ogg", 780, 4, deferrable = false),
    Foni5("foni_5.ogg", 395, 4, deferrable = false),
    Foni6("foni_6.ogg", 505, 4, deferrable = false),
    MirrorMatch("mirror_match.ogg", 1340, 7, deferrable = true),
    GuideStep("guide_step.ogg", 120, 2, deferrable = false, isChrome = true),
    ;

    companion object {
        /** Playback rates of [HoldTick] for steps 0..5: C D E G A C' (just intonation of the pentatonic). */
        val holdRates = floatArrayOf(1f, 9f / 8f, 5f / 4f, 3f / 2f, 5f / 3f, 2f)
    }
}

/** Where an effect that passed the gate goes. Called on any thread; must not block. */
fun interface Sfx {
    fun play(sound: SfxSound, rate: Float, replacesLast: Boolean)
}

/** The app-wide entry point. Everything goes through [gate]; nothing else may start an effect. */
object FoniaSfx {
    @Volatile
    var sink: Sfx = Sfx { _, _, _ -> }

    /** Pairs haptics with key sounds; set by `SfxHost` (null: no haptics, e.g. in tests). */
    @Volatile
    var haptics: ((SfxHaptic) -> Unit)? = null

    /** Runs a deferred request later (the player's handler); null runs nothing (the request is then dropped). */
    @Volatile
    var scheduler: ((delayMs: Long, run: () -> Unit) -> Unit)? = null

    /** Every decision, with times (logcat tag `FoniaSfx`), so the quiet rule can be checked from a log. */
    @Volatile
    var log: (String) -> Unit = {}

    val gate = SfxGate()

    private var foni = 0
    private var tap = 0

    /** The in-app "Sunete" switch (AudioHub.effectsMuted). */
    var muted: Boolean
        get() = gate.muted
        set(value) {
            gate.muted = value
        }

    private val taps = arrayOf(SfxSound.Tap1, SfxSound.Tap2, SfxSound.Tap3)

    /** The six parrot calls (happy, cheering, hello, thinking, squawk, surprised), in an order that alternates moods. */
    private val fonis = arrayOf(SfxSound.Foni1, SfxSound.Foni4, SfxSound.Foni2, SfxSound.Foni6, SfxSound.Foni3, SfxSound.Foni5)

    fun play(cue: SfxCue, step: Int = 0) {
        runCatching { request(soundOf(cue), step) }
    }

    private fun request(sound: SfxSound, step: Int) {
        val rate = if (sound == SfxSound.HoldTick) SfxSound.holdRates[step.coerceIn(0, SfxSound.holdRates.lastIndex)] else 1f
        when (val d = gate.request(sound, rate)) {
            is SfxGate.Decision.Play -> {
                log(d.describe(sound))
                sink.play(sound, rate, d.replacesLast)
                sound.haptic?.let { h -> haptics?.invoke(h) }
            }
            is SfxGate.Decision.Defer -> {
                log(d.describe(sound))
                val later = scheduler
                if (later == null) log("drop ${sound.name} reason=no-scheduler") else later(d.delayMs) { request(sound, step) }
            }
            is SfxGate.Decision.Drop -> log(d.describe(sound))
        }
    }

    private fun soundOf(cue: SfxCue): SfxSound = when (cue) {
        // Round robin, so two taps never sound identical.
        SfxCue.Tap -> taps[tap++ % taps.size]
        SfxCue.Card -> SfxSound.Card
        SfxCue.NavForward -> SfxSound.NavForward
        SfxCue.NavBack -> SfxSound.NavBack
        SfxCue.TakeStart -> SfxSound.MicOpen
        SfxCue.TakeGood -> SfxSound.Good
        SfxCue.TakeAlmost -> SfxSound.Almost
        SfxCue.Retake -> SfxSound.Retake
        SfxCue.HoldTick -> SfxSound.HoldTick
        SfxCue.Pop -> SfxSound.Pop
        SfxCue.Star -> SfxSound.Star
        SfxCue.BlockDone -> SfxSound.LevelUp
        SfxCue.SessionEnd -> SfxSound.SessionEnd
        SfxCue.Unlock -> SfxSound.Unlock
        SfxCue.MirrorMatch -> SfxSound.MirrorMatch
        SfxCue.GuideStep -> SfxSound.GuideStep
        SfxCue.FoniChirp -> fonis[foni++ % fonis.size]
    }
}
