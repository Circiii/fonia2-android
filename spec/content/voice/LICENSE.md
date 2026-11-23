# Foni's voice: source, licence, attribution

- **What:** the synthetic clips in `clips/` (Foni's lines, the model words, chapter openings, hold prompts,
  sentences, story lines, missions, twisters). Index: `index.json` (key → file, text, voice, checked).
- **How:** generated on 2026-11-21 on a PC with ElevenLabs text-to-speech, model `eleven_multilingual_v2`,
  language `ro`, premade library voice **"Matilda"** (`XrExE9yKIg1WjnnlVkGX`), stability 0.6, similarity 0.75,
  style 0, speaker boost on. Chosen by an audition of three premade voices (Lily, Sarah, Matilda) on the same two
  lines; Matilda and Lily transcribed perfectly (ș, ț, ă, î), Matilda has the calmer pace. No voice was cloned.
- **Licence:** generated under the project owner's paid ElevenLabs subscription (Creator tier), whose terms grant
  commercial use of the generated audio. The app plays the files offline; nothing is sent anywhere at run time.
  Re-check the ElevenLabs terms before a store release. Suggested credit in the app's credits:
  „Vocea lui Foni: sinteză vocală ElevenLabs.”
- **Not synthesised (Q9):** isolated sounds (sss, șșș, zzz, jjj, lll, rrr, ț-ț-ț, ci-ci-ci, gi-gi-gi), bare
  syllables and syllable trains, Foni's mixed-up words for `listen`, and lines that end in a bare sound. These are
  marked `"voice": "human-needed"` in the content and must be recorded by a person (ideally the speech therapist).
- **Checks:** `tools/content/voice_check.py` (duration, clipping, loudness at -19 LUFS, transcript by a local
  faster-whisper "medium" model, MIT, run on the PC for evaluation only). Clips that failed are not in the index.
- **Format:** AAC-LC in .m4a, mono, 44.1 kHz, 48 kbps, peak at or below -1.5 dBFS, 60 ms silence before and
  120 ms after the speech.
