package tech.fonia.app.text

import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId

/**
 * Every string the app shows, in one place for review (chapter 01 §11). Romanian with comma-below Ș/Ț (U+0218–U+021B).
 * Child lines: at most 8 words, one thing to do, never "greșit". Adult lines: full sentences, "voi".
 * Keys in comments are the voice-script keys of chapter 06 where one exists.
 */
object T {
    const val appName = "Fonia"

    // ---- PATH
    const val pathTitle = "Drumul sunetelor"
    const val pathPick = "Alege o literă!" // path.pick
    const val pathContinue = "Continuă"
    const val pathMirror = "Oglinda"
    const val pathAdult = "Pentru adult"
    const val pathLogoA11y = "Fonia"
    fun pathToday(n: Int) = "Azi: ${Plural.attempts(n)}"
    const val pathTodayNone = "Azi: încă nimic"
    const val pathHere = "Ești aici"
    fun pathHereA11y(s: SoundId) = "Ești aici: sunetul ${soundName(s)}"
    fun pathNodeA11y(sound: SoundId, sessions: Int, today: Boolean): String {
        val done = when (sessions) { 0 -> "neînceput"; 1 -> "o lecție făcută"; else -> "${Plural.lessons(sessions)} făcute" }
        return "Sunetul ${soundName(sound)}, $done" + if (today) ", exersat azi" else ""
    }
    const val foniA11y = "Foni, papagalul"

    // ---- sheets

    // ---- microphone pre-prompt (for the adult)
    const val micTitle = "Pentru adult: microfonul"
    const val micBody = "Litera ascultă vocea copilului și răspunde pe loc. Sunetul rămâne pe acest telefon; nimic nu pleacă de aici."
    const val micAllow = "Pornește microfonul"
    const val micLater = "Fără microfon"
    const val micDeniedForever = "Microfonul e oprit din setările telefonului. Îl puteți porni din Setări › Aplicații › Fonia."
    const val micOpenSettings = "Deschide setările"
    const val micOffBanner = "Fără microfon: adultul alege după fiecare încercare."

    // ---- LESSON
    const val lessonExitA11y = "Ne oprim?"
    const val lessonMouthA11y = "Gura, în oglindă"
    fun lessonDotsA11y(done: Int, total: Int) = when {
        done == 0 -> "Abia începem"
        done >= total -> "Gata"
        done * 2 >= total -> "Peste jumătate"
        else -> "Sub jumătate"
    }
    const val exitTitle = "Ne oprim?"
    const val exitStay = "Mai stau"
    const val exitDone = "Gata"

    fun holdLine(sound: SoundId): String = when (sound) { // hold.ask.<sound>
        SoundId.S -> "Ține „sss” lung și umflă litera!"
        SoundId.Sh -> "Ține „șșș” lung și umflă litera!"
        SoundId.Z -> "Ține „zzz” lung și umflă litera!"
        SoundId.J -> "Ține „jjj” lung și umflă litera!"
        SoundId.Ts -> "Spune „ț-ț-ț” și umflă litera!"
        SoundId.Ce -> "Spune „ce-ce-ce” și umflă litera!"
        SoundId.Ge -> "Spune „ge-ge-ge” și umflă litera!"
        SoundId.R -> "Fă motorașul „rrr” și umflă litera!"
        SoundId.L -> "Ține „lll” lung și umflă litera!"
    }
    const val wordsLine = "Acum spune cuvântul!" // words.ask
    const val modelWord = "Ascultă"
    const val micListen = "Ascultă"
    const val micYourTurn = "Acum tu"
    const val micThinking = "Mă gândesc"
    const val micOff = "Microfon oprit"
    const val quietLine = "Te ascult când ești gata." // silence.1
    const val quietMore = "Mai ascult"
    const val quietSkip = "Sărim"
    const val pausedLine = "Continuăm?"
    const val pausedButton = "Continuăm"

    // verdicts (§6.4); the stamp word and Foni's line
    const val stampCorrect = "Bravo!"
    const val stampAlmost = "Aproape!"
    const val stampRetake = "Mai spune o dată"
    fun correctLine(sound: SoundId, hold: Boolean) = when { // v.correct.*
        !hold -> "Ai spus-o frumos!"
        sound == SoundId.Ts || sound == SoundId.Ce || sound == SoundId.Ge -> "Ai spus ${soundLower(sound)}-ul frumos!"
        else -> "Ai ținut ${soundLower(sound)}-ul lung!"
    }
    const val almostLine = "Aproape! Încă o dată." // v.almost
    const val unsureLine = "Hai să mai încercăm." // v.unsure
    const val retakeLine = "Mai spune o dată, mai aproape." // v.retake

    // the adult band (§6.4: "A ieșit" / "Încă nu")
    const val adultAsk = "A fost bine?"
    const val adultYes = "👍 Da"
    const val adultNo = "Mai încercăm"
    const val adultBandIdle = "Pentru adult"
    fun adultHint(heard: SoundId?, target: SoundId) =
        if (heard != null) "Poate ${heard.letter} în loc de ${target.letter}." else "Telefonul nu e sigur."
    /** Unsure with a suspicion "în probă" (the adult strip, lit). */
    fun adultMaybe(heard: SoundId?, target: SoundId) =
        if (heard != null) "Poate ${target.letter} spre ${heard.letter} (în probă)." else "Telefonul nu e sigur."
    /** The quiet strip under the thumbs: what the phone suspected, never said to the child. */
    fun adultTrial(line: String) = "În probă: $line"
    const val adultShowAsk = "Ați auzit și voi? Îi arătăm sfatul?"
    const val adultShowYes = "Da, arată"
    const val adultShowNo = "Nu"
    const val adultTrialTag = "în probă"

    // ---- AdultGate (§9)
    const val gateTitle = "Pentru adult"
    const val gateHold = "Țineți apăsat 3 secunde"
    const val gateHoldA11y = "Țineți apăsat trei secunde pentru zona adultului"
    const val gateWrite = "Scrieți cu cifre:"
    const val gateWrong = "Alt număr, vă rog."
    const val gateOk = "Intră"
    const val gateDelete = "Șterge cifra"
    const val gateKid = "Cheamă un adult!" // gate.kid

    // ---- ADULT-HOME „Cum merge”
    const val adultHomeTitle = "Cum merge"
    fun adultWeek(days: Int, attempts: Int) =
        if (days == 0) "N-ați exersat încă săptămâna asta. Două minute ajung pentru azi."
        else "Săptămâna asta ați exersat în ${Plural.days(days)}: ${Plural.attempts(attempts)}."
    fun adultSoundToday(today: Int) = "Azi: ${Plural.attempts(today)}"
    fun adultSoundWeek(week: Int) = "Săptămâna asta: ${Plural.attempts(week)}"
    fun adultCorrect(correct: Int, judged: Int) =
        if (judged == 0) "Telefonul nu a judecat încă nicio încercare."
        else "Telefonul a spus „bine” ${Plural.timesOutOf(correct, judged)}."
    fun adultAbstained(n: Int, answered: Int) =
        if (n == 0) "Telefonul nu s-a abținut." else "Nu a fost sigur ${Plural.times(n)}; ați răspuns voi ${Plural.times(answered)}."
    fun adultTrialObservation(target: SoundId, heard: SoundId?, kindName: String, times: Int, judged: Int) =
        (if (heard != null) "${target.letter} spre ${heard.letter}" else kindName) + ": ${Plural.timesOutOf(times, judged)}."
    const val adultTrialNote = "În probă: copilului nu i se spune."
    const val adultMayErr = "Sunt doar observațiile telefonului; poate greși."
    const val adultCalibrated = "Calibrat pe copil"
    fun adultCalibrating(n: Int, of: Int) = "Calibrare: $n din $of încercări confirmate"
    const val adultNothing = "Încă nu sunt încercări. După prima lecție, aici apar sunetele exersate."
    const val adultRowCalibrate = "Învață telefonul vocea lui"
    const val adultRowSettings = "Setări"
    const val adultBack = "Înapoi"
    fun kindName(kindRaw: String) = when (kindRaw) {
        "omission" -> "Sunetul lipsește"
        "stopping" -> "Sunet oprit"
        "weakOrShort" -> "Sunet slab"
        "placeSwap" -> "Alt loc al limbii"
        "devoicing" -> "Fără voce"
        "deaffrication" -> "Fără oprire scurtă"
        "affricatePlace" -> "Alt loc"
        "rToL" -> "R ca L"
        else -> "Altă formă"
    }

    // ---- ADULT-CALIBRATE
    const val calTitle = "Învață telefonul vocea lui"
    const val calWhy = "Fiecare copil sună altfel. Când îi arătați telefonului cum sună sunetul lui bun, telefonul îl compară cu el însuși, nu cu alți copii."
    const val calHow = "Ascultați ultimele încercări și spuneți care au ieșit bine. Se păstrează măsurătorile; înregistrările rămân doar ultimele trei pe sunet."
    const val calNone = "Încă nu sunt înregistrări. Faceți o lecție, apoi reveniți aici."
    const val calPlay = "Ascultă"
    const val calGood = "👍 A ieșit"
    const val calNotYet = "Încă nu"
    fun calPhone(verdictKey: String) = when (verdictKey) {
        "correct" -> "telefonul: bine"
        "almost" -> "telefonul: aproape"
        "unsure" -> "telefonul: nesigur"
        "namedError" -> "telefonul: încă nu"
        else -> "telefonul: fără răspuns"
    }
    fun calAnswered(good: Boolean) = if (good) "voi: a ieșit" else "voi: încă nu"

    // ---- ADULT-SETTINGS
    const val setTitle = "Setări"
    const val setPrivacy = "Nimic nu pleacă de pe telefon. Fără cont, fără internet."
    const val setWho = "Cine exersează"
    const val setWhoChild = "Un copil, cu un adult"
    const val setWhoSelf = "Doar eu (adolescent sau adult)"
    const val setVoice = "Vocea care exersează"
    const val setVoiceHelp = "Telefonul compară sunetul cu vocile de același fel. Un adult care încearcă aplicația alege o voce de adult."
    const val voiceChild = "Copil"
    const val voiceAdultHigh = "Adult, voce mai înaltă"
    const val voiceAdultLow = "Adult, voce mai joasă"
    const val setMotion = "Mișcare redusă"
    const val setMotionHelp = "Literele se estompează în loc să sară."
    const val setSpeaking = "Vorbit în public"
    const val setSpeakingHelp = "Pentru adolescenți și adulți."
    const val setLicenses = "Licențe"
    const val setWipe = "Șterge tot"
    const val setWipeHelp = "Încercările, înregistrările și setările dispar de pe acest telefon."
    const val wipeTitle = "Ștergem tot?"
    const val wipeBody = "Dispar încercările, înregistrările, calibrarea și setările. Nu se poate anula. Pentru confirmare, scrieți numărul."
    const val wipeConfirm = "Șterge tot"
    const val wipeCancel = "Renunț"
    const val on = "Pornit"
    const val off = "Oprit"

    // ---- licences
    const val licTitle = "Licențe"
    const val licVoice = "Vocea lui Foni e generată pe calculator."
    val licenses = listOf(
        "Allo (fontul literelor)" to "SIL Open Font License 1.1",
        "Oboe (sunetul)" to "Apache License 2.0",
        "AndroidX, Jetpack Compose, Media3" to "Apache License 2.0",
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
        "SQLite" to "domeniu public",
    )

    // ---- TEEN-HOME (stub; the screens come from :feature:speaking)
    const val teenTitle = "Exersează"
    const val teenSpeaking = "Vorbit în public"
    const val teenSpeakingHelp = "Un minut pe zi, fără „ăăă”."
    const val teenSounds = "Sunete"
    const val teenSoundsHelp = "Același drum al sunetelor, în stil simplu."
    const val teenSettings = "Setări"

    // ---- onboarding (short: ON-0, ON-1, ON-2, ON-5)
    const val on0Hello = "Bună! Eu sunt Foni. Cine e lângă mine?" // on0.hello
    const val on0Child = "Un copil și un adult"
    const val on0Self = "Doar eu"
    const val on1Title = "Pentru adult"
    const val on1Card = "Vocea și imaginea copilului nu pleacă de pe telefon. Fără cont. E exersare prin joacă și nu ține locul logopedului. Stați alături, patru minute pe zi."
    const val on1Wait = "Asta e pentru adult. Eu aștept." // on1.wait
    const val on1Start = "Începem"
    const val on1SelfTitle = "Ce vrei să-ți iasă mai bine?"
    const val on1SelfSound = "Un sunet anume"
    const val on1SelfSpeaking = "Vorbit în public"
    const val on1SelfVoice = "Vocea ta"
    const val on2Ask = "Vreau să te aud!" // on2.ask
    const val on2Body = "Ascult doar în timpul exercițiilor. Nimic nu se trimite."
    const val on2Allow = "Pornește microfonul"
    const val on2Later = "Mai târziu"
    const val on5Pick = "Alege o literă de joacă." // on5.free
    const val on5Hero = "Asta e litera ta!"
    const val onSkip = "Sari peste"

    /** The instruction for a named error (§6.5), ≤ 8 words; v.err.<kind>.<sound>. */
    fun instruction(kind: ErrorKind, target: SoundId): String = when (kind) {
        ErrorKind.PlaceSwap -> when (target) {
            SoundId.S -> "Zâmbește și ține limba în față."
            SoundId.Z -> "Zâmbește, limba în față: zzz."
            SoundId.Sh -> "Fă buzele pâlnie, limba înapoi."
            SoundId.J -> "Buzele pâlnie, limba înapoi: jjj."
            else -> "Uită-te la literă și mai încearcă."
        }
        ErrorKind.AffricatePlace -> when (target) {
            SoundId.Ts -> "Zâmbește, limba în față: ț."
            SoundId.Ce -> "Buzele pâlnie, limba înapoi: ce."
            SoundId.Ge -> "Buzele pâlnie, limba înapoi: ge."
            else -> "Uită-te la literă și mai încearcă."
        }
        ErrorKind.Omission -> "Nu uita sunetul de la început!"
        ErrorKind.Stopping -> "Lasă aerul să curgă lung."
        ErrorKind.Devoicing -> "Pune vocea: bâzâie ca albina!"
        ErrorKind.RtoL -> "Lasă vârful limbii să bată sus."
        ErrorKind.Deaffrication -> "Atinge dinții o clipă, apoi ț!"
        ErrorKind.RtoGlide -> "Ridică vârful limbii după dinți."
        ErrorKind.RUvular -> "Vârful limbii lucrează, nu gâtul."
        ErrorKind.LtoGlide -> "Lipește vârful limbii sus, după dinți."
        ErrorKind.Lateral -> "Suflă aerul pe mijloc, ca prin pai."
        ErrorKind.Interdental -> "Ține limba în spatele dinților."
        ErrorKind.RtoVowel -> "Vârful limbii sus: rrr!"
        ErrorKind.LtoR -> "Ține limba lipită, nu o bate."
        ErrorKind.WeakOrShort -> "Încă o dată, mai tare!"
    }

    // ---- END (§7)
    const val endDone = "Ai exersat până la capăt!" // end.done
    const val endShort = "Ai exersat puțin azi. Pe mâine!" // end.short
    const val endPraise = "Bravo!"
    fun endCount(n: Int) = "Ai încercat ${Plural.times(n)}."
    const val endAgain = "Încă una?"
    const val endFinish = "Gata"
    const val endAdultLabel = "Pentru adult"
    fun endAdult(n: Int, sound: SoundId, bestHoldSeconds: Double): String {
        val first = "Azi: ${Plural.attempts(n)} la ${sound.letter}"
        val hold = if (bestHoldSeconds >= 0.5) "; cel mai lung ${soundLower(sound)}, ${Plural.seconds(bestHoldSeconds)}" else ""
        return "$first$hold. Spune-i: „Ai încercat până a ieșit.”"
    }

    // ---- names
    fun soundName(s: SoundId) = when (s) { SoundId.Ce -> "CE, CI"; SoundId.Ge -> "GE, GI"; else -> s.letter }
    private fun soundLower(s: SoundId) = when (s) { SoundId.Ce -> "ce"; SoundId.Ge -> "ge"; else -> s.letter.lowercase() }
    fun letterA11y(letter: String) = "Litera $letter"
    // ---- MIRROR (chapter 01 §8, docs/v2/04 §4, §11). Child lines ≤ 8 words; never „greșit”, never red.
    const val mirrorA11y = "Oglinda: gura ta, cu un ghid pentru buze"
    const val mirrorClose = "Închide oglinda"
    const val mirrorDone = "Am făcut"
    const val mirrorRest = "Fă o față liniștită, cu gura închisă."
    const val mirrorRestA11y = "Telefonul învață fața ta liniștită"
    const val mirrorLook = "Fă gura ca ghidul albastru."
    const val mirrorMatched = "Așa! Ține-o așa."
    const val mirrorCheer = "Bravo! Exact așa."
    const val mirrorTongue = "Limba lucrează ascunsă. Uită-te la buze."
    const val mirrorCameraOn = "Camera e pornită"
    const val mirrorCameraOff = "Oprește camera"
    const val mirrorWantToSee = "Vreau să mă văd"
    const val mirrorNoCamera = "Fără oglindă. Uită-te la literă."
    const val mirrorPickA11y = "Alege sunetul"
    const val mirrorFixture = "Imagini de probă, nu camera"
    // the adult's pre-prompt (docs/v2/04 §11), before the system dialog
    const val camTitle = "Pentru adult: camera"
    const val camBody = "Camera e o oglindă. Telefonul se uită doar la forma buzelor. Nicio imagine nu se salvează și nu pleacă de pe telefon."
    const val camAllow = "Pornește camera"
    const val camLater = "Fără cameră"
    const val camOpenSettings = "Deschide setările"
    const val camDeniedForever = "Camera e oprită din setările telefonului. O puteți porni din Setări › Aplicații › Fonia."

    /** What the camera's advice key asks for (`CameraAdvice.key`, spec/poses); null for an unknown key. */
    fun mirrorAdvice(key: String): String? = when (key) {
        "lips.round" -> "Rotunjește buzele."
        "lips.spread" -> "Întinde buzele, ca la zâmbet."
        "lips.lessRound" -> "Lasă buzele mai libere."
        "lips.lessSpread" -> "Zâmbește mai puțin."
        "mouth.openMore" -> "Deschide gura mai mult."
        "mouth.openLess" -> "Apropie puțin dinții."
        "lips.part" -> "Desparte puțin buzele."
        else -> null
    }

    /** The camera's quality issue (`QualityIssue.key`); null = nothing to say. */
    fun mirrorQuality(key: String): String? = when (key) {
        "noFace" -> "Unde ești? Vino în oglindă."
        "partialFace" -> "Arată-mi toată gura."
        "tooFar" -> "Vino mai aproape."
        "tooClose" -> "Puțin mai departe."
        "turnToCamera" -> "Uită-te drept în oglindă."
        "tooDark" -> "Mai multă lumină."
        "tooBright" -> "E prea multă lumină."
        else -> null
    }

    /** The target shape in words, from the pose's lip class (`spec/poses/<sound>.json` → `lips.class`). */
    fun mirrorShape(lipClass: String): String = when (lipClass) {
        "smileTeethClose", "smile" -> "Zâmbet mic, dinții aproape."
        "funnel" -> "Buzele rotunde, ca o pâlnie."
        "funnelLight" -> "Buzele puțin rotunde."
        "halfOpen" -> "Gura puțin deschisă."
        "halfOpenSmile" -> "Gura deschisă, cu un zâmbet mic."
        else -> mirrorLook
    }

    // ---- TEEN-HOME, NO-UM, MINUTE (chapter 01 §10): the grown shell, "tu"
    const val teenHomeTitle = "Bună!"
    const val teenToday = "Azi"
    const val teenStart = "Începe"
    fun teenMinuteStep(n: Int, of: Int) = "Pasul $n din $of."
    const val teenTodayCard = "Un minut, fără „ăăă”"
    const val teenTodayHelp = "Vorbește liber, apoi ascultă-te curat."
    const val teenPractice = "Exersează"
    const val teenNoUm = "Tu, fără ăăă"
    const val teenNoUmHelp = "Vorbești până la un minut; îți arătăm umpluturile."
    const val teenMinute = "Un minut pe zi"
    const val teenMinuteHelp = "Șapte subiecte, de la tine la public."
    const val teenSoundsLink = "Sunete: drumul literelor"
    const val teenWhisperOn = "Transcriptul se face pe telefon."
    const val teenWhisperOff = "Fără modelul de text: numărăm pauzele și „ăăă” după sunet."
    const val speakMicTitle = "Microfonul"
    const val speakMicBody = "Te ascultăm doar cât vorbești. Vocea rămâne pe telefon; nimic nu pleacă de aici."
    const val speakMicAllow = "Pornește microfonul"
    const val speakMicLater = "Nu acum"
    const val speakMicDenied = "Microfonul e oprit din setările telefonului. Îl poți porni din Setări › Aplicații › Fonia."
    const val speakKept = "Păstrat pe telefon."
    const val speakFixture = "Înregistrare de probă, nu microfonul"
    fun teenDays(n: Int) = if (n <= 0) "Prima zi" else "Ai exersat ${Plural.days(n)}"

    // ---- M3: the sound house, the exercise steps, END choices, PATH days
    const val houseGo = "Hai să exersăm"
    const val houseGoCaption = "4 minute cu Foni"
    const val houseChoose = "Sau alege tu:"
    const val houseBackA11y = "Înapoi la drum"
    fun houseHero(name: String, who: String) = "$name, $who"
    fun houseA11y(s: SoundId) = "Casa sunetului ${soundName(s)}"
    const val lockAdultTitle = "Cheamă un adult"
    const val lockAdultBody = "Un adult ascultă sunetul tău singur. Apoi se deschide drumul mai departe."
    const val lockAdultButton = "Sunt adult"
    const val lockNotYetTitle = "Ajungi aici curând"
    const val lockNotYetBody = "Exersează cartonașele deschise. Drumul se deschide pas cu pas."
    const val lockOk = "Bine"
    fun cardA11y(title: String, subtitle: String, locked: Boolean, dots: Int) =
        "$title. $subtitle." + (if (locked) " Închis deocamdată." else "") + if (dots > 0) " Jucat ${Plural.times(dots)}." else ""

    fun stepLine(kind: tech.fonia.lessons.StepKind, s: SoundId): String = when (kind) {
        tech.fonia.lessons.StepKind.Hold -> holdLine(s)
        tech.fonia.lessons.StepKind.Listen -> listenAsk
        tech.fonia.lessons.StepKind.Syllables -> trainLine
        tech.fonia.lessons.StepKind.Pairs -> pairListen
        tech.fonia.lessons.StepKind.Sentence -> sentenceLine
        tech.fonia.lessons.StepKind.Twister -> twisterSlow
        tech.fonia.lessons.StepKind.Retell -> retellListen
        tech.fonia.lessons.StepKind.Mission -> missionLine
        tech.fonia.lessons.StepKind.Move -> moveLine
        else -> wordsLine
    }
    const val listenAsk = "A zis bine sau încurcat?"
    fun listenSaysText(w: String) = "Foni zice: „$w”. Bine sau încurcat?"
    const val listenRight = "Da! A zis bine."
    const val listenCaught = "M-ai prins! Am încurcat."
    fun listenAgain(w: String) = "Ascultă încă o dată: „$w”."
    const val listenRightCard = "Bine"
    const val listenMixedCard = "Încurcat"
    const val pairListen = "Ascultă-le pe amândouă."
    fun pairSay(w: String) = "Acum spune: „$w”!"
    const val retellListen = "Ascultă povestea."
    const val retellYourTurn = "Acum spune tu povestea!"
    const val twisterSlow = "Rar, ca melcul."
    const val twisterFast = "Acum ca iepurașul!"
    const val tempoSlow = "melc"
    const val tempoFast = "iepuraș"
    const val trainLine = "Urcă în tren: silabă cu silabă!"
    const val sentenceLine = "Spune propoziția cu mine."
    const val missionLine = "Misiunea de azi! Spune-o tare."
    const val missionSent = "Am trimis-o adultului!"
    const val missionCard = "Misiune"
    const val missionAdult = "Pentru adult: azi, faceți asta împreună."
    const val missionNone = "Azi nu e nicio misiune încă. Faceți una din cartonașul „Misiunea de azi”."
    const val missionDone = "Am făcut-o"
    const val missionDoneTick = "Făcută"
    const val moveLine = "Zboară ca Foni!"
    const val moveDone = "Gata"
    const val togetherDone = "Am făcut"
    const val triedLine = "Bravo că ai încercat!"
    const val adultJudges = "Aici decideți voi: a sunat clar?"
    const val adultTurnLine = "Adultul ascultă acum."
    fun letterSay(s: SoundId) = "Spune ${soundName(s)}"

    // ---- END choices
    const val endOneMore = "Încă una?"
    const val endPickOther = "Alege alt exercițiu"

    // ---- PATH days (D13 / Q7: the row only while whole; the total only grows)
    fun pathDays(n: Int) = if (n == 1) "1 zi" else "$n zile"
    fun pathStreak(n: Int) = "$n la rând"
    const val pathContinueA11y = "Continuă lecția"

    // ---- „Vezi gura”
    const val mouthTitle = "Vezi gura"
    const val mouthHide = "Ascunde gura"
    const val mouthInside = "Vezi pe dinăuntru"
    const val mouthFront = "Din față"
    const val mouthPlay = "Încă o dată"
    const val mouthNoInside = "Vederea din interior vine după ce o verifică un logoped."

    // ---- the adult's page for one sound
    fun adultSoundTitle(s: SoundId) = "Sunetul ${soundName(s)}"
    fun adultSoundRung(rung: String) = "Treapta de acum: $rung"
    fun adultSoundWins(n: Int, of: Int) = "Reușite la sunetul singur: $n din $of"
    const val adultLockTitle = "Sunetul singur sună bine?"
    const val adultLockBody = "Rugați copilul să spună sunetul de trei ori. Dacă sună bine, se deschid silabele și cuvintele."
    const val adultLockYes = "Da, sună bine"
    const val adultLockNo = "Încă nu"
    const val adultLockAsk = "Întreb logopedul"
    const val adultLockDone = "Lacătul e deschis."
    const val adultUnlockAll = "Deblochează tot pentru acest sunet"
    const val adultUnlockAllHelp = "Toate cartonașele devin deschise, oricând. Bun dacă exersați cu un logoped."
    const val adultRelock = "Închide la loc cartonașele neatinse"
    const val adultSoundsHeading = "Sunete și exerciții"
    const val adultMissionHeading = "Misiunea de azi"
    const val licZipa = "ZIPA-small (modelul de foneme)"
    const val licZipaText = "CC BY 4.0, autorii ZIPA (Jian Zhu și alții). Folosit nemodificat. ONNX Runtime: MIT License."
    const val licMouth = "Gura 3D (din GNM Head v3.0)"
    const val licMouthText = "Google LLC, Apache License 2.0, modificat."

}

/**
 * Romanian plurals for counted nouns (chapter 01 §11): "1 zi", "3 zile", "20 de zile"; "de" when the last two digits
 * are 00 or at least 20, never at zero.
 */
object Plural {
    fun needsDe(n: Long): Boolean {
        if (n == 0L) return false
        val lastTwo = n % 100
        return lastTwo == 0L || lastTwo >= 20
    }

    private fun group(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        s.reversed().forEachIndexed { i, ch -> if (i > 0 && i % 3 == 0) sb.append('.'); sb.append(ch) }
        return sb.reverse().toString()
    }

    fun count(n: Int, one: String, many: String): String {
        val v = n.toLong()
        return when {
            v == 1L -> "1 $one"
            needsDe(v) -> "${group(v)} de $many"
            else -> "${group(v)} $many"
        }
    }

    fun attempts(n: Int) = count(n, "încercare", "încercări")
    fun lessons(n: Int) = count(n, "lecție", "lecții")
    fun days(n: Int) = count(n, "zi", "zile")

    /** "o dată", "de 2 ori", "de 20 de ori". */
    fun times(n: Int): String = when {
        n == 1 -> "o dată"
        needsDe(n.toLong()) -> "de ${group(n.toLong())} de ori"
        else -> "de $n ori"
    }

    /** "o dată din 12", "de 6 ori din 40" (chapter 01 §11: „O dată din N”, „De N ori din N”). */
    fun timesOutOf(n: Int, of: Int): String = "${if (n == 0) "de 0 ori" else times(n)} din $of"

    /** "1 secundă", "2,8 secunde" (one decimal, comma). */
    fun seconds(s: Double): String {
        val tenths = Math.round(s * 10).toInt()
        if (tenths == 10) return "1 secundă"
        val text = if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10},${tenths % 10}"
        return if (tenths % 10 == 0 && needsDe((tenths / 10).toLong())) "$text de secunde" else "$text secunde"
    }
}
