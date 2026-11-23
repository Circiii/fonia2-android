# Downloads the public-domain / CC0 photos from Wikimedia Commons and crops them into phone-like frames (PNG, lossless,
# no ICC profile). Test fixtures only; nothing here ships in the app. Licences are in facecamera/MODELS.md.
#
#   python -I prepare_frames.py <download dir> <frames dir>
import os
import sys
import urllib.request

import numpy as np
from PIL import Image

COMMONS = "https://upload.wikimedia.org/wikipedia/commons/thumb/"
SOURCES = {
    # name: (1280 px thumbnail, licence)
    "kim.jpg": (COMMONS + "3/3e/Jonny_Kim_official_portrait_%28cropped%29.jpg/1280px-Jonny_Kim_official_portrait_%28cropped%29.jpg", "public domain (NASA)"),
    "ride.jpg": (COMMONS + "2/23/Dr._Sally_Ride_-_Flickr_-_NASA_Goddard_Photo_and_Video.jpg/1280px-Dr._Sally_Ride_-_Flickr_-_NASA_Goddard_Photo_and_Video.jpg", "public domain (NASA)"),
    "voice.jpg": (COMMONS + "d/de/Voice_Training_-_Expannding_and_Flexing_the_Vocal_Tract.jpg/1280px-Voice_Training_-_Expannding_and_Flexing_the_Vocal_Tract.jpg", "CC0 (Paul Newham)"),
    "pout.jpg": (COMMONS + "d/d5/Cat_Power_Bonnaroo_2006_Pout_Face.jpg/1280px-Cat_Power_Bonnaroo_2006_Pout_Face.jpg", "public domain (Fsamuels)"),
    "barron.jpg": (COMMONS + "5/50/Kayla_Barron%2C_official_portrait_%282021%29.jpg/1280px-Kayla_Barron%2C_official_portrait_%282021%29.jpg", "public domain (NASA)"),
}

# name: (source, crop box, resize to or None)
FRAMES = {
    "smile": ("kim.jpg", (40, 250, 1240, 1850), (480, 640)),          # frontal smile with teeth
    "speaking": ("ride.jpg", (290, 210, 1110, 1303), (480, 640)),     # mouth half open, eyes to the side
    "wide_open": ("voice.jpg", (0, 0, 1280, 880), (640, 440)),        # jaw wide open, head rolled, landscape frame
    "pout": ("pout.jpg", (330, 60, 810, 700), None),                  # lips pressed, head turned 38°, small face
    "small_smile": ("barron.jpg", (400, 120, 1000, 920), (480, 640)), # small face, broad smile
}

if __name__ == "__main__":
    downloads, out = sys.argv[1], sys.argv[2]
    os.makedirs(downloads, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    for name, (url, licence) in SOURCES.items():
        path = os.path.join(downloads, name)
        if not os.path.exists(path):
            request = urllib.request.Request(url, headers={"User-Agent": "FoniaTestFixtures/1.0 (https://github.com/Circiii)"})
            with urllib.request.urlopen(request) as response, open(path, "wb") as f:
                f.write(response.read())
        print(name, licence)
    for frame, (source, box, size) in FRAMES.items():
        im = Image.open(os.path.join(downloads, source)).convert("RGB").crop(box)
        if size:
            im = im.resize(size, Image.LANCZOS)
        # Without the source's ICC profile: Android would colour-convert on decode, MediaPipe reads the pixels as they are.
        Image.fromarray(np.asarray(im)).save(os.path.join(out, frame + ".png"), optimize=True)
        print(frame, im.size)
