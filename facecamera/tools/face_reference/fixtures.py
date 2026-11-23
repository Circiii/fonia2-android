# Writes the test fixtures: the frames, MediaPipe's outputs on them (IMAGE mode) and, for the sampler test, what the
# prototype fed the models (8-bit, before normalisation) and the face rectangle it used.
#
#   python -I fixtures.py <face_landmarker.task> <frames dir> facecamera/src/androidTest/assets/faces
import sys, os, json, shutil
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
from prototype import Pipeline, warp, F

task, frames_dir, out_dir = sys.argv[1:4]
names = ["smile", "speaking", "wide_open", "pout", "small_smile"]
sampler_names = ["smile", "wide_open"]


def rounded(x, digits=7):
    if isinstance(x, list):
        return [rounded(v, digits) for v in x]
    return float("%.*g" % (digits, x))


p = Pipeline(task)
for n in names:
    shutil.copyfile(os.path.join(frames_dir, n + ".png"), os.path.join(out_dir, n + ".png"))
    ref = json.load(open(os.path.join(frames_dir, n + ".mediapipe.json")))
    img = np.asarray(Image.open(os.path.join(frames_dir, n + ".png")).convert("RGB"))
    H, W, _ = img.shape
    rect = p.detect(img)
    out = {
        "source": "MediaPipe 1.2.2 (Python), FaceLandmarker IMAGE mode, face_landmarker.task sha256 64184e22...",
        "width": ref["width"], "height": ref["height"], "face": ref["face"],
        "landmarks": rounded(ref["landmarks"]),
        "blendshapes": {k: rounded(v) for k, v in ref["blendshapes"].items()},
        "matrix": rounded(ref["matrix"]),
        "prototype_detection_rect": [float(v) for v in rect],
    }
    json.dump(out, open(os.path.join(out_dir, n + ".json"), "w"), separators=(",", ":"))
    if n in sampler_names:
        cx, cy = F(0.5) * F(W), F(0.5) * F(H)
        if F(1.0) > F(H) / F(W):
            nw, nh = F(W), F(W)
        else:
            nw, nh = F(H), F(H)
        Image.fromarray(warp(img, cx, cy, nw, nh, 0.0, 128, 128, "zero")).save(os.path.join(out_dir, n + ".detector_input.png"))
        rcx, rcy, rw, rh, rrot = rect
        Image.fromarray(warp(img, rcx * F(W), rcy * F(H), rw * F(W), rh * F(H), rrot, 256, 256, "replicate")).save(
            os.path.join(out_dir, n + ".landmark_input.png"))
    print(n, "rect", [round(float(v), 4) for v in rect])
