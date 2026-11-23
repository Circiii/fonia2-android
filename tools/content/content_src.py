"""Authoring source of the Fonia 2 content (I2, I4, I8). `build_content_v2.py` turns it into spec/content/v2/*.json.

Everything here is original, written for Fonia after docs/v2/06_METODA_SI_CONTINUT.md §4, except the twisters
marked folk (traditional folklore, named in `folkRef`). Nothing comes from Carmen Ivanov's book.
All of it is a DRAFT until a speech therapist reviews it (review.status = "draft").

Word token format: "word [emoji] [V]". No emoji = a drawing is needed; the icon keyword is the word itself.
"V" = needsTherapistCheck (enters sessions only after the therapist approves it).
Listen token: "target>foil:errorKind". Pair token: "a/b contrast [V]" (contrast = sound raw, "t", "d", "i" or "∅").
"""

CONTENT_VERSION = "2.0.0-m1"

# Planned counts per word rung (chapter 06 §4); N16 lets Z/J/GE final pass with 9-11.
PLAN = {
    "s": {"wordsInitial": 14, "wordsMedial": 12, "wordsFinal": 12, "clusters": 14},
    "ș": {"wordsInitial": 12, "wordsMedial": 12, "wordsFinal": 12, "clusters": 10},
    "z": {"wordsInitial": 12, "wordsMedial": 12, "wordsFinal": 9, "clusters": 10},
    "j": {"wordsInitial": 13, "wordsMedial": 12, "wordsFinal": 11, "clusters": 7},
    "ț": {"wordsInitial": 12, "wordsMedial": 13, "wordsFinal": 13, "clusters": 11},
    "ce": {"wordsInitial": 14, "wordsMedial": 12, "wordsFinal": 14, "clusters": 9},
    "ge": {"wordsInitial": 14, "wordsMedial": 12, "wordsFinal": 11, "clusters": 9},
    "r": {"wordsInitial": 15, "wordsMedial": 14, "wordsFinal": 16, "clusters": 15},
    "l": {"wordsInitial": 15, "wordsMedial": 15, "wordsFinal": 16, "clusters": 15},
}

# Typical confusion partners: never inside the early rungs (syllables … wordsFinal) of the sound.
CONFUSIONS = {
    "s": ["ș", "z", "ț"],
    "ș": ["s", "j"],
    "z": ["s", "j"],
    "j": ["z", "ș"],
    "ț": ["s", "ce"],
    "ce": ["ț", "ș", "ge"],
    "ge": ["ce", "z", "j"],
    "r": ["l"],
    "l": ["r"],
}

ORDER = ["s", "ș", "z", "j", "ț", "ce", "ge", "r", "l"]
SLUG = {"s": "s", "ș": "sh", "z": "z", "j": "j", "ț": "ts", "ce": "ce", "ge": "ge", "r": "r", "l": "l"}
RUNGS = ["listen", "warmup", "isolated", "syllables", "back", "wordsInitial", "wordsMedial", "wordsFinal",
         "clusters", "pairs", "sentences", "twister", "retell", "everyday"]

# The fifteen warm-ups of chapter 06 §5 (library I3); the sound files only reference their ids.
WARMUPS = {
    "burta-balon": "Mâna pe burtă. Umflă balonul, apoi suflă lung.",
    "pupic": "Fă gura ca pentru un pupic!",
    "zambet": "Zâmbește cât poți de larg!",
    "zambet-pupic": "Schimbă: zâmbet, pupic, zâmbet, pupic!",
    "hipopotam": "Deschide gura mare, ca un hipopotam!",
    "motorasul-buzelor": "Buze moi. Fă ca motorul: brrr!",
    "calul": "Plescăie cu limba: clop, clop, clop!",
    "catelusul": "Scoate limba și bag-o la loc!",
    "ceasul": "Mută limba dintr-un colț în altul!",
    "cameleon": "Întinde limba spre nas, apoi spre bărbie!",
    "limba-sus-gura": "Deschide gura. Urcă limba după dinții de sus.",
    "gat-vibreaza": "Palma pe gât. Spune lung: mmmm.",
    "sarpele-lung": "Sâsâie cât mai lung, ca un șarpe.",
    "morisca": "Suflă tare în morișcă! Acum încet.",
    "periuta": "Limba e periuță. Atinge fiecare dinte.",
}

# Chapter lines (open.<slug>.<rung>) that are the same shape for every hero; {name} and {letter} are filled in.
CHAPTER_TEMPLATES = {
    "warmup": "Înainte de joacă, trezim gura împreună cu {name}.",
    "syllables": "{name} pune sunetul lângă vocale. Urcă în trenul de silabe!",
    "back": "Acum sunetul vine la urmă. {name} te așteaptă la capăt.",
    "wordsInitial": "{name} a pierdut cuvintele care încep cu {letter}. Le căutăm?",
    "wordsMedial": "Unele cuvinte ascund {letter} la mijloc. Le găsim împreună?",
    "wordsFinal": "Acum {letter} stă la final. Ține-l până la capăt!",
    "clusters": "{letter} are vecini noi, alte consoane. Hai să-i cunoaștem!",
    "pairs": "Două cuvinte seamănă mult. {name} le încurcă. Ajuți?",
    "sentences": "{name} spune povești scurte. Spune-le și tu, cuvânt cu cuvânt.",
    "twister": "O frământare! Întâi rar, ca melcul. Apoi puțin mai repede.",
    "retell": "Uite trei poze cu {name}. Ce s-a întâmplat?",
    "everyday": "Azi {letter} vine cu tine acasă. Ai o misiune!",
}

SOUNDS = {
    "s": dict(
        letter="S", graphemes=["s"], voiced=False, holdMode="sustain", typicalByAge=5,
        hero=dict(name="Sâsâilă", who="șarpele", emoji="🐍", says="sssss",
                  line="Sâsâilă alunecă prin iarbă: sssss. Sâsâie și tu cu el!",
                  listen="Ascultă bine! Sâsâilă încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Sâsâilă sâsâie lung. Sâsâie și tu, cât poți!"),
        howParent=["Zâmbet larg, dinții aproape unul de altul.", "Vârful limbii stă jos, în spatele dinților de jos.",
                   "Aerul iese rece, drept pe mijloc."],
        warmups=["zambet", "sarpele-lung", "periuta", "morisca", "zambet-pupic"],
        hold=dict(model="sss", prompts=["Sâsâie lung, ca Sâsâilă!", "Sâsâie încet, apoi mai tare!",
                                        "Sâsâie până se umple litera!"]),
        cv="sa so su se si să sâ", vc="as os us es is ăs", vcv="asa oso usu ese isi ăsă",
        listen="sanie>tanie:stopping; supă>șupă:placeSwap; suc>tuc:stopping; casă>cașă:placeSwap; "
               "masă>mașă:placeSwap; pisoi>pitoi:stopping; sos>șoș:placeSwap; vase>vate:stopping",
        wordsInitial="sanie 🛷; supă 🍲; suc 🧃; sac; sapă ⛏️; sos 🥫; sobă; sabie 🗡️; săpun 🧼; soare ☀️; sare 🧂; "
                     "salată 🥗; sandale 👡; semafor 🚦",
        wordsMedial="pisică 🐱; casă 🏠; masă; oase 🦴; pisoi 🐈; vase 🍽️; desen 🖍️; nisip 🏖️; fasole 🫘; "
                    "păsărică 🐦; busolă 🧭 V; coasă V",
        wordsFinal="nas 👃; os 🦴; ananas 🍍; cactus 🌵; sos 🥫; vas 🏺; pas 👣; cocos 🥥; compas 📐; tenis 🎾; "
                   "ceas ⌚; atlas 🗺️ V",
        clusters="stea ⭐; scaun 🪑; muscă 🪰; mască 🎭; vestă 🦺; spic 🌾 V; spital 🏥; castel 🏰; stilou 🖋️; "
                 "cosmonaut 🧑‍🚀; urs 🐻; castravete 🥒; strugure 🍇; rucsac 🎒",
        pairs="nas/naș ș; muscă/mușcă ș; cos/coș ș V; sar/zar z; vase/vaze z; sobă/tobă t; sac/tac t; sap/țap ț V",
        sentences=["Sanda pune supa pe masă.", "Pisica a sosit acasă.", "Sami bea suc de ananas.",
                   "În sac sunt sandale.", "Pisoiul stă sub masă.", "Vasul e pe sobă.", "Am un nas și un os."],
        stories=[
            (["🛷", "🐱", "🏠"], ["Sami are o sanie nouă.", "Pe sanie urcă o pisică.", "Sami și pisica ajung acasă."]),
            (["🍲", "🍽️", "🐈"], ["Mama pune supa pe masă.", "Un pisoi sare pe masă.", "Pisoiul vrea supă!"]),
            (["🎒", "🍍", "🧃"], ["În sac e un ananas.", "Sanda taie ananasul.", "Acum avem suc de ananas!"]),
        ],
        missions=["La masă, cere: sare, te rog!", "Găsiți trei lucruri care încep cu S.", "La culcare: somn ușor!",
                  "În parc, strigă: sus, sus!", "Numără pașii până la ușă: un pas, doi pași."],
    ),
    "ș": dict(
        letter="Ș", graphemes=["ș"], voiced=False, holdMode="sustain", typicalByAge=5,
        hero=dict(name="Șușu", who="trenulețul", emoji="🚂", says="șșșș",
                  line="Trenulețul Șușu pleacă la drum: șșșș! Suflă și tu, ca trenul.",
                  listen="Ascultă bine! Trenulețul Șușu încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Șușu suflă lung, ca trenul. Suflă și tu, cât poți!"),
        howParent=["Buzele rotunde, ca pentru un pupic.", "Limba urcă puțin și se face ca o ceșcuță.",
                   "Aerul iese cald, pe mijloc."],
        warmups=["pupic", "zambet-pupic", "cameleon", "morisca"],
        hold=dict(model="șșș", prompts=["Suflă lung, ca trenulețul Șușu!", "Buze rotunde și suflă lung!",
                                        "Suflă până ajunge trenul în gară!"]),
        cv="șa șo șu șe și șă șâ", vc="aș oș uș eș iș ăș", vcv="așa oșo ușu eșe iși ășă",
        listen="mașină>masină:placeSwap; ușă>usă:placeSwap; șapcă>sapcă:placeSwap; păpușă>păpusă:placeSwap; "
               "șal>tal:stopping; cămașă>cămasă:placeSwap; duș>du:omission; șină>sină:placeSwap",
        wordsInitial="șah ♟️; șal 🧣; șapcă 🧢; șapte 7️⃣; șină 🛤️; șoim 🦅; șuncă 🥓; șarpe 🐍; șurub 🔩; "
                     "șofer 🚕; șoarece 🐭; șopârlă 🦎",
        wordsMedial="mașină 🚗; ușă 🚪; cămașă 👔; păpușă 🪆; cușcă; pișcot 🍪; roșie 🍅; cireșe 🍒; "
                    "mașinuță 🏎️; mușețel 🌼; mătușă 👵 V; pușculiță 🐖 V",
        wordsFinal="coș 🧺; cocoș 🐓; duș 🚿; iepuraș 🐇; copilaș 👶; mieluș 🐑; pantofaș 👟; gălbenuș 🍳; "
                   "cățeluș 🐕; fluturaș 🦋; măgăruș 🫏; oraș 🏙️",
        clusters="pește 🐟; căști 🎧; poștă 📮; școală 🏫; căpșună 🍓; peștișor 🐠; știulete 🌽; ștampilă; "
                 "broaște 🐸; șnur V",
        pairs="naș/nas s; mușcă/muscă s; pește/peste s; coș/cos s V; șoc/joc j V; șir/jir j V",
        sentences=["Mașina stă lângă ușă.", "Păpușa are o șapcă.", "Cocoșul șade pe coș.", "Am șase șosete noi.",
                   "Mielușul face duș.", "Iepurașul a șoptit ceva.", "Șoimul e pe șină."],
        stories=[
            (["🚗", "🚪", "🪆"], ["Mașina oprește la ușă.", "Din mașină coboară o păpușă.", "Păpușa bate la ușă."]),
            (["🐇", "🧺", "🧢"], ["Iepurașul are un coș.", "În coș e o șapcă.", "Iepurașul își pune șapca."]),
            (["🐓", "🚿", "👔"], ["Cocoșul s-a murdărit.", "Cocoșul face duș.", "Apoi își pune o cămașă."]),
        ],
        missions=["Când te încalți: întâi șosetele!", "La culcare, șoptește: șșș, doarme!",
                  "În mașină, numărați mașinile.", "Închide ușa și spune: ușa!", "La baie: fac duș!"],
    ),
    "z": dict(
        letter="Z", graphemes=["z"], voiced=True, holdMode="sustain", typicalByAge=5,
        hero=dict(name="Zuzu", who="albinuța", emoji="🐝", says="zzzz",
                  line="Albinuța Zuzu zboară din floare în floare: zzzz! Palma pe gât și bâzâie cu ea.",
                  listen="Ascultă bine! Albinuța Zuzu încurcă uneori cuvintele. O prinzi?",
                  isolated="Zuzu bâzâie lung. Bâzâie și tu, cât poți!"),
        howParent=["Zâmbet larg, ca la S.", "Vârful limbii jos, în spatele dinților de jos.",
                   "Palma pe gât: trebuie să bâzâie."],
        warmups=["zambet", "gat-vibreaza", "sarpele-lung", "periuta"],
        hold=dict(model="zzz", prompts=["Bâzâie lung, ca Zuzu!", "Palma pe gât și bâzâie!",
                                        "Bâzâie până zboară albinuța!"]),
        cv="za zo zu ze zi ză zâ", vc="az oz uz ez iz ăz", vcv="aza ozo uzu eze izi ăză",
        listen="zână>sână:devoicing; zid>sid:devoicing; vază>vasă:devoicing; buză>bujă:placeSwap; "
               "zece>dece:stopping; poză>pojă:placeSwap; zâmbet>jâmbet:placeSwap; muzică>mujică:placeSwap",
        wordsInitial="zână 🧚; zăpadă ❄️; zece 🔟; zid 🧱; zambilă 🪻; zâmbet 😀; zero 0️⃣; zar 🎲; ziar 📰; "
                     "zebră 🦓; zahăr; zimbru 🦬 V",
        wordsMedial="vază 🏺; buză 👄; poză 🖼️; muzică 🎵; cozonac 🍞; magazin 🏪; cazan V; gazon V; mazăre 🫛; "
                    "dinozaur 🦕; televizor 📺; bluză 👚",
        wordsFinal="autobuz 🚌; roz 🩷; orez 🍚; obraz; praz; troleibuz 🚎; ovăz 🌾 V; gaz V; miez V",
        clusters="zmeu 🪁; cizme 👢; mânz 🐎; ghiozdan 🎒; frunză 🍃; brânză 🧀; varză 🥬; zbor ✈️; zmeură; "
                 "zgârie-nori 🏙️",
        pairs="zar/sar s; vaze/vase s; zare/sare s; zar/jar j; cozi/coji j",
        sentences=["Zâna zice bună ziua.", "Vaza e pe zid.", "Zoe vede zece poze.", "În magazin e muzică.",
                   "Zâna zâmbește în zăpadă.", "Buzele zânei sunt roz.", "Zoe ia un cozonac."],
        stories=[
            (["🧚", "❄️", "🪁"], ["O zână zboară peste zăpadă.", "Pe zăpadă găsește un zmeu.",
                                 "Zâna ridică zmeul în vânt."]),
            (["🏺", "🪻", "🧱"], ["Zoe are o vază goală.", "Pune în vază o zambilă.", "Vaza stă lângă zid."]),
            (["🚌", "🏪", "🍞"], ["Zoe merge cu autobuzul.", "Coboară la magazin.", "Cumpără un cozonac."]),
        ],
        missions=["La prânz: bună ziua!", "Numără până la zece.", "Vezi o albină? Bâzâie: zzz!",
                  "Găsiți ceva roz în casă.", "Pe stradă, căutați trecerea de zebră."],
    ),
    "j": dict(
        letter="J", graphemes=["j"], voiced=True, holdMode="sustain", typicalByAge=5,
        hero=dict(name="Vâjâilă", who="vântul", emoji="🌬️", says="jjjj",
                  line="Vântul Vâjâilă suflă printre frunze: jjjj! Palma pe gât și suflă cu el.",
                  listen="Ascultă bine! Vântul Vâjâilă încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Vâjâilă vâjâie lung. Vâjâie și tu, cât poți!"),
        howParent=["Buzele rotunde, ca la Ș.", "Limba urcă puțin, ca o ceșcuță.", "Palma pe gât: trebuie să vibreze."],
        warmups=["pupic", "gat-vibreaza", "zambet-pupic", "cameleon"],
        hold=dict(model="jjj", prompts=["Suflă lung, ca vântul Vâjâilă!", "Buze rotunde, palma pe gât!",
                                        "Suflă până se mișcă frunzele!"]),
        cv="ja jo ju je ji jă jâ", vc="aj oj uj ej ij ăj", vcv="aja ojo uju eje iji ăjă",
        listen="jeleu>șeleu:devoicing; joc>zoc:placeSwap; ojă>oză:placeSwap; cojoc>cozoc:placeSwap; "
               "bagaj>bagaz:placeSwap; ruj>ruș:devoicing; joben>doben:stopping; pijama>pizama:placeSwap",
        wordsInitial="joc 🧩; jeleu 🍬; jos ⬇️; joben 🎩; jambon 🍖; jachetă 🧥; jar 🔥; jaguar 🐆; jucărie 🧸; "
                     "junglă 🌴; jurnal 📓; jeton 🪙 V; judo 🥋 V",
        wordsMedial="ojă 💅; cojoc; pijama; coajă; bujor 🌺; plajă 🏖️; stejar 🌳; ajutor 🆘; vijelie 🌪️; "
                    "bijuterie 💎; prăjitură 🍰; vrăjitoare 🧙‍♀️",
        wordsFinal="ruj 💄; etaj 🏢; bagaj 🧳; bandaj 🩹; mesaj 💬; garaj; patinaj ⛸️; ambalaj 📦; peisaj 🏞️ V; "
                   "masaj V; colaj V",
        clusters="cârjă 🩼; grajd; aranjament 💐; drojdie V; jgheab V; barjă V; jneapăn V",
        pairs="jar/zar z; coji/cozi z; joc/șoc ș V; jir/șir ș V",
        sentences=["Jeleul e jos.", "Jojo joacă un joc nou.", "Pijamaua e în bagaj.", "Mama are ojă și ruj.",
                   "Bagajul e la etaj.", "Jana ajută un pui.", "Cojocul e jos, lângă joben."],
        stories=[
            (["🧸", "⬇️", "🆘"], ["Jana are o jucărie.", "Jucăria cade jos.", "Jojo vine în ajutor."]),
            (["🧳", "🏖️", "🧩"], ["Jojo își face bagajul.", "Merge la plajă.", "Pe plajă alege un joc."]),
            (["🧥", "🌪️", "🔥"], ["Bunicul își ia cojocul.", "Afară bate o vijelie.", "În sobă, jarul ține de cald."]),
        ],
        missions=["Cade ceva? Spune: e jos!", "Cere ajutor: mă ajuți, te rog?", "Alege un joc și spune-i numele.",
                  "Seara: îmi iau pijamaua!", "La plecare: unde e bagajul?"],
    ),
    "ț": dict(
        letter="Ț", graphemes=["ț"], voiced=False, holdMode="burst", typicalByAge=5,
        hero=dict(name="Țuțu", who="șoricelul", emoji="🐭", says="ț-ț-ț",
                  line="Șoricelul Țuțu e mic și iute: ț-ț-ț! Fă și tu ca el, scurt și repede.",
                  listen="Ascultă bine! Șoricelul Țuțu încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Țuțu face pași mici, scurt și repede. Fă și tu!"),
        howParent=["Zâmbet, dinții aproape.", "Vârful limbii atinge o clipă dinții de sus.",
                   "Apoi aerul iese scurt și rece."],
        warmups=["zambet", "periuta", "zambet-pupic", "burta-balon"],
        hold=dict(model="ț-ț-ț", prompts=["Scurt și repede, ca Țuțu!", "Atinge dinții o clipă, apoi suflă!",
                                          "Trei pași mici, ca șoricelul!"]),
        cv="ța țo țu țe ți ță țâ", vc="aț oț uț eț iț ăț", vcv="ața oțo uțu ețe iți ăță",
        listen="țap>tap:stopping; ață>asă:deaffrication; cuțit>cusit:deaffrication; fetiță>fetită:stopping; "
               "băț>băci:affricatePlace; pătuț>pătus:deaffrication; țintă>cintă:affricatePlace; puiuț>puiut:stopping",
        wordsInitial="țap 🐐; țintă 🎯; țânc 🧒; țepi 🦔; țeavă; țânțar 🦟; țurțur 🧊; țarc; țăran 🧑‍🌾; "
                     "țară 🗺️ V; țiglă V; țelină V",
        wordsMedial="ață 🧵; bețe 🥢; cuțit 🔪; fetiță 👧; gheață 🧊; lăbuță 🐾; cățel 🐶; rață 🦆; bufniță 🦉; "
                    "periuță 🪥; brățară 📿; bunicuță 👵; linguriță 🥄",
        wordsFinal="băț 🪵; pătuț 🛏️; puiuț 🐥; coteț; bănuț 🪙; căluț 🐴; brăduț 🎄; ghemuleț 🧶; colț; lanț ⛓️; "
                   "prinț 🤴; puț V; hoț V",
        clusters="lanț ⛓️; dinți 🦷; munți ⛰️; colț; prinț 🤴; cărți 📚; porți; arțar 🍁; țânțar 🦟; șorț; "
                 "mărțișor 🎀",
        pairs="ține/tine t; laț/lat t; ațe/ace ce; țel/cel ce V; țap/sap s V",
        sentences=["Fetița ține o ață.", "Puiuțul e în pătuț.", "Pițu ține un băț.", "Țapul e lângă coteț.",
                   "Cățelul are un băț.", "Pe gheață e un puiuț.", "Bunicuța taie pâine cu un cuțit."],
        stories=[
            (["👧", "🧵", "🐶"], ["Fetița are un ghem de ață.", "Cățelul vede ața.", "Cățelul se joacă cu ața."]),
            (["🐥", "🛏️", "🦟"], ["Puiuțul doarme în pătuț.", "Vine un țânțar.", "Țânțarul zboară departe."]),
            (["🐐", "🏚️", "🪵"], ["Un țap iese din coteț.", "Țapul găsește un băț.", "Țapul duce bățul acasă."]),
        ],
        missions=["Când primești ceva: mulțumesc!", "La periuță: dinți curați!", "Mai vrei? Spune: încă puțin.",
                  "Numără degetele: toți zece!", "Afară e frig? Spune: ce ger, ce gheață!"],
    ),
    "ce": dict(
        letter="CE", graphemes=["ce", "ci"], voiced=False, holdMode="burst", typicalByAge=5,
        hero=dict(name="Cici", who="stropitoarea", emoji="💧", says="ci-ci-ci",
                  line="Stropitoarea Cici udă florile: ci-ci-ci! Stropește și tu, scurt, ca ea.",
                  listen="Ascultă bine! Stropitoarea Cici încurcă uneori cuvintele. O prinzi?",
                  isolated="Cici stropește florile, scurt și repede. Stropește și tu!"),
        howParent=["Buzele rotunde, ca la Ș.", "Limba atinge o clipă cerul gurii, apoi se desprinde.",
                   "Aerul iese scurt și cald."],
        warmups=["pupic", "zambet-pupic", "calul", "burta-balon"],
        hold=dict(model="ci-ci-ci", prompts=["Scurt, ca stropitoarea Cici!", "Buze rotunde, apoi un strop scurt!",
                                             "Trei stropi mici, unul după altul!"]),
        cv="cea cio ciu ce ci", vc="aci oci uci eci ici", vcv="ace oce uce ece ice",
        listen="ceas>țeas:affricatePlace; ceai>țai:affricatePlace; cinci>șinși:deaffrication; "
               "ciocan>țocan:affricatePlace; nuci>nuți:affricatePlace; zece>zețe:affricatePlace; "
               "papuci>papuși:deaffrication; vaci>vati:stopping",
        wordsInitial="ceas ⌚; ceai 🍵; ceapă 🧅; cioc; cină 🍽️ V; cinci 5️⃣; cerc ⭕; cerb 🦌; ciocan 🔨; "
                     "ciorap 🧦; ciorbă 🍲; cizme 👢; ciupercă 🍄; ciocolată 🍫",
        wordsMedial="ace 🪡; zece 🔟; cocean 🌽; vecin V; Crăciun 🎅; căciulă; berbece 🐏; foarfece ✂️; picior 🦶; "
                    "bicicletă 🚲; felicitare 💌; motocicletă 🏍️",
        wordsFinal="nuci 🌰; vaci 🐄; maci 🌺; saci; cinci 5️⃣; raci 🦞; melci 🐌; porci 🐖; papuci 🥿; copaci 🌳; "
                   "bunici 👴; arici 🦔; colaci 🥯; furnici 🐜",
        clusters="cinci 5️⃣; melci 🐌; porci 🐖; purcel 🐷; bocanci 🥾; urcior 🏺; salcie; dulciuri 🍬; dulceață 🍯",
        pairs="ace/ațe ț; cină/șină ș; cine/tine t; cer/ger ge; reci/regi ge",
        sentences=["Ce faci, vecine?", "Cici bea ceai cu nuci.", "Bunicii au cinci vaci.", "Cici ține un ciocan.",
                   "La cină avem ceapă.", "Am zece ace și cinci nuci.", "Cinci nuci cad pe ceas."],
        stories=[
            (["🦔", "🌰", "🌳"], ["Ariciul iese la plimbare.", "Ariciul găsește nuci.", "Duce nucile sub copaci."]),
            (["⌚", "🍽️", "🍵"], ["Ceasul sună: e seară.", "Toți stau la cină.", "La final, bem un ceai."]),
            (["👴", "🐄", "🔨"], ["Bunicii au o fermă.", "Pe fermă sunt cinci vaci.", "Bunicul repară gardul cu un ciocan."]),
        ],
        missions=["Întreabă pe cineva: ce faci?", "Numără treptele până la cinci.", "Seara: îmi citești ceva?",
                  "La masă: ce bun e!", "Arată cinci degete și spune: cinci!"],
    ),
    "ge": dict(
        letter="GE", graphemes=["ge", "gi"], voiced=True, holdMode="burst", typicalByAge=5,
        hero=dict(name="Gigel", who="motorașul", emoji="🛵", says="gi-gi-gi",
                  line="Motorașul Gigel pornește: gi-gi-gi! Palma pe gât și pornește-l și tu.",
                  listen="Ascultă bine! Motorașul Gigel încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Gigel pornește scurt, de trei ori. Pornește-l și tu!"),
        howParent=["Buzele rotunde, ca la CE.", "Limba atinge o clipă cerul gurii.", "Palma pe gât: trebuie să vibreze."],
        warmups=["pupic", "gat-vibreaza", "zambet-pupic", "burta-balon"],
        hold=dict(model="gi-gi-gi", prompts=["Pornește motorașul Gigel!", "Palma pe gât, apoi scurt!",
                                             "Trei porniri mici, una după alta!"]),
        cv="gea gio giu ge gi", vc="agi ogi ugi egi igi", vcv="age oge uge ege ige",
        listen="geam>ceam:devoicing; gem>dem:stopping; geantă>zeantă:affricatePlace; deget>decet:devoicing; "
               "mingi>minji:affricatePlace; pungi>punzi:affricatePlace; magie>mazie:affricatePlace; "
               "pagină>pacină:devoicing",
        wordsInitial="gem 🫙; ger 🥶; gel 🧴; geam 🪟; geacă 🧥; geantă 👜; gips V; geană 👁️ V; gemeni 👯; "
                     "girafă 🦒; gigant V; genunchi 🦵; gimnastică 🤸; girofar 🚨 V",
        wordsMedial="rege 🤴; deget ☝️; magie 🪄; magiun; regină 👸; pagină 📄; fulgere ⚡; agendă 📒 V; "
                    "imagine 🖼️ V; legendă V; frigider; vegetale 🥦 V",
        wordsFinal="fulgi ❄️; pungi 🛍️; mingi ⚽; blugi 👖; fragi; fagi V; dragi V; lungi V; fugi V; covrigi 🥨; "
                   "colegi 🧑‍🤝‍🧑",
        clusters="minge 🏀; ninge 🌨️; merge 🚶; plânge 😢; înger 👼; argint 🥈; argilă; fulgere ⚡; mărgele 📿",
        pairs="ger/cer ce; regi/reci ce; fagi/faci ce; magi/maci ce V",
        sentences=["Gina are gem pe deget.", "Geanta e lângă geam.", "Mingea e în geantă.", "Afară ninge și e ger.",
                   "Gelu pune gem în pungi.", "Gigi întoarce o pagină.", "Gemenii au mingi noi."],
        stories=[
            (["🦒", "🪟", "👜"], ["O girafă se uită pe geam.", "Vede o geantă pe masă.", "Girafa ia geanta cu gâtul lung."]),
            (["🫙", "☝️", "🥨"], ["Gina deschide borcanul cu gem.", "Gemul ajunge pe deget.", "Gina unge gem pe covrigi."]),
            (["⚽", "❄️", "🧥"], ["Gelu se joacă cu mingea.", "Încep să cadă fulgi.", "Gelu își pune geaca."]),
        ],
        missions=["Uită-te pe geam și spune ce vezi.", "La micul dejun: pâine cu gem!", "La plecare: îmi iau geanta!",
                  "Ninge? Spune: ninge!", "Arată un deget și numește-l."],
    ),
    "r": dict(
        letter="R", graphemes=["r"], voiced=True, holdMode="burst", typicalByAge=5,
        hero=dict(name="Rică", who="tigrul", emoji="🐯", says="rrr",
                  line="Tigrul Rică mârâie când se joacă: rrr! Mârâie și tu, ca un tigru.",
                  listen="Ascultă bine! Tigrul Rică încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Rică mârâie când se joacă. Mârâie și tu, ca el!"),
        howParent=["Gura puțin deschisă.", "Vârful limbii urcă după dinții de sus.",
                   "Aerul face vârful limbii să vibreze. O bătaie e bună."],
        warmups=["hipopotam", "motorasul-buzelor", "calul", "limba-sus-gura", "ceasul"],
        hold=dict(model="rrr", prompts=["Mârâie ca tigrul Rică!", "Vârful limbii sus, apoi mârâie!",
                                        "Pornește motorul: scurt, apoi lung!"]),
        cv="ra ro ru re ri ră râ", vc="ar or ur er ir ăr", vcv="ara oro uru ere iri ără",
        listen="roată>loată:rToL; robot>lobot:rToL; iepure>iepule:rToL; nor>nol:rToL; rață>iață:rToGlide; "
               "ramă>amă:rToVowel; vapor>vapoi:rToGlide; rechin>lechin:rToL",
        wordsInitial="rac 🦀; râu 🏞️; ramă 🖼️; râmă 🪱; radio 📻; roată 🛞; robot 🤖; raton 🦝; rechin 🦈; "
                     "rachetă 🚀; robinet 🚰; rață 🦆; roșie 🍅; rochie 👗; rinocer 🦏",
        wordsMedial="pară 🍐; mure 🫐; pirat 🏴‍☠️; aripă 🪽; iepure 🐰; ureche 👂; vioară 🎻; coroană 👑; pădure 🌲; "
                    "burete 🧽; baterie 🔋; girafă 🦒; sirenă 🧜; parașută 🪂",
        wordsFinal="măr 🍎; nor ☁️; păr 💇; vapor 🚢; pahar 🥛; piper 🌶️; covor; motor; castor 🦫; pompier 🧑‍🚒; "
                   "bucătar 🧑‍🍳; car V; zar 🎲; ziar 📰; picior 🦶; dinozaur 🦕",
        clusters="tren 🚆; trei 3️⃣; drum 🛣️; cort ⛺; arc 🏹; barcă ⛵; tigru 🐅; creion ✏️; morcov 🥕; dragon 🐉; "
                 "furnică 🐜; tractor 🚜; trompetă 🎺; trandafir 🌹; broască 🐸",
        pairs="rac/lac l; ramă/lamă l; car/cal l; var/val l; rege/lege l; rață/ață ∅; rac/ac ∅",
        sentences=["Racul are o roată.", "Robotul repară radioul.", "Rada are o pară.", "Pe râu e o rață.",
                   "Iepurele are urechi mari.", "Norul e sus, pe cer.", "Vaporul are motor.", "Trei tigri trag un tren."],
        stories=[
            (["🦀", "🏞️", "🦆"], ["Un rac iese din râu.", "Pe râu înoată o rață.", "Racul și rața se joacă."]),
            (["🤖", "🛞", "🚀"], ["Robotul a pierdut o roată.", "Rică îi aduce roata.", "Robotul pleacă cu racheta."]),
            (["🐰", "🌲", "🍎"], ["Un iepure fuge prin pădure.", "Sub un copac e un măr.", "Iepurele ronțăie mărul."]),
        ],
        missions=["La gustare: un măr, te rog!", "Găsiți trei lucruri roșii.", "Mârâie ca un tigru: rrr!",
                  "Spune cuiva: mersi frumos!", "La culcare: noapte bună, somn ușor!"],
    ),
    "l": dict(
        letter="L", graphemes=["l"], voiced=True, holdMode="sustain", typicalByAge=5,
        hero=dict(name="Lalalu", who="leul", emoji="🦁", says="lll",
                  line="Leul Lalalu cântă cu limba sus: la-la-la! Cântă și tu cu el.",
                  listen="Ascultă bine! Leul Lalalu încurcă uneori cuvintele. Îl prinzi?",
                  isolated="Lalalu cântă cu limba sus. Cântă și tu, lung!"),
        howParent=["Gura deschisă, ca la «a».", "Vârful limbii se lipește sus, după dinții de sus.",
                   "Vocea curge pe lângă limbă."],
        warmups=["hipopotam", "catelusul", "ceasul", "limba-sus-gura", "cameleon"],
        hold=dict(model="lll", prompts=["Cântă lung, ca leul Lalalu!", "Limba sus, apoi cântă!",
                                        "Ține limba lipită și cântă!"]),
        cv="la lo lu le li lă lâ", vc="al ol ul el il ăl", vcv="ala olo ulu ele ili ălă",
        listen="lună>iună:lToGlide; lapte>rapte:lToR; balon>baion:lToGlide; lup>iup:lToGlide; inel>iner:lToR; "
               "telefon>teiefon:lToGlide; copil>copir:lToR; lalea>ralea:lToR",
        wordsInitial="lac; leu 🦁; lup 🐺; lună 🌙; lift 🛗; lupă 🔍; lapte 🥛; lampă 🪔; limbă 👅; lalea 🌷; "
                     "lacăt 🔒; lopată 🪏; lămâie 🍋; lebădă 🦢; liliac 🦇",
        wordsMedial="bilă 🎱; ulei; balon 🎈; pilot 🧑‍✈️; palat 🏰; colac 🛟; bilet 🎫; delfin 🐬; halat 🥼 V; "
                    "cămilă 🐫; balenă 🐋; salată 🥗; telefon 📱; elefant 🐘; ciocolată 🍫",
        wordsFinal="cal 🐎; val 🌊; bol 🥣; inel 💍; miel 🐑; copil 🧒; tunel 🚇; hotel 🏨; fotbal ⚽; papagal 🦜; "
                   "vițel 🐄; cățel 🐶; castel 🏰; spital 🏥; ghiocel; clopoțel 🔔",
        clusters="melc 🐌; bloc 🏢; plic ✉️; glob 🌍; clovn 🤡; flaut 🪈; vulpe 🦊; albină 🐝; ploaie 🌧️; "
                 "clătite 🥞; bluză 👚; plajă 🏖️; floare 🌸; fluture 🦋; clopoțel 🔔",
        pairs="lac/rac r; cal/car r; lamă/ramă r; val/var r; lege/rege r; lac/iac i V",
        sentences=["Lupul bea lapte.", "Luna e lângă lac.", "Lili are un balon alb.", "Calul e pe deal.",
                   "Copilul ține un inel.", "Elefantul e la bal.", "Delfinul face un val.", "Papagalul cântă la-la-la."],
        stories=[
            (["🦁", "🎈", "🌙"], ["Leul Lalalu are un balon.", "Balonul zboară sus.", "Balonul ajunge la lună."]),
            (["🐺", "🏞️", "🦢"], ["Un lup merge la lac.", "Pe lac plutește o lebădă.", "Lupul o salută."]),
            (["🧒", "🐎", "⛰️"], ["Un copil are un cal.", "Copilul urcă pe cal.", "Calul aleargă pe deal."]),
        ],
        missions=["Dimineața: vreau lapte, te rog!", "La plecare: la revedere!", "Seara, căutați luna.",
                  "Cântă la-la-la la baie.", "Numește ce e alb în cameră."],
    ),
}

# Emoji for pair words that do not appear in any word list.
PAIR_EMOJI = {
    "sare": "🧂", "țel": "🎯", "cel": "👉", "maci": "🌺",
    "naș": "🤵", "mușcă": "😬", "coș": "🧺", "cos": "🪡", "sar": "🦘", "vaze": "🏺", "tobă": "🥁", "tac": "🎱",
    "țap": "🐐", "sap": "⛏️", "peste": "🌉", "joc": "🧩", "șoc": "⚡", "șir": "🔗", "jir": "🌰", "zare": "🌅",
    "jar": "🔥", "cozi": "🎀", "coji": "🥔", "tine": "🫵", "ține": "🤲", "laț": "🪢", "lat": "↔️", "țeapă": "🌵",
    "ațe": "🧵", "cine": "❓", "cer": "🌌", "ger": "🥶", "reci": "🧊", "regi": "🤴", "faci": "🛠️", "fagi": "🌳",
    "magi": "🧙", "lac": "🏞️", "lamă": "🔪", "cal": "🐎", "var": "🪣", "val": "🌊", "lege": "📜", "rege": "🤴",
    "ac": "🪡", "iac": "🐃", "șină": "🛤️", "rac": "🦀", "ramă": "🖼️", "car": "🛒", "rață": "🦆", "ață": "🧵",
    "ceapă": "🧅", "ace": "🪡", "cină": "🍽️", "muscă": "🪰", "nas": "👃", "vase": "🍽️", "sobă": "🔥", "sac": "🎒",
    "zar": "🎲", "pește": "🐟",
}

# I4: twisters. Folk ones are traditional folklore, written from common knowledge (not from any book);
# `folkRef` says what is kept. Originals 1-18 are chapter 06 §6.2; the rest were written for Fonia.
TWISTERS = [
    ("s", "Sus pe sanie stă Sami, sub sanie stă un sac.", "original", None, "4+"),
    ("s", "Sosul sare-n supă, supa sare-n sos.", "original", None, "4+"),
    ("s", "Sus, sus, sus, sare Sami sus.", "original", None, "4+"),
    ("s,ș", "Șase sași în șase saci.", "folk",
     "Traditional Romanian tongue twister (oral folklore), full text. Given after the S-Ș pairs.", "4+"),
    ("ș", "Păpușa Șuși șade-n coș și șoptește: șșș!", "original", None, "4+"),
    ("ș", "Șase șoimi șoptesc și șed pe șine.", "original", None, "4+"),
    ("ș", "Știu că știi că știu că știi.", "folk", "Traditional Romanian tongue twister (oral folklore), short form.", "7+"),
    ("z", "Zoe și Zizi zăresc zece zâne pe zid.", "original", None, "4+"),
    ("z", "Zuzu zice zi de zi: zum, zum, zum!", "original", None, "4+"),
    ("z", "Zuzu zboară, Zizi zâmbește.", "original", None, "4+"),
    ("j", "Jojo joacă jos un joc cu jeleu.", "original", None, "4+"),
    ("j", "Jana ajunge jos cu-n bagaj bej.", "original", None, "4+"),
    ("j", "Jos, jos, jos, jucăria e jos.", "original", None, "4+"),
    ("ț", "Țuțu ține-n lăbuțe un băț și-o ață.", "original", None, "4+"),
    ("ț", "Un puiuț, doi puiuți, toți țopăie-n pătuț.", "original", None, "4+"),
    ("ț", "Țuțu țopăie, puiuțul țopăie.", "original", None, "4+"),
    ("ce", "Cinci pitici cer nuci, cinci nuci cer pitici.", "original", None, "7+"),
    ("ce", "Cici face cinci ceaiuri și zice: ce de ceai!", "original", None, "4+"),
    ("ce", "Cinci arici mănâncă nuci.", "original", None, "4+"),
    ("ge", "Gigi unge gem pe deget și fuge la geam.", "original", None, "4+"),
    ("ge", "Ninge, ninge, Gelu-și pune mingea-n geantă.", "original", None, "4+"),
    ("ge", "Gigi și Gina au gem și mingi.", "original", None, "4+"),
    ("r", "Rada ară, Radu rade, rața râde.", "original", None, "4+"),
    ("r", "Trei tigri trag trei trăsuri.", "original", None, "4+"),
    ("r", "Rică râde, racul râde, râul curge.", "original", None, "4+"),
    ("r", "Capra calcă piatra, piatra crapă-n patru.", "folk",
     "Traditional Romanian tongue twister (oral folklore), first line only; the ending (a curse) is left out for children.",
     "7+"),
    ("l", "Leul Lalalu lipește litere pe lună.", "original", None, "4+"),
    ("l", "Un balon alb, un val, un cal: la-la-la la bal!", "original", None, "4+"),
    ("l", "Lalalu cântă la lună: la-la-la.", "original", None, "4+"),
    ("s,r,l", "Un vultur stă pe pisc cu un pix în plisc.", "folk",
     "Traditional Romanian tongue twister (oral folklore), full text. Older children.", "7+"),
]
