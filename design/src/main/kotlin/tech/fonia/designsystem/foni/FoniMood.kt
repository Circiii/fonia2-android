package tech.fonia.designsystem.foni

/**
 * What Foni is doing. Every mood is friendly: there is no sad, scolding or failure pose ([Unsure] is a curious „hmm”).
 * [description] is what TalkBack reads (Romanian, the child's language).
 */
enum class FoniMood {
    /** Standing around: breathes, blinks, looks about, now and then ruffles, wags, hops or peers at something. */
    Idle,
    /** Leans in with his head cocked, eyes wide; the child's voice makes him lean further (the beak stays shut). */
    Listening,
    /** The beak follows `voiceLevel` (lip-sync); with no level he babbles on his own, with nods and wing gestures. */
    Talking,
    /** Looks up, taps his tail, thought bubbles rise in front of him. */
    Thinking,
    /** Squash-and-stretch hops with flapping wings, smiling eyes and twinkling stars; then a happy bounce. */
    Cheering,
    /** Warm double nods, smiling eyes, a beckoning wing: „hai, încă o dată!”. */
    Encouraging,
    /** A gentle „hmm”: head tilted, eyes up and aside, a small shrug and a humming beak. Never sad. */
    Unsure,
    /** Presents with his wing stretched out behind him towards `lookAt` (he turns his back to it), eyes glancing there. */
    Pointing,
    /** Eyes closed, slow breathing, nods off and catches himself; Zzz drift up. */
    Sleepy,
    /** Raises his wing and waves, in bursts, with a little bounce. */
    Waving;

    /** TalkBack text for this mood. */
    val description: String
        get() = when (this) {
            Idle -> "Foni, papagalul, stă și se uită în jur"
            Listening -> "Foni te ascultă"
            Talking -> "Foni vorbește"
            Thinking -> "Foni se gândește"
            Cheering -> "Foni se bucură și sare de fericire"
            Encouraging -> "Foni te încurajează"
            Unsure -> "Foni se întreabă: hmm?"
            Pointing -> "Foni arată cu aripa"
            Sleepy -> "Foni moțăie"
            Waving -> "Foni îți face cu aripa"
        }
}
