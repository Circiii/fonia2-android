# The Kotlin pipeline written first in numpy, on the same models through LiteRT's Python interpreter, and checked
# against the official MediaPipe Face Landmarker: the place to try a change to the pre- or post-processing quickly.
# FrameSamplerTests compares the Kotlin sampler with this one pixel for pixel.
#
#   python -I prototype.py <face_landmarker.task> <frames dir> smile speaking wide_open pout small_smile
# (needs <name>.png and <name>.mediapipe.json from mediapipe_reference.py in the frames dir)
import sys, os, json, math, struct, zipfile
import numpy as np
from PIL import Image
from ai_edge_litert.interpreter import Interpreter

F = np.float32


def _varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7f) << s
        s += 7
        if c < 0x80:
            return r, i


def _fields(b):
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        f, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 5:
            v = b[i:i + 4]
            i += 4
        elif wt == 1:
            v = b[i:i + 8]
            i += 8
        elif wt == 2:
            n, i = _varint(b, i)
            v = b[i:i + n]
            i += n
        else:
            raise ValueError(wt)
        yield f, wt, v


def geometry_metadata(b):
    """geometry_pipeline_metadata_landmarks.binarypb: canonical vertices (468 x 3) and Procrustes weights (468)."""
    vertices, weights = [], np.zeros(468, dtype=F)
    for f, wt, v in _fields(b):
        if f == 1:
            for f2, wt2, v2 in _fields(v):
                if f2 == 3:
                    vertices += list(struct.unpack("<%df" % (len(v2) // 4), v2)) if wt2 == 2 else [struct.unpack("<f", v2)[0]]
        elif f == 2:
            ident, weight = 0, 0.0
            for f2, wt2, v2 in _fields(v):
                if f2 == 1:
                    ident = v2
                elif f2 == 2:
                    weight = struct.unpack("<f", v2)[0]
            weights[ident] = weight
    positions = np.array(vertices, dtype=F).reshape(-1, 5)[:, :3]
    return positions, weights

LANDMARKS_SUBSET = [0, 1, 4, 5, 6, 7, 8, 10, 13, 14, 17, 21, 33, 37, 39, 40, 46, 52, 53, 54, 55, 58, 61, 63, 65, 66, 67, 70, 78, 80,
    81, 82, 84, 87, 88, 91, 93, 95, 103, 105, 107, 109, 127, 132, 133, 136, 144, 145, 146, 148, 149, 150, 152, 153, 154, 155, 157, 158, 159, 160,
    161, 162, 163, 168, 172, 173, 176, 178, 181, 185, 191, 195, 197, 234, 246, 249, 251, 263, 267, 269, 270, 276, 282, 283, 284, 285, 288, 291, 293, 295,
    296, 297, 300, 308, 310, 311, 312, 314, 317, 318, 321, 323, 324, 332, 334, 336, 338, 356, 361, 362, 365, 373, 374, 375, 377, 378, 379, 380, 381, 382,
    384, 385, 386, 387, 388, 389, 390, 397, 398, 400, 402, 405, 409, 415, 454, 466, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477]

NAMES = ["_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight", "cheekPuff",
    "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft",
    "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
    "eyeWideLeft", "eyeWideRight", "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight",
    "mouthFrownLeft", "mouthFrownRight", "mouthFunnel", "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight", "mouthPressLeft",
    "mouthPressRight", "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper", "mouthShrugLower", "mouthShrugUpper",
    "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight", "mouthUpperUpLeft", "mouthUpperUpRight",
    "noseSneerLeft", "noseSneerRight"]


# ---- image to tensor: OpenCV warpPerspective INTER_LINEAR in fixed point (INTER_BITS=5, COEF_BITS=15) ----
def box_points(cx, cy, w, h, rot):
    # roi.rotation * 180.f / M_PI: a float product divided by the double pi, stored as float.
    angle_deg = F(float(F(rot) * F(180.0)) / math.pi)
    a_rad = float(angle_deg) * math.pi / 180.0
    b = F(math.cos(a_rad)) * F(0.5)
    a = F(math.sin(a_rad)) * F(0.5)
    cx, cy, w, h = F(cx), F(cy), F(w), F(h)
    p0 = (cx - a * h - b * w, cy + b * h - a * w)
    p1 = (cx + a * h - b * w, cy - b * h - a * w)
    p2 = (F(2) * cx - p0[0], F(2) * cy - p0[1])
    p3 = (F(2) * cx - p1[0], F(2) * cy - p1[1])
    return [p0, p1, p2, p3]


def warp(img, cx, cy, w, h, rot, out_w, out_h, border):
    """img: HxWxC uint8 -> out_h x out_w x C uint8, emulating cv::warpPerspective(INTER_LINEAR)."""
    pts = box_points(cx, cy, w, h, rot)
    p0 = np.array([float(pts[0][0]), float(pts[0][1])])
    p1 = np.array([float(pts[1][0]), float(pts[1][1])])
    p2 = np.array([float(pts[2][0]), float(pts[2][1])])
    ax = (p2 - p1) / out_w
    ay = (p0 - p1) / out_h
    H, W, C = img.shape
    u = np.arange(out_w, dtype=float)[None, :]
    v = np.arange(out_h, dtype=float)[:, None]
    X = p1[0] + ax[0] * u + ay[0] * v
    Y = p1[1] + ax[1] * u + ay[1] * v
    Xi = np.rint(X * 32).astype(np.int64)
    Yi = np.rint(Y * 32).astype(np.int64)
    sx = Xi >> 5
    sy = Yi >> 5
    fx = Xi & 31
    fy = Yi & 31
    w00 = (32 - fy) * (32 - fx) * 32
    w01 = (32 - fy) * fx * 32
    w10 = fy * (32 - fx) * 32
    w11 = fy * fx * 32
    exact = (fx == 0) & (fy == 0)
    w00 = np.where(exact, 32767, w00)
    w11 = np.where(exact, 1, w11)

    def fetch(yy, xx):
        vals = img[np.clip(yy, 0, H - 1), np.clip(xx, 0, W - 1)].astype(np.int64)
        if border == "replicate":
            return vals
        inside = (xx >= 0) & (xx < W) & (yy >= 0) & (yy < H)
        return np.where(inside[..., None], vals, 0)

    acc = (fetch(sy, sx) * w00[..., None] + fetch(sy, sx + 1) * w01[..., None]
           + fetch(sy + 1, sx) * w10[..., None] + fetch(sy + 1, sx + 1) * w11[..., None])
    out = (acc + (1 << 14)) >> 15
    if border == "zero":
        far = (sx >= W) | (sx + 1 < 0) | (sy >= H) | (sy + 1 < 0)
        out = np.where(far[..., None], 0, out)
    return np.clip(out, 0, 255).astype(np.uint8)


def to_tensor(pix, lo, hi):
    scale = F((hi - lo) / 255.0)
    offset = F(lo)
    return (pix.astype(F) * scale + offset).astype(F)[None]


def make_anchors():
    out = []
    strides = [8, 16, 16, 16]
    layer = 0
    while layer < 4:
        last = layer
        n = 0
        while last < 4 and strides[last] == strides[layer]:
            n += 2
            last += 1
        fm = math.ceil(128 / strides[layer])
        for y in range(fm):
            for x in range(fm):
                for _ in range(n):
                    out.append(((x + 0.5) / fm, (y + 0.5) / fm))
        layer = last
    return np.array(out, dtype=F)


ANCHORS = make_anchors()
assert len(ANCHORS) == 896


def normalize_radians(a):
    return F(a - 2 * math.pi * math.floor((a - (-math.pi)) / (2 * math.pi)))


def sigmoid(x):
    return F(1.0) / (F(1.0) + np.exp(-F(x)))


def iou(a, b):
    ax0, ay0, aw, ah = a
    bx0, by0, bw, bh = b
    ix0, iy0 = max(ax0, bx0), max(ay0, by0)
    ix1, iy1 = min(ax0 + aw, bx0 + bw), min(ay0 + ah, by0 + bh)
    if ix1 < ix0 or iy1 < iy0:
        return 0.0
    inter = (ix1 - ix0) * (iy1 - iy0)
    norm = aw * ah + bw * bh - inter
    return inter / norm if norm > 0 else 0.0


class Pipeline:
    def __init__(self, task):
        bundle = zipfile.ZipFile(task)
        self.det = Interpreter(model_content=bundle.read("face_detector.tflite"))
        self.det.allocate_tensors()
        self.lmk = Interpreter(model_content=bundle.read("face_landmarks_detector.tflite"))
        self.lmk.allocate_tensors()
        self.bs = Interpreter(model_content=bundle.read("face_blendshapes.tflite"))
        self.bs.allocate_tensors()
        positions, self.weights = geometry_metadata(bundle.read("geometry_pipeline_metadata_landmarks.binarypb"))
        self.canonical = positions.T.copy()

    def run(self, interp, x):
        interp.set_tensor(interp.get_input_details()[0]["index"], x)
        interp.invoke()
        return [interp.get_tensor(d["index"]) for d in interp.get_output_details()]

    def detect(self, img):
        H, W, _ = img.shape
        cx, cy = F(0.5) * F(W), F(0.5) * F(H)
        tensor_ar = F(1.0)
        roi_ar = F(H) / F(W)
        if tensor_ar > roi_ar:
            nw, nh = F(W), F(W) * tensor_ar
        else:
            nw, nh = F(H) / tensor_ar, F(H)
        pix = warp(img, cx, cy, nw, nh, 0.0, 128, 128, "zero")
        boxes, scores = self.run(self.det, to_tensor(pix, -1.0, 1.0))
        raw = boxes[0]
        sc = sigmoid(np.clip(scores[0, :, 0], -100, 100))
        dets = []
        for i in range(896):
            if sc[i] < 0.5:
                continue
            ax, ay = ANCHORS[i]
            xc = raw[i, 0] / F(128) + ax
            yc = raw[i, 1] / F(128) + ay
            w = raw[i, 2] / F(128)
            h = raw[i, 3] / F(128)
            xmin, ymin, xmax, ymax = xc - w / F(2), yc - h / F(2), xc + w / F(2), yc + h / F(2)
            kps = [(raw[i, 4 + 2 * k] / F(128) + ax, raw[i, 5 + 2 * k] / F(128) + ay) for k in range(6)]
            if xmax - xmin < 0 or ymax - ymin < 0:
                continue
            dets.append((F(sc[i]), [xmin, ymin, xmax - xmin, ymax - ymin], kps))
        if not dets:
            return None
        order = sorted(range(len(dets)), key=lambda k: -dets[k][0])
        top = dets[order[0]]
        cand = [dets[k] for k in order if iou(dets[k][1], top[1]) > 0.5]
        tot = F(0)
        wx0 = wy0 = wx1 = wy1 = F(0)
        kp = np.zeros(12, dtype=F)
        for s, b, k in cand:
            tot += s
            wx0 += b[0] * s
            wy0 += b[1] * s
            wx1 += (b[0] + b[2]) * s
            wy1 += (b[1] + b[3]) * s
            for j in range(6):
                kp[2 * j] += k[j][0] * s
                kp[2 * j + 1] += k[j][1] * s
        xmin = wx0 / tot
        ymin = wy0 / tot
        bw = wx1 / tot - xmin
        bh = wy1 / tot - ymin
        kp = kp / tot
        m0 = nw / F(W)
        m3 = (F(-0.5) * nw + cx) / F(W)
        m5 = nh / F(H)
        m7 = (F(-0.5) * nh + cy) / F(H)
        px = lambda x: x * m0 + m3
        py = lambda y: y * m5 + m7
        corners = [(px(xmin), py(ymin)), (px(xmin + bw), py(ymin)), (px(xmin + bw), py(ymin + bh)), (px(xmin), py(ymin + bh))]
        x0 = min(c[0] for c in corners)
        y0 = min(c[1] for c in corners)
        x1 = max(c[0] for c in corners)
        y1 = max(c[1] for c in corners)
        kpp = [(px(kp[2 * j]), py(kp[2 * j + 1])) for j in range(6)]
        rect = [x0 + (x1 - x0) / F(2), y0 + (y1 - y0) / F(2), x1 - x0, y1 - y0]
        ex0, ey0 = kpp[0][0] * F(W), kpp[0][1] * F(H)
        ex1, ey1 = kpp[1][0] * F(W), kpp[1][1] * F(H)
        rot = normalize_radians(0 - math.atan2(-(ey1 - ey0), ex1 - ex0))
        return [F(rect[0]), F(rect[1]), F(rect[2]) * F(1.5), F(rect[3]) * F(1.5), F(rot)]

    def landmarks(self, img, rect):
        H, W, _ = img.shape
        cx, cy, w, h, rot = rect
        pcx, pcy, pw, ph = cx * F(W), cy * F(H), w * F(W), h * F(H)
        pix = warp(img, pcx, pcy, pw, ph, rot, 256, 256, "replicate")
        lm, pres, tongue = self.run(self.lmk, to_tensor(pix, 0.0, 1.0))
        presence = float(sigmoid(pres.reshape(-1)[0]))
        if not presence > 0.5:
            return None, presence, float(tongue.reshape(-1)[0])
        raw = lm.reshape(478, 3)
        nx = raw[:, 0] / F(256)
        ny = raw[:, 1] / F(256)
        nz = raw[:, 2] / F(256)
        c, d = F(math.cos(rot)), F(math.sin(rot))
        m0 = pw * c / F(W)
        m1 = -ph * d / F(W)
        m3 = (F(-0.5) * pw * c + F(0.5) * ph * d + pcx) / F(W)
        m4 = pw * d / F(H)
        m5 = ph * c / F(H)
        m7 = (F(-0.5) * ph * c - F(0.5) * pw * d + pcy) / F(H)
        zs = F(math.sqrt(float(m0) ** 2 + float(m4) ** 2))
        out = np.stack([nx * m0 + ny * m1 + m3, nx * m4 + ny * m5 + m7, zs * nz], axis=1).astype(F)
        return out, presence, float(tongue.reshape(-1)[0])

    def next_rect(self, lm, W, H):
        x0, x1 = lm[:, 0].min(), lm[:, 0].max()
        y0, y1 = lm[:, 1].min(), lm[:, 1].max()
        cx, cy, w, h = x0 + (x1 - x0) / F(2), y0 + (y1 - y0) / F(2), x1 - x0, y1 - y0
        ex0, ey0 = lm[33, 0] * F(W), lm[33, 1] * F(H)
        ex1, ey1 = lm[263, 0] * F(W), lm[263, 1] * F(H)
        rot = normalize_radians(0 - math.atan2(-(ey1 - ey0), ex1 - ex0))
        long_side = max(w * F(W), h * F(H))
        w = long_side / F(W)
        h = long_side / F(H)
        return [F(cx), F(cy), F(w) * F(1.5), F(h) * F(1.5), F(rot)]

    def blendshapes(self, lm, W, H):
        pts = np.stack([lm[LANDMARKS_SUBSET, 0] * F(W), lm[LANDMARKS_SUBSET, 1] * F(H)], axis=1).astype(F)[None]
        return self.run(self.bs, pts)[0].reshape(-1)

    def geometry(self, lm, W, H):
        near = F(1.0)
        hn = F(2.0) * near * F(math.tan(F(0.5) * (F(3.14159265358979323846) / F(180)) * F(63.0)))
        wn = F(W) * hn / F(H)
        left, right, bottom, top = F(-0.5) * wn, F(0.5) * wn, F(-0.5) * hn, F(0.5) * hn
        s = lm[:468].T.astype(F).copy()
        s[1] = F(1) - s[1]
        s[0] *= (right - left)
        s[1] *= (top - bottom)
        s[2] *= (right - left)
        s[0] += left
        s[1] += bottom
        depth_offset = s[2].mean(dtype=F)
        inter = s.copy()
        inter[2] *= -1
        scale1 = F(np.linalg.norm(self.procrustes(self.canonical, inter, self.weights)[:3, 0]))
        inter = s.copy()
        inter[2] = (inter[2] - depth_offset + near) / scale1
        inter[0] = inter[0] * inter[2] / near
        inter[1] = inter[1] * inter[2] / near
        inter[2] *= -1
        scale2 = F(np.linalg.norm(self.procrustes(self.canonical, inter, self.weights)[:3, 0]))
        total = scale1 * scale2
        m = s.copy()
        m[2] = (m[2] - depth_offset + near) / total
        m[0] = m[0] * m[2] / near
        m[1] = m[1] * m[2] / near
        m[2] *= -1
        return self.procrustes(self.canonical, m, self.weights)

    @staticmethod
    def procrustes(src, tgt, w):
        sw = np.sqrt(w).astype(F)
        ws = src * sw
        wt = tgt * sw
        total = (sw * sw).sum(dtype=F)
        com = (ws * sw).sum(axis=1, dtype=F) / total
        cws = ws - com[:, None] * sw[None, :]
        design = (wt @ cws.T).astype(F)
        U, S_, Vt = np.linalg.svd(design.astype(np.float64))
        if np.linalg.det(U) * np.linalg.det(Vt) < 0:
            U[:, 2] *= -1
        R = (U @ Vt).astype(F)
        num = ((R @ cws) * wt).sum(dtype=F)
        den = (cws * ws).sum(dtype=F)
        RS = (num / den) * R
        diffs = (wt - RS @ ws) * sw
        t = diffs.sum(axis=1, dtype=F) / total
        M = np.eye(4, dtype=F)
        M[:3, :3] = RS
        M[:3, 3] = t
        return M

    def image_mode(self, img):
        H, W, _ = img.shape
        rect = self.detect(img)
        if rect is None:
            return None
        lm, presence, tongue = self.landmarks(img, rect)
        if lm is None:
            return None
        return {"landmarks": lm, "blendshapes": self.blendshapes(lm, W, H), "matrix": self.geometry(lm, W, H),
                "presence": presence, "tongue": tongue, "rect": rect}


if __name__ == "__main__":
    task, img_dir = sys.argv[1], sys.argv[2]
    p = Pipeline(task)
    for n in sys.argv[3:]:
        img = np.asarray(Image.open(os.path.join(img_dir, n + ".png")).convert("RGB"))
        ref = json.load(open(os.path.join(img_dir, n + ".mediapipe.json")))
        out = p.image_mode(img)
        if out is None or not ref["face"]:
            print(n, "ours", out is not None, "ref", ref["face"])
            continue
        dl = np.abs(out["landmarks"] - np.array(ref["landmarks"], dtype=F))
        rb = np.array([ref["blendshapes"][k] for k in NAMES], dtype=F)
        db = np.abs(out["blendshapes"] - rb)
        dm = np.abs(out["matrix"] - np.array(ref["matrix"], dtype=F))
        print("%-12s lm max|dxy| %.2e max|dz| %.2e  bs max %.2e (%s)  rot max %.2e trans max %.2e  presence %.3f tongue %.3f" % (
            n, dl[:, :2].max(), dl[:, 2].max(), db.max(), NAMES[int(db.argmax())], dm[:3, :3].max(), dm[:3, 3].max(),
            out["presence"], out["tongue"]))
