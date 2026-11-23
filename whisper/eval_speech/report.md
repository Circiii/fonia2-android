# Parity with iOS: Whisper base on Google sdk_gphone64_x86_64

Android 16 (API 36), x86_64, 4 cores, 4 ggml threads, fonia_whisper. ggml: WHISPER : VITISAI = 0 | COREML = 0 | OPENVINO = 0 | CPU : SSE3 = 1 | SSSE3 = 1 | AVX = 1 | AVX2 = 1 | F16C = 1 | FMA = 1 | BMI2 = 1 | REPACK = 1 |
Prewarm 1.08 s, load 0.29 s, first clip 3362 ms, second 2785 ms.

## Minimal pairs

| Fixture | Target | Rival | Heard (iOS / Android) | Margin iOS | Margin Android | Shift | Outcome iOS | Outcome Android | Same | ms | Steps (iOS / Android) | No-speech |
|---|---|---|---|---:|---:|---:|---|---|---|---:|---|---:|
| en-ship | ship | sheep | Shab / Shab | 4.18 | 4.17 | -0.01 | target | target | yes | 1886 | 13 / 13 | 0.50 |
| en-ship | sheep | ship | Shab / Shab | -4.18 | -4.17 | +0.01 | rival | rival | yes | 1804 | 13 / 13 | 0.50 |
| en-sheep | sheep | ship | Sheep. / Sheep. | 3.92 | 3.95 | +0.03 | target | target | yes | 1677 | 14 / 14 | 0.40 |
| en-sheep | ship | sheep | Sheep. / Sheep. | -3.92 | -3.95 | -0.03 | rival | rival | yes | 1167 | 14 / 14 | 0.40 |
| en-think | think | sink | Think. / Think. | 8.38 | 8.34 | -0.04 | target | target | yes | 1177 | 13 / 13 | 0.20 |
| en-think | sink | think | Think. / Think. | -8.38 | -8.34 | +0.04 | rival | rival | yes | 1198 | 13 / 13 | 0.20 |
| en-sink | sink | think | Sink! / Sink! | -0.08 | 0.01 | +0.09 | unclear | unclear | yes | 1426 | 14 / 14 | 0.29 |
| en-sink | think | sink | Sink! / Sink! | 0.08 | -0.01 | -0.09 | unclear | unclear | yes | 1485 | 14 / 14 | 0.29 |
| en-wine | wine | vine | Wine. / Wine. | 5.82 | 5.84 | +0.02 | target | target | yes | 1424 | 12 / 12 | 0.29 |
| en-wine | vine | wine | Wine. / Wine. | -5.82 | -5.84 | -0.02 | rival | rival | yes | 1289 | 12 / 12 | 0.29 |
| en-vine | vine | wine | Vina. / Vina. | 2.19 | 2.13 | -0.06 | target | target | yes | 1260 | 13 / 13 | 0.24 |
| en-vine | wine | vine | Vina. / Vina. | -2.19 | -2.13 | +0.06 | rival | rival | yes | 1390 | 13 / 13 | 0.24 |
| en-walk | walk | work | Walk. / Walk. | 8.25 | 8.24 | -0.01 | target | target | yes | 1352 | 12 / 12 | 0.16 |
| en-walk | work | walk | Walk. / Walk. | -8.25 | -8.24 | +0.01 | rival | rival | yes | 1249 | 12 / 12 | 0.16 |
| en-work | work | walk | Work. / Work. | 7.24 | 7.21 | -0.03 | target | target | yes | 1247 | 12 / 12 | 0.19 |
| en-work | walk | work | Work. / Work. | -7.24 | -7.21 | +0.03 | rival | rival | yes | 4162 | 12 / 12 | 0.19 |
| ro-accent-sheep | The sheep is on the ship. | The ship is on the ship. | The sheep is on the ship. / The sheep is on the ship. | 5.80 | 5.83 | +0.03 | target | target | yes | 1740 | 24 / 24 | 0.04 |
| ro-accent-think | I think three things. | I sink tree sings. | I think three things. / I think three things. | 33.20 | 33.19 | -0.01 | target | target | yes | 1179 | 18 / 18 | 0.11 |
| en-wrong-think | I think three things. | I sink tree sings. | I sink tree sings. / I sink tree sings. | -3.17 | -3.16 | +0.01 | rival | rival | yes | 1409 | 18 / 18 | 0.22 |
| ro-accent-walk | I walk to work. | I work to walk. | I walk to work. / I walk to work. | 16.06 | 16.09 | +0.03 | target | target | yes | 1709 | 18 / 18 | 0.08 |
| en-wine | ship | sheep | Wine. / Wine. | 0.66 | 0.60 | -0.06 | otherWord | otherWord | yes | 1805 | 13 / 13 | 0.29 |
| silence | ship | sheep | (nothing) / (nothing) | -0.43 | -0.51 | -0.08 | notHeard | notHeard | yes | 1702 | 12 / 12 | 0.96 |
| en-animal | ANimal | aniMAL | Animal. / Animal. | 0.00 | 0.00 | +0.00 | sameText | sameText | yes | 1436 | 6 / 6 | 0.53 |

Same outcome as iOS on 23 of 23 rows. Margin shift (Android minus iOS, nats): median +0.00, median absolute 0.03, largest 0.09; single words: median +0.00, median absolute 0.03, largest 0.09.

## Sentences

| Fixture | Target | Heard (Android) | Result iOS | Result Android | Score iOS / Android | Word statuses that match | ms | Words (Android) |
|---|---|---|---|---|---|---:|---:|---|
| en-sentence-sheep | The sheep is on the ship. | The sheep is on the ship. | allHeard | allHeard | 1.00 / 1.00 | 6 of 6 | 1471 | The:good@0.60-0.66 sheep:good@0.66-0.94 is:good@0.94-1.14 on:good@1.12-1.26 the:good@1.26-1.36 ship:good@1.36-1.74 |
| en-sentence-think | I think three things. | I think three things. | allHeard | allHeard | 1.00 / 1.00 | 4 of 4 | 1347 | I:good@0.54-0.64 think:good@0.64-0.86 three:good@0.86-1.18 things:good@1.16-1.84 |
| en-wrong-think | I think three things. | I sink tree sings. | severalMissed | severalMissed | 0.25 / 0.25 | 4 of 4 | 1467 | I:good@0.54-0.62 think:missed(sink)@0.62-0.96 three:missed(tree)@0.96-1.26 things:missed(sings)@1.26-1.92 |
| ro-accent-sheep | The sheep is on the ship. | The sheep is on the ship. | allHeard | allHeard | 1.00 / 1.00 | 6 of 6 | 1457 | The:good@0.60-0.64 sheep:good@0.64-0.82 is:good@0.82-0.96 on:good@0.96-1.08 the:good@1.08-1.18 ship:good@1.16-1.50 |
| ro-accent-think | I think three things. | I think three things. | allHeard | allHeard | 1.00 / 1.00 | 4 of 4 | 1431 | I:good@0.52-0.60 think:good@0.60-0.86 three:good@0.86-1.16 things:good@1.14-1.74 |
| ro-accent-walk | I walk to work. | I walk to work. | allHeard | allHeard | 1.00 / 1.00 | 4 of 4 | 1444 | I:good@0.54-0.62 walk:good@0.62-0.82 to:good@0.82-1.00 work:good@1.00-1.54 |
| en-sentence-sheep | The ship is on the sheep. | The sheep is on the ship. | severalMissed | severalMissed | 0.60 / 0.60 | 6 of 6 | 1293 | The:good@0.60-0.66 ship:missed(sheep)@0.66-0.92 is:good@0.92-1.14 on:good@1.12-1.26 the:good@1.26-1.36 sheep:missed(ship)@1.36-1.74 |

Same result as iOS on 7 of 7 sentences; same word status on 34 of 34 words.

## Free speech

| Fixture | Pace iOS / Android | Words/min iOS / Android | Fillers iOS | Fillers Android | Focus iOS / Android | Score iOS / Android | ms |
|---|---|---|---|---|---|---|---:|
| en-speech | fast / fast | 174 / 174 | basically 1, like 1, so 1, um 1 | basically 1, like 1, so 1, um 1 | fast / fast | 0.80 / 0.80 | 2499 |
| ro-speech | fast / fast | 190 / 190 | deci 1, practic 1 | deci 1, practic 1 | fast / fast | 0.80 / 0.80 | 2582 |

- en-speech (Android): Hi, everyone. Um, today I want to talk about, like, why sleep matters. So, basically, teenagers need about nine hours of sleep, and most of us get less than seven.
- ro-speech (Android): Bună ziua tuturor. Astați vreau să vă vorbesc despre somn, deci, practic, adolescente au nevoie de 9 ore de somn, dar ce mai mulți doar mai puțin de 7.

## Latency

Median of three runs per row. Single-word pair: median 1371 ms (iOS 588), slowest 4162 ms. All pairs: median 1424 ms (iOS 541). Sentence: median 1444 ms (iOS 446).
