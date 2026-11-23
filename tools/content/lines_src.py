"""Foni's lines (I5) and the parent texts (I8), original, after chapter 06 §7 and §9.

Error-kind instructions are NOT written here: they are the child lines of spec/feedback.json (keys fb.*), copied
in by the builder so the voice clip says exactly what the screen shows. The checker fails if they drift apart.
Lines contain no isolated sounds or bare syllables (Q9): those come from a human recording.
"""

# (key, category, when, text, maxSeconds)
LINES = [
    # Greeting.
    ("greet.1", "greeting", "first screen of a session", "Bună! Eu sunt Foni.", 2.0),
    ("greet.2", "greeting", "returning child", "Bine ai venit înapoi!", 2.0),
    ("greet.3", "greeting", "session start", "Hai să ne jucăm cu sunetele!", 2.5),
    ("greet.4", "greeting", "session start", "Ce bine că ai venit!", 2.0),
    ("greet.5", "greeting", "after the opening line", "Gata? Pornim!", 1.5),
    # Model and "your turn".
    ("model.listen", "model", "before a model clip", "Ascultă.", 1.0),
    ("model.yourTurn", "model", "after a model clip", "Acum tu!", 1.0),
    ("model.repeat", "model", "repeat after the model", "Spune după mine.", 1.5),
    ("model.name", "model", "naming a picture without a model", "Ce vezi în poză?", 1.5),
    ("model.together", "model", "saying it together with the adult", "Hai împreună.", 1.5),
    ("model.again", "model", "model again on request", "Încă o dată, împreună.", 2.0),
    ("model.slow", "model", "sentence modelled slowly", "Rar, cuvânt cu cuvânt.", 2.0),
    # Instruction per step.
    ("step.listen", "step", "Listen", "Ascultă și atinge poza potrivită.", 2.5),
    ("step.listen.foil", "step", "Listen, after Foni speaks", "Am spus bine sau am încurcat?", 2.5),
    ("step.warmup", "step", "Warmup", "Hai să trezim gura!", 1.5),
    ("step.hold.sustain", "step", "Hold, sustained sounds", "Ține sunetul lung, cât poți.", 2.5),
    ("step.hold.burst", "step", "Hold, bursts", "Scurt și repede, de trei ori.", 2.5),
    ("step.syllables", "step", "Syllables", "Urcă în trenul de silabe!", 2.0),
    ("step.syllables.split", "step", "Syllables, first time on the rung", "Întâi despărțit, apoi legat.", 2.5),
    ("step.words", "step", "Words", "Ce vezi în poză? Spune tu.", 2.0),
    ("step.words.final", "step", "Words, final position", "Ține finalul puțin mai lung.", 2.5),
    ("step.pairs", "step", "Pairs", "Ascultă două cuvinte. Apoi spune-le tu.", 3.0),
    ("step.sentence", "step", "Sentence", "Spune toată propoziția.", 2.0),
    ("step.twister.slow", "step", "Twister, snail tempo", "Rar, ca melcul.", 1.5),
    ("step.twister.step", "step", "Twister, one step faster", "Acum puțin mai repede, ca iepurașul.", 3.0),
    ("step.retell", "step", "Retell", "Uite trei poze. Povestește-mi!", 2.5),
    ("step.mission", "step", "Mission", "Misiunea de azi, pentru acasă.", 2.5),
    ("step.surprise", "step", "the surprise item in block 3", "Uite o surpriză!", 1.5),
    ("step.final", "step", "the last sure item", "Încă una ușoară, pentru final.", 2.5),
    # Movement break.
    ("move.1", "move", "Move", "Sus! Zboară ca mine, cu aripile mari!", 2.5),
    ("move.2", "move", "Move", "Trei respirații mari, ca un balon.", 2.5),
    ("move.3", "move", "Move", "Gura se odihnește. Scutură mâinile!", 2.5),
    ("move.4", "move", "Move", "Întinde aripile și zboară cu mine!", 2.5),
    # Praise that names what happened (effort and the act, never "ești deștept").
    ("praise.longHold.1", "praise", "sound held long", "Ai ținut sunetul lung!", 1.5),
    ("praise.longHold.2", "praise", "sound held long", "Ce lung a fost!", 1.5),
    ("praise.longHold.3", "praise", "sound held long", "Până la capăt!", 1.5),
    ("praise.longerThanLast.1", "praise", "longer than last time", "Mai lung ca ieri!", 1.5),
    ("praise.longerThanLast.2", "praise", "longer than last time", "Ai bătut recordul tău!", 1.5),
    ("praise.roundLips.1", "praise", "round lips seen", "Buze rotunde, exact așa!", 1.5),
    ("praise.roundLips.2", "praise", "round lips seen", "Pupic perfect!", 1.5),
    ("praise.wideSmile.1", "praise", "wide smile seen", "Zâmbet de crocodil!", 1.5),
    ("praise.wideSmile.2", "praise", "wide smile seen", "Ți-am văzut dinții!", 1.5),
    ("praise.tongueIn.1", "praise", "tongue stayed behind the teeth", "Limba a stat după dinți!", 1.5),
    ("praise.tongueIn.2", "praise", "tongue stayed behind the teeth", "Limba cuminte, în gură!", 1.5),
    ("praise.voiceOn.1", "praise", "voicing on (Z, J, GE)", "Ți-a bâzâit gâtul!", 1.5),
    ("praise.voiceOn.2", "praise", "voicing on (Z, J, GE)", "Motorul a pornit!", 1.5),
    ("praise.clearS.1", "praise", "clear S", "Sunet ascuțit, ca un ac!", 1.5),
    ("praise.clearS.2", "praise", "clear S", "Aer rece, drept!", 1.5),
    ("praise.clearSh.1", "praise", "clear Ș", "Sunet rotund și cald!", 1.5),
    ("praise.clearSh.2", "praise", "clear Ș", "Ca un tren adevărat!", 1.5),
    ("praise.rTrill.1", "praise", "R vibrated", "Limba a tremurat!", 1.5),
    ("praise.rTrill.2", "praise", "R vibrated", "Ai mârâit ca Rică!", 1.5),
    ("praise.lUp.1", "praise", "L with the tongue up", "Limba a urcat sus!", 1.5),
    ("praise.lUp.2", "praise", "L with the tongue up", "Ai cântat ca Lalalu!", 1.5),
    ("praise.threeInRow.1", "praise", "three syllables in a row", "Trei la rând!", 1.5),
    ("praise.threeInRow.2", "praise", "three syllables in a row", "Tot trenul, vagon cu vagon!", 1.5),
    ("praise.finalClear.1", "praise", "final sound kept", "Finalul s-a auzit clar!", 1.5),
    ("praise.finalClear.2", "praise", "final sound kept", "N-a fugit sunetul!", 1.5),
    ("praise.persisted.1", "praise", "success after several tries", "Ai mai încercat și a ieșit!", 1.5),
    ("praise.persisted.2", "praise", "success after several tries", "Nu te-ai lăsat!", 1.5),
    ("praise.afterTip.1", "praise", "success after an instruction", "Ai schimbat ceva și a mers!", 1.5),
    ("praise.afterTip.2", "praise", "success after an instruction", "Ai făcut exact ce ți-am arătat!", 1.5),
    ("praise.namedAlone.1", "praise", "named the picture without a model", "Ai știut tu!", 1.5),
    ("praise.namedAlone.2", "praise", "named the picture without a model", "Fără ajutor!", 1.5),
    ("praise.caughtFoni.1", "praise", "caught Foni's mix-up", "M-ai prins!", 1.5),
    ("praise.caughtFoni.2", "praise", "caught Foni's mix-up", "Ai urechi bune!", 1.5),
    ("praise.wholeSentence.1", "praise", "whole sentence", "Toată propoziția, clar!", 1.5),
    ("praise.wholeSentence.2", "praise", "whole sentence", "Fiecare cuvânt s-a auzit!", 1.5),
    ("praise.fastTwister.1", "praise", "twister at the faster tempo", "Și repede, și clar!", 1.5),
    ("praise.fastTwister.2", "praise", "twister at the faster tempo", "Limba nu s-a încurcat!", 1.5),
    ("praise.effort.1", "praise", "any honest try", "Bravo că ai încercat!", 1.5),
    ("praise.effort.2", "praise", "end of a block", "Ai lucrat frumos!", 1.5),
    ("praise.effort.3", "praise", "any honest try", "Îmi place cum încerci!", 1.5),
    # Almost.
    ("almost.1", "almost", "Almost", "Aproape!", 1.0),
    ("almost.2", "almost", "Almost", "Foarte aproape! Încă o dată.", 2.0),
    ("almost.3", "almost", "Almost", "Aproape! Mai încercăm o dată?", 2.0),
    ("almost.4", "almost", "Almost", "Era aproape. Hai încă una.", 2.0),
    # Let's try again.
    ("again.1", "again", "Retake or a miss", "Hai să mai încercăm.", 1.5),
    ("again.2", "again", "Retake or a miss", "Mai spune o dată.", 1.5),
    ("again.3", "again", "Retake or a miss", "Încă o dată, împreună cu mine.", 2.0),
    ("again.4", "again", "Retake or a miss", "Hai să-l mai spunem o dată.", 2.0),
    # The phone is unsure: ask the adult.
    ("unsure.1", "unsure", "Unsure", "Hmm, n-am auzit bine. Mai spunem o dată?", 3.0),
    ("unsure.2", "unsure", "Unsure", "Întreabă-l pe cel mare de lângă tine.", 2.5),
    ("unsure.3", "unsure", "Unsure", "Nu sunt sigur. Ce zice omul mare?", 2.5),
    ("unsure.4", "unsure", "Unsure", "Hai să întrebăm un om mare.", 2.0),
    ("unsure.5", "unsure", "Unsure", "Omul mare te-a auzit mai bine.", 2.0),
    ("unsure.6", "unsure", "Unsure", "Spune-i și omului mare.", 2.0),
    # End of session and "one more".
    ("end.1", "end", "Final", "Gata pe azi! Ascultă-te.", 2.0),
    ("end.2", "end", "Final, offer one more", "Mai vrei una mică?", 1.5),
    ("end.3", "end", "goodbye", "Pe mâine!", 1.0),
    ("end.4", "end", "Final", "Ai muncit frumos azi.", 2.0),
    ("end.5", "end", "adult's turn", "Acum e rândul omului mare.", 2.0),
    ("end.6", "end", "listen to yourself", "Ascultă cum ai spus.", 1.5),
    ("end.7", "end", "Final, offer one more", "Încă una mică? Doar dacă vrei.", 2.5),
    ("end.8", "end", "goodbye", "Ne vedem mâine!", 1.5),
    ("end.9", "end", "Final", "M-ai ajutat mult azi.", 2.0),
    ("end.10", "end", "Final", "Gata! Litera e plină.", 2.0),
    ("end.11", "end", "Final", "Ce joacă frumoasă!", 1.5),
    ("end.12", "end", "goodbye", "Pa-pa! Zbor acasă.", 1.5),
    # The lock after `isolated`.
    ("lock.1", "lock", "AdultGate", "Cheamă un om mare să te asculte.", 2.5),
    ("lock.2", "lock", "AdultGate", "Omul mare ascultă și apasă.", 2.5),
    ("lock.3", "lock", "AdultGate", "Mergem mai departe când sună bine.", 2.5),
    # Spoken to the adult.
    ("adult.1", "adult", "adult zone", "Pentru adult: vocea copilului rămâne pe telefon.", 3.5),
    ("adult.2", "adult", "adult judges a take", "Dacă a sunat bine, apăsați degetul mare.", 3.0),
    ("adult.3", "adult", "adult judges a take", "Cum a sunat? Alegeți voi.", 2.0),
    ("adult.4", "adult", "listen back together", "Ascultați împreună înregistrarea.", 2.5),
    ("adult.5", "adult", "Mission", "Pentru adult: misiunea de azi e pentru acasă.", 3.0),
    ("adult.6", "adult", "Mission", "Bifați misiunea când ați făcut-o.", 2.5),
]

# Rung names the parent sees (and that summaries fill into {rung} / {next}).
RUNG_NAMES = {
    "listen": "Ascultăm", "warmup": "Trezim gura", "isolated": "Sunetul singur", "syllables": "Silabe",
    "back": "Sunetul la urmă", "wordsInitial": "La început de cuvânt", "wordsMedial": "La mijloc de cuvânt",
    "wordsFinal": "La final de cuvânt", "clusters": "Cu alte consoane", "pairs": "Nu le încurcăm",
    "sentences": "Propoziții", "twister": "Frământări", "retell": "Povestim", "everyday": "Acasă",
}
POSITION_NAMES = {"initial": "începutul cuvântului", "medial": "mijlocul cuvântului", "final": "finalul cuvântului",
                  "cluster": "cuvintele cu alte consoane"}

PARENT_CARDS = [
    dict(id="listen-warmup", title="Ascultăm și trezim gura", rungs=["listen", "warmup"],
         target="Copilul aude diferența și își mișcă buzele și limba.",
         goodAttempt="Arată poza potrivită; ține poziția o secundă.",
         ifNot="Faceți voi primul, în oglindă. Două exerciții ajung."),
    dict(id="isolated", title="Sunetul singur", rungs=["isolated"],
         target="Sunetul sună curat, singur.",
         goodAttempt="{letter} lung și egal, 2 secunde, fără alt sunet lipit.",
         ifNot="Nu insistați. Opriți-vă și spuneți-i logopedului; treapta următoare se deschide doar când sună bine."),
    dict(id="syllables", title="Silabe", rungs=["syllables", "back"],
         target="Sunetul lipit de o vocală.",
         goodAttempt="„{syllable}” dintr-o bucată, cu {letter} clar.",
         ifNot="Despărțiți: „{split}”, apoi apropiați încet."),
    dict(id="words", title="Cuvinte", rungs=["wordsInitial", "wordsMedial", "wordsFinal", "clusters"],
         target="Sunetul în cuvinte, la început, la mijloc și la final.",
         goodAttempt="Cuvântul întreg, cu {letter} la locul lui.",
         ifNot="Spuneți voi cuvântul rar, apăsând pe sunet. După două încercări, treceți mai departe."),
    dict(id="pairs", title="Nu le încurcăm", rungs=["pairs"],
         target="Deosebește {letter} de {partner}.",
         goodAttempt="Arată corect poza, apoi spune ambele cuvinte.",
         ifNot="Rămâneți la ascultat. Foni încurcă, copilul îl prinde."),
    dict(id="speech", title="Vorbim așa", rungs=["sentences", "twister", "retell", "everyday"],
         target="Sunetul rămâne bun și când vorbește liber.",
         goodAttempt="Propoziția curge, sunetul e la locul lui.",
         ifNot="Încetiniți. Fără cronometru. În restul zilei nu corectați."),
]

TIPS = [
    "Stați față în față, la aceeași înălțime, cu telefonul între voi.",
    "Liniște în cameră: fără televizor.",
    "Puțin și des: patru minute pe zi, la aceeași oră.",
    "Opriți-vă cât încă e bine.",
    "Lăudați ce a făcut: „Ai ținut sunetul lung!”",
    "Nu-l corectați cu un „nu”. Repetați voi cuvântul, rar și frumos.",
    "Nu corectați la masă sau în fața altora.",
    "După două încercări care nu ies, treceți la ceva mai ușor.",
    "Graiul de acasă e în regulă. Lucrăm doar sunetele de la logoped.",
    "Spuneți-i logopedului ce ați auzit. Raportul din aplicație e pentru el.",
]

# {best} is the play button of the best take; the app draws it.
SUMMARIES = [
    dict(id="attempts-best", text="Azi: {n} încercări la {letter}. Cea mai bună: {best}.", vars=["n", "letter", "best"]),
    dict(id="hold-longer", text="Azi: {n} încercări. {letter} a ținut {sec} secunde, mai mult ca data trecută.",
         vars=["n", "letter", "sec"]),
    dict(id="rung-done", text="Azi ați terminat treapta „{rung}”. Mâine: „{next}”.", vars=["rung", "next"]),
    dict(id="harder", text="Azi a fost mai greu la {position}. Mâine o luăm mai ușor.", vars=["position"]),
    dict(id="unsure", text="Telefonul n-a fost sigur de {k} ori. Părerea voastră a contat.", vars=["k"]),
    dict(id="persisted", text="Spuneți-i: ai încercat de {k} ori până a ieșit.", vars=["k"]),
    dict(id="week", text="{z} zile, {n} încercări.", vars=["z", "n"]),
]

REMINDERS = [
    dict(id="anchor", text="După {anchor}, {letter} cu Foni. Patru minute.", vars=["anchor", "letter"]),
    dict(id="time", text="E ora voastră: {anchor}. Foni e gata.", vars=["anchor"]),
    dict(id="monday", text="Începem săptămâna asta? O rundă mică ajunge.", vars=[]),
    dict(id="weekly", text="Vă mai amintesc doar o dată pe săptămână. Alegeți alt moment din „Pentru adult”.", vars=[]),
]

PRIVACY = dict(
    title="Ce se întâmplă cu vocea și cu imaginea copilului",
    paragraphs=[
        "Totul rămâne pe acest telefon. Aplicația nici nu are voie să folosească internetul.",
        "Microfonul ascultă doar când pe ecran apare capsula de microfon. Păstrăm cel mult trei înregistrări "
        "pentru fiecare sunet, cel mult 90 de zile, încuiate cu o cheie a telefonului. Ele servesc la „Ascultă-te” "
        "și la „atunci și acum”.",
        "Camera lucrează ca o oglindă. Imaginile nu se salvează nicăieri.",
        "Aplicația nu cere cont sau e-mail și nu are reclame. Nimeni din echipa Fonia nu vă vede datele.",
        "Raportul pentru logoped pleacă doar dacă îl trimiteți voi, și conține numere, nu înregistrări.",
        "„Șterge tot” din „Pentru adult” șterge tot, pe loc.",
        "Fonia e un ajutor pentru exersat acasă și nu ține locul logopedului.",
    ],
)
