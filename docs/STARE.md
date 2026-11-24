# Starea aplicației (25 noiembrie 2026)

## Ce există
- Ecranele aplicației iOS, portate, cu fontul Baloo 2 și litere vii (Allo) la ce se exersează.
- Verificarea rapidă a celor nouă sunete (2 minute), din care se face planul; „Atunci și acum” la a doua verificare.
- Sesiunea zilnică dintr-o atingere; lecțiile se deschid prin reușita proprie (fără confirmarea unui adult).
- Antrenament de dicție pe fiecare sunet (încălzire, silabe, coloane de cuvinte, perechi, propoziții, frământare la trei viteze, text scurt), cu texte originale din `spec/content/v2`.
- Verificarea sunetului: măsurători acustice + modelul de foneme ZIPA, cu abținere; grupul de voce (copil / femeie / bărbat) se ia din fiecare rostire; calibrare pe vocea proprie.
- Feedback vizual: litera reacționează în timp real, bila între Ș și S, unda vocii, mărgelele lui R; după rostire, unde a stat sunetul față de zona-țintă și un singur indiciu.
- Oglinda: sus gura 3D corectă, jos camera, cu corecții desenate pe buze.
- Ghidul „Cum spun?” cu gura 3D și pașii; „Tu, fără ăăă” în zona de vorbit.
- Voci: clipuri generate (ElevenLabs), aceleași voci peste tot. Aplicația nu folosește vocea sintetică a telefonului; un text fără clip rămâne tăcut.
- Efecte sonore proprii (`legacy/design/src/main/assets/sfx`), cu regula „nimic nu se aude cât microfonul e deschis”.

## Ce e slab sau neverificat
- Toate cifrele de acuratețe vin de pe voci sintetice de adult (`spec/fusion/EVAL_v2.md`). Nimic nu e măsurat pe copii sau pe voci reale. În `spec/unlock.json` nimic nu e deblocat pentru copii.
- GE e slab (nu se deosebește de J la început de cuvânt); la vocile tinere apar mai multe laude greșite.
- 27 de texte nu au clip (sunete izolate și câteva cuvinte): lista în `tools/voice/human-needed.txt`. Trebuie înregistrate de un om.
- Clipurile antrenamentului de dicție (`spec/content/voice`) sunt cu altă voce decât restul și nu au fost ascultate de un om.
- Oglinda și gura 3D au fost verificate doar pe emulator; pe telefon nu s-a măsurat viteza.
- `domain/src/test/resources/ios-sessions.txt` a fost rescris din Android după ce s-au schimbat cuvintele cu înregistrare: de confirmat cu exportul iOS pe același conținut.
- Vederea din lateral a gurii 3D apare doar în build-urile de depanare până e aprobată de un logoped (`insideApproved` în `spec/poses/mouth3d`).
- Whisper nu se mai descarcă din aplicație (fără permisiunea de internet): modelul trebuie pus în APK sau copiat pe telefon.

## Unde lucrezi
- Ecrane: `legacy/features/src/main/java/tech/fonia/features/` (`home`, `session`, `lessons`, `guide`, `speaking`, verificarea rapidă și antrenamentul).
- Design și fonturi: `legacy/design` (`Typography.kt`, `Palette.kt`); litera vie și contoarele: `design`.
- Verificarea sunetului: `speech/src/main/kotlin/tech/fonia/judge` (citește `speech/JUDGE.md`), date în `spec/`.
- Steaguri de lansare pentru teste fără microfon: `LaunchArguments` în `legacy/features/.../app/AppModel.kt` (`foniaStart`, `foniaRoute`, `foniaFixtures`, `foniaMicFile`).
- Testele rapide: `./gradlew :engine:dsp:test :core:domain:test :core:data:test :engine:judge:test :engine:face:test :legacy:features:testDebugUnitTest`.
