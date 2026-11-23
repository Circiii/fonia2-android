# Stream (VIDEO) mode: tracking from the previous frame's landmarks plus One Euro smoothing, checked against
# MediaPipe's VIDEO running mode on a sequence of exact crops (integer offsets) and plain gray frames. Writes the
# reference StreamParityTests reads.
#
#   python -I prototype_stream.py <face_landmarker.task> <frames dir>/smile.png <faces fixtures dir>/sequence.json
import sys, os, json, math
import numpy as np
from PIL import Image
import mediapipe as mp
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions
sys.path.insert(0, os.path.dirname(__file__))
from prototype import Pipeline, NAMES, F

CANVAS_W, CANVAS_H, PLACE_X, PLACE_Y, GRAY = 640, 800, 80, 80, 128
FRAME_W, FRAME_H = 480, 640
# (offset_x, offset_y) for a crop of the canvas, or None for an all-gray frame.
SEQUENCE = [(80 + 4 * k, 80 + 3 * k) for k in range(12)] + [None, None] + [(124 - 6 * k, 113 - 5 * k) for k in range(10)]
LANDMARK_SUBSET = [1, 13, 14, 33, 61, 133, 152, 199, 263, 291, 362, 468, 473]


def canvas(src_png):
    c = np.full((CANVAS_H, CANVAS_W, 3), GRAY, dtype=np.uint8)
    img = np.asarray(Image.open(src_png).convert("RGB"))
    c[PLACE_Y:PLACE_Y + img.shape[0], PLACE_X:PLACE_X + img.shape[1]] = img
    return c


def frames(src_png):
    c = canvas(src_png)
    for off in SEQUENCE:
        if off is None:
            yield np.full((FRAME_H, FRAME_W, 3), GRAY, dtype=np.uint8)
        else:
            x, y = off
            yield np.ascontiguousarray(c[y:y + FRAME_H, x:x + FRAME_W])


class OneEuro:
    def __init__(self, frequency=30.0, min_cutoff=0.05, beta=80.0, dcutoff=1.0):
        self.frequency, self.min_cutoff, self.beta, self.dcutoff = frequency, min_cutoff, beta, dcutoff
        self.last_time = -1
        self.x_init = False
        self.dx_init = False

    def alpha(self, cutoff):
        te = 1.0 / self.frequency
        tau = 1.0 / (2 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / te)

    def lp_x(self, value, a):
        a = F(a)
        if self.x_init:
            r = F(a * F(value) + (1.0 - a) * self.x_stored)
        else:
            r = F(value)
            self.x_init = True
        self.x_raw = F(value)
        self.x_stored = r
        return r

    def lp_dx(self, value, a):
        a = F(a)
        if self.dx_init:
            r = F(a * F(value) + (1.0 - a) * self.dx_stored)
        else:
            r = F(value)
            self.dx_init = True
        self.dx_stored = r
        return r

    def apply(self, t_ns, value, value_scale):
        if self.last_time >= t_ns:
            return value
        if self.last_time != 0 and t_ns != 0:
            self.frequency = 1.0 / ((t_ns - self.last_time) * 1e-9)
        self.last_time = t_ns
        dvalue = (float(value) - float(self.x_raw)) * value_scale * self.frequency if self.x_init else 0.0
        edvalue = float(self.lp_dx(dvalue, self.alpha(self.dcutoff)))
        cutoff = self.min_cutoff + self.beta * abs(edvalue)
        return float(self.lp_x(value, self.alpha(cutoff)))


class Stream:
    def __init__(self, p):
        self.p = p
        self.prev = None
        self.filters = None

    def process(self, img, t_us):
        H, W, _ = img.shape
        rect = self.prev if self.prev is not None else self.p.detect(img)
        if rect is None:
            self.filters = None
            return None
        lm, presence, tongue = self.p.landmarks(img, rect)
        if lm is None:
            self.prev = None
            self.filters = None
            return None
        self.prev = self.p.next_rect(lm, W, H)
        px = lm[:, 0] * F(W)
        py = lm[:, 1] * F(H)
        pz = lm[:, 2] * F(W)
        scale = (float(px.max() - px.min()) + float(py.max() - py.min())) / 2.0
        value_scale = float(F(1.0) / F(scale))
        if self.filters is None:
            self.filters = [[OneEuro() for _ in range(3)] for _ in range(478)]
        t_ns = t_us * 1000
        out = np.zeros_like(lm)
        for i in range(478):
            out[i, 0] = F(self.filters[i][0].apply(t_ns, px[i], value_scale)) / F(W)
            out[i, 1] = F(self.filters[i][1].apply(t_ns, py[i], value_scale)) / F(H)
            out[i, 2] = F(self.filters[i][2].apply(t_ns, pz[i], value_scale)) / F(W)
        return {"landmarks": out, "blendshapes": self.p.blendshapes(out, W, H), "matrix": self.p.geometry(out, W, H)}


def mediapipe_video(task, src_png):
    opts = vision.FaceLandmarkerOptions(base_options=BaseOptions(model_asset_path=task), running_mode=vision.RunningMode.VIDEO,
                                        num_faces=1, output_face_blendshapes=True, output_facial_transformation_matrixes=True)
    out = []
    with vision.FaceLandmarker.create_from_options(opts) as fl:
        for k, f in enumerate(frames(src_png)):
            r = fl.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=f), 1000 + 33 * k)
            if not r.face_landmarks:
                out.append(None)
                continue
            out.append({"landmarks": np.array([[p.x, p.y, p.z] for p in r.face_landmarks[0]], dtype=F),
                        "blendshapes": np.array([c.score for c in r.face_blendshapes[0]], dtype=F),
                        "matrix": np.array(r.facial_transformation_matrixes[0], dtype=F)})
    return out


if __name__ == "__main__":
    task, src_png, out_json = sys.argv[1:4]
    ref = mediapipe_video(task, src_png)
    s = Stream(Pipeline(task))
    worst = 0.0
    for k, f in enumerate(frames(src_png)):
        ours = s.process(f, (1000 + 33 * k) * 1000)
        r = ref[k]
        if (ours is None) != (r is None):
            print(k, "presence differs", ours is None, r is None)
            continue
        if r is None:
            print(k, "no face (both)")
            continue
        dl = np.abs(ours["landmarks"] - r["landmarks"]).max()
        db = np.abs(ours["blendshapes"] - r["blendshapes"]).max()
        dm = np.abs(ours["matrix"][:3, :3] - r["matrix"][:3, :3]).max()
        worst = max(worst, db)
        print("%2d lm %.2e bs %.2e rot %.2e  jawOpen %.4f smile %.4f" % (k, dl, db, dm, r["blendshapes"][25], r["blendshapes"][44]))
    print("worst blendshape diff", worst)
    frames_json = []
    for r in ref:
        if r is None:
            frames_json.append(None)
        else:
            frames_json.append({"landmarks": {str(i): [float(v) for v in r["landmarks"][i]] for i in LANDMARK_SUBSET},
                                "blendshapes": {NAMES[i]: float(r["blendshapes"][i]) for i in range(52)},
                                "matrix": r["matrix"].astype(float).tolist()})
    json.dump({"canvas": {"width": CANVAS_W, "height": CANVAS_H, "x": PLACE_X, "y": PLACE_Y, "gray": GRAY},
               "frame": {"width": FRAME_W, "height": FRAME_H},
               "timestamps_ms": [1000 + 33 * k for k in range(len(SEQUENCE))],
               "offsets": [list(o) if o is not None else None for o in SEQUENCE],
               "frames": frames_json}, open(out_json, "w"))
