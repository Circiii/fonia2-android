# 05. Litera vie și sistemul de design

Scris pe 20 noiembrie 2026. Acoperă pachetele **C1–C5** (litera) și **D1–D5** (design, componente, Foni, sunet și vibrații, mișcare). Numele și deciziile vin din `00_PLAN_MASTER.md` și nu sunt schimbate aici; ce cred că trebuie schimbat e la §15.

Surse: rapoartele S, Z, AC, O, T, Y, AB, `android-inventory.md`, prototipurile `allo.html`, `allo-viu.html`, `allo-lab.html`. Fontul l-am inspectat azi cu fontTools 4.63 (`vendor_fonts/allo/fonts/variable/AlloVariable.ttf`), contrastele le-am calculat cu formula WCAG 2, iar sunetele ciornă le-am măsurat cu `earshot measure`. Marcaje: **[Verificat]** = măsurat sau citit azi, **[Estimare]**, **[Propunere]**.

## 1. Ideea vizuală

Ecranul e aproape gol: hârtie albăstruie, text în cerneală, un singur buton plin. În mijloc stă **o literă-autocolant uriașă, care e vie**: respiră, se apleacă să asculte, se umflă cu vocea copilului, sare când sunetul iese și, când nu iese, arată cu propriul corp ce e de făcut (virgula cade la loc, aerul se mută pe mijloc). Foni e desenat cu același contur în trei straturi, deci litera și papagalul par decupate din aceeași coală de autocolante. Restul tace: fără scene pictate pe ecranul de lucru, fără gradient, fără iconițe colorate, fără nimic care se mișcă singur. Ce o deosebește de alte aplicații pentru copii: feedback-ul nu e o bară, o stea sau o față zâmbitoare pusă lângă conținut, ci **chiar conținutul** (litera) își schimbă forma, iar tot ecranul are patru nuanțe și un singur font.

Două suprafețe, nu mai multe **[Propunere]**:

| Suprafață | Fundal | Text | Acțiunea principală | Unde |
|---|---|---|---|---|
| `Paper` | `bg` | `ink` | `ContinueButton` plin `accent`, text alb | tot, în afară de scenă |
| `Stage` | `accent` (albastru plin) | alb | `ContinueButton` alb, text `ink` | doar unde `LiveLetter` e erou: pașii `Hold`, `Syllables`, `Words`, `Pairs` din `LESSON`, `END`, `ON-0` |

## 2. D1: tokenuri

### 2.1 Cele 14 roluri de culoare (tema luminoasă)

Schema din O (neutre + accent + sărbătoare), refăcută pe D5. Valorile vin din prototipul `allo-viu.html`, aprobat vizual de echipă. Nu există roșu în aplicație.

| # | Token | Hex | La ce folosește | Interzis |
|---|---|---|---|---|
| 1 | `bg` | `#F4F6FF` | fundalul `Paper` | — |
| 2 | `surface` | `#FFFFFF` | carduri, foi, text pe `accent`, butonul de pe `Stage` | — |
| 3 | `fill` | `#E6EEFF` | câmpuri, șina de progres, stare apăsată, selecție | text `accent` pe el (4,44:1) |
| 4 | `separator` | `#DFE4F7` | linii decorative | ca singur contur al unui control (1,27:1) |
| 5 | `inkSecondary` | `#5A638A` | text secundar, conturul câmpurilor | — |
| 6 | `ink` | `#131A3A` | text, iconițe, litere simple; fundalul „Respiră cu litera”; baza temei întunecate | — |
| 7 | `accent` | `#2F5BFF` | **acțiunea principală**, selecție, progres, fundalul `Stage` | două lucruri pline `accent` pe același ecran |
| 8 | `accentDeep` | `#14228A` | text albastru, apăsat, conturul autocolantului albastru | — |
| 9 | `voice` (roz) | `#FF4F93` | **sunetul copilului**: marginea autocolantului-țintă, `MicCapsule` când ascultă | text; semn de sine stătător pe `bg` (2,87:1) |
| 10 | `voiceDeep` | `#8A1247` | conturul autocolantului roz, text pe fețe roz | „greșit” sau avertizare |
| 11 | `parrot` (verde) | `#17B55E` | Foni, „confirmat de adult”, autocolantul de reușită | text alb pe el (2,69:1); semn fără contur |
| 12 | `parrotDeep` | `#0A5A30` | text verde, conturul autocolantului verde | — |
| 13 | `sun` (galbenul aripii) | `#FFD23F` | stele, sărbătoare, vagonul auriu | text; fundal de buton obișnuit |
| 14 | `sunDeep` | `#9A6200` | conturul autocolantului galben, text pe fețe galbene | text mic pe `sun` (3,53:1) |

**Seturile de autocolant** (`sticker.*`, grup separat, nu roluri) **[Verificat în prototip]**:

| Set | `face` | `edge` | `rim` | Sens (unul singur) |
|---|---|---|---|---|
| `Voice` | `#FFF6E6` | `#FF4F93` | `#8A1247` | sunetul-țintă al copilului |
| `Parrot` | `#EFFFE0` | `#17B55E` | `#0A5A30` | reușită, confirmat |
| `Sun` | `#FFF3B8` | `#FFD23F` | `#9A6200` | stea, recompensă, combo |
| `Sky` | `#E6EEFF` | `#6C8BFF` | `#14228A` | „Aproape”, joacă neutră; nu pe `Stage` |
| `Flat` | `ink` (pe `Paper`) / alb (pe `Stage`) | — | — | literele care nu sunt țintă |

Umbra: același contur, decalat `(0,03 em; 0,09 em)`, negru cu alfa 0,20.

### 2.2 Contraste **[Verificat, calculate azi]**

| Pereche (text pe fundal) | Raport | Verdict |
|---|---|---|
| `ink` pe `bg` / `surface` / `fill` | 15,75 / 16,98 / 14,59 | orice text |
| `inkSecondary` pe `bg` / `surface` / `fill` | 5,43 / 5,86 / 5,03 | orice text |
| alb pe `accent` (buton, `Stage`) | 5,17 | orice text |
| `accent` pe `surface` / `bg` / `fill` | 5,17 / 4,79 / **4,44** | pe `fill` se folosește `accentDeep` |
| `accentDeep` pe `surface` / `bg` / `fill` | 12,92 / 11,98 / 11,10 | orice text |
| `ink` pe `voice` / `parrot` / `sun` | 5,49 / 6,31 / 11,76 | textul pe o nuanță vie e mereu `ink` |
| alb pe `voice` / `parrot` | 3,09 / **2,69** | interzis ca text |
| `voiceDeep` pe `surface` / `bg` / fața `Voice` | 9,30 / 8,62 / 8,67 | orice text |
| `parrotDeep` pe `surface` / `bg` / fața `Parrot` | 8,34 / 7,73 / 7,96 | orice text |
| `sunDeep` pe `surface` / `bg` / fața `Sun` | 5,10 / 4,73 / 4,55 | orice text |
| `sunDeep` pe `sun` | 3,53 | doar text mare sau semn |
| fața `Voice` pe `accent` (autocolant pe `Stage`) | 4,82 | forma literei se citește prin față |
| `rim` pe `accent`: `voiceDeep` / `parrotDeep` / `sunDeep` | 1,80 / 1,61 / 1,01 | conturul nu poartă forma pe `Stage` |

Consecința ultimelor două rânduri: autocolantul are mereu ≥ 3:1 față de fundal, pe `Paper` prin `rim` (≥ 8,6:1) și pe `Stage` prin `face` (≥ 4,8:1). De aceea cele trei straturi nu sunt decor.

### 2.3 Reguli de culoare

1. Un singur lucru plin colorat pe ecran (acțiunea principală sau scena).
2. O culoare, un sens: albastru = acțiune, roz = sunetul tău, verde = Foni și reușită, galben = sărbătoare.
3. Nicio informație doar prin culoare: fiecare stare are și formă, glifă sau cuvânt (tabelul de mai jos).
4. Copilul nu vede roșu. Nici adultul: acțiunile distructive sunt buton `ink` cu glifa ⚠, text explicit și confirmare în doi pași.
5. **Familiile de sunete nu au palete.** Un sunet se recunoaște după literă (`SoundTile`), pictograma gurii și locul pe `PATH`.

| Verdict | Formă (purtătorul de sens) | Glifă Allo | Set | Cuvânt |
|---|---|---|---|---|
| `Correct` | sare, se așază drept, plin | ✓ | `Parrot` | „Bravo!” |
| `Almost` | oblic, umplut pe jumătate, se leagănă o dată | ● lângă ○ | `Sky` | „Aproape!” |
| `NamedError(kind)` | animația-instrucțiune a tipului (§5.2) | → | `Voice` pe literă, recuzită `Sun` | instrucțiunea ≤ 8 cuvinte |
| `Unsure` | tremur desenat de mână, înclinat | ? | `Flat` (fără culoare) | „Hai să mai încercăm” |
| `Retake` | litera se strânge și revine | ↻ | `Flat` | „Mai spune o dată” |

Glife prezente în font **[Verificat]**: ★ ♥ ✓ → ← ↑ ↓ ↻ ✕ × ⚠ ⌕ ⏎ ⌫ ● ○ • … ▶ + −. Lipsesc: ☆ ✔ ◐ ♪ ☺ (nu le folosim).

**Întunecat și contrast mărit.** v1 e forțată pe luminos, dar fișierul de tokenuri are de pe acum trei moduri. Întunecat **[Propunere]**: se calculează în OKLCH cu aceleași roluri: `bg` = `ink`, `surface` = `ink` cu L +0,06, `ink` = `bg` luminos; nuanțele vii păstrează nuanța și croma și primesc L 0,72–0,80; valorile `Deep` devin L 0,88 (ca text pe fundal închis). Contrast mărit: `inkSecondary` → `ink`, `separator` → `inkSecondary`, semnele folosesc doar valorile `Deep`, `rim` crește cu 25 %. Generatorul rulează același test de contrast pe toate modurile și pică dacă o pereche din §2.2 scade sub prag.

### 2.4 Litere: doar Allo

Fișier: `res/font/allo_variable.ttf` (1,19 MB, OFL, versiunea 1.0.3 fixată prin SHA-256; `OFL.txt` în `assets/licenses/`). Axe **[Verificat]**: `wght` 100–900 (implicit 400), `ROND` 0–100 (implicit 50), `opsz` 9–32 (implicit 14). `opsz` = mărimea în sp, tăiată la 9–32.

| Token | sp | `wght` | Înălțimea rândului | Folosire |
|---|---|---|---|---|
| `display` | 40 | 800 | 44 | titluri de ecran la copil, `END` |
| `title` | 28 | 750 | 34 | titluri |
| `title2` | 22 | 700 | 28 | titluri de card |
| `headline` | 18 | 650 | 24 | butoane, rânduri |
| `body` | 17 | 500 | 24 | text |
| `label` | 15 | 600 | 20 | etichete, cipuri |
| `caption` | 13 | 500 | 18 | note; nimic sub 13 sp |
| `practiceWord` | 56 (se micșorează până la 36) | 300 simplu / 900 țintă | 64 | `LiveWord` |
| `practiceLine` | 28 | 400 | 38 | propoziții, frământări |
| `counter` | 28 | 800 | 32 | contoare, cu `tnum` |
| `heroLetter` | înălțime 200 dp (160–240 după ecran), nu sp | din `inflation` | — | `LiveLetter` |

| Cochilie | `ROND` | Treaptă | Altele |
|---|---|---|---|
| copil (`kid`) | 85 pentru text, 100 pentru titluri și butoane | +1 treaptă (`body` devine 20 sp) | marginea paginii 20 dp |
| adult și adolescent (`grown`) | 30 | scara din tabel | marginea paginii 16 dp, fără autocolante, fără Foni |

Funcții OpenType **[Verificat în GSUB]**: `ss01` („a” cu o buclă: `a → a.1`, inclusiv `ă`, `â`) și `ss02` (I cu serife, l cu codiță, 1 cu talpă, zero tăiat) se pornesc pe **tot ce exersează copilul** (`practiceWord`, `practiceLine`, `LiveLetter`, `LiveWord`) și rămân oprite în interfață. `tnum` pe orice număr care se schimbă. `case` lângă majuscule (`S–Ș`). `calt` oprit în cuvintele exersate (altfel `->` devine săgeată). Oblicul nu e o axă: Allo are desene oblice separate, statice **[Verificat în README]**. Pentru „Aproape” folosim o forfecare a conturului (§5.1), nu fișierele oblice.

**IPA.** Allo nu are simboluri fonetice. Ele apar doar la adult, în `ADULT-SOUNDS` („ce a auzit telefonul”: ʃ, θ, ɬ). Tokenul `type.ipa` trimite la fontul sistemului (`FontFamily.Default`, Noto), 15 sp. Copilul nu vede niciodată IPA.

### 2.5 Spațiu, colțuri, adâncime

| Grup | Valori |
|---|---|
| Spațiu (dp) | 4 · 8 · 12 · 16 · 24 · 32 · 48 |
| Colțuri (dp) | 8 câmpuri · 12 cipuri · 20 carduri · 28 foi · capsulă pentru butoane; colțul interior = exterior − padding |
| Adâncime | 0 pentru tot conținutul (plat); 1 doar pentru foaia care urcă (umbră `ink` alfa 0,12, blur 24 dp, y 8 dp). Umbra autocolantului nu e adâncime, e parte din desen |
| Ținte | copil, acțiuni principale ≥ 64 dp (`ContinueButton` 72 dp înălțime); restul ≥ 48 dp; 12 dp între ținte |

### 2.6 O singură sursă

- **Fișiere:** `spec/tokens/fonia.tokens.json` (W3C Design Tokens 2025.10, `$value`/`$type`, trei moduri), `spec/tokens/sd.config.mjs`, `spec/tokens/package.json` cu versiunea Style Dictionary 5 fixată **[Propunere; versiunea exactă neverificată]**.
- **Comandă:** `npm --prefix spec/tokens run build`, apelată de `./gradlew :core:designsystem:generateDesignTokens`. Scrie `core/designsystem/src/main/kotlin/tech/fonia/designsystem/generated/FoniaTokens.kt` și `spec/tokens/build/FoniaTokens.swift`.
- **Fișierele generate se comit.** Build-ul Android nu are nevoie de Node. CI regenerează și rulează `git diff --exit-code`.
- **Verificarea „fără culoare scrisă de mână”:** `./gradlew checkNoRawColors` (în A2) caută în `**/*.kt`, în afara `generated/`, tiparele `Color\(0x`, `Color\(\s*red`, `#[0-9A-Fa-f]{6}`, `Color\.(Red|Green|Blue|Yellow|Magenta|Cyan|Gray|Black|White)` și `parseColor`. Permise: `Color.Transparent`, `Color.Unspecified`. Orice potrivire oprește build-ul.
- **Testul de contrast:** `TokensContrastTest` (JVM) recalculează tabelul §2.2 din JSON.

## 3. C1 și C2: interpolatorul de contururi

### 3.1 Ce am verificat pe font

| Fapt | Valoare **[Verificat]** |
|---|---|
| `unitsPerEm`, ascendent, descendent, majusculă, x | 2048 · 1984 · −494 · 1490 · 1118 |
| Regiuni distincte în `gvar` (tot fontul) | 44 |
| Vârfuri normalizate pe axă | `wght`: −1, 0,36, 1 · `ROND`: −1, −0,8, −0,4, 1 · `opsz`: −1, 0,333, 1 |
| Mastere intermediare în valori de utilizator | `wght` 600 · `ROND` 10 și 30 · `opsz` 20 |
| `avar` pe `wght` | 0,2→0,18 · 0,4→0,36 · 0,6→0,54 · 0,8→0,76 (liniar pe porțiuni) |
| „S” | 1 contur, 64 de puncte, 44 de tupluri, avans 1284 |
| „Ș” | compus: `S` (0, 0) + `commaaccent` (369, 0); 12 tupluri care mută doar componenta și punctele fantomă |
| ș, Ț, ț, ă, â, î, i, j | toate compuse (bază + `commaaccent` / `brevecomb` / `circumflexcomb` / `dotaccentcomb`) |
| Setul de export, cu toate componentele | 137 de glife, 4.168 de puncte, 3.993 de tupluri |
| Mărimea JSON-ului dens, cu `opsz` liber | 1,12 MB (202 KB gzip) |

### 3.2 C1: exportul (o dată, pe PC)

Script: `spec/glyphs/export_allo.py` (fontTools). Pași:

1. Verifică SHA-256 al fontului. Fixează `opsz = 32` cu `fontTools.varLib.instancer`, deci rămân doar regiunile `wght` × `ROND` (cel mult 19). Mărime așteptată: ~0,5 MB, ~90 KB gzip **[Estimare]**.
2. Setul de glife: alfabetul român mare și mic (31 + 31, cu ă â î ș ț), alternativele `ss01`/`ss02` (`a.1`, `abreve.1`, `acircumflex.1`, `I.1`, `Icircumflex.1`, `l.ss02`, `one.foot`, `zero.slash`), cifrele și variantele `.tf`, punctuația `. , ! ? - … „ ” : ; ' ( )`, glifele-iconiță din §2.3 și toate componentele la care trimit.
3. Pe fiecare glifă simplă: punctele implicite (x, y întregi), bitul „pe curbă”, capetele de contur, avansul. Pe fiecare tuplu: indexul regiunii și **deltele dense**, cu IUP aplicat la export (`fontTools.varLib.iup.iup_delta`), inclusiv cele 4 puncte fantomă (avansul vine din fantomele 1 și 2).
4. Pe fiecare glifă compusă: lista `[componentă, dx, dy]` și tuplurile care mută decalajele și fantomele. Componentele nu se „coc” în contur: virgula lui Ș rămâne piesă separată, ca s-o putem anima.
5. Scrie și vectorii de aur: `spec/glyphs/golden/*.json`, contururi obținute cu instancer-ul fontTools la 56 de poziții (`wght` 100, 180, 250, 400, 600, 750, 900 × `ROND` 0, 5, 10, 20, 30, 50, 75, 100) pentru 20 de glife (S Ș ș Ț ț R L Z J C G a.1 ă â î i j ★ ✓ →).

Forma fișierului `spec/glyphs/allo-hero.json`:

```json
{ "font":"Allo","version":"1.0.3","sha256":"…","upm":2048,"ascent":1984,"descent":-494,
  "capHeight":1490,"xHeight":1118,"pinned":{"opsz":32},
  "axes":[{"tag":"wght","min":100,"def":400,"max":900,
           "avar":[[-1,-1],[0,0],[0.2,0.18],[0.4,0.36],[0.6,0.54],[0.8,0.76],[1,1]]},
          {"tag":"ROND","min":0,"def":50,"max":100,"avar":[[-1,-1],[0,0],[1,1]]}],
  "regions":[[[-1,-1,0],[0,0,0]], [[0,0.36,1],[0,0,0]]],
  "cmap":{"S":"S","Ș":"Scommaaccent"}, "alt":{"ss01":{"a":"a.1"},"ss02":{"l":"l.ss02"}},
  "glyphs":{
    "S":{"adv":1284,"ends":[63],"on":"<bitset base64>","xy":[/*128*/],
         "tuples":[{"r":0,"d":[/*(64+4)*2*/]}]},
    "Scommaaccent":{"adv":1284,"comps":[["S",0,0],["commaaccent",369,0]],
         "tuples":[{"r":0,"d":[/*(2+4)*2*/]}]} } }
```

### 3.3 C2: `:core:glyphs` (Kotlin pur, fără Android)

| Piesă | Contract |
|---|---|
| `AlloOutlines.load(stream)` | o dată, pe `Dispatchers.Default`, la pornire. Țintă ≤ 50 ms pe telefonul de referință **[Estimare]**; dacă nu, un task din `build-logic` scrie o variantă binară (int16) din același JSON |
| `AxisMap.normalize(tag, user)` | normalizarea implicită (min/implicit/max → −1/0/1), apoi harta `avar` pe porțiuni |
| `VariationInterpolator.scalars(wghtN, rondN, out)` | pentru fiecare regiune, produsul pe axe al funcției-cort `(lo, vârf, hi)`: axa cu vârf 0 e sărită; în afara `(lo, hi)` dă 0; altfel `(v−lo)/(vârf−lo)` sau `(hi−v)/(hi−vârf)`. Tratează corect masterele intermediare. Calculat o dată pe cadru, nu pe glifă |
| `interpolate(glyph, scalars, outXY)` | `xy = implicit + Σ scalar[r] · delta[r]`, în `FloatArray` refolosit; fără rotunjire la întreg (vrem mișcare continuă). Avans = `fantoma2.x − fantoma1.x` |
| `Composite` | fiecare componentă se interpolează separat; decalajul ei vine din tuplurile glifei compuse. API-ul expune decalajul ca s-o poată suprascrie animația (virgula) |
| `PathSink` (`moveTo`, `lineTo`, `quadTo`, `close`) | modulul nu cunoaște `Path`. Regulile TrueType: punct implicit la mijlocul a două puncte de control consecutive; contur care începe cu punct de control. Adaptorul spre `androidx.compose.ui.graphics.Path` (cu `rewind()`, fără alocări) stă în `:core:designsystem` |
| `LetterLayout` | avansul **rezervat** la `wght` 900, `ROND` 100, plus jumătate din `rim` pe fiecare parte, înmulțit cu scara maximă; glifa se centrează în el, deci nimic nu sare când litera se umflă |
| `OutlineOps` | `subdivide(maxLen)`, `shear(k)`, `sampleEvenly(n)` pentru particule, `noiseDisplace(seed, amp)` pentru tremur; toate scriu în buffere refolosite |

Se recalculează doar când s-au schimbat axele (compararea valorilor cu prag 0,25 unități `wght`, 0,1 `ROND`); transformările (scară, rotație, translație) nu ating conturul.

**Teste și ținte (definiția lui „gata” pentru C2):**

| Test | Prag |
|---|---|
| Contur față de aur (1.120 de cazuri) | abatere maximă pe coordonată ≤ 0,75 unități din 2048 (fontTools rotunjește la întreg), avans ≤ 1 unitate |
| Valorile implicite (`wght` 400, `ROND` 50) | identice cu `glyf` |
| Fără alocări | 10.000 de apeluri `interpolate` + `emit`: 0 octeți alocați (`ThreadMXBean.getThreadAllocatedBytes`) |
| Viteză | ≤ 0,05 ms pe glifă (interpolare + cale) pe telefonul de referință; 50 de glife ≤ 0,5 ms **[Estimare, de măsurat în `:benchmark`]** |
| Paritate iOS | aceleași fișiere de aur, citite de testele Swift (J8) |

### 3.4 Modurile A și B

| Mod | Cum | Unde |
|---|---|---|
| **A** static | `FontFamily` Allo cu `FontVariation.Settings` fixe din tokenuri; `Text` obișnuit | tot textul de citit și interfața |
| **B** trepte cu cache | `Typeface` pe trepte (`wght` din 25 în 25, `ROND` din 5 în 5), LRU propriu de 64, cheie `Long` împachetată după treaptă (nu după valoarea brută), preîncălzit în fundal la intrarea în ecran; desen cu `nativeCanvas.drawText`, poziții din `Paint.getTextRunAdvances` la grosimea rezervată | literele simple din `LiveWord` (karaoke de grosime, accent, ecou) |
| **C** interpolator | §3.3 | `LiveLetter`, `StickerText`, literele-țintă din `LiveWord`, glifele-iconiță animate |

## 4. C3: `LiveLetter`, `LiveWord`, `StickerText`

```kotlin
@Immutable data class StickerSet(val face: Color, val edge: Color, val rim: Color)   // FoniaSticker.Voice…

sealed interface LetterCue {                       // vocabularul din §5
    data object Breathe; data object Listen; data object Correct; data object Almost
    data object Unsure; data object Retake; data object Pop
    data class Beat(val strength: Float = 1f) : LetterCue
    data class Instruct(val kind: ErrorKind, val heard: String? = null) : LetterCue
}
@Stable class LiveLetterState { fun cue(c: LetterCue); val busy: Boolean }

@Composable fun LiveLetter(
    grapheme: String,                              // 1–3 grafeme: „Ș”, „ce”, „sa”
    state: LiveLetterState,
    modifier: Modifier = Modifier,
    inflation: () -> Float = { 0f },               // 0–1, citit doar la desen
    roundness: (() -> Float)? = null,              // suprascrie ROND (S↔Ș); null = din inflation
    energy: () -> Float = { 0f },                  // 0–1, micro-reacție la nivelul vocii
    sticker: StickerSet? = FoniaSticker.Voice,     // null = literă simplă (cochilia grown)
    height: Dp = 200.dp,
    onPop: (() -> Unit)? = null,
    contentDescription: String = grapheme,
)

@Composable fun LiveWord(
    text: String,                                  // 2–50 de litere
    target: List<IntRange> = emptyList(),          // literele-țintă devin autocolant (mod C)
    weightAt: ((index: Int) -> Float)? = null,     // grosime pe literă (mod B): karaoke, accent
    state: LiveLetterState? = null,                // cue-urile se aplică țintei
    modifier: Modifier = Modifier, contentDescription: String = text,
)

@Composable fun StickerText(text: String, sticker: StickerSet, modifier: Modifier = Modifier,
    weight: Float = 900f, roundness: Float = 100f, tilt: Float = 0f, contentDescription: String = text)
```

Față de propunerea din Z: `effects: Set<LetterEffect>` a devenit `LetterCue` (un eveniment, nu o configurație), `weight` a dispărut din `LiveLetter` (vine din `inflation`), iar `LiveWord` și `StickerText` sunt componente separate, cum cere planul-mamă.

**Straturile** (aceeași cale, de jos în sus):

| Strat | Desen | Lățimea liniei (em), la `inflation` 0 → 1 |
|---|---|---|
| umbră | contur + umplere, decalat (0,03; 0,09) em, alfa 0,20 | ca `rim` |
| `rim` | `Stroke(join = Round, cap = Round)` + umplere | 0,06 → 0,24 |
| `edge` | la fel | 0,03 → 0,12 |
| `face` | umplere | — |

În `StickerText` și `LiveWord` straturile se desenează **pe tot cuvântul**: întâi toate umbrele, apoi toate `rim`, toate `edge`, toate `face`, ca literele vecine să nu se taie.

**`inflation` e singurul număr de poveste** **[valorile din prototip]**:

| Mărime | `inflation` 0 | `inflation` 1 |
|---|---|---|
| `wght` | 180 | 900 |
| `ROND` (dacă `roundness` e `null`) | 0 | 100 |
| scară | 0,80 | 1,30 (vârful de POP: 1,70) |
| `rim` / `edge` | 0,06 / 0,03 em | 0,24 / 0,12 em |

**Datele continue.** `LiveLetter` nu primește `LiveSnapshot`; ecranul îi dă lambde care citesc ultimul `LiveSnapshot` publicat. Lambdele sunt apelate numai în `draw` (un `Modifier.Node` cu `DrawModifierNode`), deci 100 de valori pe secundă nu produc recompunere și nici layout. Înainte de desen, fiecare valoare trece prin resortul `follow` (§10), integrat cu timpul cadrului. Cue-urile (`state.cue`) vin din banda de evenimente și pornesc animații cu durată proprie.

**Accesibilitate.** `semantics { contentDescription }` pe rădăcină; nimic din straturi nu ajunge la TalkBack. Schimbarea de verdict se anunță ca `liveRegion = Polite` pe `VerdictStamp`, nu pe literă. Textul mărit la 200 % nu atinge `heroLetter` (e în dp), dar `LiveWord` se micșorează până la 36 sp și apoi trece pe două rânduri la granița de silabă.

**Mișcare redusă** (`ANIMATOR_DURATION_SCALE == 0` sau setarea Fonia): axele se schimbă în trepte de 100 ms, fără resort; fără sărituri, particule sau tremur; forma finală a fiecărui cue rămâne, pentru că ea e informația. RTL nu e necesar.

**Teste:** capturi de ecran (Roborazzi pe JVM, în `:core:designsystem`) cu intrări fixe: `inflation` 0 / 0,5 / 1; fiecare set de autocolant pe `Paper` și pe `Stage`; cadrul final al fiecărui `LetterCue`; „Ș”, „ț”, „ce”; `LiveWord` cu ținta la început, mijloc și sfârșit; text mărit 200 %. Un test de compoziție numără recompunerile în 2 s de `inflation` animat: trebuie să fie 0.

## 5. C4: vocabularul de animații al literei

Resorturile și duratele sunt tokenii din §10. Totul rulează pe API 29; unde există o variantă mai bogată pe API mai nou, e scrisă. Sunetele și vibrațiile trec prin `FxGate` (§9), deci nu pornesc cât microfonul e deschis. **Tremurul e rezervat pentru `Unsure`.**

### 5.1 Vocabularul de bază

| Cue | Declanșator | Ce se mișcă | Resort / curbă, durată | Sunet · vibrație | Mișcare redusă |
|---|---|---|---|---|---|
| **Respiră** | litera apare și nimic nu se întâmplă 1 s | `wght` ±30, scară ±1,5 %; 2 cicluri de 3,5 s, apoi stă | sinus | — | stă |
| **Ascultă** | `MicOpened` | se apleacă 4° spre copil, scară 0,96, `rim` −15 %; rămâne așa | `bounce`, ~260 ms | — (microfon deschis) | doar `rim` mai subțire |
| **Urmărește vocea** | fiecare cadru cu microfonul deschis | `inflation`, `roundness`, `energy` → axe; `energy` dă ±1,2 % scară | `follow` | — | trepte de 100 ms |
| **Umflă** | sunet bun ținut (`inflation` crește); la pauză scade de 1,6 ori mai repede | tot tabelul `inflation` | `follow` | — | la fel, în trepte |
| **POP** | `inflation` ≥ 1 și `MicClosed` | anticipare (scară 0,92, 100 ms) → 1,70 → 1,0; 80 de particule din contur (`sampleEvenly`), pe normală, cu gravitație, 800 ms; inel de șoc (cerc desenat; AGSL de la API 33) | `reward` | `balloon_pop` · clic puternic, la vârf ±20 ms | bifă statică, fără particule |
| **Corect** | `Verdict.Correct` | salt 0,12 em cu turtire la aterizare (`scaleY` 0,8 → 1,08 → 1), setul devine `Parrot`, autocolantul „se lipește” (rotație 3° → 0); 24 de stele mici; umbra se lățește la aterizare | `reward`, ~600 ms | `good` · tic + clic | schimb de set + ✓ |
| **Aproape** | `Verdict.Almost` | forfecare a conturului la 9°, `face` umplută doar până la jumătate (`clipRect` pe înălțime), un singur legănat ±3° | `bounce`, 480 ms | `almost` · tic slab | oblic + jumătate, fără legănat |
| **Nesigur** | `Verdict.Unsure` | conturul „fierbe”: 4 căi precalculate cu `noiseDisplace` (amplitudine 0,035 em, zgomot coerent cu lungimea de undă 0,35 em, după `subdivide(0,08 em)`), schimbate la 125 ms, timp de 1,2 s; apoi îngheață înclinat ±4°; setul trece pe `Flat` | cadre, 1,2 s | `unsure` · două ticuri slabe la 120 ms | înclinat ±4° + „?” |
| **Reia** | `Verdict.Retake` | scară 1 → 0,9 → 1, o rotire de 8° dus-întors, glifa ↻ apare 600 ms lângă `MicCapsule` | `bounce`, 260 ms | — (urmează vocea lui Foni) | doar ↻ |
| **Bătaia de silabă** | `Beat` (început de silabă sau tempo) | salt 0,06 em, `wght` +150 timp de 140 ms, silaba activă ia setul `Voice` | `bounce`; decalaj 40 ms între litere | `syllable` doar când bate aplicația, nu copilul · tic | doar `wght` |
| **Silaba accentuată** | date de conținut sau detectorul de accent | `wght` 900, scară 1,12 față de 0,9 la celelalte; rămâne așa | `bounce` | — | la fel, fără resort |
| **Contor** | valoarea se schimbă | cifrele se rostogolesc pe verticală (0,9 em), cu `tnum`; lățimea e fixă | `bounce`, 260 ms | `count_tick` (cel mult unul la 120 ms) | schimbare directă |

Tremurul fără filtre SVG: pe Android nu există `feTurbulence`, dar avem punctele conturului, deci mutăm punctele cu un zgomot determinist (`:core:glyphs`, aceeași funcție pe iOS, testabilă cu vectori de aur). Nu cere `RenderEffect` sau AGSL, deci merge pe API 29. Pentru literele simple din `LiveWord` (mod B), varianta e: rotație alternată ±3° pe literă și 1 dp de deplasare la 8 cadre pe secundă.

### 5.2 Animațiile-instrucțiune pentru `NamedError(kind)`

Fiecare rulează o dată (≤ 1,6 s), rămâne pe cadrul final cât spune Foni instrucțiunea și se reia la atingere. Recuzita (pufuri, dinți, limbă, undă) e desenată cu aceleași trei straturi. Sunet: `hint` la început; vibrație: un tic slab. Mișcare redusă: cadrul final, static. Apar copilului doar pentru tipurile deblocate (F5).

| Animație | `ErrorKind` | Ce vede copilul | Parametri |
|---|---|---|---|
| **comma-drop** (și ridicarea) | `PlaceSwap` S↔Ș, `AffricatePlace` T-bază (Ț) | ținta Ș, s-a auzit S: virgula cade de sus, sare o dată și se lipește, iar litera se rotunjește (`ROND` → 100). Ținta S, s-a auzit Ș: virgula se desprinde, cade din cadru, litera se ascute (`ROND` → 10) | virgula: resort 0,5 / 400, pornește la −0,6 em, ajunge cu 50 ms după corp; `ROND` pe `bounce` |
| **swap-glide** | `PlaceSwap` Z↔J, `AffricatePlace` Ț↔CE, `RtoL`, `RtoGlide`, `LtoGlide`, `LtoR` | litera auzită apare ca fantomă `Flat`, alunecă afară spre stânga, iar ținta alunecă în loc ca autocolant; la R urmează **trill-ripple**, la L o „lipire” (apăsare scurtă sus, apoi nemișcare) | 2 × 260 ms, `bounce`; fantoma la alfa 0,45 |
| **trill-ripple** | `RtoVowel`, `RUvular`, finalul pentru `RtoL`/`RtoGlide` | o undă scurtă trece o singură dată prin piciorul lui R (punctele se mută pe normală), ca o coardă ciupită; la `RUvular` un punct `Sun` pleacă din spatele literei și se oprește în vârf | 3 creste, 22 Hz, 420 ms, amplitudine 0,03 em → 0 |
| **voicing-wave** | `Devoicing` | sub linia de bază apare o linie dreaptă care devine undă și intră în literă, care se îngroașă (`wght` +200); o singură linie, pe un singur traseu, ca să nu semene cu tremurul | undă sinus, λ 0,25 em, 900 ms, apoi rămâne o undă mică |
| **air-to-centre** | `Lateral` | două pufuri ies din lateralele literei, se strâng și pleacă împreună pe mijloc, în față | pufuri `RoundedPolygon`, 1,2 s, curbă `out` |
| **tongue-behind-teeth** | `Interdental` | sub literă: o linie de dinți (`surface` cu contur `ink`) și o limbă roz care iese printre ei, apoi alunecă în spatele lor | 1,2 s, `bounce` la final |
| **closure-snap** | `Deaffrication` (lipsește oprirea) · `Stopping` (lipsește aerul) | varianta „snap”: un T mic (D la GE) vine din stânga și se lipește de literă cu o turtire. Varianta „flow”: litera se întinde ca o panglică (`scaleX` 1 → 1,5), cu dungi de aer, și revine | snap: `reward`, 480 ms; flow: 900 ms |
| **sag-and-refill** | `WeakOrShort` | litera se lasă (`wght` 250, scară 0,9, se înclină 5°), apoi se umple la loc până la plin | 480 ms jos, 700 ms sus pe `follow` |
| **slot-drop** | `Omission` | locul gol din cuvânt pulsează o dată în `Sun`, litera cade în el și se așază | ca la virgulă |

## 6. C5: cele șase mini-jocuri

Comun: toate rulează fără verdictul ML. **Efortul** se punctează așa: 1 bilet pentru orice încercare cu voce detectată, 2 pentru pragul jocului atins, +1 la degetul mare al adultului; niciun bilet nu se pierde. **Dificultatea** se mișcă după ultimele 8 încercări, ca reușita să stea la 80–85 %; copilul vede mereu aceeași țintă („litera plină”). Intrările sunt descrise ca nevoi; numele câmpurilor din `LiveSnapshot` le fixează capitolul 03. Testul de acceptare rulează în modul „fixture” (clip WAV → reducer → stare), plus o captură la un `LiveSnapshot` fix.

| Joc | Regula | Ecranul | Intrări | Adaptare | Final | Test de acceptare |
|---|---|---|---|---|---|---|
| **Umflă litera** (pas `Hold`) | ții sunetul, litera se umflă; la plin face POP | `Stage`; `LiveLetter` 240 dp; `MicCapsule`; Foni mic | „sunet bun acum” (fricativă prezentă, voce pentru Z/J), nivel, loc S/Ș pentru `roundness` | durata-țintă 1,5 → 4 s, pas 0,5 s | 5 litere lipite pe o bandă | clip de 3 s cu „sss”: `inflation` ajunge la 1 în 3,0 ± 0,2 s; clip de 1 s: nu ajunge, primește 1 bilet |
| **Trenul de silabe** (pas `Syllables`) | fiecare silabă spusă e un vagon care sare; 3 la rând dau vagonul auriu | `Stage`; `LiveWord` cu silabele ca vagoane | începuturi de silabă (`SessionEvent`), locul consoanei | lungimea trenului 2 → 5; vocale variate | trenul pleacă din cadru | clip „sa-sa-sa”: 3 `Beat` la ±60 ms de începuturile etichetate; combo-ul nu scade la o ratare |
| **Ariciul și norul** (pas `Pairs`, S/Ș și Z/J) | litera se ascute sau se rotunjește live; copilul o duce spre personajul cerut | `Stage`; două ținte desenate (formă cu colțuri, formă rotundă), fără palete noi | locul S/Ș continuu (0–1), calibrat pe copil | lățimea zonei-țintă 0,35 → 0,2; timpul de ținut 0,6 → 1,2 s | 6 „hrăniri” | clip „șșș” → `ROND` > 80 în 300 ms; clip „sss” → `ROND` < 20 |
| **Autocolantul fuge din cuvânt** (pas `Words`) | spui cuvântul, litera-țintă se dezlipește și zboară în album | `Paper`; poză + `LiveWord` cu țintă | sfârșit de încercare, „s-a spus cuvântul”, degetul mare opțional | poziția țintei (început → mijloc → final), lungimea cuvântului | pagina de album cu 9 autocolante | clip cu cuvântul: un zbor pe arc, ≤ 700 ms, contorul +1; liniște: `Retake`, niciun zbor |
| **Ecoul din peșteră** (pas `Words`, apoi `Retell`) | Foni spune cuvântul; literele revin ca ecou tot mai subțire; la ultima treaptă rămâne doar poza | `Paper` cu `LiveWord` în mod B | începutul vocii, latența răspunsului | numărul de ecouri 3 → 1; fără model după 2 reușite | 6 cuvinte numite fără model | `wght` scade 700 → 400 → 200 pe trepte; răspuns sub 4 s = 2 bilete |
| **Toba literelor** (pas `Syllables`, 7–10 ani) | aceeași silabă de 4 ori pe tempo; silaba nimerită se îngroașă | `Stage`; `LiveLetter` care pulsează | începuturi de silabă față de tempo | tempo 330 → 220 ms; fereastra ±120 → ±80 ms | 4 măsuri | începuturi sintetice la +50 ms: 4 din 4 „nimerite”; la +200 ms: 0, dar 1 bilet fiecare |

Cât microfonul e deschis, toba bate doar vizual. `Mission` și `Twister` nu au joc propriu: folosesc `LiveWord` cu karaoke de grosime.

## 7. D2: componentele

Fiecare componentă are în `:core:designsystem` un `@Preview` pe fiecare stare și un test de captură (Roborazzi), pe `Paper`, pe `Stage` unde e cazul, cu text mărit 200 % și cu mișcare redusă.

| Componentă | Stări | Mărimi | Note |
|---|---|---|---|
| `LiveLetter`, `LiveWord`, `StickerText` | §4–§5 | §2.4 | — |
| `FoniMascot` | `idle`, `speaks`, `listens`, `reacts`, `cheers`, `points`, `asksAdult`, `rests` | 64 / 120 / 220 dp | §8 |
| `ContinueButton` | activ, apăsat (coboară 3 dp pe muchia `accentDeep`), inactiv, în așteptare | 72 dp înalt, lățime plină minus margini | singurul lucru plin de pe ecran; stă în conținut, nu lipit de marginea de jos; glifa → din font |
| `MicCapsule` | „Ascultă” (Foni vorbește), „Acum tu” (deschis: inel `voice` care se umple după nivel, fără puls), „Mă gândesc”, oprit | 64 dp înalt | are mereu și cuvântul, nu doar culoarea |
| `VerdictStamp` | cele 5 verdicte din §2.3 | `StickerText` 40 sp + rând de text | `liveRegion` |
| `PathNode` | blocat (contur, glifă lacăt desenată), disponibil, curent (autocolant `Voice`, respiră de 2 ori), făcut (✓ `Parrot`), „cere adult” | 72 dp, țintă 88 dp | litera sunetului, nu o iconiță |
| `SoundTile` | neînceput (subțire, ascuțit), în lucru, stăpânit (plin, rotund), ales | 88 × 104 dp | „alfabetul care crește” din S |
| `AdultGate` | întrebare, răspuns greșit (se scutură ușor foaia, fără roșu), trecut | foaie | cochilia `grown` |
| `ParentCard` | pliat (un rând), deschis, cu întrebarea „Cum a sunat?” și două butoane | 56 dp pliat | tipografie sobră chiar și în ecranul copilului |
| `MirrorView`, `LipTargetOverlay`, `Mouth3D` | definite în capitolul 04 | — | de aici primesc rama (colț 28 dp), culorile (ghidaj `sun`, potrivit `parrot` + ✓) și straturile de linie |
| `FoniaButton` | `primary`, `secondary` (contur `inkSecondary`), `plain`; apăsat, inactiv | 56 dp (`kid` 64) | înlocuiește cele 6 stiluri vechi |
| `FoniaCard` | simplu, apăsabil, ales | 2 mărimi | plat, colț 20 dp |
| `FoniaSheet` | jumătate, întreagă | colț 28 dp | singura umbră din aplicație |
| `ProgressTrack`, `StepDots` | 0–1; pas curent | 8 dp înalt; puncte 10 dp | `fill` + `accent`; la copil, puncte, nu procente |
| `Counter` | — | `counter` | cifre care se rostogolesc |
| `Chip`, `Toggle`, `ListRow`, `SegmentedControl` | ales/neales, pornit/oprit, cu sau fără săgeată | ≥ 48 dp | doar în cochilia `grown`; `Toggle` are și glifă (✓ / ○) |
| `IconGlyph` | — | 20 / 24 / 32 dp | glifă Allo ca iconiță, cu grosimea textului de lângă ea |
| `Banner` | informație, atenție (⚠, fundal `fill`) | — | mesaje de sistem, fără roșu |

**Ce păstrăm din vechiul `:design`, ca logică:** `NumericText` (→ `Counter`), `WrapLayout`, micșorarea automată din `TargetWord` (→ `LiveWord`), `rememberFrameClock` și tiparul „citesc nivelurile o dată pe cadru, fără recompunere”, `LocalReduceMotion`, `Animation.kt` / `Geometry.kt` / `Painting.kt` (resorturi, colțuri continue), `FoniaAssets` (cache), regula din `RatingRow` (fără roșu), funcția pură de poză a lui Foni (`ParrotPose`), `MouthGeometry` și `SagittalView` ca rezervă 2D pentru capitolul 04. Restul (`Palette`, `Typography`, `Controls`, `Cards`, `StartButton`, `Scenes`, `Motion`) se șterge.

## 8. D3: Foni v2

**Recomandare: păpușă condusă din cod, pe straturile existente, fără Rive.** Motive: codul de azi e deja așa (`ParrotPose` e o funcție pură de timp, stare și voce, cu ciocul deschis după nivelul vocii); planul gratuit Rive pune un splash străin la export, iar planul fără splash costă 9 $/loc/lună **[Verificat în AC]**; o funcție pură se testează pe JVM și se rescrie identic în Swift.

| Subiect | Decizie |
|---|---|
| Structură | funcția de poză se mută în `:core:designsystem` ca `FoniRig` (fără Compose), cu parametrii în `spec/foni/rig.json` (pivoți, limite de unghi) pentru iOS |
| Conturul de autocolant | pentru fiecare strat se coc offline două măști alfa dilatate (`_edge`, `_rim`; script `spec/foni/bake_outline.py`, Pillow). La desen: toate măștile `rim` (nuanțate `parrotDeep`), apoi toate `edge` (nuanțate `#FFF6E6`), apoi desenul. Dilatarea reuniunii e reuniunea dilatărilor, deci conturul e corect în orice poză. Lățimi: `edge` 0,05 și `rim` 0,03 din înălțime; umbra ca la litere. Fără shadere, API 29 |
| Mărime pe disc | cele 13 straturi au azi ~27 MB de bitmapuri; se refac în WebP, cel mult 660 px pe latură; țintă ≤ 6 MB cu măști cu tot **[Estimare]** |
| Performanță | 39 de desene de bitmap pe cadru (13 × 3); țintă ≤ 1 ms **[Estimare]**; poza se recalculează doar cât Foni e activ |

| Comportament | Când | Ce face |
|---|---|---|
| `idle` | nimic de făcut | respiră de 2 ori, clipește rar, apoi stă nemișcat pe ecranele de lucru |
| `speaks` | rulează un clip de voce | ciocul urmează anvelopa clipului: `unghi = 22° · nivel^0,6`, pe resortul `follow`; capul se mișcă ≤ 2 % |
| `listens` | microfonul e deschis | cap înclinat 8° spre literă, un ochi spre copil, cioc închis, respirație la jumătate, niciun sunet |
| `reacts` | ≤ 100 ms după `MicClosed`, la orice voce | clipit + săritură mică (efortul e văzut înaintea verdictului) |
| `cheers` | `Correct`, final de bloc | două bătăi de aripi, săritură 12 dp, ochi zâmbitori, 600 ms |
| `points` | `Almost`, `NamedError` | arată cu aripa spre literă și se uită la ea, ca atenția să meargă la animația-instrucțiune |
| `asksAdult` | `Unsure` | se întoarce spre `ParentCard`, cap înclinat |
| `rests` | 6 s fără voce | privește copilul, clipește lent; fără nerăbdare |

**Niciodată:** trist, plângând, dezamăgit, „mi-a fost dor de tine”, reacție la seria ruptă. **Plasare:** un singur Foni pe ecran; în `LESSON` stă jos, în colțul de la început, la 120 dp, privește spre literă și nu intră în dreptunghiul literei plus 16 dp; 220 dp în onboarding și `END`; 64 dp lângă `ParentCard`. Lipsește din cochilia `grown`. **Sarcini de artă:** curățarea marginilor celor 13 straturi; ochi cu trei stări (deschis, calm, zâmbet); o aripă care poate arăta; verificarea culorilor față de `parrot` și `sun`; măștile de contur.

## 9. D4: sunet și vibrații

### 9.1 Setul de 16 sunete

O singură familie: pentatonica Do major, timbru de marimbă moale plus „pop”. Nimic nu coboară ca un „greșit”. Lungimile ciornelor sunt măsurate azi cu `earshot measure`; toate ciornele au vârful la −6 dBFS, deci nivelurile trebuie puse pe trepte.

| # | Nume | Declanșator | Sursă (banca Earshot, MIT) | Ton | Lungime (ciornă → țintă) | Vârf țintă |
|---|---|---|---|---|---|---|
| 1 | `tap` | atingere | `tap/soft` | — | 0,130 s → ≤ 0,10 | −12 dBFS |
| 2 | `select` | alegere | `ui_pop/blip` | La5 | 0,118 s | −10 |
| 3 | `letter_pop` | o literă apare, jeleu | `pop/0` | Do5 | 0,200 s | −10 |
| 4 | `balloon_pop` | POP | `fit/pop/mouth_plop` | — | 0,281 s | −8 |
| 5 | `good` | `Correct` | `notify/mid_tine` | Sol5 | 0,425 s | −8 |
| 6 | `almost` | `Almost` | **nou**: `mallet/marimba`, două note | Re5 → Mi5 | ≤ 0,30 s | −10 |
| 7 | `great` | final de bloc; o dată pe sesiune la final | `mallet/up4` | Do6 | 0,749 s | −6 |
| 8 | `star` | steaua ajunge în contor | `mallet/celesta` | Mi6 | 0,391 s | −8 |
| 9 | `hint` | începutul unei animații-instrucțiune | în ciornă `ui_pop/fall` (coboară); se înlocuiește cu `ui_pop/blip` pe o singură notă **[Propunere]** | Re5 | 0,206 s | −10 |
| 10 | `unsure` | `Unsure` | `drip/bloop` | La4 | 0,388 s → ≤ 0,30 | −10 |
| 11 | `mic_open` | înainte de deschiderea microfonului | `toggle/on` | Do → Sol | 0,167 s | −12 |
| 12 | `mic_close` | după închidere | `toggle/off` | Sol → Do | 0,136 s | −12 |
| 13 | `page` | tranziție, cartonaș | `swish/soft` | — | 0,883 s → ≤ 0,30 | −12 |
| 14 | `count_tick` | contor | `hover_tick/mid` | — | 0,089 s | −12 |
| 15 | `syllable` | bătaia dată de aplicație | `mallet/marimba` | Sol4 | 0,564 s → ≤ 0,25 | −10 |
| 16 | `sticker_slap` | autocolant lipit | **nou**: familia de impact moale din bancă | — | ≤ 0,15 s | −10 |

Pentru toate: vârf real ≤ −3 dBTP, intrare ≥ 2 ms, ieșire ≥ 10 ms, fără liniște la capete, cel puțin 120 ms între două sunete, cel mult 2 suprapuse. Format: OGG Vorbis, mono, 48 kHz, în `res/raw/sfx_*.ogg` (set ≤ 150 KB **[Estimare]**); sursele WAV, `sounds.json` și scriptul `render.sh` (pornit din `fonia-brain/sfx/render_draft.sh`) stau în `spec/sfx/`.

**Tonul care urcă la umflare (propus în Z și AC) nu se face:** umflarea se întâmplă cu microfonul deschis, iar regula de liniște câștigă.

### 9.2 Redare, vibrații, sincron

| Subiect | Decizie |
|---|---|
| Redare | `SoundPool` (maximum 4 fluxuri), `USAGE_ASSISTANCE_SONIFICATION`, încărcat la pornire; `play` doar după `OnLoadCompleteListener`. **Oboe rămâne doar pentru captură**; ieșire prin Oboe nu e nevoie în v1 |
| Vibrații, nivelul C (API 29 și rezervă) | `VibrationEffect.createPredefined` (`EFFECT_TICK`, `EFFECT_CLICK`, `EFFECT_DOUBLE_CLICK`, `EFFECT_HEAVY_CLICK`); fără control de amplitudine: doar tic și clic |
| Nivelul B (API 30–35) | `VibrationEffect.startComposition()` cu `TICK`, `CLICK`, `LOW_TICK`, `THUD`, numai după `areAllPrimitivesSupported`; altfel nivelul C |
| Nivelul A (Android 16 cu `VibrationEffect.Builder`) | `Preset` scalabile și anvelope, cu rezervă automată; se face la M6 **[Propunere; constanta de versiune neverificată]** |
| Vocabular | `tap` tic slab · `select` tic · POP clic puternic · `Correct` tic + clic la 80 ms · final de bloc trei ticuri urcătoare + clic (≤ 600 ms) · `Almost` și `hint` un tic slab · `Unsure` două ticuri slabe · `sticker_slap` thud. Nimic lung sau bâzâit |
| Sincron | sunetul pornește în cadrul vârfului vizual; vibrația cu 10–20 ms înainte (motorul are latență), ținta ±20 ms față de vârf; imaginea ≤ 100 ms după `MicClosed`; sunetul și vibrația la ≤ 50 ms una de alta **[Propunere; de calibrat pe telefonul real]** |
| Setări | în `ADULT-SETTINGS`: „Sunete” și „Vibrații”, două comutatoare separate; se respectă și modul silențios al telefonului |

### 9.3 Regula de liniște și unde e impusă

**Cât microfonul e deschis nu se aude și nu vibrează nimic.** Fereastra: de la 150 ms după sfârșitul lui `mic_open` până la 150 ms după sfârșitul vorbirii.

| Strat | Cum |
|---|---|
| 1. Un singur proprietar | `FxGate` (în `:core:designsystem`, pachetul `fx`) e singura clasă care ține `SoundPool` și `Vibrator`. Primește starea microfonului printr-o interfață mică din `:core:model` (`MicGate.isOpen`, scrisă de `:platform:audio` la pornirea și oprirea reală a capturii). Cu microfonul deschis, `FxGate.emit` aruncă evenimentul; cu sunetele sau vibrațiile oprite, la fel. Tot el ține bugetul: 120 ms între sunete, „`great` o dată” |
| 2. Reducerul | `:engine:session` emite efecte sonore doar după `MicClosed`. Test de proprietate: pe 1.000 de secvențe aleatoare de evenimente, niciun efect între `MicOpened` și `MicClosed` |
| 3. Compose | `FoniaTheme` furnizează un `LocalHapticFeedback` care trece prin `FxGate`; sunetul automat al lui `clickable` (Compose 1.13) se oprește la nivel de temă **[Neverificat cum; de aflat în D4]** |
| 4. Lint | `checkFxGate` pică build-ul dacă `SoundPool`, `Vibrator`, `VibrationEffect`, `performHapticFeedback` sau `MediaPlayer` apar în afara pachetului `fx` |
| 5. Vocea lui Foni | redarea clipurilor (capitolele 02 și 06) întreabă același `MicGate` |

### 9.4 Verificarea cu Earshot și licențele

`spec/sfx/check.sh` rulează pe fiecare fișier `earshot measure` (lungime, vârf, vârf real, atac, cădere; pică dacă iese din tabel) și `earshot describe` / `earshot match` cu descrierea din `sounds.json` (de exemplu „pop scurt de bulă, cald”), ca să confirme caracterul. Treptele de tărie se verifică și cu `ffmpeg -af ebur128` (maximul momentan), pentru că sub 0,4 s Earshot nu dă LUFS. `spec/sfx/LICENSES.md` (copiat în `assets/licenses/`) are pentru fiecare sunet: sursa, comanda de randare, licența (banca Earshot: MIT, cu textul licenței) și data. Dacă intră vreun sunet din `wyzowl/sfx-kit` sau Kenney (CC0), se trece la fel.

## 10. D5: sistemul de mișcare

Un fișier, `FoniaMotion.kt`, generat din tokenuri.

| Token | Valoare | Folosire |
|---|---|---|
| `bounce` (**resortul-semnătură**) | amortizare 0,55 · rigiditate 500 | butoane, silabe, virgula, lipirea autocolantului |
| `follow` | 0,95 · 400 | tot ce urmărește vocea (fără tremur); semnalul e netezit înainte (One-Euro, în capitolul 03) |
| `reward` | 0,45 · 400 | POP, `Correct`; o dată pe ecran |
| `quick` / `standard` / `slow` | 140 / 260 / 480 ms **[din laborator]** | apăsare / schimbare de stare / intrare de ecran |
| `out` | `CubicBezier(0,2, 0,8, 0,3, 1)` | particule, zboruri pe arc |
| Decalaj | 40 ms pe literă, tot șirul ≤ 320 ms | cuvinte, liste |

| Regulă | Conținut |
|---|---|
| Bugetul pe ecran | un efect-erou deodată, plus cel mult 2 micro-efecte; ≤ 150 de particule (60 pe ecranul cu camera); o sărbătoare ≤ 1,2 s și nu blochează `ContinueButton`; `great` și confetti o dată pe sesiune |
| Ce nu se mișcă niciodată | textul care se citește; literele care nu sunt ținta, cât copilul spune cuvântul; orice pe ecranul de lucru, fără o cauză (vocea, atingerea sau un verdict) |
| Fotosensibilitate | nimic nu clipește de peste 3 ori pe secundă; nicio schimbare de luminanță pe mai mult de un sfert de ecran în mai puțin de 300 ms; tremurul mută doar conturul, la 8 cadre pe secundă, 1,2 s |
| Mișcare redusă | se citește `ANIMATOR_DURATION_SCALE == 0` și setarea Fonia; tranzițiile devin estompare de 150 ms; fără particule, sărituri, tremur; forma literei, sunetul și vibrația rămân |
| Tranziții de ecran | estompare + urcare de 16 dp, `slow`; litera de pe `PATH` crește în `LESSON` ca element comun (`SharedTransitionLayout`) |
| Bugetul de cadru | P90 ≤ 8 ms la 120 Hz și ≤ 16 ms la 60 Hz; `LiveLetter` ≤ 1,5 ms pe cadru. Se măsoară în `:benchmark` cu Macrobenchmark și `FrameTimingMetric`, pe scenariul „`Hold`, 10 s de sunet din fixture”, pe telefonul real (J4). La depășire, cad întâi particulele, niciodată reacția literei |

## 11. Bibliotecile de efecte

| Decizie | Ce | De ce |
|---|---|---|
| **Da** | Konfetti pentru confetti-ul de final și stelele din punct | singura bibliotecă Compose matură (ISC) |
| **Da** | emițător propriu (~100 de linii, `Canvas`, pool de obiecte) | particule din conturul literei, steaua pe arc, pufurile de aer |
| **Da** | `graphics-shapes` | stele, pufuri, `Morph` stea → cerc la POP |
| **Da, puțin** | Lottie pentru cel mult 3 piese decorative, niciuna în lecție: explicația permisiunii de microfon, „ține telefonul la o palmă”, „caut fața” din `MIRROR` | Lottie Simple License (comercial, fără credit); fiecare ≤ 100 KB, fără raster, recolorat pe tokenuri; URL, autor și dată în `assets/lottie/LICENSES.md`. Dacă în 30 de minute nu găsim una care, recolorată, să nu mai pară de stoc, o desenăm în cod și scoatem dependența |
| **Nu** | Rive, dotLottie, `compose-shimmer` (v1 nu descarcă nimic), HypnoticCanvas, dyn4j / jbox2d (groapa cu litere are fizica ei, ~60 de linii, ca în laborator), ieșire Oboe | fiecare ar adăuga greutate sau o a doua cale pentru același lucru |
| **Regulă** | niciun efect doar cu shader. Inelul de șoc: AGSL de la API 33, cerc desenat dedesubt. Strălucire: `RenderEffect` de la API 31, `BlurMaskFilter` pe API 29–30. Ramura de rezervă are captură de test |

```kotlin
// :core:designsystem
implementation("nl.dionsegijn:konfetti-compose:2.2.0")
implementation("androidx.graphics:graphics-shapes:1.1.0")
implementation("com.airbnb.android:lottie-compose:6.7.1")     // se scoate dacă piesele se desenează în cod
testImplementation("io.github.takahirom.roborazzi:roborazzi-compose")   // versiunea o fixează A2
```

## 12. Lista „UI impecabil” (agentul de design-QA, pe fiecare captură)

1. Un singur lucru plin colorat: acțiunea principală sau scena.
2. Cel mult patru nuanțe vii pe ecran, fiecare cu sensul ei; nicio culoare în afara tokenurilor; nicăieri roșu.
3. Nicio stare spusă doar prin culoare: există formă, glifă sau cuvânt.
4. Contrast: text ≥ 4,5:1, text mare și semne ≥ 3:1; text pe nuanță vie doar `ink`.
5. Ținte: copil ≥ 64 dp la acțiunile principale, restul ≥ 48 dp, 12 dp între ele; nimic important lipit de marginea de jos.
6. Cel mult 3 stiluri de text; toate distanțele din scara 4–48; aceeași margine de pagină pe tot ecranul.
7. Aliniere optică: litera-erou e centrată pe forma ei, nu pe cutie; umbra autocolantului nu e tăiată.
8. Autocolantul apare doar pe ținta copilului, pe verdict și pe Foni; textul de citit e simplu.
9. Un singur Foni, în afara dreptunghiului literei; lipsește în cochilia `grown`.
10. Merge cu text mărit 200 % fără text tăiat; cuvintele lungi se rup la silabă.
11. Copilul poate termina ecranul fără să citească; adultul găsește ce are de făcut într-un singur rând.
12. Cadrul cu mișcare redusă spune același lucru ca animația.

## 13. Ce există la fiecare etapă și ordinea de tăiere

| Etapă | Din acest capitol |
|---|---|
| **M0** | D1: tokenurile luminoase, Allo în `res/font`, scara de litere, `checkNoRawColors`. C1: `allo-hero.json` + vectorii de aur. C2: interpolatorul cu testele verzi. C3: `LiveLetter` cu straturi, `inflation` și `follow`, legat la microfon pentru S/Ș. `FxGate` cu regula de liniște și 3 sunete ciornă |
| **M1** | C3 complet (`LiveWord`, `StickerText`). C4: respiră, ascultă, urmărește, umflă + POP, corect, aproape, nesigur, reia, bătaie de silabă, **comma-drop** pentru S↔Ș. C5: Umflă litera, Trenul de silabe, Autocolantul fuge din cuvânt. D2: `ContinueButton`, `MicCapsule`, `VerdictStamp`, `PathNode`, `SoundTile`, butoane, carduri, progres. D4: cele 14 ciorne prin `SoundPool`. Foni: păpușa veche pe tokenurile noi |
| **M2** | C4: voicing-wave, closure-snap „flow”, slot-drop, swap-glide (valul 1 de erori), sag-and-refill. C5: Ariciul și norul. D2: `AdultGate`, `ParentCard`, ramele pentru oglindă |
| **M3** | D2: cochilia `grown` (`ListRow`, `Toggle`, `Chip`, `FoniaSheet`, piesele raportului). C5: Ecoul, Toba. `Counter` |
| **M4** | D3: Foni v2 (contur, comportamentele noi). D4: setul final de 16, vibrațiile pe nivelurile B și C, licențele. D5: particule, Konfetti, mișcare redusă, accesibilitate, măsurători, toate capturile de test. C4: animațiile pentru valurile 2 și 3, gata dar blocate de `spec/unlock.json` |
| **M5** | nimic nou: capturi și demo-uri (virgula lui Ș, umflă + POP, tremurul), reparații |
| **M6** | vibrațiile de nivel A, groapa cu litere și jeleul (joacă liberă), tema întunecată, morf între litere diferite, pachetul pentru Swift (JSON, aur, `rig.json`) |

**Ordinea de tăiere, în acest capitol:** (1) piesele Lottie, desenate în cod; (2) groapa, jeleul, morful; (3) Toba, apoi Ecoul; (4) animațiile-instrucțiune din valurile 2 și 3 (rămâne cadrul final static plus textul); (5) conturul lui Foni (rămâne desenul vechi pe culorile noi); (6) grosimile pe trepte din `LiveWord` (rămân statice). **Nu se taie:** `LiveLetter` cu umflare și POP, comma-drop, tremurul pentru nesigur, corect și aproape, regula de liniște, tokenurile cu verificarea de culori.

## 14. Pachetele de lucru

| Pachet | Scop | Pași | Fișiere și module |
|---|---|---|---|
| **C1** exportul | contururile Allo ca date | script de export; `opsz` fixat; set de glife; vectori de aur; README cu comanda | `spec/glyphs/export_allo.py`, `allo-hero.json`, `golden/` |
| **C2** interpolatorul | `gvar` + `avar` în Kotlin pur | model și încărcare; normalizare; scalari; interpolare; compuse; `PathSink`; `LetterLayout`; `OutlineOps`; teste | `:core:glyphs` (nou) |
| **C3** componentele literei | `LiveLetter`, `LiveWord`, `StickerText` | adaptorul spre `Path`; nodul de desen pe straturi; `inflation`; modul B cu cache; semantică; mișcare redusă; capturi | `:core:designsystem/letter/` |
| **C4** efectele | vocabularul din §5 | `LetterCue` și mașina de stări; cele 12 cue-uri de bază; emițătorul de particule; cele 9 animații-instrucțiune; legarea de `FxGate` | `:core:designsystem/letter/cues/`, `fx/` |
| **C5** mini-jocurile | cele șase jocuri ca pași de lecție | contractul de intrări cu capitolul 03; regulile ca funcții pure (bilete, adaptare); ecranele; testele pe fixture | logica în `:engine:session`, ecranele în `:feature:lesson/games/` |
| **D1** tokenuri | o sursă, două platforme | JSON; configurarea generatorului; tema Compose; scara de litere; cele două verificări; testul de contrast | `spec/tokens/`, `:core:designsystem/theme/`, `build-logic/` |
| **D2** componente | inventarul din §7 | mutarea logicii păstrate; componentele în ordinea etapelor; previzualizări; capturi | `:core:designsystem/components/` |
| **D3** Foni | păpușa cu contur și comportamente noi | `FoniRig`; coacerea măștilor; WebP; cele 8 comportamente; legarea de anvelopa vocii și de `MicGate` | `:core:designsystem/foni/`, `spec/foni/` |
| **D4** sunet și vibrații | setul de 16, `FxGate`, trei niveluri | `MicGate`; `FxGate`; randare și verificare cu Earshot; conversie OGG; vibrațiile B și C; lint; setări; licențe | `:core:designsystem/fx/`, `:core:model`, `spec/sfx/` |
| **D5** mișcare | tokeni, bugete, măsurare | `FoniaMotion`; mișcarea redusă; bugetul în `FxGate`; scenariul de benchmark; trecerea de accesibilitate | `:core:designsystem/motion/`, `:benchmark` |

| Pachet | „Gata” înseamnă | Ore-agent **[Estimare]** | Depinde de | Riscuri |
|---|---|---|---|---|
| **C1** | JSON-ul se regenerează identic; aurul există; mărimea e notată | 2 | A3 | o versiune nouă de font: SHA fixat |
| **C2** | testele din §3.3 verzi pe JVM | 5 | C1 | o greșeală la masterele intermediare sau la `avar`: aurul are poziții chiar pe vârfuri și între ele |
| **C3** | capturile verzi; 0 recompuneri; TalkBack citește textul | 6 | C2, D1 | alunecarea avansului între modul B și C: aceeași grosime rezervată în ambele |
| **C4** | fiecare cue are captură pe cadrul final și cu mișcare redusă; verificat pe telefon cu vocea | 10 (6 bază + 4 instrucțiuni) | C3, D4, E7 | prea multe efecte deodată: bugetul din `FxGate` |
| **C5** | testele de acceptare din §6 verzi; jucate pe telefon | 9 (≈1,5 pe joc) | C4, E1, F1, G4 | câmpurile din `LiveSnapshot` întârzie: jocurile 1, 2, 4 merg doar cu voce detectată și nivel |
| **D1** | generare reproductibilă; cele două verificări în CI; contrastul verde | 3 | A2, A3 | Node pe CI: fișierele generate sunt comise |
| **D2** | fiecare componentă are previzualizare, captură și trece lista din §12 | 10 | D1, C3 | derivă între ecrane: ecranele nu au voie să-și facă propriile controale |
| **D3** | 8 comportamente cu capturi; ≤ 6 MB; niciun sunet cu microfonul deschis | 6 | D1, D4, I5 | marginile straturilor vechi arată prost cu contur: rezerva e Foni fără contur |
| **D4** | `check.sh` verde; testul de proprietate verde; lint verde; încercat pe telefon | 6 | E1 (starea reală a microfonului) | vibrația se aude în microfon pe telefonul real: de aceea regula e pe 5 straturi |
| **D5** | P90 în buget pe telefon; capturile cu mișcare redusă | 4 | J4 | telefonul de test e lent: cad particulele |

Total: aproximativ 61 de ore-agent, din care 22 se pot face în paralel (C1–C2 cu D1; D3 cu D4; C5 cu D2).

## 15. Propuneri de schimbare și întrebări deschise

### Propuneri de schimbare (față de planul-mamă; de trecut în `09_DECIZII_NOI.md` dacă sunt acceptate)

1. **Nume noi de adăugat la §3 din planul-mamă:** `FxGate`, `MicGate`, `StickerSet` / `FoniaSticker`, `LetterCue`, `LiveLetterState`, `FoniaMotion`, suprafețele `Paper` și `Stage`, componentele mici `FoniaButton`, `FoniaCard`, `FoniaSheet`, `SoundTile`, `Counter`, `IconGlyph`.
2. **`FxGate` și regula de liniște vin la M0, nu la M4.** Planul-mamă pune „sunete, vibrații” la M4; setul final poate rămâne acolo, dar poarta trebuie să existe înainte de primul sunet.
3. **`:core:glyphs` nu produce `Path`**, ci scrie într-un `PathSink`; adaptorul stă în `:core:designsystem`. Altfel modulul n-ar mai fi Kotlin pur.
4. **Cele trei mini-jocuri de la M1** nu sunt numite în planul-mamă. Propun: Umflă litera, Trenul de silabe, Autocolantul fuge din cuvânt.
5. **O a noua animație-instrucțiune, slot-drop, pentru `Omission`:** tipul există în `ErrorKind` și e în valul 1, dar nu avea animație.
6. **Fără roșu nici la adult** (ștergerea se face cu buton `ink`, ⚠ și confirmare în doi pași). Merge mai departe decât F4, care vorbește doar despre copil.
7. **Fără tonul sintetizat la umflare și fără ieșire prin Oboe** (Z și AC le propuneau): contrazic regula de liniște.
8. **Fără Rive.** Raportul AC punea papagalul în Rive; recomand păpușa din cod (§8).
9. **Inventarul și o notă mai veche spun că vechiul Foni a fost scos**, iar D4 spune că rămâne, îmbunătățit. Am urmat D4 și am presupus că straturile existente sunt baza.

### Întrebări deschise

1. Scena albastră plină (`Stage`) pentru pașii cu litera-erou, sau totul pe hârtie? Am propus scena, ca în prototip.
2. Foni v2 pornește de la cele 13 straturi de azi sau de la un desen nou? De răspuns depind sarcinile de artă din D3.
3. Timbrul: marimbă caldă (ciorna de azi) sau „bule” sintetice? De ascultat cele 14 ciorne din `fonia-brain/sfx/draft/`.
4. Care e telefonul de referință și ce rată de cadre are? De el depind toate țintele marcate **[Estimare]**.
5. `ss01` și `ss02` doar pe ce exersează copilul (cum am scris) sau pe tot textul din cochilia copilului?
6. Cine verifică ținta de 64 dp și înălțimea literei cu un copil real de 4–5 ani în probele din 21–25 noiembrie?
7. Dacă fișierul de contururi se încarcă în peste 50 ms pe telefonul real, facem varianta binară (o oră în plus) sau încărcăm în spatele ecranului de pornire?
