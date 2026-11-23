# How far do two runs of the same stream drift when the first frame's landmarks differ by ~1e-6? (StreamParityTests)
#
#   python -I sensitivity.py <face_landmarker.task> <frames dir>/smile.png
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from prototype import Pipeline, F
from prototype_stream import Stream, frames
task, src = sys.argv[1], sys.argv[2]
p = Pipeline(task)
a, b = Stream(p), Stream(p)
orig = p.landmarks
for k, f in enumerate(frames(src)):
    ra = a.process(f, (1000 + 33 * k) * 1000)
    if k == 0:
        def nudged(img, rect):
            lm, pr, t = orig(img, rect)
            return (None if lm is None else lm + F(1e-6)), pr, t
        p.landmarks = nudged
    rb = b.process(f, (1000 + 33 * k) * 1000)
    p.landmarks = orig
    if ra is None or rb is None:
        print(k, "none"); continue
    print("%2d lm %.2e bs %.2e" % (k, np.abs(ra["landmarks"] - rb["landmarks"]).max(), np.abs(ra["blendshapes"] - rb["blendshapes"]).max()))
