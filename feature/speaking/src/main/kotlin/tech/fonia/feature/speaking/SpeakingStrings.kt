package tech.fonia.feature.speaking

/**
 * Every Romanian line of the speaking screens (teen/adult shell: "tu", short). Comma-below Ș/Ț; no "greșit", no
 * grades, no "diagnostic/terapie/tratează" (checked by `SpeakingStringsTest`). Numbers come from
 * `tech.fonia.speaking.SpeakingText`.
 */
object SpeakingStrings {
    const val NO_UM_TITLE = "Tu, fără ăăă"
    const val MINUTE_TITLE = "Un minut pe zi"
    const val FREE_TOPIC = "Liber"
    const val PREPARE = "Vorbește până la 60 de secunde. Totul rămâne pe telefon."
    const val START = "Începe"
    const val DONE_TALKING = "Gata"
    const val THINKING = "Mă gândesc"
    const val SAY_MORE = "Mai spune puțin."
    const val TAP_HINT = "Atinge un cuvânt tăiat ca să-l păstrezi, sau unul punctat ca să-l tai."
    const val LISTEN_CLEAN = "Ascultă-te fără ăăă"
    const val AS_IT_WAS = "cum a fost"
    const val WITHOUT = "fără ăăă"
    const val STOP = "Oprește"
    const val SAY_IT_AGAIN = "Acum spune-o tu așa"
    const val AGAIN = "Încă o dată"
    const val FINISH = "Gata"
    const val KEEP = "Păstrează"
    const val KEPT_NOTE = "Rostirile dispar când ieși, dacă nu apeși „Păstrează”."
    const val STEP_BACK = "Un pas înapoi"
    const val NEXT_STEP = "Pasul următor"
    const val HOW_WAS_IT = "Cum ți s-a părut?"
    const val SELF_HARD = "Greu"
    const val SELF_SOSO = "Așa și așa"
    const val SELF_EASY = "Ușor"
    const val WORKED = "Ce a mers"
    const val TO_TRY = "De încercat"
    const val AGAIN_SHORTER = "Încă o dată, mai scurt"
    const val LISTEN = "Ascultă"
    const val TRANSCRIPT_LEGEND = "Mai mare = mai tare · sus/jos = tonul · gol = pauză · tăiat = umplutură"
    const val CUT = "tăiat"
    const val KEPT = "păstrat"
    const val MAYBE = "propus"

    fun step(n: Int, of: Int) = "Pasul $n din $of"

    /**
     * The 7 topics from personal to public (fallback; the app passes `speechPrompts` from the content when it has
     * them). Original text.
     */
    val TOPICS = listOf(
        "Ce ai mâncat azi și cum ți s-a părut?",
        "Povestește o zi bună de vacanță.",
        "Un lucru pe care îl știi să-l faci bine. Cum l-ai învățat?",
        "Recomandă-i unui prieten o carte, un film sau un joc.",
        "Ce ai schimba la școala sau la locul tău de muncă?",
        "Explică pe scurt cum funcționează ceva care îți place.",
        "Convinge clasa să încerce un lucru nou.",
    )
}
