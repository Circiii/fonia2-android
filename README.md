# Fonia pentru Android

Fonia ajută copiii să-și exerseze sunetele limbii române (S, Ș, Z, J, Ț, CE/CI, GE/GI, R, L) vorbind cu o literă vie, iar adolescenții și adulții să vorbească mai clar în public. Este versiunea pentru Android a aplicației iOS Fonia, cu aceleași ecrane și același conținut, plus:

- **litera vie**: litera-țintă reacționează la voce în timp real (fontul Allo, animat);
- **verificarea sunetului** pe telefon: măsurători acustice și un model de foneme, cu abținere când nu e sigur;
- **oglinda**: sus gura corectă în 3D, jos copilul, cu corecții mari desenate pe buze în timp real;
- **gura 3D** care arată pas cu pas buzele, dinții, limba și drumul aerului;
- **„Tu, fără ăăă”**: îți auzi propria voce cu umpluturile tăiate;
- efecte sonore, mișcare și fontul Baloo 2 în toată interfața.

Totul rulează pe telefon. Aplicația nu cere acces la internet.

## Build

```bash
git clone --recurse-submodules https://github.com/Circiii/fonia2-android.git
cd fonia2-android
./gradlew :engine:phones:test          # o dată, cu internet: aduce modelul de foneme în ~/.gradle/fonia-models
./gradlew :legacy:app:assembleDebug    # APK-ul complet, cu modelul inclus
```

- `local.properties` trebuie să conțină `sdk.dir=` (calea către Android SDK). JDK 17 (cel din Android Studio merge).
- Conținutul (cuvinte, voci, imagini) e submodulul `content` (`Circiii/fonia-content`, privat; ramura `voice-elevenlabs` are vocile noi).
- Fără modelul de foneme (build mai mic, verificarea rapidă a sunetului): adaugă `-Pfonia.zipa=false`.
- Pentru emulator: adaugă `-Pfonia.abi=x86_64`. Microfonul emulatorului trebuie pornit din Extended controls › Microphone.
- APK-ul iese în `legacy/app/build/outputs/apk/debug/`.

Starea de azi, ce e slab și ce urmează: `docs/STARE.md`.

## Structură

| Folder | Ce e |
|---|---|
| `legacy/app`, `legacy/features`, `legacy/design` | aplicația (ecranele, după aplicația iOS) |
| `design` | litera vie, mișcarea, Foni |
| `glyphs` | contururile fontului Allo și interpolatorul |
| `live`, `audio` | microfonul și banda audio în timp real |
| `speech`, `phones`, `ml` | judecătorul de sunet și modelul de foneme |
| `face`, `facecamera` | punctele feței și oglinda |
| `mouth3d` | gura 3D |
| `speaking`, `feature/speaking` | „Tu, fără ăăă” și transcriptul prozodic |
| `domain`, `store` | conținutul, sesiunile, datele |
| `collector` | aplicația de înregistrat date pentru antrenare |
| `spec` | praguri, coeficienți, poze ale gurii, date de test |
| `docs` | planul și regulile (`docs/REGULI.md`) |

Testele pure: `./gradlew :engine:dsp:test :core:domain:test :core:data:test :engine:judge:test :engine:face:test`.
