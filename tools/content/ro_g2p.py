"""A small Romanian grapheme-to-phoneme helper, only as precise as the content checks need.

It knows the spelling traps that decide where a target sound really is:
- "ce/ci" = [tʃ] and "ge/gi" = [dʒ], but "che/chi" = [k] and "ghe/ghi" = [g];
- in "cea/cio/ciu", "gea/gio/giu" the e/i is only a spelling mark ("ceapă" = /tʃapə/);
- "x" = [ks], so it contains an [s];
- a final "i" after a consonant is not a vowel, it only palatalises ("nuci" ends in [tʃ]);
- "ș" [ʃ], "ț" [ts], "j" [ʒ], "ă" [ə], "â/î" [ɨ].

Phonemes are returned as a list of strings. The palatal mark of a silent final i is "ʲ" and is not a phoneme
for position purposes. Glides (i/u next to a vowel) stay vowels here: only consonant positions matter.
"""

from __future__ import annotations

VOWELS = {"a", "ə", "ɨ", "e", "i", "o", "u"}
PALATAL = "ʲ"
CONSONANTS = {"p", "b", "t", "d", "k", "g", "f", "v", "s", "z", "ʃ", "ʒ", "ts", "tʃ", "dʒ", "h", "m", "n", "l", "r"}

# Sound id (SoundId.raw) -> phoneme.
SOUND_PHONEME = {"s": "s", "ș": "ʃ", "z": "z", "j": "ʒ", "ț": "ts", "ce": "tʃ", "ge": "dʒ", "r": "r", "l": "l"}
PHONEME_SOUND = {v: k for k, v in SOUND_PHONEME.items()}
# Contrast tokens used in pairs that are not one of the nine sounds.
EXTRA_CONTRAST = {"t": "t", "d": "d", "k": "k", "i": "i"}

SIMPLE = {
    "a": "a", "ă": "ə", "â": "ɨ", "î": "ɨ", "e": "e", "i": "i", "o": "o", "u": "u",
    "b": "b", "d": "d", "f": "f", "h": "h", "j": "ʒ", "k": "k", "l": "l", "m": "m", "n": "n", "p": "p",
    "q": "k", "r": "r", "s": "s", "ș": "ʃ", "t": "t", "ț": "ts", "v": "v", "w": "v", "y": "i", "z": "z",
}

# Words whose final i is a real vowel although it follows a consonant.
SYLLABIC_FINAL_I = {"taxi", "kiwi", "ski", "safari", "mini", "colibri", "salami"}


def phonemes(word: str, final_i_vowel: bool = False) -> list[str]:
    """Phonemes of one word (letters only; hyphens join the parts). `final_i_vowel` keeps a final i syllabic,
    as in the bare syllable "si" or "iși"."""
    w = word.lower().replace("-", "").replace("’", "").replace("'", "")
    out: list[str] = []
    i = 0
    n = len(w)
    while i < n:
        c = w[i]
        nxt = w[i + 1] if i + 1 < n else ""
        if c in ("c", "g"):
            hard = "k" if c == "c" else "g"
            soft = "tʃ" if c == "c" else "dʒ"
            if nxt == "h" and i + 2 < n and w[i + 2] in "ei":
                out.append(hard)
                i += 2
                continue
            if nxt in ("e", "i"):
                out.append(soft)
                after = w[i + 2] if i + 2 < n else ""
                marker = (nxt == "e" and after in ("a", "o")) or (nxt == "i" and after in ("a", "o", "u"))
                i += 2 if marker else 1
                continue
            out.append(hard)
            i += 1
            continue
        if c == "x":
            out.extend(["k", "s"])
            i += 1
            continue
        if c == "i" and i == n - 1 and not final_i_vowel and w not in SYLLABIC_FINAL_I:
            prev = out[-1] if out else ""
            if prev in CONSONANTS:
                # "nuci", "pași", "copii": a final i after a consonant (or the second i of "ii") only palatalises.
                out.append(PALATAL)
                i += 1
                continue
            if prev == "i" and len(out) >= 2 and out[-2] in CONSONANTS:
                out.append(PALATAL)
                i += 1
                continue
        mapped = SIMPLE.get(c)
        if mapped is not None:
            out.append(mapped)
        i += 1
    return out


def core(ph: list[str]) -> list[str]:
    """Phonemes without the palatal mark."""
    return [p for p in ph if p != PALATAL]


def is_consonant(p: str) -> bool:
    return p in CONSONANTS


def positions(word: str, sound: str, final_i_vowel: bool = False) -> set[str]:
    """Where the sound occurs in the word: any of "initial", "medial", "final"."""
    target = SOUND_PHONEME[sound]
    ph = core(phonemes(word, final_i_vowel))
    found: set[str] = set()
    for k, p in enumerate(ph):
        if p != target:
            continue
        if k == 0:
            found.add("initial")
        elif k == len(ph) - 1:
            found.add("final")
        else:
            found.add("medial")
    return found


def in_cluster(word: str, sound: str) -> bool:
    """True when the target sits right next to another consonant."""
    target = SOUND_PHONEME[sound]
    ph = core(phonemes(word))
    for k, p in enumerate(ph):
        if p != target:
            continue
        if (k > 0 and is_consonant(ph[k - 1])) or (k + 1 < len(ph) and is_consonant(ph[k + 1])):
            return True
    return False


def count_sound(text: str, sound: str) -> int:
    target = SOUND_PHONEME[sound]
    return sum(core(phonemes(w)).count(target) for w in words_of(text))


def sounds_in(text: str) -> set[str]:
    """The nine practice sounds that occur anywhere in the text."""
    found: set[str] = set()
    for w in words_of(text):
        for p in core(phonemes(w)):
            if p in PHONEME_SOUND:
                found.add(PHONEME_SOUND[p])
    return found


def words_of(text: str) -> list[str]:
    cleaned = []
    for ch in text:
        cleaned.append(ch if (ch.isalpha() or ch in "-") else " ")
    return [w.strip("-") for w in "".join(cleaned).split() if w.strip("-")]


def syllable_count(word: str) -> int:
    """Vowel groups; diphthongs count once. Close enough to order words by length."""
    ph = core(phonemes(word))
    groups = 0
    previous_vowel = False
    for p in ph:
        vowel = p in VOWELS
        if vowel and not previous_vowel:
            groups += 1
        previous_vowel = vowel
    return max(groups, 1)


def single_substitution(a: str, b: str) -> tuple[str, str] | None:
    """When the two words differ by exactly one phoneme (substitution), the two phonemes; else None.
    A one-phoneme deletion returns (phoneme, "∅")."""
    pa, pb = core(phonemes(a)), core(phonemes(b))
    if len(pa) == len(pb):
        diffs = [(x, y) for x, y in zip(pa, pb) if x != y]
        return diffs[0] if len(diffs) == 1 else None
    longer, shorter, flip = (pa, pb, False) if len(pa) > len(pb) else (pb, pa, True)
    if len(longer) - len(shorter) != 1:
        return None
    for k in range(len(longer)):
        if longer[:k] + longer[k + 1:] == shorter:
            return ("∅", longer[k]) if flip else (longer[k], "∅")
    return None


if __name__ == "__main__":
    import sys

    for word in sys.argv[1:]:
        print(word, phonemes(word), {s: sorted(positions(word, s)) for s in sounds_in(word)})
