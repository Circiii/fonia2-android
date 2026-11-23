# 03. Motorul de sunet

Capitol din planul Fonia 2, scris pe 20 noiembrie 2026. Ține de `00_PLAN_MASTER.md` (deciziile D1–D16, F1–F9, numele din §3, politica din §5) și nu le schimbă. Acoperă pachetele **B1, B3, B4, E2–E8, F3**.

Surse: `redesign/AB_diagnoza_erori_sunete.md` (AB), `redesign/P_ml_audio_pronuntie_v2.md` (P), `redesign/AA_metoda_ivanov_logopedie.md` §B2 și §C (AA), `redesign/Y` (prin sinteză), `fonia-brain/notes/android-inventory.md`, `fonia-android/phones/EVAL.md` și codul din `fonia-android/dsp`, `phones`, `audio`, `domain`, `features/session`.

**Etichete pe cifre:** **[V]** = Verificat (în codul de azi sau într-o sursă citată), **[E]** = Estimare, **[P]** = Propunere (valoare de pornire, se fixează pe date). Nimic din `DSPThresholds` nu e reglat pe copii **[V]**.

## 1. Lanțul de judecată P0–P5

```
:platform:audio  Oboe 48 kHz mono float, UNPROCESSED sau VOICE_RECOGNITION → inel SPSC
   │ fir „analysis” (un singur proprietar, fără alocări)
:engine:live  LiveEngine.process(hop 480 eșantioane)
   ├─ LiveAnalyzer (există) + LiveExtras (nou) ──► LiveSnapshot (triplu buffer, citit la desen)
   ├─ Endpointer (SpeechGate + TakeEnd, mutate aici) ──► SessionEvent.AttemptStarted / AttemptEnded(clipRef)
   └─ decimare 3:1 ──► inel 16 kHz (ZIPA, Whisper)
:engine:judge  AttemptJudge.judge(clip, target, childNorm)        (înlocuiește TakeJudge + ShadowMode)
   P0 CaptureGate   CaptureQuality + SNR pe bandă + lățime de bandă           ─► Retake
   P1 WordGate      ZIPA fitPerLabel; Whisper la propoziții și perechi          ─► Retake
   P2 Segmenter     aliniere CTC (ZIPA) ∩ segmente DSP                         ─► TargetSegment[]
   P3 Features      trăsături DSP z-normalizate pe copil + LPR ZIPA + captură   ─► FeatureVector
   P4 FusionHead    spec/fusion/<sound>.json                                    ─► p(correct), p(kind)
   P5 DecisionPolicy + spec/unlock.json                                         ─► Verdict + AdultNote + JudgeTrace
   └─► SessionEvent.VerdictReady ─► :engine:session
```

| Etapă | Intră | Iese | Ce există și se leagă | Ce e nou |
|---|---|---|---|---|
| P0 | clipul de 48 kHz, `CaptureInfo` | `CaptureReport` sau `Retake(reason)` | `CaptureQuality`, `clippedSampleCount`, `captureOutcome` (`dsp/Judging.kt`) | `CaptureGate`: SNR în 4–8 kHz, trepte de bandă, eșantioane pierdute (§8) |
| P1 | clipul de 16 kHz, cuvântul | `WordReport` sau `Retake(OtherWord)` | `PhoneScorer.judge` (`fitPerLabel`, `Abstention.PoorFit`), `SpeechJudge.judgeSentence`, `judgePair` | `WordGate`: durată și număr de silabe plauzibile |
| P2 | `ClipSummary`, `Posteriors` | `TargetSegment` (start, sfârșit, vecini vocalici) pe fiecare apariție | `analyzeClip`, `alignFricatives`, `findTapCandidates`, `CtcForward` | `CtcAligner` (Viterbi), intersecția cu segmentul DSP |
| P3 | segmentele, `ChildNorm` | `FeatureVector` (≤ 60 de valori, cu mască) | `FricativeSegment`, `TapEvent`, `TrillSegment`, `OccurrenceGop` | `SegmentFeatures` (§4), `ChildNorm` (§6) |
| P4 | `FeatureVector` | probabilități calibrate pe clase | — | `FusionHead` (§7) |
| P5 | probabilități, registrul de deblocare | `Verdict`, `AdultNote`, `JudgeTrace` | `SoundOutcome`, `ScoringPolicy` rămân ca intrări și ca plasă de siguranță | `DecisionPolicy`, `UnlockRegistry` |

**Capul v0, fără date.** Până există coeficienți ajustați, `FusionHead` e un tabel de reguli scris de mână peste ce dă codul azi, ca M0–M1 să aibă verdict cu motiv: `WrongPlace` → `PlaceSwap` (la africate `AffricatePlace`); `Unvoiced` → `Devoicing`; `Missing` → `Omission`; `Weak` → `WeakOrShort`; `Good`, `Tap`, `Trill` → `Correct`; `Partial` ≥ 0,6 **[V]** → `Almost`; `Unclear(*)`, `LateralHint` → `Unsure` (cu notă pentru adult); `NotHeard`, `TooShort`, `Clipped` → `Retake`. La R și L, `WordScore.heardAs` (l, j, ʁ, r, w) dă `RtoL`, `RtoGlide`, `RUvular`, `LtoR`, `LtoGlide` doar când `lpr ≤ −3` **[V]**.

**`JudgeTrace`** se salvează la fiecare încercare: vectorul de trăsături, probabilitățile, decizia, timpii pe etape și versiunile (`thresholds`, `fusion`, `unlock`, `featureVersion`, modelul ZIPA). Fără audio.

**Două viteze.** Fiecare `spec/fusion/<sound>.json` are două capete: `fast` (doar DSP) și `full` (DSP + ZIPA). Dacă `fast` dă `p(correct) ≥ tauOkFast`, verdictul pleacă imediat. Altfel litera intră în „ascultare” și așteaptă `full`. Dacă ZIPA depășește termenul, decide `fast` cu pragurile lui mai stricte, iar ce rămâne nehotărât devine `Unsure`.

| Buget (telefon mediu, clip de 1,5 s) | Țintă | Bază |
|---|---|---|
| Liniștea care închide încercarea (nu intră în cei 100 ms): `Hold`/izolat 250 ms **[V]**, silabe 300 ms **[P]**, cuvinte 400 ms **[P]**, propoziții 700 ms **[V]** | — | `utteranceQuietSeconds`, `TakeEnd.DEFAULT_SILENCE` |
| Pornire speculativă: la 150 ms de liniște **[P]** rulează P0–P3 pe clipul provizoriu; se aruncă dacă vocea revine | — | ca verdictul să fie gata la `AttemptEnded` |
| P0 + `analyzeClip` + trăsături noi | ≤ 60 ms p95 **[E]** | AB §4: ~20 ms pe secundă de audio, nemăsurat |
| P4 + P5 | < 1 ms **[E]** | ≤ 60 × 12 înmulțiri |
| **Verdict acustic după `AttemptEnded`** | **≤ 100 ms** | master, Y |
| ZIPA (fbank + rețea + GOP) | ≤ 350 ms p95 **[P]** | pe PC 32–35 ms pe cuvânt cu 1 fir **[V]**; pe telefon 150–400 ms **[E]** |
| **Verdict cu ZIPA** | **≤ 600 ms** | master |
| Whisper | 300–800 ms **[E]** | vezi „Propuneri de schimbare” nr. 6 |

## 2. Banda continuă: ce primește litera

Hopul de captură e de 480 de eșantioane (10 ms) **[V]**. Analiza grea rulează la 512 eșantioane (10,7 ms, ~94 de valori pe secundă) pe un cadru Blackman de 2.048 de eșantioane **[V]**. Anvelopa pentru R are pas de 128 de eșantioane (2,67 ms) **[V]**. Toate valorile sunt 0–1, netezite pe firul de analiză (un pol), scrise în `LiveSnapshot`.

| Câmp | Familii | Calcul pe hop | Netezire și normalizare |
|---|---|---|---|
| `level` | toate | `clamp01((rmsDb − floorDb) / 40)` **[V]** | atac 20 ms, revenire 120 ms **[P]** |
| `speaking` | toate | VAD: 11 dB peste podea, agățare 0,22 s **[V]** | — |
| `fric` | S Ș Z J Ț CE GE | `clamp01((hfDb − hfFloor − 12) / 25)` **[V]** | 30 ms **[P]** |
| `place`, `placeValid` | S Ș Z J Ț CE GE | `sNess = sigmoid(2,2 · (0,45·z_centroid + 0,30·z_vârf + 0,25·z_raport))` **[V]**, 0 = Ș, 1 = S; față de țintă: `sNess` la S, Z, Ț și `1 − sNess` la restul. `z` vine din `ChildNorm` când e calibrat | mediană pe 3 cadre + 40 ms **[P]**; fără fricație ține valoarea 80 ms, apoi revine la 0,5 **[P]** |
| `voicing` | Z J GE, R, L | `max(clamp01((clarity − 0,45) / 0,30), clamp01((E(80–500 Hz) − podea − 6 dB) / 12 dB))`; capătul 0,75 = `voicedClarity` **[V]**, restul **[P]** | 50 ms **[P]** |
| `trill`, `trillRateHz` | R | `trillStrength = clamp01(((periodicitate − 0,3) / 0,45) · min(1, adâncime / 3,5 dB))`, bandă 18–38 Hz, fereastră 0,24 s **[V]** | întârziere proprie ~150 ms **[E]**; `trillRateHz` e în Hz, nenormalizat |
| `tapPulse` | R, L (ca `LtoR`) | dip al anvelopei de 10–42 ms, ≥ 6 dB, între flancuri sonore **[V]**; port live nou | impuls 1 → 0 în 150 ms **[P]** |
| `burstPulse` | Ț CE GE | urcare ≥ 12 dB în 10 ms după ≥ 20 ms sub podea + 6 dB **[P]** | impuls 1 → 0 în 120 ms **[P]** |
| `holdSeconds` | `Hold`, izolat | timp continuu pe țintă; pauze ≤ 120 ms nu resetează **[P]**; ținte 2, 3, 4 s **[V]** | brut, în secunde |
| `stability` | `Hold` | `1 − clamp01(sd(place, 300 ms) / 0,15)` la sibilante; `1 − clamp01(sd(level în dB, 300 ms) / 6)` la R și L **[P]** | 100 ms |
| `uncertainty` | toate | `max(captură proastă în ultimele 200 ms, 1 − |sNess − 0,5| / u)`; `u` = 0,12 cu prior, 0,08 calibrat **[V]** | 150 ms; hrănește `tremble` |

| Familia | Litera citește |
|---|---|
| S, Ș | `level`, `fric`, `place`, `holdSeconds`, `stability`, `uncertainty` |
| Z, J | la fel + `voicing` |
| Ț, CE, GE | `burstPulse`, `fric`, `place` (doar ≥ 4 cadre de fricație **[V]**), `voicing` la GE |
| R | `voicing`, `trill`, `trillRateHz`, `tapPulse`, `level` |
| L | `voicing`, `level`, `holdSeconds`, `stability` (locul lui L nu se aude sigur **[V]**) |

Indiciul lateral nu intră în banda continuă: e prea nesigur pe cadre scurte **[P]**. Pe bandă îngustă (Bluetooth sau sub 32 kHz) `placeValid` rămâne fals **[V]**.

```kotlin
class LiveSnapshot {                       // mutabil, refolosit; scris doar de firul de analiză
    var tNanos = 0L; var level = 0f; var speaking = false
    var fric = 0f; var place = 0.5f; var placeValid = false; var voicing = 0f
    var trill = 0f; var trillRateHz = 0f; var tapPulse = 0f; var burstPulse = 0f
    var holdSeconds = 0f; var stability = 0f; var uncertainty = 0f
}
```

## 3. Cele nouă sunete

**Cum se citesc tabelele.** „Semnătura” dă valorile de pornire. `lpr(x)` = log-raportul ZIPA al țintei față de candidatul x; „clar” înseamnă `lpr ≤ −3` **[V]**. `_z` = față de ancorele copilului (§6). Fiabilitatea (R ridicată, M medie, S scăzută) e **[E]** din AB, pentru copil calibrat, în cameră liniștită. Textele pentru copil au cel mult 8 cuvinte; forma finală o dă capitolul 06. Animațiile sunt identificatori pentru capitolul 05: `comma-drop`, `air-to-centre`, `tongue-behind-teeth`, `voicing-wave`, `trill-ripple`, `closure-snap`, `sag`, `tremble`, plus trei cerute aici: `stretch`, `drop-in`, `thicken`.

**Rânduri comune tuturor sunetelor**

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Omission` | niciun segment aliniat la locul sunetului (cost de aliniere 1 **[V]**) | `alignFricatives` sau lipsa dipului + candidatul ∅ din `RestrictedGop`, clar | R în cuvinte cunoscute | 1 la S și R; 2 la rest | „Nu uita șarpele!” (eroul sunetului) | „Sunetul lipsește din cuvânt.” | `drop-in` |
| `WeakOrShort` | loc bun, dar fricație < 60 ms **[P]**, sau < 18 dB peste podea **[P]**, sau difuz (platitudine > 0,7 și < 22 dB peste podea **[V]**) | DSP | M | 1 **[P]** | „Încă o dată, mai tare!” | „Locul e bun; sunetul e încă slab.” | `sag` → `thicken` |
| captură sau alt cuvânt | §8 | P0, P1 | — | — | „Mai spune o dată” | cauza, pe card | `tremble` ușor |

### 3.1 S (`s`)
Țintă: fricativă surdă, zâmbet, vârful limbii jos după dinți, aer rece pe mijloc. Camera: ajută la `PlaceSwap` (buze), obligatorie la `Interdental`.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `PlaceSwap` (→ Ș) | centroid și vârf cu ~1–2,5 kHz sub S-ul copilului **[V]** | `sNess < 0,5 − u` + `lpr(ʃ)` | R calibrat | 1 | „Zâmbește și ține limba în față.” | „S-ul seamănă cu Ș; cereți un zâmbet larg.” | `comma-drop` invers: virgula sare, litera se ascute |
| `Stopping` (→ T) | gol ≥ 20 ms **[V]**, explozie, fricație < 40 ms **[V]** | `gapMs`, `fricDurMs`, `lpr(t)` | R | 1 | „Lasă aerul să curgă lung: sss.” | „În loc de S iese T; exersați S lung.” | `stretch` |
| `Lateral` | energie ↑ la 2,5–5 kHz, vârf ~3,2 kHz, ↓ peste 5 kHz, cădere peste 11 kHz **[V]** | `latIdx_z ≥ 2` **[P]** + `lpr(ɬ)`; doar cu bandă ≥ 16 kHz **[V]** | M cu bandă plină, altfel S | 3 | „Suflă aerul pe mijloc, ca prin pai.” | „Aerul pare să iasă pe lateral. Arătați-i logopedului.” | `air-to-centre` |
| `Interdental` | mai slab, mai plat, centroid mai jos **[V]** | `relLevelDb_z ≤ −1,5`, `flat_z ≥ 1,5`, `cog_z ≤ −1` **[P]** + `lpr(θ)` + limba văzută de cameră | S audio; M cu camera | 3, doar cu camera | „Ține limba în spatele dinților.” | „Limba pare să iasă între dinți; verificați în oglindă.” | `tongue-behind-teeth` |

### 3.2 Ș (`ș`)
Țintă: fricativă surdă, buze rotunde (F3), limba „ceșcuță”, aer cald. Atenție: pe vocea adultă de test ZIPA aude des Ș ca S (10,5 % alarme, 31,6 % abțineri **[V]**), deci la loc decide DSP-ul.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `PlaceSwap` (→ S) | centroid și vârf peste Ș-ul copilului **[V]** | `sNess > 0,5 + u` + `lpr(s)`; camera: fără rotunjire | R calibrat | 1 | „Fă buzele pâlnie, limba înapoi.” | „Ș-ul seamănă cu S; buzele țuguiate ajută.” | `comma-drop`: virgula cade, litera se rotunjește |
| `Stopping` (→ T, Ț) | gol + explozie înaintea fricației | `gapMs ≥ 20`, `burstDb`, `lpr(t)`, `lpr(ts)` | R (T), M (Ț) | 2 | „Lasă aerul să curgă lin: șșș.” | „Ș-ul pornește cu o oprire; exersați-l lung.” | `stretch` |
| `Lateral` | vârf ~3,2 kHz, plat peste 4 kHz **[V]** | ca la S, cu referința Ș | S–M | 3 | „Suflă pe mijloc, cu buzele pâlnie.” | „Ș sună lateral. Arătați-i logopedului.” | `air-to-centre` |
| `Interdental` | ca la S | ca la S + camera | S; M cu camera | 3 | „Limba înăuntru, buzele pâlnie.” | „Limba iese între dinți la Ș.” | `tongue-behind-teeth` |

### 3.3 Z (`z`)
Țintă: ca S, cu voce. Sonoritatea nu se judecă la final de cuvânt **[V]**.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Devoicing` (→ S) | sub 25 % din fricație e sonoră **[V]**; azi: claritate > 0,75 pe majoritatea cadrelor, segment ≥ 80 ms **[V]** | `voicedFrac`, `hnrDb`, E sub 500 Hz, `lpr(s)` | R | 1 | „Pune vocea: bâzâie ca albina!” | „Z-ul iese fără voce; puneți mâna pe gât.” | `voicing-wave` |
| `PlaceSwap` (→ J) | centroid coborât, sonor | `sNess` + `lpr(ʒ)` | R calibrat | 2 | „Zâmbește, limba în față: zzz.” | „Z-ul seamănă cu J.” | `comma-drop` invers |
| `Stopping` (→ D) | închidere + explozie sonoră, fără fricație lungă | `fricDurMs`, `gapMs` (bară de sonoritate), `lpr(d)` | R | 2 | „Lasă albina să bâzâie lung.” | „Z devine D; exersați Z prelungit.” | `stretch` |
| `Lateral`, `Interdental` | ca la S, pe partea de zgomot | + `lpr(ɮ)`, `lpr(ð)` | S | 3 | ca la S | ca la S | ca la S |

### 3.4 J (`j`)
Țintă: ca Ș, cu voce.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Devoicing` (→ Ș) | ca la Z | `voicedFrac`, `lpr(ʃ)` | R | 1 | „Pornește motorașul vocii: jjj.” | „J-ul iese fără voce.” | `voicing-wave` |
| `PlaceSwap` (→ Z) | centroid ridicat, sonor | `sNess` + `lpr(z)` | R calibrat | 2 | „Buzele pâlnie, limba înapoi: jjj.” | „J-ul seamănă cu Z.” | `comma-drop` |
| `Stopping` (→ D, GE) | gol + explozie | `gapMs`, `lpr(d)`, `lpr(dʒ)` | M | 2 | „Lasă vântul să sufle lung.” | „J-ul pornește cu o oprire.” | `stretch` |
| `Lateral`, `Interdental` | ca la Ș | ca la Ș | S | 3 | ca la Ș | ca la Ș | ca la Ș |

### 3.5 Ț (`ț`)
Țintă: închidere scurtă, explozie, S scurt. ZIPA îl vede ca `t s` și s-a abținut la 41,2 % din cuvintele corecte de test **[V]**: aici trăsăturile noi (§4, nr. 6) duc greul.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Deaffrication` (→ S) | fără gol ≥ 20 ms sub podea + 6 dB **[V]**; fricație > 1,3 × Ț-ul copilului sau > 110 ms **[P]**; urcare lentă, > 40 ms **[P]** (plaja 10–100 ms **[V]**) | `gapMs`, `riseMs`, `fricRatio`, `lpr(s)` | M–R | 2 | „Atinge dinții o clipă, apoi ț!” | „Lipsește oprirea scurtă din Ț.” | `closure-snap`: un T se lipește în față |
| `Stopping` (→ T) | explozie, fricație < 25 ms **[V]** | `fricDurMs`, `lpr(t)` | R | 2 | „După t, lasă aerul să șuiere.” | „Ț-ul se oprește la T.” | `stretch` (coada de S) |
| `AffricatePlace` (→ CE) | fricația scurtă are centroid coborât; cere ≥ 4 cadre de 10 ms, altfel abținere **[V]** | `sNess` pe fricație + `lpr(tʃ)`; camera: buze | M | 2 | „Zâmbește, limba în față: ț.” | „Ț-ul seamănă cu CE.” | `comma-drop` invers |
| `Lateral`, `Interdental` | ca la S, pe fricația scurtă, cu prag mai strict | ca la S | S | 3 | ca la S | ca la S | ca la S |

### 3.6 CE/CI (`ce`)
Țintă: închidere + Ș scurt, buze împinse înainte. La final („nuci”) nu există vocală: se judecă doar închiderea și fricația. CE spus ȘE poate fi grai: prima apariție merge doar la adult.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Deaffrication` (→ Ș) | fără gol; fricație lungă și joasă | `gapMs`, `fricRatio`, `lpr(ʃ)` | M–R | 2 | „Oprește o clipă, apoi ce!” | „Lipsește oprirea scurtă din CE.” | `closure-snap` |
| `Stopping` (→ T) | explozie fără fricație | `fricDurMs < 25`, `lpr(t)` | R | 2 | „După oprire, lasă aerul: ce!” | „CE devine T.” | `stretch` |
| `AffricatePlace` (→ Ț) | centroid ridicat pe fricația scurtă | `sNess` + `lpr(ts)` | M | 2 | „Buzele pâlnie, limba puțin înapoi.” | „CE seamănă cu Ț.” | `comma-drop` |
| `Lateral` | ca la Ș | ca la Ș | S | 3 | „Suflă pe mijloc: ce!” | „CE sună lateral.” | `air-to-centre` |

### 3.7 GE/GI (`ge`)
Țintă: ca CE, cu voce. E cel mai slab sunet al ZIPA pe test (AUC 0,852 **[V]**).

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `Devoicing` (→ CE) | fără bară de sonoritate în închidere, fricație surdă. Fricația e prea scurtă pentru regula de 80 ms **[V]**, deci se măsoară pe închidere + fricație, ≥ 60 ms **[P]** | `voicedFrac` extins, E sub 500 Hz în gol, `lpr(tʃ)` | M–R | 2 | „Pornește vocea: ge!” | „GE iese fără voce.” | `voicing-wave` |
| `Deaffrication` (→ J) | fără gol | `gapMs`, `lpr(ʒ)` | M | 2 | „Oprește o clipă, apoi ge!” | „Lipsește oprirea din GE.” | `closure-snap` (un D mic) |
| `AffricatePlace` (→ Z, DZ) | centroid ridicat | `sNess`, `lpr(z)`, `lpr(dz)` | M | 2 | „Buzele pâlnie: ge!” | „GE seamănă cu Z.” | `comma-drop` |
| `Stopping` (→ D) | fără fricație | `fricDurMs`, `lpr(d)` | M | 2 | „După oprire, lasă aerul: ge!” | „GE devine D.” | `stretch` |

### 3.8 R (`r`)
Țintă: vârful limbii bate o dată (în cuvinte) sau vibrează. **Ambele sunt corecte**: dip de 10–42 ms, ≥ 6 dB, cu flancuri sonore, sau vibrație la 18–38 Hz cu ≥ 3 contacte **[V]**. În afara poziției vocală–R–vocală, lipsa dipului nu dovedește nimic (`RHOTIC_CONTEXT` **[V]**); acolo decide ZIPA sau verdictul e `Unsure`. Camera nu ajută.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `RtoL` | sonorant continuu, fără dip | niciun tap sau tril + `lpr(l)` (pe test R/L: AUC 1,000 **[V]**, voce adultă) | M | 1 | „Lasă vârful limbii să bată sus.” | „R-ul sună ca L.” | `trill-ripple` pe piciorul literei |
| `RtoGlide` (→ I) | fără închidere, F2 aproape de /i/: `F2_z > 1,5` **[P]** | formanți + `lpr(j)` | M | 2 | „Ridică vârful limbii după dinți.” | „R-ul sună ca I.” | `sag` → `trill-ripple` |
| `RtoVowel` (→ Î, Ă) | formanți stabili, fără constricție | `lpr(ɨ)`, `lpr(ə)`, `ampStepDb ≈ 0` | M | 2 **[P]** | „Vârful limbii sus: rrr!” | „În loc de R se aude o vocală.” | `sag` → `trill-ripple` |
| `RUvular` | zgomot jos cu vârf la 0,7–1,8 kHz **[V]**. Ritmul (25–35 Hz) se suprapune cu R-ul bun **[V]**, deci nu decide | `uvNoiseDb` + `lpr(ʁ)`, `lpr(ʀ)` | M cu ZIPA; S doar DSP | 2 | „Vârful limbii lucrează, nu gâtul.” | „R-ul se face în gât. Arătați-i logopedului.” | `trill-ripple` mutat din spate în vârf |
| „aproape” ([ɹ], fără contact) | fără dip, `lpr(ɹ)` | — | S–M | — | (`Almost`) | „R-ul e moale, fără atingere.” | `sag` |

### 3.9 L (`l`)
Țintă: vârful limbii lipit sus, vocea iese pe lângă limbă. Azi nu are detector DSP **[V]**: judecă ZIPA (pe test: fără alarme false **[V]**, voce adultă) și adultul. La izolat și silabe adultul rămâne judecătorul principal până la validare.

| Tip | Semnătura | Detector | Fiab. | Val | Copil | Adult | Animație |
|---|---|---|---|---|---|---|---|
| `LtoGlide` (→ I) | F2 foarte sus, fără salt de amplitudine la eliberare | `F2_z`↑, `ampStepDb`, `lpr(j)` | M | 2 | „Lipește vârful limbii sus, după dinți.” | „L-ul sună ca I.” | `sag`, apoi litera se îndreaptă |
| `LtoGlide` (→ U) | F1 și F2 joase; buze rotunde în cameră | `F2_z`↓, `lpr(w)`, camera | M; R cu camera | 2 | „Zâmbește, vârful limbii sus.” | „L-ul sună ca U; buzele nu ajută aici.” | `sag` + litera își pierde rotunjimea |
| `LtoR` | dip sau vibrație unde trebuia continuitate | tap sau tril pe segment + `lpr(r)`, `lpr(ɾ)` | M–R | 2 **[P]** | „Ține limba lipită, nu o bate.” | „L-ul sună ca R.” | `trill-ripple` care se oprește |

### 3.10 Ce nu se poate deosebi din sunet

| Situația | De ce | Ce face aplicația |
|---|---|---|
| S addental față de S interdental | spectrele medii practic coincid **[V]** | fără tip; `Almost`; adultului: „sunetul e încă neclar, de verificat cu logopedul”. Cu limba văzută de cameră poate numi doar `Interdental` |
| S palatalizat, S nazal | fără indiciu robust pe un microfon **[V]** | niciodată copilului; o notă în zona adultului |
| Lateral pe telefoane cu bandă sub 16 kHz sau pe Bluetooth | căderea de peste 11 kHz nu se mai vede **[V]** | tipul e oprit pe acel telefon; adultul vede de ce |
| R din gât față de R vibrat, după ritm | aceleași 25–35 Hz **[V]** | decid spectrul și ZIPA; altfel `Unsure` |
| R în grupuri sau la margine de cuvânt, doar cu DSP | flancurile sonore lipsesc **[V]** | ZIPA sau `Unsure` |
| Locul unei africate cu fricație sub 40 ms | prea puține cadre **[V]** | abținere pe loc; restul se judecă |
| Sonoritatea lui Z sau J la final de cuvânt | în română se desonorizează **[V]** | nu se judecă |
| Unde stă limba la L | nu se aude; camera vede doar limba ieșită | adultul, oglinda |
| Vocea adultului în locul copilului | — | F0 median sub 0,7 × al copilului **[P]** → `Retake` („Acum tu!”), fără ancoră |

Regulă din AA: la `Lateral`, la `RUvular` și la un `Interdental` care persistă, aplicația nu împinge exersare multă acasă. Instrucțiunea apare de cel mult două ori pe sesiune; dacă tipul revine în peste 60 % din încercări, două sesiuni la rând **[P]**, `ParentCard` spune „Arată-i asta logopedului”.

## 4. Trăsături noi (E2), în ordinea implementării

Toate se calculează după încercare, pe `TargetSegment`, în `:engine:dsp`, și au un vector de aur în `spec/detectors/`. Costurile sunt **[E]** pe secunda de audio, nemăsurate.

| # | Trăsătură | Definiție și parametri | Cost | Test sintetic | Extinde |
|---|---|---|---|---|---|
| 1 | Momente și pantă | pe spectrul multitaper (6 tapere sinus, mijlocul de 50 % **[V]**), banda 2–20 kHz **[V]**: M1 = Σf·P/ΣP; M2 = √(Σ(f−M1)²P/ΣP); asimetrie și kurtoză din momentele 3 și 4 normalizate; `slopeDbPerKhz` = regresie pe 10·log₁₀P în 2–11 kHz | < 1 ms | zgomot alb: M1 ≈ 11 kHz ± 0,3, asimetrie ≈ 0, pantă ≈ 0; zgomot trecut prin rezonator la 7 kHz: M1 la ± 150 Hz | `FeatureExtractor`, `MultitaperAnalyzer` |
| 2 | Platitudine | există: medie geometrică / aritmetică pe blocuri de 500 Hz, 2–11 kHz **[V]**; devine intrare de fuziune, plus varianta 5–11 kHz | < 1 ms | zgomot alb ≥ 0,9; ton pur ≤ 0,05 | `FrameFeatures.flatness` |
| 3 | Nivel relativ | `relLevelDb` = RMS-ul miezului fricației (2–15 kHz) − RMS-ul nucleului vocalic vecin (± 150 ms); la izolat, față de podea | < 1 ms | „vocală” armonică + zgomot la −10 dB → −10 ± 1 dB | `FricativeSegment.aboveFloorDb` |
| 4 | Fracție sonoră și HNR | `voicedFrac` = cadre de 10 ms cu claritate YIN > 0,75 **[V]** și E(80–500 Hz) > podea + 6 dB **[P]**, împărțite la cadrele segmentului; `hnrDb` = 10·log₁₀(r/(1−r)), r = vârful autocorelației pe semnalul sub 1 kHz, limitat la −10…30 dB. Clase: < 25 % surd, 25–90 % parțial, > 90 % sonor **[V]** | ~3 ms | zgomot + ton de 150, 250, 300 Hz pe 0, 50, 100 % din durată → ± 10 puncte | `YINPitchTracker`, `makeSegment` |
| 5 | Indicii laterale | `latLowDb` = E(2,5–5) − E(5–11 kHz); `latTopDb` = E(5–11) − E(11–20 kHz) (azi `bandRatioDb` **[V]**); `latPeakDb` = vârful din 2,8–3,6 kHz peste mediana 2–8 kHz; `latMod` = adâncimea modulației lui la 4–30 Hz (cere ≥ 150 ms); `latIdx` = suma z-urilor față de S-ul bun al copilului | ~2 ms | zgomot cu vârf la 8 kHz față de zgomot cu vârf la 3,2 kHz și tăiere peste 11 kHz: semnul indicelui | `Lateral`, `lowBandDb` |
| 6 | Africate | anvelopă la 1 ms (fereastră de 5 ms): `gapMs` = cea mai lungă porțiune sub podea + 6 dB **[V]** în cei 150 ms dinaintea fricației (la sonore: doar energie sub 500 Hz); `burstDb` = cea mai mare urcare în 10 ms; `riseMs` = 10 % → 90 % din vârful anvelopei de fricație; `fricDurMs`; `fricRatio` = durata față de mediana copilului | ~1 ms | liniște + clic + rampă de zgomot de 5, 40, 90 ms → `riseMs` ± 3 ms; gol de 0, 15, 30, 60 ms → `gapMs` ± 3 ms | `EnvelopeTracker` |
| 7 | Formanți F1–F3 | pe inelul de 16 kHz: preaccentuare 0,97, Hamming 25 ms, pas 10 ms, LPC prin autocorelație, ordin 12, plafon 8 kHz (după AB) **[P]**; rădăcini cu lățime < 600 Hz; mediană pe 5 cadre; plaje la copii: F1 250–1.300, F2 800–3.600, F3 2.300–5.000 Hz **[E]**. Ieșiri: `F2_z`, `F3_z`, F3−F2 în Bark față de vocalele copilului, `ampStepDb` (nivel la 20 ms după eliberare minus înainte), `formantConfidence` | ~5 ms | trei rezonatoare în cascadă, F0 de 250 Hz: F1, F2 la ± 8 %; la F0 de 350 Hz: ± 12 % și încredere scăzută (trăsăturile se maschează) | nou: `Formants.kt` |
| 8 | Zgomot uvular | `uvNoiseDb` = E(0,7–2 kHz) a părții aperiodice − E(2–5 kHz) pe segmentul R; `uvPeakHz` în 0,7–1,8 kHz **[V]** | < 1 ms | ton sonor + zgomot de bandă la 1,2 kHz față de un dip curat | benzile din `Bands` |

## 5. Modelul de foneme (E3)

**Azi** **[V]**: 16 kHz → `ZipaFbank` (80 de benzi mel, 25/10 ms) → `ZipaModel` (int8, 70,7 MB, 127 de simboluri, un rând la 20 ms) → `RestrictedGop` (un forward CTC pe fiecare candidat: ținta, alternativele, omisiunea) → `ScoringPolicy` (`acceptLpr` 0, `rejectLpr` −3, `minFitPerLabel` −3, `minFrames` 10). Rulează doar în `ShadowMode`. Pe clipuri adulte: AUC 0,979, 3,6 % alarme false, 8,6 % abțineri.

**Schimbări.**
1. ZIPA devine judecător: `PhoneScorer` e apelat din `AttemptJudge` prin portul `PhonemeScorer`.
2. `Alternatives` v2, cu seturile de mai jos. Fiecare candidat costă sub o milisecundă **[V]**.
3. Fuziunea primește toată distribuția: `lpr_k` pentru fiecare candidat (limitat la ± 12 nat), `gop`, `fitPerLabel`, numărul de cadre.
4. `CtcAligner`: Viterbi pe secvența acceptată dă intervalul fiecărui fonem (rezoluție 20 ms). P2 alege segmentul DSP care se suprapune cu el (± 60 ms **[P]**); fără potrivire rămâne poziția din `alignFricatives`.

| Țintă (simboluri ZIPA) | Set de confuzie v2 (toate verificate în `ZipaInventory` **[V]**) |
|---|---|
| S `s` | `ʃ`, `t s`, `z`, `t`, `θ`, `ɬ`, `ɕ`, `s̪` (două simboluri), ∅ |
| Ș `ʃ` | `s`, `t ʃ`, `t s`, `ʒ`, `t`, `ɬ`, `θ`, ∅ |
| Z `z` | `s`, `ʒ`, `d`, `ð`, `ɮ`, `d z`, ∅ |
| J `ʒ` | `ʃ`, `z`, `d ʒ`, `d`, `ɮ`, ∅ |
| Ț `t s` | `s`, `t ʃ`, `ʃ`, `t`, `t θ`, ∅ |
| CE `t ʃ` | `t s`, `ʃ`, `t`, `s`, ∅ |
| GE `d ʒ` | `ʒ`, `z`, `d z`, `t ʃ`, `d`, ∅ |
| R `r` sau `ɾ` | `l`, `j`/`i`, `ɨ`/`ə`, `ʁ`/`ʀ`, `ɹ`, `w`, ∅ |
| L `l` | `j`/`i`, `w`/`u`, `r`/`ɾ`, ∅ |

Variantele cu diacritice (`s̪`, `z̥`) și `ʂ`, `ɕ` ca realizări acceptate ale lui Ș rămân doar în jurnal până măsurăm cât de des le emite modelul **[P]**.

**Cost și măsurare.** Pe PC: 32–35 ms pe cuvânt cu un fir, încărcare la rece 2,3–2,7 s **[V]**. Pe telefon: nemăsurat. La M0–M1, un ecran de depanare rulează 50 de cuvinte din `spec/` cu 1, 2 și 4 fire și scrie p50 și p95 pe fbank, rețea și GOP, memoria (PSS) și încălzirea după 5 minute. Modelul se încarcă la intrarea în lecție, cu o inferență de încălzire.

**Dacă e prea lent** (p95 > 350 ms), în ordine: (a) 2–4 fire **[V]**; (b) doar fereastra țintei ± 400 ms **[P]**; (c) ZIPA rulează doar când `fast` nu e sigur (30–50 % din încercări **[E]**); (d) rezultatul întârziat merge doar la adult; (e) oprit: rămân capetele `fast`, iar tipurile care cer ZIPA (R, L, africatele) rămân „în probă”.

## 6. Calibrarea pe copil (E4)

**Ancora** = vectorul de trăsături al unui segment pe care un adult sau un logoped l-a marcat bun. Se ține pe copil × sunet × telefon × sursă audio. Azi există doar pentru S și Ș (`TalkerCalibration`: ≥ 10 ancore, ≥ 3 pe loc, cel mult 30 pe loc, cele vechi cad **[V]**).

`ChildNorm` extinde modelul la toate trăsăturile din §4. Pentru trăsătura f, cu n ancore (medie x̄, varianță s²) și valorile populației pentru grupa de vârstă (μ₀, σ₀² din `spec/thresholds/population.json`):

`μ = (n·x̄ + κ·μ₀) / (n + κ)`, `σ² = (n·s² + κ·σ₀²) / (n + κ)`, `σ ≥ σ_min`, `z = (x − μ) / σ`, cu κ = 5 **[P]**.

Podele: 290 Hz la centroid, 400 Hz la vârf, 2,5 dB la raportul de benzi **[V]**. Populația de pornire la copii: granița S/Ș la 7,9 kHz, scară 984 Hz, mai largă sub 5 ani **[V]**. Pentru S/Ș rămâne regula de azi: granița e mijlocul dintre medianele celor două locuri **[V]**.

| Treaptă de calibrare | Condiție | Ce are voie motorul |
|---|---|---|
| 0 | sub 5 ancore la sunet | prior de grup, bandă de nesiguranță 0,12 (0,16 la 4 ani, 0,2 la 3 ani) **[V]**. Erorile de loc și de distorsiune nu se numesc copilului |
| 1 | ≥ 5 ancore la sunetul-țintă **[P]** | z față de copil; `PlaceSwap`, `AffricatePlace`, `WeakOrShort` se pot numi |
| 2 | ≥ 3 pe fiecare loc, ≥ 10 în total, separare ≥ 1,5 abateri **[V]** | granița proprie, bandă 0,08 **[V]**; `Lateral` și `Interdental` devin posibile (pe același telefon) |

Copilul care nu poate încă spune sunetul nu are ancore bune la el. Atunci folosim doar ancorele locului opus (granița = mediana lor + 2σ **[P]**), iar tipul se numește numai dacă adultul e de față.

**De unde vin ancorele.**
- *Fotografia vocii* (onboarding): 6 cuvinte și vocalele /a/, /i/, /u/ ținute. Dă F0 median, formanții de referință pentru `F2_z`, nivelul obișnuit, zgomotul camerei și lățimea de bandă a telefonului. Sibilantele de acolo devin ancore doar dacă adultul confirmă fiecare cuvânt.
- *ADULT-CALIBRATE*: pe sunet, 5 rostiri (izolat sau silabă); adultul ridică degetul după fiecare; ecranul arată câte s-au strâns. Sub două minute.
- *Degetul adultului din lecție*: un 👍 pe o încercare cu captură bună adaugă o ancoră.

**Stocare.** Schema e a capitolului 02. Am nevoie de: copil, sunet, loc, data, sursa (`snapshot`, `calibrate`, `thumb`, `therapist`), modelul telefonului, sursa audio, rata, `featureVersion`, vectorul de trăsături, legătura cu încercarea. Fără audio.

**Deriva.** Ponderea unei ancore se înjumătățește la 90 de zile **[P]**. La schimbarea telefonului sau a sursei audio, trăsăturile de peste 7 kHz revin la treapta 0 și adultul e invitat la recalibrare. Dacă încercările confirmate de adult stau peste 1σ de ancore la 10 la rând **[P]**, norma se reface. Pragurile unui copil se mută doar spre prudență **[V]**.

## 7. Fuziunea și decizia (E5)

**Capul.** Pe fiecare sunet, o regresie logistică multinomială: clasele sunt `correct` și tipurile de eroare ale sunetului; intrările, cel mult 60: trăsăturile z, `lpr_k`, captura (SNR, bandă), contextul (poziție, nivel, treaptă de calibrare) și, opțional, camera (`lipRound`, `tongueVisible`). O intrare lipsă ia valoarea 0 și ridică o mască. Calibrarea: o temperatură pe cap, plus tabel izotonic pe clasă când sunt destule date. Ținta: ECE ≤ 0,05 **[V]**.

```json
{ "schema": 1, "sound": "s", "version": "2026-11-22.r01", "featureVersion": "f3a9c1",
  "trainedOn": { "round": "r01", "speakers": 9, "tokens": 412, "evidenceLevel": "imitations" },
  "classes": ["correct", "Omission", "Stopping", "WeakOrShort", "PlaceSwap", "Lateral", "Interdental"],
  "levels":  { "Omission": 1, "Stopping": 2, "WeakOrShort": 2, "PlaceSwap": 4, "Lateral": 5, "Interdental": 5 },
  "inputs": [ { "name": "sNess",  "mean": 0.5, "scale": 0.25, "clip": [-4, 4] },
              { "name": "lpr.sh", "mean": 0.0, "scale": 3.0,  "clip": [-4, 4], "mask": "hasZipa" } ],
  "heads": { "fast": { "uses": "dsp",      "bias": [0.0], "weights": [[0.0]], "temperature": 1.3 },
             "full": { "uses": "dsp+zipa", "bias": [0.0], "weights": [[0.0]], "temperature": 1.1 } },
  "isotonic": { "correct": { "x": [0, 0.5, 1], "y": [0, 0.42, 1] } },
  "decision": { "tauOk": 0.80, "tauOkFast": 0.90, "tauAlmost": 0.35, "delta": 0.20, "coarseMax": 0.15,
                "tauErr": { "PlaceSwap": 0.85, "Stopping": 0.85, "Omission": 0.85, "WeakOrShort": 0.75 } },
  "metrics": { "falseAlarm": 0.04, "wrongPraise": 0.08, "answered": 0.83, "ece": 0.04 } }
```

`weights` are un rând pe clasă și o coloană pe intrare. Toate pragurile de mai sus sunt **[P]**: `tauOk` e cel mai mic prag cu laudă greșită ≤ 10 %, `tauErr[k]` cel mai mic cu alarmă falsă ≤ 5 % (§10).

**Ordinea de la grosier la fin.** Nivelurile sunt: 1 prezență, 2 manieră, 3 sonoritate, 4 loc, 5 distorsiune **[V]**. Un tip se poate numi doar dacă probabilitatea adunată a tipurilor mai grosiere e sub `coarseMax` și dacă măsurătoarea lui e validă (de exemplu sonoritatea pe un segment destul de lung).

```kotlin
fun decide(p: Probs, d: Decision, unlock: UnlockView, ctx: JudgeContext): Verdict {
    if (p.correct >= d.tauOk + ctx.childOkOffset) return Verdict.Correct
    val k = p.topError
    val nameable = p[k] >= d.tauErr(k) + ctx.childErrOffset(k) &&
        p[k] - p.secondError >= d.delta &&
        p.sumCoarserThan(d.level(k)) <= d.coarseMax && ctx.gateValid(k)
    if (nameable && unlock.allows(ctx.sound, k, ctx)) return Verdict.NamedError(k)
    if (p.correct >= d.tauAlmost) return Verdict.Almost
    return Verdict.Unsure                       // AdultNote(k, p[k]) dacă tipul e măcar „în probă”
}
```

**`spec/unlock.json`.**

```json
{ "schema": 1, "version": "2026-11-22.1",
  "rules": {
    "trial":    { "minTokensPerClass": 30, "minSpeakers": 5, "maxFalseAlarm": 0.10 },
    "pilot":    { "minTokensPerClass": 60, "minSpeakers": 8, "maxFalseAlarm": 0.05, "minPrecision": 0.80,
                  "runtime": { "calibrationTier": 1, "adultPresent": true, "tauErrExtra": 0.07 } },
    "children": { "minTokensPerClass": 150, "minChildren": 15, "maxPerChild": 10, "minCorrect": 250,
                  "minCorrectChildren": 25, "maxFalseAlarmCiUpper": 0.08, "minPrecisionCiLower": 0.70,
                  "maxContradictory": 0.02, "minKappaHuman": 0.60 } },
  "entries": [
    { "sound": "s", "kind": "PlaceSwap", "wave": 1, "state": "trial", "evidenceLevel": null,
      "requires": { "minBandwidthHz": 11000 }, "evidence": null },
    { "sound": "s", "kind": "Interdental", "wave": 3, "state": "locked",
      "requires": { "camera": true, "minBandwidthHz": 16000 }, "evidence": null } ],
  "signedBy": [ { "role": "logoped", "code": "T01", "date": "2026-11-22" } ] }
```

| Stare | Cine vede tipul | Cum se ajunge |
|---|---|---|
| `locked` | nimeni; doar `JudgeTrace` | starea de pornire |
| `trial` („în probă”) | adultul, cu mențiunea „în probă” | regula `trial` **[P]**, pe validare încrucișată pe vorbitor |
| `unlocked`, `evidenceLevel: "imitations"` | și copilul, doar cu condițiile `runtime` | regula `pilot` **[P]**: adulți, logopezi și copiii disponibili; adult de față, copil calibrat, prag mai strict |
| `unlocked`, `evidenceLevel: "children"` | și copilul | regula `children` **[V]** (AB §5) |

Fișierul e citit din APK și nu se schimbă pe telefon. Doar raportul din E6 propune o trecere; o persoană din echipă și un logoped o semnează, iar fișierul intră în același commit cu raportul. Un test din CI pică dacă o intrare `unlocked` nu are dovada cerută de regulă.

**Trei încercări nesigure la rând** (`Retake` nu se numără; orice alt verdict resetează): sesiunea coboară un nivel de dificultate *în aceeași treaptă* (§11), iar `ParentCard` spune ce a observat telefonul. La a doua serie în aceeași sesiune, pasul se încheie pe un exercițiu ușor și sunetul primește „ascultați împreună” în ADULT-SOUNDS. Treapta nu coboară.

**Degetul adultului.** Adultul e întrebat la fiecare `Unsure` și, de control, la o încercare din șase **[P]**. Verdictul lui hotărăște încercarea.

| Telefonul | Adultul | Ce se numără | Urmare |
|---|---|---|---|
| `Unsure` | 👍 | reușită | ancoră (dacă captura e bună); etichetă în jurnal |
| `Unsure` | „încă nu” | nereușită | copilul vede „Hai să mai încercăm” |
| `Correct` | „încă nu” | nereușită | dezacord în jurnal; la 3 dezacorduri din 20 pe un sunet, `childOkOffset` + 0,05 **[P]** și invitație la recalibrare |
| `NamedError`, `Almost` | 👍 | reușită | instrucțiunea nu se repetă; `childErrOffset(k)` + 0,05, până la 0,97 **[P]** |
| `Retake` | — | nimic | adultul nu e întrebat |

## 8. Porțile de captură și de cuvânt (E8)

| Verificare | Prag | Rezultat |
|---|---|---|
| Tăiere (clipping) | serii ≥ 3 eșantioane la ≥ 0,985, peste 0,1 % din clip **[V]** | `Retake(Clipped)` |
| Prea încet | RMS al vorbirii < −50 dBFS sau plajă dinamică < 12 dB **[V]** | `Retake(TooQuiet)` |
| Prea scurt, fără voce | vorbire < 0,12 s **[V]**; nimic în 6 s **[V]** | `Retake(TooShort)`, `Retake(NoSpeech)` |
| SNR pe bandă | E(4–8 kHz) din fricație − E(4–8 kHz) din cele 300 ms dinainte < 15 dB **[P]** | la sibilante și africate: `Retake(Noisy)` |
| Lățime de bandă | ≥ 16 kHz: totul; 11–16 kHz: fără `Lateral`; sub 11 kHz, rată < 32 kHz sau Bluetooth: fără loc și distorsiune, rămân prezența, maniera, sonoritatea și ZIPA **[V]** | trepte, nu refuz |
| Eșantioane pierdute | `CapturedClip.dropped > 0` în zona țintei | `Retake(Dropped)` |
| Vocea adultului | F0 median < 0,7 × al copilului **[P]** | `Retake(AdultVoice)` |

**Camera, la începutul sesiunii.** O secundă de ascultat cu microfonul deschis (aplicația tace oricum). Dacă podeaua trece de −45 dBFS sau lasă sub 15 dB până la nivelul obișnuit al sibilantelor copilului **[P]**, `ParentCard` întreabă „E cam gălăgie. Mergem într-un loc mai liniștit?”. Cu „Continuăm aici” tipurile de distorsiune se opresc și verdictele înclină spre `Unsure`. Podeaua e urmărită tot timpul (`NoiseFloor` **[V]**); o urcare de peste 10 dB timp de 5 s **[P]** repetă întrebarea o singură dată.

**Poarta de cuvânt.**
- Cuvinte: `fitPerLabel ≥ −3` și ≥ 10 cadre **[V]**. Poarta e blândă cu cuvintele lungi (3,6 % „alt cuvânt” acceptat pe test **[V]**), deci se adaugă: durata vorbirii între 0,5× și 2,5× din cea așteptată (0,09 s pe fonem + 0,15 s **[E]**) și numărul de silabe la ± 1 **[P]**.
- Propoziții, perechi, frământări: Whisper (`judgeSentence`, `judgePair`). `otherWord` → `Retake(OtherWord)`. Un cuvânt „auzit” de Whisper nu dovedește sunetul: modelul repară un S lateral **[V]**.
- Izolat și silabe: fără poartă de cuvânt; se cere doar maniera așteptată și o durată minimă.

**Cum arată „Retake”.** Nu e încercare: nu intră în scor, în progresie sau în rata de reușită. Copilul vede „Mai spune o dată” și un gest pe cauză (mai aproape, mai tare, „doar tu”); litera face `tremble` ușor, fără culoare. După două la rând, Foni dă din nou modelul, iar `ParentCard` numește cauza.

## 9. Colectorul (B1) și setul de date (B3, B4)

**„Fonia Colector”** e un ecran doar în varianta de depanare (`app/src/debug`). Pași: acord → fișa vorbitorului → verificarea camerei → rostiri → reascultare → export.

| Element | Specificație |
|---|---|
| Acord | adult: text pe un ecran, bifă și inițiale. Copil: acordul scris al părintelui (protocolul e în B2) și codul formularului introdus în aplicație. Retragerea se face după codul vorbitorului |
| Fișa | cod (`A07` adult, `T02` logoped, `C13` copil), grupă de vârstă (`4-5`, `6-7`, `8-10`, `11-17`, `adult`), rol. Fără nume și fără dată de naștere. Telefonul și sursa audio se completează singure |
| Pachete de 10 minute | A: S, Ș, Z, J · B: R, L · C: Ț, CE, GE. Pe sunet, **corect**: 3 izolate, 6 silabe, 9 cuvinte (câte 3 pe poziție), 2 propoziții. **Greșeli imitate** (doar adulți și logopezi): fiecare tip al sunetului × 5 (2 silabe, 3 cuvinte), cu o indicație pe ecran („Spune «sare» cu limba între dinți”) și butonul „n-am reușit s-o imit” |
| Copii | doar numit de imagini după modelul lui Foni, 6 cuvinte pe sunet, sub 5 minute. Greșelile lor naturale le etichetează logopezii |
| Sunet | WAV, 48 kHz, mono, PCM pe 16 biți, sursa fără procesare, cu 300 ms păstrați înainte și după; fără tăiere, fără compresie |
| Metadate | `session.json` + un rând pe clip în `clips.jsonl`: vorbitor, grupă, rol, telefon, Android, `audioSource`, rată, lățime de bandă măsurată, `promptId`, sunet, nivel, poziție, text, `intendedLabel` (`correct` sau tip + înlocuitor), numărul rostirii, podeaua camerei, rezumatul `CaptureQuality`, versiunile aplicației și ale pragurilor, versiunea acordului |
| Nume de fișier | `<rundă>/<vorbitor>/<sunet>/<promptId>__<etichetă>__t<n>.wav`, de exemplu `r01/A07/s/s_w_ini_sare__Interdental__t1.wav` |
| Export | arhivă zip cu listă SHA-256, scrisă în folderul aplicației: se ia prin USB (`adb pull`) sau din foaia de partajare (Nearby Share, Bluetooth). Aplicația nu are permisiunea de internet |

**Pagina de etichetat (B3).** O pagină HTML locală, servită de un script mic (`uv run label_server.py --data <folder>`), fără internet. Clipurile vin în ordine amestecată, cu eticheta intenționată ascunsă. Butoane: corect · aproape · tipurile sunetului · alt sunet · nu se aude · nu știu; siguranță 1–3; notă. Fiecare evaluator scrie în `labels/<cod>.jsonl`. Doi evaluatori lucrează separat. Scriptul de acord calculează κ pe corect/incorect și κ pe tip, plus matricea de confuzie. Eticheta de aur există doar când cei doi sunt de acord; restul sunt „disputate”, scoase din antrenare și păstrate în raport. 10 % se reascultă după cel puțin două săptămâni **[V]**.

**Arhiva (B4)**, pe PC-ul echipei, în afara git:

```
fonia-data/
  rounds/r01_2026-11-21/raw/<vorbitor>/…wav, clips.jsonl, session.json
  rounds/r01_2026-11-21/labels/<evaluator>.jsonl, gold.jsonl
  features/<featureVersion>/r01.jsonl
  reports/r01/report.html, summary.json
  consent/            (formulare scanate, criptat, separat)
  SPEAKERS.csv        (cod, rol, grupă de vârstă; fără nume)
```

| Scop | Minimum |
|---|---|
| Primii coeficienți ai unui sunet | ≥ 60 de rostiri corecte de la ≥ 6 vorbitori și ≥ 30 pe fiecare tip de la ≥ 5 vorbitori **[P]**. Opt adulți pe un pachet dau ~160 corecte și ~40 pe tip |
| „În probă” și deblocare | regulile `trial`, `pilot`, `children` din §7 |
| O afirmație pe copii, pe un sunet | ~250 corecte + ~250 greșite de la ≥ 25 de copii **[V]** (P §4) |

**Reguli pentru înregistrările copiilor.** Acord scris al părintelui înainte de prima rostire. Doar cod, fără nume și fără video. Clipurile merg de pe telefonul echipei pe PC-ul echipei (folder criptat), nu în cloud, nu în git, nu prin aplicații de mesagerie. După ce suma de control se verifică, se șterg de pe telefon. Ștergere la cerere în 48 de ore, după cod. Nu se folosesc în film sau în pitch. În git intră doar vectorii de aur de la adulți din echipă.

## 10. Antrenare și evaluare (E6)

| Piesă | Cum |
|---|---|
| Mediu | `tools/train/`, proiect `uv` (`pyproject.toml` + `uv.lock`): `numpy`, `pandas`, `scipy`, `scikit-learn`, `soundfile`, `matplotlib`, `jinja2`, `pytest`. Fără rețele neuronale |
| Trăsături | **același cod Kotlin ca pe telefon**, printr-un utilitar JVM, `:tools:featurecli`: `featurecli extract --clips clips.jsonl --spec spec/ --out features.jsonl` scrie pe clip vectorul de trăsături (numele intrărilor de fuziune), `SoundOutcome`, scorurile ZIPA, captura și `featureVersion`. `featurecli judge --fusion …` rulează P0–P5 complet. Python nu recalculează nicio trăsătură |
| Paritate telefon–PC | 20 de WAV de aur judecate pe telefon și pe JVM: trăsături DSP la 10⁻⁴ relativ, `lpr` la 0,05 nat **[E]** |
| Ajustare | `fit_heads.py`: regresie multinomială cu L2 (C ales prin validare interioară), clase echilibrate. Calibrarea pe copil se simulează: 5 rostiri corecte ale fiecărui vorbitor devin ancore și ies din evaluare |
| Validare | `GroupKFold` pe vorbitor (leave-one-speaker-out sub 10 vorbitori). Niciodată împărțire pe înregistrare **[V]** |
| Calibrare | temperatură pe predicțiile din afara pliului; izotonică de la 300 de rostiri în sus **[P]** |
| Praguri | pe curba acoperire–risc: `tauOk` și `tauErr[k]` minime care țin țintele de mai jos, cu limita de sus a intervalului |
| Export | `spec/fusion/<sound>.json` + propunerea pentru `spec/unlock.json` |

| Metrică | Țintă |
|---|---|
| Alarmă falsă pe rostiri corecte | ≤ 5 % (limita de sus a intervalului ≤ 8 %) **[V]** |
| Laudă greșită | ≤ 10 % **[V]** |
| Răspunsuri (fără `Unsure`; `Retake` nu intră) | ≥ 80 % pe corect/încă nu **[V]**; ≥ 60 % pe tipuri numite **[V]** |
| Acord cu logopezii | κ model–consens ≥ κ logoped–logoped − 0,10 **[V]** |
| Precizia unui tip numit | ≥ 0,80 (limita de jos ≥ 0,70) **[V]** |
| Instrucțiuni contrare (S→Ș luat drept Ș→S) | ≤ 2 % **[V]** |
| Calibrare | ECE ≤ 0,05 **[V]** |

Intervalele se fac prin bootstrap pe vorbitor.

**Raportul după fiecare rundă** (`reports/rNN/report.html`, `summary.json`): câți vorbitori și câte rostiri pe rol, vârstă și clasă; acordul om–om; matricea de confuzie pe sunet; curbele acoperire–risc; metricile cu intervale; rezultatele pe telefon; trecerile propuse în `unlock.json`; fraza exactă care se poate spune în pitch; versiunile.

**Teste de regresie.** `spec/detectors/*.json`: `{ wav, target: { sound, text, level }, calibration?, expect: { features: { nume: [valoare, toleranță] }, verdict, kind? } }`. Cel puțin 4 WAV pe sunet (corect, una sau două greșeli, liniște, zgomot) plus semnalele sintetice din §4. Rulează pe JVM în sub 10 s **[P]**. Un fișier de fuziune nou nu intră dacă schimbă un verdict de aur fără notă în `09_DECIZII_NOI.md`.

## 11. Progresia (F3)

| Regulă | Valoare | Stare |
|---|---|---|
| Ce e o încercare | `AttemptEnded` cu verdict `Correct`, `Almost`, `NamedError` sau `Unsure`. `Retake` nu se numără. Pașii `Listen` și `Warmup` au scorul lor (atingeri, poziții ținute) | **[P]** |
| Scorul blocului | reușită = `Correct` sau 👍 de la adult. `Almost` și `NamedError` = încă nu. Un `Unsure` fără adult iese din bloc; după 10 încercări fără 6 hotărâte, blocul nu decide nimic | **[P]** |
| Bloc și avans | 6 încercări; se urcă la ≥ 5 | **[V]** `Progression` |
| Coborâre | azi codul coboară singur sub 3 din 6 **[V]**. Nou: `ProgressionStep.Drop` devine `SuggestStepDown`; adultul vede „Coborâm o treaptă?” în ADULT-SOUNDS, iar treapta se schimbă doar la confirmarea lui | **[P]** |
| Lacătul după `isolated` | 12 reușite, apoi `NeedsAdult` **[V]**. Adultul confirmă în ADULT-CALIBRATE, iar aceleași rostiri devin ancore. `openedByParent` îl ridică **[V]** | păstrat |
| Adultul decide și la ieșirea din | `sentences`, `retell` **[V]**, `twister` **[P]** | extins |
| Sugestia de logoped | 9 blocuri slabe pe `isolated` **[V]** | păstrat |
| Trepte | `Rung` trece de la 10 la 14 valori: `listen`, `warmup` înainte de `isolated`, `back` după `syllables`, `twister` după `sentences` | cu capitolul 06 |

**Dificultatea ascunsă, spre 80–85 % reușită** **[V]** (Y). Se măsoară pe ultimele 12 încercări hotărâte ale sunetului (reușită = 1, `Almost` = 0,5 **[P]**) și se reevaluează la fiecare 6. Sub 70 %: un nivel mai ușor; peste 90 %: un nivel mai greu; plajă −2…+2 **[P]**. `ChallengePoint` (azi 60–80 % **[V]**) se reglează pe noua țintă.

| Mai ușor | Mai greu |
|---|---|
| modelul lui Foni înaintea fiecărei încercări; model rar (0,6× **[V]**); silabe deschise, cuvinte scurte, fără al doilea sunet greu | fără model; cuvinte lungi; cuvinte cu alt sunet greu; poziții amestecate |

Dificultatea nu se reglează niciodată relaxând judecătorul: `tauOk` și `tauErr` nu coboară. Primul exercițiu al unui bloc și ultimul al sesiunii vin din lista ușoară, ca sesiunea să se termine pe o reușită.

## 12. Calendarul capitolului și ce spunem pe 26–27 noiembrie

| Sunet | M0 (sâmbătă prânz) | M1 (duminică) | M2 (luni) | M3 (marți) | M4–M5 (miercuri–vineri) | M6 (până la finală) |
|---|---|---|---|---|---|---|
| S | bandă continuă (`place`, `hold`), capul v0, colector pachet A | verdict cu motiv; cap ajustat pe runda 1; calibrare; tipurile din valul 1 „în probă” sau `pilot` | ZIPA în fuziune, `Stopping` și `Omission` cu ZIPA | ADULT-CALIBRATE, degetul adultului | praguri din rundele 2–3, cifrele din raport | `Lateral`, `Interdental` „în probă” (cu camera) |
| Ș | la fel | la fel | la fel | la fel | la fel | la fel |
| Z | înregistrat (pachet A) | `voicing` live, capul v0 (`Devoicing`) | cap ajustat; `Devoicing` `pilot`, loc „în probă” | calibrare | praguri | `PlaceSwap`, `Stopping` |
| J | la fel | la fel | la fel | la fel | la fel | la fel |
| Ț | — | înregistrat (pachet C) | trăsăturile 6, `burstPulse`, cap v0 + ajustat; tipuri „în probă” | calibrare | praguri; rămân „în probă” | valul 2 spre `pilot` |
| CE/CI | — | înregistrat | la fel | la fel | la fel | la fel |
| GE/GI | — | înregistrat | la fel + sonoritate în închidere | la fel | la fel | la fel |
| R | `trill`, `tapPulse` live (există) | înregistrat (pachet B) | cap cu ZIPA: `RtoL`, `Omission` `pilot` dacă trec | calibrare | praguri | `RtoGlide`, `RUvular`, formanți |
| L | — | înregistrat (pachet B) | cap doar ZIPA, „în probă”; adultul judecă | — | măsurat acordul cu adultul | formanți, `LtoGlide` |

| Putem spune la încărcare | Nu spunem |
|---|---|
| Câți oameni și câte rostiri am înregistrat (adulți, logopezi, copii, separat) | procente „pe copii” |
| Cât de des se potrivesc doi logopezi pe materialul nostru (κ) | „mai bun decât un logoped” |
| Cât de des se potrivește telefonul cu ei pe aceste date, cu mențiunea „mai ales adulți care imită” | „detectează sigmatismul lateral”, „diagnostic” |
| În câte cazuri telefonul s-a abținut și a întrebat adultul | că imitațiile adulților prezic copiii (aceeași metodă a scăzut de la 86 % la 75,7 % **[V]**) |
| Regula de deblocare și starea fiecărui tip, cu nivelul de dovadă | că toate cele 9 sunete numesc greșeala copilului |

Așteptarea cinstită **[E]**: pe 26, tipurile din valul 1 ajung cel mult la `unlocked` cu `evidenceLevel: "imitations"`; africatele, R (în afară de `RtoL`) și L rămân „în probă”; valul 3 rămâne blocat.

## 13. Pachetele de lucru

| Pachet | Scop și pași | Fișiere și module | Gata când | Ore-agent | Depinde de | Riscuri |
|---|---|---|---|---|---|---|
| **B1** Colectorul | acord, fișă, verificarea camerei, pachetele A–C din JSON, înregistrare cu sfârșit automat, reascultare, export zip | `app/src/debug/…/collector/`, `:platform:audio` (`CaptureEngine`, `WAV`), `spec/collector/prompts.json` | pe telefonul real: un pachet în ≤ 10 min, zip cu SHA valid, WAV de 48 kHz fără procesare; test JVM pentru nume și metadate | 5 | A1, A4, E1 | telefonul nu declară `UNPROCESSED`; lista de rostiri prea lungă |
| **B3** Etichetarea | pagină locală, doi evaluatori, κ, `gold.jsonl` | `tools/label/` | 50 de clipuri etichetate de două persoane, κ calculat; `pytest` verde | 3 | B1 | logopezii n-au timp; κ mic pe tip |
| **B4** Arhiva | structura de foldere, `SPEAKERS.csv`, script de import și verificare, regulile de confidențialitate scrise | `tools/data/`, `docs/v2/date.md` | importul unei runde verifică sumele și refuză fișiere fără acord | 1,5 | B1, B2 | date de copii ajunse în git (blocaj prin `.gitignore` + test) |
| **E2** Trăsături noi | cele 8 din §4, în ordine; `LiveExtras` (`voicing`, `tapPulse`, `burstPulse`, `stability`) | `dsp/…/SpectralShape.kt`, `Voicing.kt`, `Affricate.kt`, `Formants.kt`, `LateralCues.kt`, `:engine:live` | fiecare cu test sintetic și vector în `spec/detectors/`; cele 240 de teste vechi rămân verzi; banda continuă nu alocă | 8 | E1 | formanții la F0 mare; cost peste buget |
| **E3** Foneme | `Alternatives` v2, distribuția completă, `CtcAligner`, portul `PhonemeScorer`, măsurarea pe telefon | `phones/…/TargetSound.kt`, `RestrictedGop.kt`, nou `CtcAligner.kt`; `:platform:ml` | testele `:phones` verzi; AUC pe clipurile adulte nu scade sub 0,97; tabel de latență de pe telefon | 5 | E9 | prea lent pe telefon; Ș și Ț auzite greșit |
| **E4** Calibrare | `ChildNorm`, treptele 0–2, ancore din cele trei surse, derivă | `dsp/…/ChildNorm.kt` (lângă `TalkerCalibration`), `:core:data` | teste: shrinkage, podele, ancore inversate respinse, paritate cu `TalkerCalibration` pe S/Ș | 4 | F4, E2 | ancore greșite de la adult; copil fără nicio rostire bună |
| **E5** Fuziune și decizie | `FusionHead` (v0 + JSON), `DecisionPolicy`, `UnlockRegistry`, `JudgeTrace`, `AttemptJudge` în locul `TakeJudge` | `:engine:judge`, `spec/fusion/`, `spec/unlock.json` | teste pe fiecare ramură a deciziei; vectorii de aur dau verdictul așteptat; testul „deblocat fără dovadă” pică în CI | 6 | E2, E3, E4 | prea multe `Unsure`; reguli v0 prea încrezătoare |
| **E6** Antrenare | `:tools:featurecli`, `fit_heads.py`, raportul, regresiile | `tools/featurecli/`, `tools/train/` | runda 1 produce `spec/fusion/s.json` și raportul; paritate telefon–PC pe 20 de clipuri | 6 | B1, B3, E2, E5 | puține date; supraajustare pe 8 vorbitori |
| **E7** Mesaje și animații | tabelul sunet × tip → cheia textului pentru copil, cheia pentru adult, animația, exercițiul următor | `spec/feedback.json`, stratul de texte (cap. 06), `LiveLetter` (cap. 05) | fiecare pereche din §3 are intrare; test: text pentru copil ≤ 8 cuvinte, fără cuvinte interzise | 3 | E5, C4, I5 | text nepotrivit vârstei; animație confundată cu `tremble` |
| **E8** Porți | `CaptureGate`, verificarea camerei, `WordGate`, cauzele de `Retake` | `:engine:judge`, `dsp/CaptureQuality.kt`, `:engine:live` | teste cu zgomot, tăiere, alt cuvânt, bandă îngustă; pe telefon, într-o cameră zgomotoasă apare cardul | 3 | E1, E3 | praguri de SNR greșite pe telefonul real |
| **F3** Progresie | 14 trepte, `SuggestStepDown`, scorul blocului, dificultatea ascunsă, legătura cu `VerdictReady` | `domain/Progression.kt`, `Curriculum.kt`, `adaptive/ChallengePoint.kt`, `:engine:session` | vectori noi în `spec/`; test: fără adult, treapta nu coboară; lacătul ține | 4 | F1, F4, E5 | vectorii de sesiune iOS trebuie regenerați |

Total: ~48,5 ore-agent **[E]**. E2, E3 și B1 pot merge în paralel (module diferite); E5 le așteaptă pe toate.

## 14. Propuneri de schimbare și întrebări deschise

**Propuneri de schimbare** (planul-mamă rămâne neatins până se hotărăște)

1. **Două niveluri de dovadă la deblocare.** §5.4 din planul-mamă nu deosebește deblocarea pe imitații de cea pe copii. Fără nivelul `imitations` (cu adult de față și copil calibrat), pe 26 niciun tip nu ajunge la copil, deși M1 cere „verdict cu motiv pentru S și Ș”.
2. **Tipuri fără val în planul-mamă:** `WeakOrShort`, `RtoVowel`, `LtoR`, `PlaceSwap` la Z și J, `Stopping` în afară de S, `Devoicing` la GE. Le-am pus în valurile 1 și 2, ca mai sus.
3. **`NamedError(kind)` nu spune cu ce a fost înlocuit sunetul.** `LtoGlide` are două instrucțiuni (I și U), `Stopping` la Ș are T și Ț. Propun `NamedError(kind, heardAs?)`.
4. **Nume de fișier ASCII** pentru `spec/fusion/<sound>.json`: `s, sh, z, j, ts, ce, ge, r, l` (cheile `ș` și `ț` rămân în JSON).
5. **Fișiere noi în `spec/`:** `spec/feedback.json`, `spec/thresholds/population.json`, `spec/collector/prompts.json`. **Modul nou:** `:tools:featurecli`.
6. **Whisper nu intră în cei 600 ms la cuvinte** (300–800 ms estimat). Propun: poarta de cuvânt e ZIPA; Whisper judecă doar propoziții, perechi și frământări, cu buget propriu, iar un rezultat întârziat nu schimbă un verdict deja arătat.
7. **Bugetul de 100 ms se numără de la `AttemptEnded`.** Liniștea care închide încercarea (250–700 ms) vine înainte și nu se poate elimina.
8. **„100 de valori pe secundă”** sunt azi ~94 (analiză la 512 eșantioane). Propun să rămână așa: altfel se pierde paritatea de praguri cu iOS.
10. **Trei animații în plus** pentru capitolul 05: `stretch`, `drop-in`, `thicken`.

**Întrebări deschise**

1. Telefonul de test declară `UNPROCESSED`? Ce lățime de bandă măsurăm sâmbătă?
2. „Omisiunea” din valul 1 înseamnă toate sunetele sau doar S și R (cum propune AB)?
3. Cine sunt cei doi evaluatori și logopedul care semnează `unlock.json`? Au 2–3 ore duminică–luni?
4. La `Lateral` și `RUvular`: copilul primește instrucțiunea sau doar adultul primește „arată-i logopedului”?
5. Degetul unui părinte creează ancore sau doar al logopedului?
6. CE spus ȘE (grai): îl acceptăm implicit sau printr-o setare a adultului?
7. Cât păstrăm înregistrările copiilor după concurs?
8. Dacă ZIPA trece de 600 ms pe telefonul de test, acceptăm R și L doar „în probă” la încărcare?
9. `ʂ` și `ɕ` sunt realizări acceptate ale lui Ș în ZIPA?
10. Ștefan primește vectorii din `spec/detectors/` și fișierele de fuziune; are nevoie și de un echivalent Swift al lui `featurecli`?
