# Ce corectează cu adevărat o dislalie și unde e Fonia (2026-11-25)

Nota adună ce spun studiile deja strânse în hub (`rapoarte/B`, `rapoarte/G`, `rapoarte/J`) despre ce face ca exersarea acasă să corecteze un sunet. Le pune lângă ce face aplicația și lângă evaluarea judecătorului din 25 noiembrie (`tools/eval`, 8 voci sintetice, 1 918 judecăți). Nu e un raport nou de căutare. Nimic de aici nu e o dovadă despre copii: toate vocile evaluate sunt sintetice.

## 1. Ce spun dovezile

| Principiu | Dovada | Ce cere de la aplicație |
|---|---|---|
| **Doza: multă practică** | Maas 2019 („Bang for your buck”): mai multă practică ajută; practica comasată a mers mai bine decât cea distribuită la 4 din 6 copii (B [38]; corectat în §6); principiile învățării motorii, Maas 2008 (B [41], G [75]) | Sesiuni scurte, zilnice; multe rostiri pe sesiune; repetiție în zile diferite |
| **Feedback simplu (corect / nu încă) e suficient acasă** | RCT McKechnie 2020: KR singur de pe tabletă, 3 zile din 4, plus KP de la logoped o zi pe săptămână = rezultate echivalente la 4 luni (G [78]) | Aplicația dă KR; logopedul / părintele dă KP (ce să schimbe) |
| **Frecvența feedback-ului se reduce pe măsură ce reușește** | Preston et al. 2023 (Speech Motor Chaining): feedbackul scade la 3 din 6 rostiri pe nivelul de sus; Lowe & Buchwald 2017 nu arată că 50 % bate 100 % (vezi §6); Maas 2012: efecte mixte la copii (G [76][77]) | Adaptiv, nu fix: KP la achiziție; la automatizare verdictul la circa jumătate din rostiri; pe treptele dintre ele rămâne reducerea treptată de acum și autoevaluarea de la grupurile de consoane (vezi §6) |
| **Dificultatea la punctul de provocare** | Ținta: 60–80 % reușită pe bloc (G §6, de validat cu logopezii) | Motorul `ChallengePoint` (există în `domain/adaptive`) |
| **Judecătorul automat greșește mai ales pe rostirile CORECTE** | Ahmed 2018: acord ASR–om pe producții corecte doar 47–51 % la copii (față de 71 % la logopezi); Apraxia World a mers totuși, acasă, cu câștiguri semnificative (G [85][86]); MORA 2025 se abține când nu e sigur (G [74]) | Toleranță: mai bine „mai spune o dată” sau „aproape” decât un „greșit” fals; abținere, nu verdict, când e nesigur |
| **Biofeedback vizual ajută în ședință, generalizează greu** | staRt (McAllister 2017): câștiguri în ședință, fără generalizare fără biofeedback; RCT 2026 susține biofeedback-ul acustic (G §7.2) | Litera vie și curba sunetului ca KP la achiziție; probe fără ajutor vizual ca să vedem generalizarea |
| **Frustrarea după eșecuri repetate e un risc documentat** | G §6 punctul 7 | Fără presiune; după două reluări, „Mergem mai departe” (există) |

## 2. De ce contează respingerile false mai mult decât par

Fiecare rostire corectă respinsă face trei lucruri rele:
1. îl demotivează exact când a reușit (KR greșit);
2. trage în jos procentul de reușită, deci `ChallengePoint` face exercițiile **mai ușoare** fără motiv, iar `Mastery` nu mai trece treapta (P ≥ 0,9). Copilul rămâne blocat pe un sunet pe care îl spune deja bine;
3. îl învață să schimbe o rostire bună (caută „altceva” când nu era nimic de schimbat).

O laudă falsă e și ea rea: întărește rostirea greșită. Costul folosit la potrivirea judecătorului (`EvalV2Fits`) o cântărește de 2,5 ori mai greu decât o abținere pe o rostire corectă (5 față de 2). Tot ce e mai jos păstrează această regulă.

## 3. Unde era Fonia pe 25 noiembrie (v28, 8 voci sintetice)

Rostiri corecte acceptate: **77 %** în total. Pe sunete: R 89 %, S 90 %, L 86 %, Ș 82 %, Z 81 %, Ț 74 %, J 67 % (13 % indicații false), **CE 52 %**, **GE 41 %**. Pe silabe e mai rău decât pe cuvinte (70 % față de 80 %).

Cauza la CE și GE, văzută în `cases.csv`: modelul de foneme (ZIPA-small, ~13 h de română la antrenare) aude [s] în loc de [tʃ] și [ʒ] în loc de [dʒ] la rostiri bune. GOP-ul (cât de sigur e pe țintă) scade, iar capul complet trage spre „deafricare”. Semnalele DSP (pauza dinaintea exploziei, explozia) nu despart rostirile corecte de greșelile reale pe setul de acum (prea puține greșeli: 10 la CE, 7 la GE).

## 4. Ce urmează pentru ca aplicația chiar să corecteze

1. **Judecătorul:** acceptarea pe rostirile corecte ≥ 85 % la fiecare sunet, cu lauda falsă ≤ 5 %. Pârghiile: grila potrivirii (bias, GOP pe sunet), regula de propoziție (cuvintele auzite de recunoaștere), încălzirea modelului înainte de prima rostire.
2. **Programul de feedback adaptiv** (G §6 punctul 4): la automatizare, KR la circa jumătate din rostiri (ancora: Preston et al. 2023, vezi §6); pe treptele intermediare rămân reducerea treptată de acum și autoevaluarea de la grupurile de consoane (vezi §6); probe fără ajutor vizual pentru generalizare.
3. **Logopedul în buclă:** raportul săptămânal pentru adult cu rostirile ascultabile, ca KP-ul să vină de la om (modelul RCT McKechnie).
4. **Date reale:** pilotul cu copii și acordul părinților. Fără ele, toate cifrele de mai sus sunt despre voci sintetice de adult.

## 5. După reglajul din 25 noiembrie (v29, `77ba943`)

Configurația livrată, pe aceleași 8 voci: rostiri corecte acceptate **83,3 %** (de la 78,1 %), laude pe greșeli **2,3 %** (de la 2,1 %). R 96 %, L 90 %, S 90 %, Ș 88 %, Z 84 %, Ț 78 %, J 72 %, GE 64 %, **CE 52 %**. CE nu se repară din constante: are nevoie de greșeli reale de CE (pilot) sau de un model de foneme mai bun pe română. Detalii: `spec/fusion/EVAL_v2.md` §v3.

## 6. Corecturi la citări (2026-11-25)

- **Lowe & Buchwald 2017** a testat 32 de adulți tipici pe pseudocuvinte noi (nonwords). Toate frecvențele de feedback, inclusiv 0 %, au dus la învățare. Studiul nu arată că feedbackul la 50 % din încercări e mai bun decât la 100 %. Tabelul din §1 spunea „KR 50 % > 100 % la retenție”; nu e așa.
- **Ancora pentru „verdict la circa jumătate din rostiri” la automatizare** e Preston et al. 2023 (Speech Motor Chaining): pe nivelul cel mai înalt, feedbackul scade la 3 din 6 rostiri. E un protocol clinic pentru copii, nu un studiu care compară frecvențe.
- **Pe treptele intermediare rămâne ce există deja**, la fel pe iOS și pe Android: reducerea treptată de acum (`FeedbackSchedule.levels`: 5, apoi 4 din 6 la începutul consolidării; 4, apoi 3 din 6 la sfârșitul ei, cu 3 din 6 judecate de copil la grupurile de consoane, „Cum ți s-a părut?”, pe care telefonul nu le notează). Se potrivesc cu Speech Motor Chaining, deci rămân. „Neadoptat” înseamnă doar că nu se extind la alte trepte și că nu se adaugă feedback rezumat.
- **Maas 2019** („Bang for your buck”) a favorizat practica comasată (massed) la 4 din 6 copii, nu pe cea distribuită. Tabelul din §1 spunea invers. Sesiunile scurte și zilnice rămân, dar nu pe baza acestui studiu.
- Comentariile din cod care repetau vechea citare (`Curriculum.automatizareVerdicts`, `FeedbackFading.KR_START`) și `PENTRU_STEFAN.md` §5 punctul 4 sunt corectate la fel. Comportamentul nu s-a schimbat.
