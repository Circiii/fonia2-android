# Paritate: `:speech` (FoniaSpeech)

Portat din `fonia-app/Packages/FoniaKit/Sources/FoniaSpeech` (13 fișiere), cu testele din `Tests/FoniaSpeechTests`. Specificația: inventarul, §1.7, §5, §11.6 și §13.3. Rândurile din „Abateri” au coloanele din `docs/paritate.md`, ca să se poată muta acolo.

## Ce e portat

| iOS | Kotlin (`tech.fonia.speech`) |
|---|---|
| `SpeechTypes.swift` | `SpeechTypes.kt` |
| `SpeechText.swift` | `SpeechText.kt`; regulile de `String` din Swift sunt în `SwiftString.kt` |
| `PairJudge.swift` | `PairJudge.kt`: `PairOutcome`, `SpeechJudge.combinePair`, `SpeechJudge.judgePair` |
| `SentenceJudge.swift` | `SentenceJudge.kt`: `SpeechJudge.judgeSentence` |
| `SpeechReport.swift` | `SpeechReport.kt`: `SpeechJudge.speechReport` |
| `SpeechThresholds.swift` | `SpeechThresholds.kt`, cu toate valorile din §5.14 |
| `TokenTiming.swift` | `TokenTiming.kt`: cadre active, lipirea pe sunet, DTW, median, timpii tokenilor, gruparea în cuvinte, ferestrele de 30 s |
| `SpeechResampler.swift` | `SpeechResampler.kt` (vezi abaterile) |
| `Transcriber.swift` | `Transcriber.kt`: `Transcriber`, `StubTranscriber` |
| `ForcedDecoder.swift`, `WhisperSession.swift`, `WhisperEngine.swift` | partea care nu ține de Core ML: `ForcedDecoder.kt`, `WhisperSession.kt`, `WhisperEngine.kt`. Core ML și WhisperKit devin interfețele din `WhisperRuntime.kt` |
| `SpeechModelStore.swift` | `SpeechModelStore.kt`: manifestul fixat (21 de fișiere, mărimi, SHA-256), verificarea, reluarea fișier cu fișier, ștergerea. Rețeaua devine `SpeechModelFetcher` |

## Interfețele pentru pasul de platformă

Bucla de decodare, prefixele comune, tokenii suprimați, poarta de liniște, timpii din atenția încrucișată, ferestrele și coada de cereri sunt deja în Kotlin. Runtime-ul Android (ONNX Runtime sau whisper.cpp prin NDK) implementează doar:

```kotlin
interface WhisperRuntimeLoader {
    suspend fun parseTokenizer(modelFolder: File)          // aruncă dacă tokenizerul lipsește; niciodată rețea
    suspend fun load(modelFolder: File): WhisperRuntime
    suspend fun prewarm(modelFolder: File)
}
interface WhisperRuntime {
    val tokenizer: WhisperTokenizer
    val windowSamples: Int?                                 // null = 480 000 (30 s)
    suspend fun encodeFeatures(audio: FloatArray): WhisperEncoderOutput?   // log-mel + encoder
    fun makeDecoder(encoderOutput: WhisperEncoderOutput): WhisperDecoderRuntime?
    suspend fun unload()
}
interface WhisperDecoderRuntime {
    val kvCacheMaxSequenceLength: Int
    suspend fun predictLogits(token: Int, index: Int): WhisperDecoderOutput   // logiți + rândul de atenție încrucișată
    fun rewind(length: Int)
}
class WhisperDecoderOutput(val logits: FloatArray, val alignmentWeights: FloatArray?)
interface WhisperTokenizer {
    val specialTokens: WhisperSpecialTokens
    fun encode(text: String): List<Int>
    fun decode(tokens: List<Int>): String
    fun convertTokenToId(token: String): Int?
}
```

Peste ele, ce primesc judecătorii: transcrierea greedy cu log-probabilitatea și timpii fiecărui token (`WhisperSession.transcribe`), scorarea forțată a unei secvențe date cu log-probabilitatea fiecărui token (`WhisperSession.score`, `ForcedDecoder.run`), ponderile de aliniere (`ForcedDecoder.Step.alignment`), totul prin `WhisperEngine : Transcriber`. `FakeWhisper` (în teste) e implementarea scriptată.

Descărcarea pe Android: o implementare a `SpeechModelFetcher` (`suspend fun fetch(url: URI, allowsCellular: Boolean, onWrite: (Long) -> Unit): FetchedFile`), cu GET-uri HTTPS fără cookie-uri și fără cache, doar pe Wi-Fi dacă `allowsCellular` e fals, de pe serverul propriu. Rădăcina e `File(context.noBackupFilesDir, "FoniaSpeech")`, dată de `:features`.

## Abateri

| Modul | Ce | De ce | Ce facem în loc |
|---|---|---|---|
| `:speech` | Numele urmează convențiile Kotlin: `SpeechLanguage.EN`, `WordStatus.GOOD`, `SpeechThresholds.STANDARD`, `InferenceCost.ZERO`, `SpeechResampler.WHISPER_RATE`, `TokenTiming.SAMPLES_PER_POSITION`, `SpeechModelManifest.WHISPER_BASE_FROM_HUGGING_FACE`; `pace(for:)` devine `pace(language)` (`for` e cuvânt rezervat) | Kotlin | Valorile brute și cheile JSON sunt neschimbate (`@SerialName`). Enum-urile cu valori asociate sunt `sealed` (`PairOutcome.Target(pTarget)`, `SpeechReport.Focus.ManyHesitations(count)`), erorile sunt excepții `sealed` |
| `:speech` | Structurile Swift cu `var` sunt `data class` imuabile | Semantica de valoare | Se schimbă cu `copy`. Extensiile lui `SpeechJudge` sunt funcții de extensie: în afara pachetului cer `import tech.fonia.speech.judgeSentence` |
| `:speech` | Egalitatea `data class` compară `Double` cu `equals` (NaN egal cu NaN, -0.0 diferit de 0.0); Swift folosește `==` IEEE | Kotlin | Contează doar pentru NaN și zero negativ |
| `:speech` | `SpeechResampler`: alte eșantioane decât `AVAudioConverter` Mastering | Android nu are un convertor de calitatea asta în API-ul public | Sinc cu fereastră Kaiser (16 treceri prin zero, β 8,6, tăiere la 94 % din Nyquist-ul mai mic), latență zero, `ceil(n × țintă / sursă)` eșantioane; trece testele iOS cu aceleași toleranțe. Pe clipurile reale, marjele Whisper se compară în pasul de runtime |
| `:speech` | `resample` nu e `async`/`@concurrent` | `:speech` nu are bibliotecă de corutine | Funcție blocantă; ecranele o cheamă pe `Dispatchers.Default`. Trecerea fără conversie întoarce o copie (semantică de valoare) |
| `:speech` | `ForcedDecoder`: normalizatorul log-softmax e calculat în Float, dar însumat secvențial, cu `exp`/`log` din JVM | `vDSP.sum` vectorizat, `vForce.exp`, `logf` | Diferențe în ultimul bit al `logp`; nicio conversie Float16 (runtime-ul dă Float32) |
| `:speech` | `log10`, `exp`, `atan2` din JVM în loc de libm Darwin | Platformă | Pot diferi cu 1 ulp; pragurile compară cu `>=`/`<=`, deci contează doar la egalitate exactă |
| `:speech` | `Int(Double)` din Swift se oprește pe NaN sau infinit, `toInt()` din Kotlin saturează; `0...last` pe o felie goală se oprește în Swift (`medianFilter` pe nimic), în Kotlin dă gol | Limbaj | Doar pe intrări invalide, pe care codul nu le produce |
| `:speech` | `String`: `Character` = grafem extins (`\X`), comparații prin echivalență canonică (NFC), `lowercased()` scalar cu scalar (fără regula sigma finală din Java), seturile Foundation (`.whitespaces` = Zs + tab, `.whitespacesAndNewlines` = White_Space), `replacingOccurrences` pe grafeme întregi, regex pe text NFC, `<` pe scalarii NFC | Kotlin lucrează pe unități UTF-16 | Implementat în `SwiftString.kt`. Tabelele Unicode ale JVM (Java 21) și ale Apple pot diferi pentru caracterele noi |
| `:speech` | `WhisperEngine`: actorul și coada FIFO de continuări devin `RequestQueue` (`suspendCoroutine`, FIFO; o cerere care așteaptă nu se anulează, ca pe iOS) | `:speech` fără kotlinx.coroutines | `Task.checkCancellation()` dintre ferestre lipsește: anularea vine la apelurile suspendabile ale runtime-ului. Costul se măsoară cu `System.nanoTime()` în loc de `ContinuousClock` |
| `:speech` | `WhisperSession.load`: `AutoTokenizerWrapper.from` devine `WhisperRuntimeLoader.parseTokenizer`, `WhisperKit(config)` devine `load`, `prewarm` la fel | WhisperKit e doar pe iOS | Aceeași ordine: `generation_config.json` și tokenizerul întâi (orice eroare → `ModelNotInstalled`), apoi decodarea configurației, apoi modelul |
| `:speech` | `SpeechModelStore` nu are rădăcină implicită și nu marchează nimic „exclus din backup” | Pe Android rădăcina cere un `Context` | `:features` dă `File(context.noBackupFilesDir, "FoniaSpeech")`, pe care Auto Backup nu îl copiază niciodată. `applicationSupportUnavailable` rămâne, dar nu poate apărea |
| `:speech` | `SpeechModelStore` nu e actor | Nu ține stare în afara celor două constante | Clasă simplă; `prewarm` și `makeEngine` cer `WhisperRuntimeLoader` dat la construcție. `LocalFileFetcher` copiază URL-uri `file:`, cum face `URLSession` (folosit de teste) |
| `:speech` | Manifestul are încă fișierele Core ML din iOS (21, 149 482 602 octeți) | E specificația de azi | Runtime-ul Android va citi alte fișiere (ONNX sau GGML); rămân doar `tokenizer.json` și `tokenizer_config.json`. Lista, mărimile, hash-urile și textul „147 MB” se schimbă în pasul de runtime |
| `:speech` | `calibration.json` din `tools/eval_speech` | Repo-ul Android nu are `tools/` | Copie identică (`{}`) în `speech/eval_speech/`, dată testelor de `speech/build.gradle.kts` (`fonia.speech.eval`) |
| `:speech` | Metodele `Transcriber` sunt `suspend`; `transcribe(..., verbatim = false)` e argument implicit în interfață | Swift `async` și extensia de protocol | Același comportament |

## Testele

Swift numără funcțiile `@Test`; JUnit numără fiecare caz parametrizat (`SpeechTextTests` rulează 25 de cazuri, `SpeechResamplerTests` 8).

| Fișier iOS | Teste iOS | Portate | Trec |
|---|---:|---:|---:|
| `PairJudgeTests` | 10 | 10 | 10 |
| `SentenceJudgeTests` | 8 | 8 | 8 |
| `SpeechReportTests` | 15 | 15 | 15 |
| `SpeechResamplerTests` | 5 | 5 | 5 |
| `SpeechTextTests` | 10 | 10 | 10 |
| `SpeechThresholdsTests` | 11 | 11 | 11 |
| `TokenTimingTests` | 15 | 15 | 15 |
| `SpeechModelStoreTests` | 12 | 9 | 9 |
| `WhisperIntegrationTests` | 15 | 0 | amânate |
| **Total** | **101** | **83** | **83** |

În plus, `WhisperSeamTests` (16, noi, nu sunt de paritate): aceleași proprietăți ca testele de integrare, dar prin portul Kotlin peste `FakeWhisper`: transcriere greedy cu timpi crescători, perechea corectă și cea greșită, o singură trecere prin encoder și 11 pași de decodor pentru o pereche, scoruri identice singure și în grup, scoruri forțate negative cu `<|endoftext|>` la final, liniștea, clipul gol, propoziția, ferestrele de 30 s cu offset, scorarea doar pe primele 30 s, promptul „verbatim”, tokenii suprimați, `unload`/`load`, `prewarm` și `makeEngine` prin store, coada FIFO.

`resumes` portat: în locul oricărei erori `URLSession`, `LocalFileFetcher` aruncă `FileNotFoundException` pentru sursa lipsă.

## Amânate pentru pasul de platformă

| Test iOS | De ce | Când |
|---|---|---|
| `SpeechModelStoreTests.excludedFromBackup` | Backup-ul iCloud; pe Android contează doar ca rădăcina să fie în `noBackupFilesDir`, care cere un `Context` | test instrumentat, cu `SpeechHub` în `:features` |
| `SpeechModelStoreTests.defaultLocation` | Application Support; rădăcina Android vine din `Context` | idem |
| `SpeechModelStoreTests.realDownload` | 149 MB din rețea și modelul real | după fetcher-ul HTTPS și manifestul Android, cu un flag ca `FONIA_SPEECH_DOWNLOAD_TEST` |
| `WhisperIntegrationTests` (toate 15: `harness`, `pairRight`, `pairWrong`, `onePass`, `reuseIsClean`, `forcedScores`, `silence`, `emptyClip`, `wrongSentence`, `accentSentence`, `e2eSentence`, `englishSpeech`, `romanianSpeech`, `romanianWordBoundaries`, `unloads`) | Cer modelul Whisper real și fixturile WAV din `site/e2e/fixtures/speech` | după ce runtime-ul implementează `WhisperRuntime`; până atunci logica lor e acoperită de `WhisperSeamTests`. Marjele se compară cu tabelul din inventar §11.6 |
