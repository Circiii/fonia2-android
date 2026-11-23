# `:store` față de `FoniaStore`: ce nu e identic

Portul din `tech.fonia.store` urmează `Packages/FoniaKit/Sources/FoniaStore` (GRDB 7) fișier cu fișier. Ce e mai jos e tot ce nu s-a putut face identic, cu motivul și cu ce facem în loc. Rândurile au aceleași coloane ca `docs/paritate.md`, ca să poată fi mutate acolo.

## Ce e identic și cum s-a verificat

- **Schema și migrările.** Cele 6 migrări au aceleași nume și aceeași ordine și sunt notate în tabelul `grdb_migrations` al GRDB. Fiecare `CREATE TABLE`, `CREATE INDEX` și `ALTER TABLE` e textul pe care îl generează GRDB 7. Asta s-a verificat în sursele `SQLColumnGenerator`, `SQLTableGenerator` și `SQLIndexGenerator`: `.jsonText` e `TEXT`, iar cheia primară se scrie `PRIMARY KEY NOT NULL`. Testul `schemaShape` îngheață coloanele, tipurile, valorile implicite, cheile străine și indexurile.
- **Valorile pe disc, ca la GRDB.** UUID-ul e BLOB de 16 octeți. Datele sunt text `yyyy-MM-dd HH:mm:ss.SSS` în UTC, tăiat la milisecundă. `Bool` e 0/1, iar enumurile își păstrează valoarea `rawValue`. Coloanele JSON au cheile sortate, slash-ul scris `\/` și datele în milisecunde din 1970. La citire, datele se parsează ca în `SQLiteDateParser`. Le acoperă testele `columnsFollowGRDB` și `readsRowsWrittenOnIOS`.
- **Exportul pentru logoped**, octet cu octet, după swift-foundation `release/6.2` (iOS 26):
  - indentare de două spații și `" : "` între cheie și valoare;
  - containerele goale scrise `[\n\n<indent>]`;
  - escape-uri cu hex mic, iar slash-ul rămâne neescapat;
  - `Double` scris ca `description` fără „.0” la final (reguli din `FloatingPointToString.swift`: notație cu exponent sub 1e-4 și peste 2^53);
  - date ISO 8601 cu secundele tăiate și UUID-uri cu majuscule.

  Testul `exportIsByteIdentical` compară tot textul.
- **Retenția, ștergerea și agregatele.** `secure_delete` e pornit pe fiecare conexiune, iar după ștergeri rulează `wal_checkpoint(TRUNCATE)`; un eșec doar se jurnalizează. Agregatele folosesc același SQL. Ora de vară și miezul nopții sunt testate cu `java.time` în fusul `Europe/Bucharest`.
- **Pool-ul.** `DatabasePool` e în modul WAL, cu `synchronous = NORMAL`, un writer și cel mult 5 cititori read-only, cu `busy_timeout` de 10 s pe cititori. Fișierul `-wal` se creează ca în GRDB (issue 102). Migrările rulează cu cheile străine verificate la final.

## Diferențe

| Modul | Ce | De ce | Ce facem în loc |
|---|---|---|---|
| `:store` | Protecția fișierelor (`completeUntilFirstUserAuthentication`) și `isExcludedFromBackup` pe fiecare folder și fișier | Android nu are clase de protecție sau excludere din backup per fișier | Le dă folderul: aplicația deschide baza cu `FoniaDatabase.live(context.noBackupFilesDir)`, care e criptat (credential-encrypted, lizibil după prima deblocare) și nu intră niciodată în backup. `ProtectedStorage.protect` păstrează doar eșecul pentru un fișier lipsă, de care depinde `addRecording`. Testul `storageIsProtected` verifică faptul că toate căile stau în folderul bazei, nu atributele iOS. |
| `:store` | `FoniaDatabase.live()` fără argument | Android nu are un folder `Application Support` global, fără `Context` | `live(noBackupFilesDir: File)` deschide `<folder>/Fonia`, cu aceeași structură: `Database/fonia.sqlite` și `Recordings/<PROFIL>/<ÎNREGISTRARE>.m4a`. |
| `:store` | Închiderea bazei | Swift o închide când dispare ultima copie (`deinit`) | `FoniaDatabase` e `AutoCloseable`, cu `close()`. |
| `:store` | Observarea live (`ValueObservation`) | GRDB recitește doar după commit-urile care ating ce a citit observația | Se recitește după orice commit, iar `distinctUntilChanged` (`removeDuplicates`) lasă să treacă doar valorile schimbate, deci valorile emise sunt aceleași, cu mai multe citiri. `conflate()` ține loc de `bufferingNewest(1)`. Fluxurile sunt `Flow` reci: observarea pornește la colectare, nu la apelul funcției. Prima valoare e tot starea curentă. |
| `:store` | Anularea | GRDB anulează accesul la bază odată cu `Task`-ul | Funcțiile sunt `suspend`. `deleteProfile`, `wipeAll`, `addRecording`, `setPinned`, `deleteRecording` și `runStartupMaintenance` merg până la capăt odată pornite (`NonCancellable`), ca rândurile și fișierele audio să nu se despartă. Celelalte scrieri, anulate în timpul tranzacției, se pot totuși salva. |
| `:store` | Date: `Instant` în loc de `Date` (`Double`, secunde din 2001) | Kotlin n-are `Date` cu aceeași reprezentare | Coloanele text păstrează milisecunde, ca pe iOS. În JSON-ul din `blockHistory`, milisecundele întregi ies identic. Fracțiunile sub o milisecundă pot diferi în ultimele cifre față de ce scrie iOS pentru „același” moment. |
| `:store` | `Int` din Swift (64 de biți) devine `Int` în Kotlin (32 de biți) la numărători și trepte (`attempts`, `rung`, `good`, `total`, `selfRating`, agregate) | Tipul firesc pentru ecrane | O valoare stocată peste 2^31−1 nu se mai poate citi. `seed` rămâne pe 64 de biți (`Long`), tot domeniul. |
| `:store` | Ordinea cheilor din `.sortedKeys` | swift-foundation sortează după octeții UTF-8. Ordinea veche, fără majuscule și numerică, o păstrează doar poarta `JSONEncoder.compatibility1`, pentru aplicații legate de SDK-uri mai vechi de iOS 18. | Se folosește ordinea UTF-8, pentru că Fonia țintește iOS 26. Pentru cheile de azi, cele două ordini dau același rezultat. Ar diferi doar pentru chei noi cu majuscule sau cifre, și doar dacă aplicația iOS s-ar lega de un SDK vechi. |
| `:store` | `Codable` | Kotlin nu are `Codable` | Rândurile se scriu cu SQL explicit în ordinea proprietăților (`save` = `UPDATE`, apoi `INSERT` dacă rândul lipsește, ca în GRDB). `JSONValue` are un `KSerializer` kotlinx, care merge doar cu JSON, nu cu orice coder. |
| `:store` | Forma tipurilor | Kotlin are alte convenții | `var` din structurile Swift devin `val` + `copy` (aceeași semantică de valoare). `Recording.copy` e `internal`, pentru că `fileName` e `let` în Swift. Cazurile enumurilor sunt cu majusculă (`Profile.Kind.Child`), iar valorile scrise sunt cele din Swift; `fromRawValue` ține loc de `init?(rawValue:)`. `recordingFileURL` întoarce `File`, `exportJSON` întoarce `ByteArray`. |
| `:store` | Ordinea listei întoarse de `takesToDelete`, `addRecording` și retenție | Swift grupează printr-un `Dictionary`, cu ordine aleatoare | Aceleași înregistrări, în ordinea grupurilor la prima apariție. Testele iOS compară mulțimi. |
| `:store` | Jurnalul (`OSLog`, `privacy: .private`) | Nu există `OSLog` pe JVM | `java.util.logging` (ajunge în logcat), logger-ul `tech.fonia.store`, nivel `SEVERE`. Calea fișierului nu apare în mesaj. |
| `:store` | Erorile | Tipurile GRDB și Cocoa nu există | Ies excepții Kotlin: `SQLiteException`, `NoSuchFileException`, `IOException` și erorile interne `DatabaseError` și `RowDecodingError`. Singura eroare publică rămâne `FoniaStoreError.ProfileNotFound`. |

## Ce nu s-a verificat pe un iPhone

Pe mașina de port nu există Swift. Formatul exportului și convențiile GRDB s-au reprodus din sursele swift-foundation (`release/6.2`), Swift (`FloatingPointToString.swift`) și GRDB 7, apoi s-au înghețat în teste. Următorul pas e un export și o bază reale de pe iOS, comparate cu `exportIsByteIdentical` și `readsRowsWrittenOnIOS`.

## Teste

Cele 60 de teste iOS (`FoniaStoreTests`) sunt portate în fișiere cu aceleași nume. Fiecare păstrează intrările și valorile așteptate. `ageBandStorage` rulează pentru 4 benzi, deci sunt 63 de rulări. În `FormatParityTests.kt` mai sunt 12 teste de paritate a formatelor, care nu există pe iOS.
