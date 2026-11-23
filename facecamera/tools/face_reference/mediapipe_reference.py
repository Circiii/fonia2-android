# Reference outputs from the official MediaPipe Face Landmarker (Python 1.2.2, IMAGE mode) on the test frames:
# <name>.mediapipe.json next to each <name>.png. pip install mediapipe==1.2.2 ai-edge-litert==2.3.0 numpy pillow
#
#   python -I mediapipe_reference.py <face_landmarker.task> <frames dir> smile speaking wide_open pout small_smile
import sys, json, os
import numpy as np
from PIL import Image
import mediapipe as mp
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions

task, outdir = sys.argv[1], sys.argv[2]
names = sys.argv[3:]

def landmarker(mode=vision.RunningMode.IMAGE):
    opts = vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=task),
        running_mode=mode, num_faces=1,
        output_face_blendshapes=True, output_facial_transformation_matrixes=True)
    return vision.FaceLandmarker.create_from_options(opts)

def result_json(res, w, h):
    if not res.face_landmarks:
        return {"width": w, "height": h, "face": False}
    lm = res.face_landmarks[0]
    out = {"width": w, "height": h, "face": True,
           "landmarks": [[p.x, p.y, p.z] for p in lm],
           "blendshapes": {c.category_name: c.score for c in res.face_blendshapes[0]},
           "matrix": np.array(res.facial_transformation_matrixes[0]).astype(float).tolist()}
    return out

if __name__ == "__main__":
    with landmarker() as fl:
        for n in names:
            path = os.path.join(outdir, n + ".png")
            rgb = np.asarray(Image.open(path).convert("RGB"))
            img = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))
            res = fl.detect(img)
            j = result_json(res, rgb.shape[1], rgb.shape[0])
            json.dump(j, open(os.path.join(outdir, n + ".mediapipe.json"), "w"))
            if j["face"]:
                b = j["blendshapes"]
                print(n, "jawOpen %.3f smileL %.3f smileR %.3f funnel %.3f pucker %.3f puff %.3f" % (
                    b["jawOpen"], b["mouthSmileLeft"], b["mouthSmileRight"], b["mouthFunnel"], b["mouthPucker"], b["cheekPuff"]),
                    "matrix row-major first row", [round(x, 3) for x in j["matrix"][0]])
            else:
                print(n, "no face")
