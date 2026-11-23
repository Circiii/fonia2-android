# Paritate cu iOS

Ce nu se poate porta identic de pe iOS și de ce. Fiecare modul își ține lista în fișierul lui:

| Modul | Fișier | Teste iOS portate | Dovada de paritate |
|---|---|---|---|
| `:domain` | [domain/PARITY.md](../domain/PARITY.md) | 145 din 145 | ședințele generate sunt identice octet cu octet cu cele exportate din iOS (276 de cereri) |
| `:dsp` | [dsp/PARITY.md](../dsp/PARITY.md) | 193 din 193 | raportul pe clipurile reale coincide cu cel din iOS, în afară de două rânduri explicate de decodorul MP3 |
| `:store` | [store/PARITY.md](../store/PARITY.md) | 60 din 60 | exportul e verificat octet cu octet față de formatul din cod; comparația cu un export real de pe iPhone urmează |
| `:speech` | [speech/PARITY.md](../speech/PARITY.md) | 101 din 101 | 83 pe JVM; celelalte 18 rulează în `:whisper`, cu modelul real |
| `:face` | [face/PARITY.md](../face/PARITY.md) | 37 din 37 | 33 pe JVM; celelalte 4 rulează în `:facecamera`, cu camera; MediaPipe nu are `tongueOut` |
| `:audio` | [audio/PARITY.md](../audio/PARITY.md) | 70 din 70 | 45 de teste pe JVM și 25 în emulator, plus inelul nativ testat în emulator; captura raportează rata, presetul și banda îngustă |
| `:design` | [design/PARITY.md](../design/PARITY.md) | – (iOS n-are teste de design) | galeria pusă lângă cadrele iOS: Acasă, lecția „Cum spun S?”, butoanele, bara cu butonul galben |
| `:whisper` | [whisper/PARITY.md](../whisper/PARITY.md) | 15 + 3 (integrare și descărcare) | pe clipurile din `tools/eval_speech`: 23/23 perechi, 7/7 propoziții, 34/34 cuvinte, 2/2 rapoarte și 32/32 transcrieri identice cu iOS; marja se mută cu 0,00 nat la mediană (maximum 0,09) |
| `:facecamera` | [facecamera/PARITY.md](../facecamera/PARITY.md) | 4 (amânate din `:face`) + 13 noi în emulator | modelele de față rulate pe LiteRT dau aceleași rezultate ca pachetul oficial MediaPipe: puncte la 1e-5, scoruri la 2e-4, pe 5 fotografii; nicio conexiune de rețea; pragurile ARKit rămân de recalibrat pe aceleași fețe |

## ML nou (nu există pe iOS)

| Modul | Fișier | Teste | Ce arată evaluarea |
|---|---|---|---|
| `:phones` | [phones/EVAL.md](../phones/EVAL.md), [phones/MODELS.md](../phones/MODELS.md) | 47 | ZIPA-small int8 + GOP-SF restrâns: AUC 0,979 pe perechile minimale din conținut, 3,6% alarme false pe cuvinte corecte, 23–35 ms pe cuvânt; doar voce adultă ElevenLabs, nimic despre copii |
