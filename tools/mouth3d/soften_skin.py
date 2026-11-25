"""Retouch the scanned skin texture: no stubble, no blemishes, an even young skin tone; the lips stay sharp.

Run from the repo root:  python tools/mouth3d/soften_skin.py
Reads the scan's original texture from git (HEAD of the commit before the retouch is kept in ORIGINAL below).
"""
import subprocess, io, sys
import numpy as np
import cv2

DIR = 'mouth3d/src/main/resources/fonia/perioral/'
ORIGINAL = 'd436488'  # the commit that holds the untouched scan

def original(name, flags):
    raw = subprocess.run(['git', 'show', f'{ORIGINAL}:{DIR}{name}'], capture_output=True, check=True).stdout
    return cv2.imdecode(np.frombuffer(raw, np.uint8), flags)

img = original('pigment.jpg', cv2.IMREAD_COLOR)
h, w = img.shape[:2]
lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.float32)

# Lips: the red area around the mouth, feathered.
yy, xx = np.mgrid[0:h, 0:w]
near = (((xx - w * 0.5) / (w * 0.16)) ** 2 + ((yy - h * 0.525) / (h * 0.09)) ** 2) < 1
red = lab[..., 1] > 150
lips = cv2.morphologyEx((near & red).astype(np.uint8) * 255, cv2.MORPH_CLOSE, np.ones((25, 25), np.uint8))
lips = cv2.GaussianBlur(lips, (0, 0), 9).astype(np.float32) / 255

# Skin: the hair dots go with a median, the beard shadow by pulling the tone to the clean cheek.
flat = cv2.medianBlur(img, 21)
flat = cv2.bilateralFilter(flat, 15, 40, 15)
low = cv2.GaussianBlur(cv2.cvtColor(flat, cv2.COLOR_BGR2LAB).astype(np.float32), (0, 0), 28)
ref = np.median(lab[int(h * 0.20):int(h * 0.30), int(w * 0.27):int(w * 0.37)].reshape(-1, 3), axis=0)
ref = ref + np.array([6, 1.5, 0], np.float32)  # a touch lighter and rosier
skin = ref + (low - ref) * np.array([0.30, 0.45, 0.45], np.float32)
rng = np.random.default_rng(7)
skin[..., 0] += cv2.GaussianBlur(rng.normal(0, 2.2, (h, w)).astype(np.float32), (0, 0), 0.8)

# Lips: kept, a little fuller in colour and without the dark seam of the scan.
lip_lab = cv2.cvtColor(cv2.bilateralFilter(img, 9, 30, 9), cv2.COLOR_BGR2LAB).astype(np.float32)
lip_lab[..., 1] += 4
lip_lab[..., 0] += 3

out = skin * (1 - lips[..., None]) + lip_lab * lips[..., None]
out = cv2.cvtColor(np.clip(out, 0, 255).astype(np.uint8), cv2.COLOR_LAB2BGR)
cv2.imwrite(DIR + 'pigment.jpg', out, [cv2.IMWRITE_JPEG_QUALITY, 92])

# The relief map carries the stubble as bumps: smooth it everywhere but the lips.
rel = original('relief.png', cv2.IMREAD_UNCHANGED)
rh, rw = rel.shape[:2]
m = cv2.resize(lips, (rw, rh))
soft = cv2.GaussianBlur(rel, (0, 0), max(2.0, rw / 512))
if rel.ndim == 3:
    m = m[..., None]
cv2.imwrite(DIR + 'relief.png', np.clip(soft * (1 - m) + rel * m, 0, 255 if rel.dtype == np.uint8 else 65535).astype(rel.dtype))
print('ok', img.shape, rel.shape, rel.dtype)
