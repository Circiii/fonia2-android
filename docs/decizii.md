# Fonia Android: decizii

Luate pe 6 noiembrie 2026, înainte de planul detaliat.

| Subiect | Decizie | Ce înseamnă |
|---|---|---|
| Platformă | Kotlin + Jetpack Compose, cu C++ (NDK) pentru audio în timp real și pentru runtime-urile ML | Port aproape 1:1 din Swift/SwiftUI. Acces direct la LiteRT, MediaPipe, ML Kit, Gemini Nano, ONNX Runtime, sherpa-onnx. |
| Telefon de test | Un Android recent (2023 sau mai nou) | Modelul exact e încă de aflat (Setări → Despre telefon). |
| Telefoane suportate | Android 10 și mai nou (minSdk 29) | Aceeași generație ca iPhone-urile pe care merge iOS 26. ML pe CPU peste tot, accelerat pe GPU/NPU unde există. |
| Date și AI | Hibrid, cu acord | Implicit totul rămâne pe telefon. Pentru adulți, opțional și cu acord, un LLM din cloud primește doar text și cifre, niciodată voce sau imagine. Pentru copii nu pleacă nimic în cloud. |
| ML prioritar | Toate patru: pronunție copii (RO), citire și dislexie, adulți (engleză și vorbit în public), antrenor AI adaptiv | Ordinea exactă o dă planul. |
| Date pentru antrenare | Da, mod opțional cu acordul părintelui | Înregistrări criptate, etichetate de logoped, pentru modele proprii pe copii români. Înainte de pornire: text de consimțământ și DPIA. |
| Repo | `Circiii/fonia-android`, privat | |
| Conținut comun | Repo nou `Circiii/fonia-content`, privat, pornit din conținutul iOS de azi | Android îl folosește imediat. Pentru iOS pregătim un PR în `dulgherustefan/fonia-app`; Ștefan decide dacă și când îl acceptă. |
| AI în cloud | Se alege după un test | Două modele din cloud, pe 30 de răspunsuri reale în română: claritate, greșeli, cost, unde stau datele. |
| Publicare | APK semnat din CI acum, Google Play (testare internă) după pilot | |
| Termen | Fără termen fix | Facem totul bine, în ordinea din plan. |

## Implicit (nu a fost nevoie de întrebare)

- Interfața e identică vizual cu iOS: aceleași culori, fonturi, scene, același papagal și aceleași texte. Unde Android are reguli de sistem (gestul și butonul Înapoi, barele de sistem, permisiunile), le respectăm.
- Limba aplicației e româna, ca pe iOS. Conținutul în engleză rămâne pentru adulți.
- Întâi paritate cu iOS, apoi funcțiile ML noi.
