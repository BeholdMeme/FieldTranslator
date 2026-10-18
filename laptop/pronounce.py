"""
Russian -> English-friendly pronunciation respelling, e.g. "Привет, как дела?" -> "pri-VYET, kak di-LA?"

Mirror of core/src/main/java/app/translator/core/Pronouncer.java (CI checks they agree).

* Stress comes from a dictionary (stress.tsv, from OpenRussian.org, CC BY-SA 4.0);
  ё is always stressed; one-vowel words need none. The stressed syllable is CAPITALIZED.
* Unstressed о/а sound like "a" right before the stress (or at the start) and "uh" elsewhere;
  unstressed е/я/after-soft а sound like "i".
* Consonants are devoiced at the end of a word and before voiceless ones (хлеб -> khlyep,
  водка -> VOT-kuh) and voiced before voiced ones (сделать -> ZDYE-luht').
* ' marks a soft consonant (ь). Words without known stress are shown without capitals.
"""
import bisect
import re

VOWELS = "аеёиоуыэюя"
IOTATED = "еёюя"
CONS_MAP = {
    "б": "b", "в": "v", "г": "g", "д": "d", "ж": "zh", "з": "z", "й": "y", "к": "k",
    "л": "l", "м": "m", "н": "n", "п": "p", "р": "r", "с": "s", "т": "t", "ф": "f",
    "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sh",
}
DEVOICE = {"б": "п", "в": "ф", "г": "к", "д": "т", "ж": "ш", "з": "с"}
VOICE = {v: k for k, v in DEVOICE.items()}
VOICED_OBSTRUENTS = set("бгджз")          # в does not voice what comes before it
VOICELESS = set("пфктшсхцчщ")
NO_GLIDE_AFTER = set("жшцчщй")             # е/ё/ю/я after these: no "y" sound
HARD_ONLY = set("жшц")                     # и/е after these sound like ы

# whole-word exceptions (irregular, very common)
EXCEPTIONS = {
    "что": "shto", "чтобы": "SHTO-by", "ничто": "nish-TO", "кое-что": "KO-ye-shto",
    "здравствуйте": "ZDRAST-vooy-tye", "здравствуй": "ZDRAST-vooy",
    "пожалуйста": "pa-ZHA-luh-stuh", "сегодня": "si-VOD-nya", "сегодняшний": "si-VOD-nyash-niy",
    "конечно": "ka-NYESH-nuh", "скучно": "SKOOSH-nuh", "нарочно": "na-ROSH-nuh",
    "солнце": "SON-tse", "сердце": "SYER-tse", "чувство": "CHOOST-vuh",
    "его": "yi-VO", "сейчас": "si-CHAS", "счастье": "SHAS-tye", "счёт": "shyot",
    "лёгкий": "LYOKH-kiy", "легко": "likh-KO", "мягкий": "MYAKH-kiy",
    # common words whose dictionary-first stress is the less common reading
    "дела": "di-LA", "все": "fsye",
    # unstressed one-syllable prepositions/particles blend into the next word
    "до": "da", "по": "pa", "не": "ni", "от": "at", "о": "a", "об": "ab", "со": "sa", "во": "va", "ко": "ka",
    # words common in phrases and Bible verses that the dictionary lacks or gets wrong
    "бог": "bokh", "буду": "BOO-doo", "него": "ni-VO", "тобою": "ta-BO-yoo", "отче": "OT-che",
    "иисус": "i-ee-SOOS", "иисусе": "i-ee-SOO-sye", "иисуса": "i-ee-SOO-suh", "господе": "GOS-puh-dye",
    "божией": "BO-zhy-yey", "есмь": "yesm'", "сие": "si-YE", "нашем": "NA-shem",
    "ожидаемого": "a-zhy-DA-ye-muh-vuh", "долготерпит": "duhl-ga-TYER-pit",
    "превозносится": "pri-vaz-NO-si-tsuh", "спасены": "spa-si-NY", "изволению": "iz-va-LYE-ni-yoo",
    "труждающиеся": "TROOZH-da-yoo-shi-ye-sya",
    "руки": "ROO-ki",
    "беспилотник": "bis-pi-LOT-nik", "нами": "NA-mi", "камикадзе": "kuh-mi-KA-dze",
}
# words ending in -ого/-его where г really is г
OGO_KEEP_G = {"много", "немного", "строго", "нестрого", "дорого", "недорого", "убого", "полого", "отлого"}


class StressDict:
    def __init__(self, path=None):
        self.words, self.idx = [], []
        if path:
            with open(path, encoding="utf-8") as f:
                for line in f:
                    w, _, i = line.rstrip("\n").partition("\t")
                    if w:
                        self.words.append(w)
                        self.idx.append(int(i))

    def get(self, w):
        k = bisect.bisect_left(self.words, w)
        if k < len(self.words) and self.words[k] == w:
            return self.idx[k]
        return None


def stress_of(word, sd):
    """Index of the stressed vowel in a lowercase word, or None if unknown."""
    if "ё" in word:
        return word.index("ё")
    vi = [i for i, ch in enumerate(word) if ch in VOWELS]
    if len(vi) == 1:
        return vi[0]
    if not vi:
        return None
    return sd.get(word) if sd else None


def _assimilate(word):
    """Apply final devoicing and voicing assimilation; returns letters with substitutions."""
    w = list(word)
    # positions of real consonants (skip ь/ъ)
    nxt = None  # next consonant letter to the right (within a cluster), or "#" at word end
    for i in range(len(w) - 1, -1, -1):
        ch = w[i]
        if ch in "ьъ":
            continue
        if ch in VOWELS:
            nxt = "V"
            continue
        if ch in CONS_MAP:
            if nxt is None or nxt == "#":
                if ch in DEVOICE:
                    w[i] = DEVOICE[ch]
            elif nxt in VOICELESS and ch in DEVOICE:
                w[i] = DEVOICE[ch]
            elif nxt in VOICED_OBSTRUENTS and ch in VOICE:
                w[i] = VOICE[ch]
            nxt = w[i]
        if nxt is None:
            nxt = "#"
    return w


def _word(word, sd):
    lw = word.lower()
    if lw in EXCEPTIONS:
        return EXCEPTIONS[lw]
    st = stress_of(lw, sd)
    known = st is not None

    w = lw
    # г -> в in the genitive endings -ого/-его (его, нового, моего ...)
    if (w.endswith("ого") or w.endswith("его")) and len(w) > 3 and w not in OGO_KEEP_G:
        w = w[:-2] + "в" + w[-1]
    # silent letters: здн, стн, вств, лнц
    for a, b in (("вств", "ств"), ("здн", "зн"), ("стн", "сн"), ("лнц", "нц"), ("рдц", "рц")):
        if a in w:
            off = w.index(a)
            if st is not None and st > off:
                st -= 1
            w = w.replace(a, b, 1)
    # сч/зч -> щ
    for a in ("сч", "зч"):
        while a in w:
            off = w.index(a)
            if st is not None and st > off:
                st -= 1
            w = w[:off] + "щ" + w[off + 2:]
    suffix = ""
    for end in ("ться", "тся"):
        if w.endswith(end) and len(w) > len(end):
            w = w[:-len(end)]
            suffix = "tsuh"
            break

    letters = _assimilate(w)
    vowel_pos = [i for i, ch in enumerate(letters) if ch in VOWELS]
    stressed_vn = vowel_pos.index(st) if known and st in vowel_pos else None

    units = []          # (text, kind) kind: "c" consonant, "v" vowel, "s" soft mark
    vn = -1
    for i, ch in enumerate(letters):
        prev = letters[i - 1] if i > 0 else "#"
        if ch in VOWELS:
            vn += 1
            stressed = stressed_vn is not None and vn == stressed_vn
            last = vn == len(vowel_pos) - 1 and all(c not in VOWELS for c in letters[i + 1:])
            glide = (ch in IOTATED and prev not in NO_GLIDE_AFTER) or (ch == "и" and prev == "ь")
            if known and not stressed:
                pretonic = stressed_vn is not None and vn == stressed_vn - 1
                initial = i == 0
                if ch in "оа":
                    if prev in "чщ":
                        v = "i"
                    else:
                        v = "a" if (pretonic or initial) else "uh"
                elif ch == "я":
                    if last:
                        v = "a"
                    else:
                        v = "i"
                        if prev in CONS_MAP:
                            glide = False
                elif ch == "е":
                    v = "e" if last else ("y" if prev in HARD_ONLY else "i")
                    if not last and prev in CONS_MAP:
                        glide = False
                elif ch == "и":
                    v = "y" if prev in HARD_ONLY else "i"
                elif ch == "ы" and last and i + 2 == len(letters) and letters[-1] == "й":
                    v = "i"
                else:
                    v = {"у": "oo", "ю": "oo", "ы": "y", "э": "e", "ё": "o"}[ch]
            else:
                if ch == "и" and prev in HARD_ONLY:
                    v = "y"
                elif ch == "е" and prev in HARD_ONLY:
                    v = "e"
                else:
                    v = {"а": "a", "о": "o", "у": "oo", "ы": "y", "э": "e", "и": "ee",
                         "е": "e", "ё": "o", "ю": "oo", "я": "a"}[ch]
                    if not known and ch == "и":
                        v = "i"
            if glide:
                v = "y" + v
            units.append([v, "v", stressed])
        elif ch in "ьъ":
            nxt = letters[i + 1] if i + 1 < len(letters) else "#"
            if nxt in VOWELS:
                units.append(["", "b"])      # syllable break before the "y" sound
            elif ch == "ь":
                units.append(["'", "s"])
        elif ch in CONS_MAP:
            units.append([CONS_MAP[ch], "c"])
        else:
            units.append([ch, "c"])

    # syllables: first consonant of a cluster stays, the rest start the next syllable
    syl = [[]]
    seen_vowel = False
    pending = []
    for u in units:
        if u[1] == "v":
            if seen_vowel:
                cons = [x for x in pending]
                # split cluster: first consonant (plus its soft mark) stays behind;
                # before ь/ъ + vowel the whole cluster stays behind (семья -> sim-YA)
                k = 0
                if cons and cons[-1][1] == "b":
                    k = len(cons)
                elif len([x for x in cons if x[1] == "c"]) >= 2:
                    k = 1
                    while k < len(cons) and cons[k][1] == "s":
                        k += 1
                syl[-1].extend(cons[:k])
                syl.append(cons[k:] + [u])
            else:
                syl[-1].extend(pending + [u])
            pending = []
            seen_vowel = True
        else:
            pending.append(u)
    syl[-1].extend(pending)
    one_syllable = len(vowel_pos) <= 1

    def render(s):
        t = "".join(x[0] for x in s)
        return t.upper() if (not one_syllable and any(len(x) > 2 and x[2] for x in s)) else t
    out = "-".join(render(s) for s in syl if s)
    if suffix:
        out = out + "-" + suffix if any(u[1] == "v" for u in units) else out + suffix
    return out


WORD_RE = re.compile(r"[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*")


def pronounce(text, sd):
    """Respell every Cyrillic word in text; everything else is kept as is."""
    def one(m):
        token = m.group(0)
        if token.lower() in EXCEPTIONS:
            return EXCEPTIONS[token.lower()]
        return "-".join(_word(part, sd) for part in token.split("-"))
    return WORD_RE.sub(one, text)
