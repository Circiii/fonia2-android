# Judecătorul: CE, GE, J și L final (porți oprite)

Răspunde la punctele 1–3 din `docs/PENTRU_STEFAN.md` §5. **Tot ce e aici e oprit în spec-ul livrat.** Nu s-a schimbat niciun prag și niciun verdict: fără clipurile cu greșeli nu există cifre înainte/după, iar regula din `spec/fusion/EVAL_v2.md` cere cifre. Ce lipsește e o rulare pe clipuri.

## De ce greșește acum

- **CE (52 %).** Modelul de foneme (ZIPA-small) n-are simbol pentru [tʃ]. Îl scrie ca t + ʃ, iar la o rostire bună dă [ʃ] sau [s] aproape cât ținta. `lpr.s` n-are clasă proprie, dar coboară `zipa.gop` (greutate 0,5). `lpr.ʃ` împinge spre `deaffrication`. DSP-ul n-avea cu ce să răspundă: pauza dinaintea exploziei (`gapMs`) e goală la început de cuvânt, fiindcă `gapValid` cere sunet înainte de ea, iar majoritatea cuvintelor cu CE încep cu el.
- **GE (64 %, laude pe greșeli 16 %).** ZIPA dă aproape același scor unui „ger” bun și unui „jer” (`lpr.ʒ` între −1 și −4). Cu `correctBias` 3 și `g` 0, capul se înclină spre corect și laudă „jer”. Vocea de dinaintea exploziei (sonorizarea dinainte, tipică pentru GE în română) nu era măsurată.
- **J (72 %).** Aceeași familie de confuzii ʒ/dʒ în ZIPA. Pe singura voce de aici, J-urile bune respinse sunt mai ales silabe scurte („aj”, „ej”, „ojo”), unde ZIPA aude lipsă (∅) sau aliniază prost, nu [dʒ]. Pentru J nu am construit poartă: felul exact în care sunt respinse se vede abia în `cases.csv`, pe toate vocile.
- **L final („val”).** Judecătorul nu știa că L e la final de cuvânt. După L nu vine vocală, deci `dipDb` lipsește. `lToGlide` vine atunci numai din `lpr.j+w` (greutate −3,75). ZIPA n-are [ɫ], așa că un L final rostit mai „închis” alunecă spre [w].

## Ce e construit

1. **Semnalul de africată** (`dsp/.../AffricateOnset.kt`). Se măsoară pe segmentul pe care judecătorul îl are deja. Patru mărimi:
   - `onsetAttackDb`: cât sare banda de peste 2 kHz în 10 ms;
   - `onsetSpikeDb`: dacă primele 10 ms de șuierat sunt la nivelul celor care urmează sau peste el;
   - `voiceLeadMs`: vocea de sub 1 kHz dinaintea exploziei, la GE, numai când înainte e liniște;
   - `gapMs`: pauza, numai când s-a găsit una de cel puțin 10 ms. Pe vocea de aici, pauza ieșea 0 la majoritatea rostirilor bune de CE și GE, deci un 0 înseamnă că pauza nu a fost găsită, nu că lipsește.

   Din ele iese `stopOnset`: 0 = șuierat care crește încet (ș, j), 1 = explozie după închidere (ce, ge). Valoarea apare în urma verdictului și în `cases.csv`. **Nu e intrare în capul de fuziune.** `Features.VERSION` a rămas neschimbat. Rampele sunt alese pe semnale sintetice și nu sunt potrivite pe rostiri. Testele: `AffricateOnsetTests`.
2. **Poziția L** (`ZipaPhoneScorer` → `PhoneEvidence.wordFinal`, iar în DSP `wordFinal`). Spune dacă apariția notată e la final de cuvânt.
3. **Poarta `judge2.affricate`**, pe sunet (`"ce"`, `"ge"`). Un sunet nelistat e oprit.
   - `trustAt` + `trust`: dacă `stopOnset ≥ trustAt`, fricativele auzite de ZIPA (`ʃ`, `s` la CE; `ʒ`, `z` la GE) sunt scoase înainte de cap, iar GOP-ul se recalculează fără ele. Nici regula de laudă (`praiseAgree`) nu le mai socotește. Ținta: mai multe CE/GE bune acceptate. Nu ajută când ZIPA aude [ts] sau [t]: și acelea au explozie, deci semnalul nu le poate deosebi de [tʃ].
   - `vetoAt`: dacă `stopOnset ≤ vetoAt`, un „corect” devine „Aproape. Încă o dată, mai clar” (nota `stopVeto`). Ținta: „șer” sau „jer” să nu mai primească laudă.
4. **Poarta `judge2.finalL`**: L la final, cu dovada de alunecare `lpr(j+w)` mai slabă decât `minGlideNats`.
   - Fără `mask`, `lToGlide` nu mai e numit: copilul aude „Aproape. Încă o dată, mai clar”, nu „Lipește limba sus” (nota `finalGlideWeak`).
   - Cu `mask: true`, candidații j/w sunt scoși înainte de cap (nota `finalGlideMasked`).
5. **Raportul de evaluare.** `EvalV2Runner` scrie, lângă `cases.csv`:
   - `gates.md`, cu trei părți:
     - cum se despart rostirile bune de greșeli pe `stopOnset` (mediane, AUC);
     - fiecare setare pe toate vocile (acceptate / laude pe greșeli / cost);
     - setarea aleasă cu o voce ținută deoparte, cu laudele pe greșeli ≤ 5 %, sau ≤ cât e acum, dacă e deja peste;
   - `gates.json`: blocul ales pe toate vocile;
   - `gates.csv`: pe fiecare rostire.

   În `cases.csv` apar coloane noi la coadă: `stopOnset`, `onsetAttackDb`, `onsetSpikeDb`, `voiceLeadMs`, `riseMs`, `wordFinal`.

Spec-ul livrat n-are secțiunile `affricate` și `finalL`, deci porțile sunt oprite. `population.json`, `make_v2.py` și fișierele din `spec/fusion` sunt neatinse. iOS nu e atins.

## Ce arată singura voce de aici (nu e înainte/după)

Am rulat local doar vocea din conținut („Andrea”, 696 de judecăți). Ea nu are greșeli reale de CE și are o singură greșeală de GE. Cifrele de mai jos sunt orientative, nu se pun în `EVAL_v2.md`:

- **Acceptate în spec-ul livrat:** CE 60 % (18/30), GE 62 % (18/29), L 98 % (49/50). La cele 11 rostiri bune cu L final, `lToGlide` n-a fost numit niciodată, deci problema cu „val” nu apare pe vocea asta.
- **Cât de bine desparte `stopOnset` (AUC):**
  - Ț bun față de ț→s: 0,67;
  - CE bun față de Ș bun (în locul lui ce→șe, care lipsește): 0,67;
  - GE bun față de J bun: 0,81.

  Despărțirea e moderată. Singur, semnalul nu ajunge pentru o decizie.
- **Nicio setare din grilă n-a ridicat acceptarea pe vocea asta.** CE-urile bune respinse sunt mai ales silabe izolate aliniate prost sau rostiri în care ZIPA aude [ts] ori [t], nu [s]/[ʃ]. Veto-ul doar scade acceptarea, fiindcă aici n-are greșeli de oprit. Alegerea a fost „oprit” peste tot.

## Ce rulează Circi (cu cele 8 voci în `tmp/test_audio/` și `output/aude/`)

```bash
python3 -I tools/eval/build_manifest.py
./gradlew --stop
FONIA_EVAL_MANIFEST="$(pwd)/tools/eval/out/manifest.jsonl" FONIA_EVAL_TAG=porti \
  ./gradlew :engine:judge:test --rerun --tests tech.fonia.judge.EvalV2Runner -Pfonia.judge.zipa=true
python3 -I tools/eval/summarize.py porti
```

Rezultatele sunt în `tools/eval/out/porti/`: `gates.md`, `gates.json`, `gates.csv`, iar `cases.csv` are coloanele noi.

## Clipurile care trebuie să existe

Fără ele, raportul nu poate spune nimic despre laude:

- **greșeli reale de CE:** ce→țe („țer” cerut ca „cer”) și ce→șe („șer” ca „cer”, „șină” ca „cină”), de la mai multe voci, și la început, și în interiorul cuvântului („dulce”, „arici”);
- **greșeli de GE:** „jer” cerut ca „ger” (deaffrication), „zer” ca „ger”, „cer” ca „ger”;
- **J:** rostiri bune de J de la toate vocile, ca să se vadă cu ce etichetă sunt respinse acum;
- **L final:** rostiri bune („val”, „cal”, „sal”, „miel”) de la toate vocile, plus greșeli „cai” cerut ca „cal” (lToGlide la final).

## Cum se pornește o poartă, dacă cifrele țin

1. Copiezi blocul din `gates.json` în `spec/thresholds/population.json` → `judge2`.
2. Crești `version` la `judge2` și la fișier.
3. Pui în `spec/fusion/EVAL_v2.md` cifrele cu vocea ținută deoparte (înainte/după, pe sunet) și clipurile din care vin.
4. Regula rămâne aceeași: dacă lauda pe greșeli trece de ~5 %, poarta nu se pornește.

`tools/eval/apply_chosen.py` păstrează acum `affricate` și `finalL` când rescrie `judge2`.

Pe iOS (`app/Packages/FoniaKit/.../TakeJudge.swift`), CE și GE sunt judecate numai după loc, prezență și sonoritate. Nu se verifică dacă e explozie sau șuierat, deci „șer” cerut ca „cer” probabil trece. ZIPA doar înmoaie verdictul, iar GE e scos din a doua opinie. L n-are poartă pe poziție (`acceptL` 1,5 / `rejectL` −3,0). Semnalul nou nu e portat (`dsp/PARITY.md`). Dacă o poartă se pornește pe Android, trebuie portată și acolo.
