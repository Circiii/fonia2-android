"""Offline previews of the 3D mouth.

The mouth the app draws is rendered by the module's JVM preview, a software copy of the GL shader and of its passes
(cut plane, stencil parity, stencil-tested caps): mouth3d/src/test/.../StylePreview.kt, kept identical to
render/StyleGl.kt. This script runs it:

    python -I tools/mouth3d/preview.py style OUT_DIR [--only s,sh] [--clip rotate|front]

writes OUT_DIR/sheet.png (every pose: front, 45 degrees, profile, cut-away), one PNG per view, closer looks (closures,
highlights, other angles), OUT_DIR/timing.txt and, with --clip, OUT_DIR/clip_<kind>.mp4 (needs ffmpeg). JAVA_HOME must
point at a JDK 17+ (Android Studio's jbr).
"""
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def style(argv):
    out = Path(argv[0]).resolve()
    only = argv[argv.index('--only') + 1] if '--only' in argv else ''
    clip = argv[argv.index('--clip') + 1] if '--clip' in argv else ''
    gradlew = REPO / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    cmd = [str(gradlew), ':core:mouth3d:testDebugUnitTest', '--tests', '*StylePreviewTest*', '--console=plain', '-q',
           f'-Pmouth3d.previewDir={out.as_posix()}', f'-Pmouth3d.only={only}', f'-Pmouth3d.clip={clip}']
    subprocess.run(cmd, cwd=REPO, check=True)
    if clip:
        subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-framerate', '30', '-i', str(out / f'clip_{clip}' / 'f%04d.png'),
                        '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', str(out / f'clip_{clip}.mp4')], check=True)
    print(out / 'sheet.png')


def main():
    if len(sys.argv) < 3 or sys.argv[1] != 'style':
        print(__doc__)
        sys.exit(2)
    style(sys.argv[2:])


if __name__ == '__main__':
    main()
