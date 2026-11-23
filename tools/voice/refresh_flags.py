#!/usr/bin/env python3
"""Port of refresh_sounds / refresh_english from fonia-app tools/voices/import_clips.py, over content/json.

    python -I tools/voice/refresh_flags.py

sounds.json: a word's "recorded" is true when ro|ro|<word> has a clip. english.json: a minimal pair's "recorded" is
true when both words have all four voices, and a contrast "needsRecording" while none of its pairs is recorded.
Text edits in place (same regexes as iOS), so the JSON layout stays byte-identical to the iOS copy.
"""

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JSON = ROOT / "content/json"
MANIFEST = ROOT / "content/voice/manifest.json"
ENGLISH_VOICES = ["en", "en2", "en3", "en4"]


def normalised(text):
    return " ".join(text.split()).lower()


def read(path):
    return path.read_bytes().decode("utf-8")


def write(path, text):
    path.write_bytes(text.encode("utf-8"))


def refresh_sounds(clips):
    path = JSON / "sounds.json"

    def fix(match):
        recorded = f"ro|ro|{normalised(match.group(2))}" in clips
        return f"{match.group(1)}{'true' if recorded else 'false'}}}"

    write(path, re.sub(r'(\{"text": "([^"]+)"[^{}]*"recorded": )(?:true|false)\}', fix, read(path)))


def refresh_english(clips):
    path = JSON / "english.json"
    text = read(path)
    data = json.loads(text)
    has_all = lambda word: all(f"en|{v}|{normalised(word)}" in clips for v in ENGLISH_VOICES)

    def fix_pair(match):
        recorded = has_all(match.group(2)) and has_all(match.group(3))
        return f"{match.group(1)}{'true' if recorded else 'false'}}}"

    for contrast in data["contrasts"]:
        if contrast["kind"] != "minimalPairs":
            continue
        start = text.index(f'"id": "{contrast["id"]}"')
        end = text.index('"needsRecording"', start)
        block = re.sub(r'(\{"a": "([^"]+)", "b": "([^"]+)"[^{}]*"recorded": )(?:true|false)\}', fix_pair, text[start:end])
        any_recorded = '"recorded": true' in block
        tail = re.match(r'"needsRecording": (?:true|false)', text[end:])
        text = text[:start] + block + f'"needsRecording": {"false" if any_recorded else "true"}' + text[end + tail.end():]
    write(path, text)


def main():
    clips = json.loads(read(MANIFEST))
    refresh_sounds(clips)
    refresh_english(clips)


if __name__ == "__main__":
    main()
