# `:facecamera`: models and test data

## The model bundle

| | |
|---|---|
| File | `src/main/assets/models/face_landmarker.task` |
| Source | https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task |
| Size | 3,758,596 bytes |
| SHA-256 | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` (checked by `ModelBundle.parse` at load and by `ModelBundleTests`) |
| Version | Last-Modified 3 May 2023 (`x-goog-generation 1683136941468629`), downloaded 6 Nov 2026; unchanged since May 2023 |
| Licence | Apache-2.0: MediaPipe's models and their model cards (Face Mesh V2, Blendshape V2, BlazeFace short range: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf, https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf, https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf). The licence is to be checked again at the source before release (docs/REGULI.md) |

The `.task` is a zip (entries stored, aligned: two bytes before the first header and padding between entries, so
`ModelBundle.unzip` reads the central directory). Only numbers come out of it; nothing is downloaded at run time.

| Entry | Bytes | SHA-256 | What it is |
|---|---:|---|---|
| `face_detector.tflite` | 229,746 | `b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f` | BlazeFace short range, 2019-10-17. In: 1×128×128×3 float, (pixel − 127.5) / 127.5. Out: 1×896×16 boxes and keypoints, 1×896×1 scores |
| `face_landmarks_detector.tflite` | 2,553,590 | `c7d54204ce0448474c7f3fa9af494787c0965cbdd6f20fc72867e43046bd43d5` | Face Mesh V2 (`facemesh2_faceflag_blendshapes_full_2022_11_08`). In: 1×256×256×3 float, pixel / 255. Out, in order: 478 × (x, y, z) landmarks in input pixels, face presence (logit), `tongue_out` ("Score of whether tongue is out of mouth") |
| `face_blendshapes.tflite` | 955,312 | `4f36dded049db18d76048567439b2a7f58f1daabc00d78bfe8f3ad396a2d2082` | Blendshape V2 (`hund_face_lite_2022_10_10`), an MLP-Mixer. In: 1×146×2, 146 of the landmarks in image pixels. Out: 52 scores, `_neutral` first, then ARKit's names without `tongueOut` |
| `geometry_pipeline_metadata_landmarks.binarypb` | 19,376 | `bdbcda96dfcb7da883da124aaa2c55dee49770d934f0fcc71747f8c21bdc75b4` | The canonical face (468 vertices, centimetres) and the 33-landmark Procrustes basis, input source FACE_LANDMARK_PIPELINE |

Input normalisation and output order come from the models' TFLite metadata. The graph parameters (anchors, score
and IoU thresholds, region scales, smoothing constants, the 146-landmark subset, the geometry camera) come from
MediaPipe v1.2.2's sources, file by file in PARITY.md.

The runtime is LiteRT 2.3.2 (`com.google.ai.edge.litert:litert`, Apache-2.0), CPU with XNNPACK, from the copy in the
APK. All its native libraries (`libLiteRt.so`, `liblitert_jni.so`, arm64-v8a, armeabi-v7a, x86_64) have 16 KB LOAD
alignment (checked with `llvm-readelf -l`). No 32-bit x86 build exists, so `isSupported` is false there.

## Test data (`src/androidTest/assets/faces`, not in the app)

Five frames cut from Wikimedia Commons photos, to compare this module with the official MediaPipe Face Landmarker:

| Frame | Size | Source photo | Licence | What it tests |
|---|---|---|---|---|
| `smile.png` | 480×640 | [Jonny Kim official portrait (cropped)](https://commons.wikimedia.org/wiki/File:Jonny_Kim_official_portrait_(cropped).jpg), NASA | public domain | a broad smile with teeth; head turned 6°, eyes back on the camera |
| `speaking.png` | 480×640 | [Dr. Sally Ride, NASA Goddard](https://commons.wikimedia.org/wiki/File:Dr._Sally_Ride_-_Flickr_-_NASA_Goddard_Photo_and_Video.jpg) | public domain | speaking, lips apart, head tipped |
| `wide_open.png` | 640×440 | [Voice Training: Expanding and Flexing the Vocal Tract](https://commons.wikimedia.org/wiki/File:Voice_Training_-_Expannding_and_Flexing_the_Vocal_Tract.jpg), Paul Newham | CC0 | jaw wide open, head rolled, eyes squeezed, landscape frame |
| `pout.png` | 480×640 | [Cat Power, Bonnaroo 2006, pout face](https://commons.wikimedia.org/wiki/File:Cat_Power_Bonnaroo_2006_Pout_Face.jpg), Fsamuels | public domain | lips pressed, head turned 38°, a small face |
| `small_smile.png` | 480×640 | [Kayla Barron, official portrait (2021)](https://commons.wikimedia.org/wiki/File:Kayla_Barron,_official_portrait_(2021).jpg), NASA | public domain | a small face looking into the camera |

All adults; no child appears in the test data. The PNGs carry no ICC profile (Android would colour-convert on decode;
MediaPipe reads the pixels as they are).

Next to each frame, `<name>.json` holds MediaPipe's output in IMAGE mode (478 landmarks, 52 scores, the facial
transformation matrix) and the face rectangle the prototype's detector step found. `smile` and `wide_open` also have
`.detector_input.png` and `.landmark_input.png`: the 8-bit model inputs the prototype built, which the Kotlin sampler
must match pixel for pixel. `sequence.json` is MediaPipe's VIDEO-mode output on 24 frames made from `smile.png` by
exact crops (and two gray frames).

### Regenerating

Python 3.14 with `mediapipe==1.2.2 ai-edge-litert==2.3.0 numpy pillow` (Windows wheels exist for all four):

```bash
T=facecamera/tools/face_reference; TASK=facecamera/src/main/assets/models/face_landmarker.task
python -I $T/prepare_frames.py downloads frames                    # downloads the photos, crops the frames
python -I $T/mediapipe_reference.py $TASK frames smile speaking wide_open pout small_smile
python -I $T/prototype.py $TASK frames smile speaking wide_open pout small_smile   # prototype vs MediaPipe
python -I $T/fixtures.py $TASK frames facecamera/src/androidTest/assets/faces
python -I $T/prototype_stream.py $TASK frames/smile.png facecamera/src/androidTest/assets/faces/sequence.json
```

On 6 Nov 2026 this reproduced every committed fixture byte for byte.
