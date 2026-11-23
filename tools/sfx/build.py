"""Builds Fonia's sound-effect set v2 (Android legacy app) from one place.

    <python with numpy+scipy+soundfile> -I tools/sfx/build.py [--only name ...]

v2 (owner, 2026-11-21: "make them much cooler"): the sources are ElevenLabs sound-effects generations
(raw/elevenlabs/v2, picked by earshot in audition.json; v1's raw/elevenlabs/el7 for the card) and earshot's bank
(MIT), with computed layers only underneath (a soft thump under "good", a shimmer on the POP comes from the bank).
Each sound is: key-fitted to C major by resampling (the shift that lands its strongest partials on white keys),
filtered for a phone (high-pass 120-400 Hz, low-pass 8-14 kHz), warmed (soft tanh saturation), given a computed
room (stereo for the rewards, a touch for Foni, dry for UI ticks), trimmed tight, levelled to its tier's max
momentary loudness under a -3 dBTP ceiling, encoded to OGG Vorbis 48 kHz into legacy/design/src/main/assets/sfx/,
and measured again from the OGG. Writes manifest.json; previews + showreel into output/sunete/v2.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import sfxlib as s  # noqa: E402

ROOT = HERE.parent.parent
ASSETS = ROOT / "legacy/design/src/main/assets/sfx"
WORK = HERE / "work"
EL = HERE / "raw/elevenlabs/v2"
PREVIEWS = Path("C:/Users/circi/Desktop/HSIC/output/sunete/v2")

# Max-momentary loudness per tier (LUFS-M); his voice clips measure about -15 LUFS integrated.
TIERS = {"strong": -17.0, "medium": -20.0, "soft": -23.0, "subtle": -26.0}
TP_CEILING = -3.0

BANK_RENDERS = {
    "tick_bglock": ["mallet/glock", "--pitch", "C6"],
    "mirror_bshim": ["shimmer/m3", "--dur", "1.2"],
    "step_bwood": ["knock/wood", "--pitch", "G5"],
}


def bank(name: str) -> np.ndarray:
    """A sound of earshot's bank, rendered once (deterministic: the sound's own seed)."""
    wav = WORK / "bank2" / f"{name}.wav"
    if not wav.exists():
        wav.parent.mkdir(parents=True, exist_ok=True)
        sound, *knobs = BANK_RENDERS[name]
        subprocess.run(["earshot", "sfx", "render", sound, "-o", str(wav), *knobs], check=True, capture_output=True)
    return s.load(wav)


def el(name: str, stereo: bool = False) -> np.ndarray:
    return s.load(EL / f"{name}.mp3", stereo=stereo)


@dataclass
class Spec:
    src: object                 # () -> array
    tier: str
    kind: str                   # elevenlabs | bank | elevenlabs+bank | elevenlabs+computed
    provenance: str
    why: str                    # the earshot reading it was chosen on (audition.json)
    query: str                  # earshot description for verify
    max_s: float
    hp: float = 250.0
    lp: float = 14_000.0
    key: float | None = 150.0   # max key-fit shift in cents; None = not tonal, no fit
    extra_cents: float = 0.0    # a deliberate transposition after the fit (tap variants)
    sat: float = 0.0            # saturation mix
    room: tuple | None = None   # (rt60 s, wet energy ratio, width)
    stereo: bool = False        # keep the source's own stereo (narrowed to 0.8)
    start_s: float = 0.0        # skip this much of the source before looking for the onset
    haptic: str | None = None
    meta: dict = field(default_factory=dict)


def tap_src():
    return el("tap_c")


def good_src():
    x = el("good_c", stereo=True)
    # A soft low thump under the first note: weight on a tablet / headphones, the octave keeps it on a phone.
    return s.place([(0.0, x), (0.004, 0.22 * s.thump("C3", 0.12))])


def pop_src():
    p = el("pop_c")
    p = p / np.max(np.abs(p))
    sh = s.trim(bank("mirror_bshim"), max_s=0.40, fade_out_ms=120)
    sh = sh / np.max(np.abs(sh))
    # The bubble pop, then a tiny confetti shimmer 45 ms later, 14 dB under it.
    return s.place([(0.0, p), (0.045, 0.2 * sh)])


SET: dict[str, Spec] = {
    "tap_1": Spec(tap_src, "subtle", "elevenlabs", "ElevenLabs: 'A single tiny soft bop of pressing a squishy rubber toy button, mid-pitched and round like a little bubble' (v2/tap_c.mp3), a major third up",
                  "'a mouth pop' 4.0, 'a liquid bloop' 3.9, 'a water drop' 3.5, p 0.99 vs harsh/buzzy; body at 750 Hz (a phone plays it); tap_a/tap_d were kicks at 105-120 Hz, tap_b 'a can opening'",
                  "a soft bubble pop", 0.10, hp=300, lp=10000, sat=0.2, extra_cents=400),
    "tap_2": Spec(tap_src, "subtle", "elevenlabs", "same tap, a step up (re-landed on a white key)", "round-robin variant",
                  "a soft bubble pop", 0.10, hp=300, lp=10000, sat=0.2, extra_cents=200),
    "tap_3": Spec(tap_src, "subtle", "elevenlabs", "same tap, a step down (re-landed on a white key)", "round-robin variant",
                  "a soft bubble pop", 0.10, hp=300, lp=10000, sat=0.2, extra_cents=-200),
    "card": Spec(lambda: s.load(HERE / "raw/elevenlabs/el7_page_turn.mp3"), "soft", "elevenlabs",
                 "ElevenLabs (v1): 'a single page of a children's picture book being turned quickly' (el7_page_turn.mp3)",
                 "'a quick swipe' z 3.5, 'a soft quick paper swipe' z 4.0; all 6 v2 whoosh generations read as hits, hiss or 'error buzz'",
                 "a quick swipe", 0.30, hp=400, lp=10000, key=None, room=(0.3, 0.06, 0.6)),
    "nav_forward": Spec(lambda: el("navback_a"), "soft", "elevenlabs", "ElevenLabs: 'Soft low gentle whoosh sliding back...' (v2/navback_a.mp3), a major third up",
                        "the back whoosh ('a fast whoosh' 4.5, 'a short swish' 3.7, p 1.00) raised 400 cents so forward is brighter and quicker: one pair; page_a read as 'a record scratch' 3.5 once processed (p 0.02)",
                        "a soft airy whoosh", 0.30, hp=350, lp=8000, key=None, extra_cents=400, room=(0.3, 0.05, 0.6)),
    "nav_back": Spec(lambda: el("navback_a"), "subtle", "elevenlabs", "ElevenLabs: 'Soft low gentle whoosh sliding back...' (v2/navback_a.mp3)",
                     "'a fast whoosh' 3.2, darker (518 Hz) than forward, so back sounds softer",
                     "a soft airy whoosh", 0.30, hp=300, lp=7000, key=None, room=(0.3, 0.05, 0.6)),
    "mic_open": Spec(lambda: el("mic_b"), "soft", "elevenlabs", "ElevenLabs: 'Tiny bright upward bloop notification... rising water-drop blip' (v2/mic_b.mp3)",
                     "'bubbles underwater' 3.6 / 'a liquid bloop' 3.0, z 3.4 for 'a short cute bubbly blip'; mic_a read 'ice cubes in a glass'; trimmed to 140 ms",
                     "a short cute bubbly blip", 0.14, hp=350, lp=12000, key=150, extra_cents=700),
    "good": Spec(good_src, "strong", "elevenlabs+computed", "ElevenLabs: 'Rewarding success jingle... three fast ascending soft mallet notes on a wooden marimba with a glittering glockenspiel and magic sparkle tail' (v2/good_c.mp3) + computed soft thump",
                 "good_c z 3.3 (p 1.00 vs error/alarm/sad/cheap), 'kalimba' + 'glockenspiel run' tags, brightest of three (1707 Hz); good_a 3.0, good_b 2.2",
                 "a bright happy marimba arpeggio with a sparkling chime", 1.15, hp=110, lp=14000, sat=0.25,
                 room=(0.9, 0.10, 1.0), stereo=True, haptic="success"),
    "almost": Spec(lambda: el("almost_b"), "medium", "elevenlabs", "ElevenLabs: 'Soft hopeful nearly! cue... two quick bouncy kalimba notes going up, plush' (v2/almost_b.mp3)",
                   "z 3.7, 'a kalimba note' 3.9, p 1.00 vs sad/buzz; almost_a 2.2 read as a snare",
                   "two soft rising marimba notes", 0.55, hp=250, lp=12000, sat=0.2, room=(0.5, 0.08, 0.8)),
    "retake": Spec(lambda: el("almost_b"), "soft", "elevenlabs", "ElevenLabs: the 'almost' kalimba motif (v2/almost_b.mp3) a fourth lower and slower",
                   "the same two rising kalimba notes, lower and slower: one family, never a failure; all four retake generations had a low partial read as 'error buzz' (retake_a/c) or read as a triangle / box lid (b/d)",
                   "two soft kalimba notes", 0.6, hp=250, lp=9000, sat=0.15, extra_cents=-500, room=(0.5, 0.08, 0.8)),
    "hold_tick": Spec(lambda: bank("tick_bglock"), "subtle", "bank", "earshot bank: mallet/glock --pitch C6 (played at 6 rates C D E G A C')",
                      "'a glass ting' 3.3, tuned C6 exactly", "a tiny glockenspiel tick", 0.12, hp=400, lp=12000, key=None),
    "pop": Spec(pop_src, "strong", "elevenlabs+bank", "ElevenLabs: 'Single satisfying bubbly pop... plump water-bubble bloop' (v2/pop_c.mp3) + earshot bank shimmer/m3 as the confetti",
                "pop_c z 5.2 ('a mouth pop' 4.1, 'a bubble pop' 3.8): the strongest reading of the whole audition; pop_b read as a kick drum",
                "a juicy cartoon bubble pop", 0.45, hp=250, lp=14000, sat=0.15, room=(0.4, 0.06, 1.0), haptic="pop"),
    "star": Spec(lambda: el("star_c", stereo=True), "medium", "elevenlabs", "ElevenLabs: 'Collecting a magic star... a soft rising harp glissando that lands on a warm round bell chime' (v2/star_c.mp3)",
                 "'a glockenspiel run' 2.7, p 1.00 vs bad alts; the other three stars read mainly as a triangle; low-passed 10 kHz",
                 "a twinkling magical glockenspiel sparkle", 1.0, hp=300, lp=10000, room=(0.8, 0.10, 1.0), stereo=True),
    "levelup": Spec(lambda: el("levelup_b", stereo=True), "strong", "elevenlabs", "ElevenLabs: 'Kids game level complete sparkle: quick ascending glockenspiel and celesta run that blooms into a shimmering wind-chime and bell chord' (v2/levelup_b.mp3)",
                    "'a success chime' 3.1, 'a magic sparkle chime' 2.9, p 1.00; levelup_a darker, read as 'glass harp'",
                    "a rising magical harp glissando with chimes", 1.5, hp=250, lp=13000, sat=0.1, room=(1.0, 0.10, 1.0),
                    stereo=True, haptic="success"),
    "session_end": Spec(lambda: el("end_a", stereo=True), "strong", "elevenlabs", "ElevenLabs: 'Short proud celebratory fanfare... marimba and glockenspiel melody rising C, E, G, high C over a soft lush string pad' (v2/end_a.mp3)",
                        "already in C (C5 E6 C6 D6), 'a kalimba note' 3.3 / 'a glockenspiel run' 2.5, p 0.93; end_b had an Ab, end_c read as 'a doorbell'",
                        "a joyful short orchestral fanfare with mallets and strings", 3.0, hp=200, lp=14000, sat=0.1,
                        room=(1.2, 0.08, 1.0), stereo=True, haptic="pop"),
    "unlock": Spec(lambda: el("unlock_a", stereo=True), "medium", "elevenlabs", "ElevenLabs: 'Magical unlock sound in a kids game: a small soft wooden latch click followed by a sparkling rising chime' (v2/unlock_a.mp3)",
                   "z 3.5 for 'a magical unlock chime', p 1.00; very bright (5.8 kHz) so low-passed at 9 kHz",
                   "a magical unlock chime", 1.0, hp=300, lp=9000, room=(0.7, 0.08, 1.0), stereo=True),
    "mirror_match": Spec(lambda: bank("mirror_bshim"), "medium", "bank", "earshot bank: shimmer/m3 --dur 1.2",
                         "'a success chime' 4.3, 'a magic sparkle chime' 3.6, z 2.5, p 1.00; the 4 ElevenLabs shimmers read as a triangle at 7-10 kHz",
                         "a soft magical shimmer chime", 1.2, hp=300, lp=12000, room=(0.9, 0.12, 1.0)),
    "guide_step": Spec(lambda: bank("step_bwood"), "subtle", "bank", "earshot bank: knock/wood --pitch G5",
                       "'a wooden block hit' 3.6, p 0.99; the ElevenLabs woodblock read as a kalimba / glass ting",
                       "a soft wooden block tap", 0.12, hp=300, lp=10000, key=None),
}

FONI = [
    ("foni_1", "foni_happy", "happy", "'A cute small parrot happy chirp...'", "z 3.1, p_real 0.97"),
    ("foni_2", "foni_budgie", "cheering", "'Close recording of a happy budgerigar warbling a quick excited cheerful burst'", "z 3.0, p 0.99 vs bad, p_real 0.99"),
    ("foni_3", "foni_hello", "hello", "'A sweet young parrot saying hello with a rising two-note whistle'", "z 2.8, p_real 0.95"),
    ("foni_4", "foni_think", "thinking", "'A cute small parrot making a soft thinking murmur, a gentle low hmmm coo'", "z 2.6, p 0.96, p_real high"),
    ("foni_5", "foni_squawk", "playful squawk", "'A cute small parrot gives one short friendly squawk, not harsh'", "z 2.5, p 0.92, p_real 0.91"),
    ("foni_6", "foni_surprised", "curious / surprised", "'A cute parakeet's surprised delighted chirp, a quick bright upward chirrup'", "z 2.5, p_real 0.99"),
]
for name, src, mood, prompt, why in FONI:
    SET[name] = Spec((lambda src=src: el(src)), "medium", "elevenlabs", f"ElevenLabs: {prompt} (v2/{src}.mp3)",
                     f"{mood}: {why}; rejected: cheering trill (matched 'harsh alarm beep'), giggle ('finger squeaking on glass' 3.7), cockatiel / lovebird / curious (z < 2.2)",
                     "a cute small parrot chirping", 0.8, hp=400, lp=12000, key=100, room=(0.25, 0.04, 0.5))


def process(name: str, sp: Spec) -> tuple[np.ndarray, dict]:
    x = sp.src()
    if not sp.stereo:
        x = s.mono(x)
    info = {}
    if sp.key is not None:
        cents, err = s.key_offset_cents(x, sp.key)
        x = s.shift(x, cents)
        info.update(key_shift_cents=cents, key_error_cents=round(err, 1))
    if sp.extra_cents:
        x = s.shift(x, sp.extra_cents)
        # Land the variant on its nearest white key again (a whole tone up from E would be F#).
        again = s.key_offset_cents(x, 120)[0] if sp.key is not None else 0.0
        x = s.shift(x, again)
        info["transposed_cents"] = sp.extra_cents + again
    x = s.highpass(x, sp.hp)
    x = s.lowpass(x, sp.lp)
    if sp.sat:
        x = s.saturate(x, 2.0, sp.sat)
    if sp.stereo:
        x = s.narrow(x, 0.8)
    x = s.trim(x, max_s=sp.max_s, fade_out_ms=min(80.0, 400.0 * sp.max_s), start_s=sp.start_s)
    if sp.room:
        rt, wet, width = sp.room
        x = s.reverb(x, rt, wet, width=width)
        # Keep the length cap: the room's tail fades with the sound.
        x = s.trim(x, max_s=sp.max_s + 0.15 * rt, fade_out_ms=min(150.0, 300.0 * sp.max_s), head_db=-50)
    x, gain, limited = s.normalise(x, TIERS[sp.tier], TP_CEILING)
    info.update(gain_db=round(gain, 1), peak_limited=bool(limited))
    return x, info


def measure(x: np.ndarray) -> dict:
    w = s.width_db(x)
    return {
        "duration_s": round(len(x) / s.SR, 3),
        "channels": 2 if x.ndim == 2 else 1,
        "momentary_max_lufs": round(s.momentary_max(x), 1),
        "true_peak_dbtp": round(s.true_peak_db(x), 1),
        "lead_silence_ms": round(s.lead_silence_ms(x), 1),
        "tail_last10ms_db": round(s.tail_db(x), 1),
        "energy_below_300hz_pct": round(100 * s.band_share(x), 1),
        "centroid_hz": round(s.centroid_hz(x)),
        "side_over_mid_db": None if w is None else round(w, 1),
    }


def to_mp3(wav: Path, mp3: Path) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(wav), "-c:a", "libmp3lame", "-b:a", "192k", str(mp3)], check=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="*")
    args = ap.parse_args()
    out, reel = [], []
    for old in ASSETS.glob("*.ogg"):
        if not args.only and old.stem not in SET:
            old.unlink()
    for i, (name, sp) in enumerate(SET.items()):
        if args.only and name not in args.only:
            continue
        x, info = process(name, sp)
        wav = WORK / "wav" / f"{name}.wav"
        s.save_wav(wav, x)
        ogg = ASSETS / f"{name}.ogg"
        s.encode_ogg(wav, ogg)
        shipped = s.load(ogg, stereo=(x.ndim == 2))
        m = measure(shipped)
        m["ogg_bytes"] = ogg.stat().st_size
        out.append({"name": name, "file": f"sfx/{name}.ogg", "tier": sp.tier, "target_momentary_lufs": TIERS[sp.tier],
                    "source": sp.kind, "provenance": sp.provenance, "why": sp.why, "earshot_query": sp.query,
                    "haptic": sp.haptic, **info, "measured_from_ogg": m})
        pw = WORK / "preview" / f"{i + 1:02d}_{name}.wav"
        s.save_wav(pw, s.stereo(shipped))
        PREVIEWS.mkdir(parents=True, exist_ok=True)
        to_mp3(pw, PREVIEWS / f"{i + 1:02d}_{name}.mp3")
        reel.append(s.stereo(shipped))
        if name == "hold_tick":
            for r in (9 / 8, 5 / 4, 3 / 2, 5 / 3, 2.0):
                reel.append(np.zeros((int(0.18 * s.SR), 2)))
                reel.append(s.stereo(s.shift(s.mono(shipped), 1200 * np.log2(r))))
        reel.append(np.zeros((int(0.5 * s.SR), 2)))
        print(f"{name:13s} {sp.kind:18s} {m['duration_s']:.3f}s ch{m['channels']} M {m['momentary_max_lufs']:6.1f} "
              f"TP {m['true_peak_dbtp']:5.1f} lead {m['lead_silence_ms']:4.1f} tail {m['tail_last10ms_db']:6.1f} "
              f"<300 {m['energy_below_300hz_pct']:4.1f}% key {info.get('key_shift_cents', '-')}c/{info.get('key_error_cents', '-')} "
              f"{m['ogg_bytes']} B{' (TP-limited)' if info['peak_limited'] else ''}", flush=True)
    if not args.only:
        rw = WORK / "showreel.wav"
        s.save_wav(rw, np.concatenate(reel))
        to_mp3(rw, PREVIEWS / "00_showreel.mp3")
        total = sum(e["measured_from_ogg"]["ogg_bytes"] for e in out)
        manifest = {
            "version": 2,
            "family": "C major (every tonal sound key-fitted to the white keys); ElevenLabs mallets, kalimba, glockenspiel, harp, bubbles and real-sounding parrot calls, earshot-bank wood / glock / shimmer; never a falling or buzzing cue",
            "format": "OGG Vorbis q4, 48 kHz; stereo for the rewards (side narrowed to 0.8 + a computed room), mono for UI ticks and Foni",
            "levels": {"tiers_max_momentary_lufs": TIERS, "true_peak_ceiling_dbtp_before_encode": TP_CEILING,
                       "reference": "his voice clips measure about -15 LUFS integrated"},
            "chain": "key fit (resample, <= 1.5 semitones), high-pass 110-400 Hz, low-pass 7-14 kHz, tanh saturation 10-25 % (warmth), computed stereo room 0.25-1.2 s at 4-12 % wet, trim (onset -1 ms, -60 dB tail), fades, tier gain with a 4 dB look-ahead limiter",
            "total_bytes": total,
            "elevenlabs_generations": {"v1": 7, "v2": 49, "shipped_from": sorted({p.provenance.split("(")[-1].rstrip(")") for p in SET.values() if "ElevenLabs" in p.provenance})},
            "sounds": out,
        }
        (HERE / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"total {total} bytes in {len(out)} files")


if __name__ == "__main__":
    main()
