"""
Builds the offline learning data from the OpenRussian.org dictionary (CC BY-SA 4.0,
https://github.com/Badestrand/russian-dictionary):

  <out>/stress.tsv   every word form with its stressed vowel (for pronunciation)
  <out>/learn.json   alphabet + reading lessons (words use only letters learned so far)

usage: python build_learning_data.py <csv_dir> <out_dir>
"""
import csv
import json
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "laptop"))
from pronounce import StressDict, pronounce  # noqa: E402

VOWELS = "аеёиоуыэюя"
FILES = ["others", "nouns", "verbs", "adjectives"]
NOT_FORMS = {"bare", "translations_en", "translations_de", "gender", "partner", "animate",
             "indeclinable", "sg_only", "pl_only", "aspect"}

# letter, name (how Russians say the letter), sound, example word, meaning
ALPHABET = [
    ("А", "a", "a as in father", "мама", "mom"),
    ("Б", "be", "b as in bat", "банк", "bank"),
    ("В", "ve", "v as in van (looks like B!)", "вода", "water"),
    ("Г", "ge", "g as in go", "год", "year"),
    ("Д", "de", "d as in dog", "дом", "house"),
    ("Е", "ye", "ye as in yes", "нет", "no"),
    ("Ё", "yo", "yo as in yonder - always stressed", "ёлка", "fir tree"),
    ("Ж", "zhe", "s in pleasure", "жук", "beetle"),
    ("З", "ze", "z as in zoo (looks like 3)", "зима", "winter"),
    ("И", "i", "ee as in meet (looks like a backwards N)", "мир", "world, peace"),
    ("Й", "i kratkoye", "short y as in boy", "мой", "my"),
    ("К", "ka", "k as in kite", "кот", "cat"),
    ("Л", "el", "l as in lamp", "лампа", "lamp"),
    ("М", "em", "m as in mom", "мама", "mom"),
    ("Н", "en", "n as in no (looks like H!)", "нос", "nose"),
    ("О", "o", "o as in more when stressed; 'a'/'uh' when not", "окно", "window"),
    ("П", "pe", "p as in pen", "папа", "dad"),
    ("Р", "er", "rolled r, a quick tap of the tongue (looks like P!)", "рука", "hand, arm"),
    ("С", "es", "s as in sun (looks like C)", "сок", "juice"),
    ("Т", "te", "t as in top", "там", "there"),
    ("У", "u", "oo as in boot (looks like y)", "утро", "morning"),
    ("Ф", "ef", "f as in fun", "фото", "photo"),
    ("Х", "kha", "ch in Scottish loch - a breathy k (looks like X)", "хлеб", "bread"),
    ("Ц", "tse", "ts as in cats", "цирк", "circus"),
    ("Ч", "che", "ch as in chair", "чай", "tea"),
    ("Ш", "sha", "sh as in shop, tongue pulled back", "шапка", "hat"),
    ("Щ", "shcha", "a long, soft sh, as in fresh sheep", "борщ", "borscht"),
    ("Ъ", "tvyordy znak", "hard sign: no sound, a tiny pause before the next vowel", "подъезд", "building entrance"),
    ("Ы", "y", "like i in bit with the tongue pulled back", "сыр", "cheese"),
    ("Ь", "myagky znak", "soft sign: no sound, makes the letter before it soft", "день", "day"),
    ("Э", "e", "e as in bet", "это", "this, it"),
    ("Ю", "yu", "yoo as in you", "юг", "south"),
    ("Я", "ya", "ya as in yard", "я", "I"),
]

LESSONS = [
    ("Look the same, sound the same", "А К М О Т",
     "These five letters look and sound almost like English. You can already read real words!"),
    ("False friends", "В Е Н Р С У Х",
     "These look like English letters but sound different: В=v, Е=ye, Н=n, Р=r, С=s, У=oo, Х=kh."),
    ("New letters, part 1", "Б Г Д З И Л П",
     "New shapes with familiar sounds. И looks like a backwards N and says 'ee'."),
    ("New letters, part 2", "Ж Й Ф Ц Ч Ш Щ",
     "The 'hissing' letters: zh, ts, ch, sh and a long soft sh - plus Й (short y) and Ф (f)."),
    ("Special vowels", "Ё Ы Э Ю Я",
     "Я, Ю, Ё add a 'y' in front (ya, yoo, yo). Ы is a deep 'i'. Э is a plain 'e'."),
    ("The two signs", "Ь Ъ",
     "These make no sound. Ь softens the letter before it; Ъ makes a small break."),
]

TIPS = [
    "Stress matters: the CAPITAL syllable in the pronunciation is said louder and longer.",
    "Unstressed О sounds like 'a' or 'uh': молоко = muh-la-KO.",
    "Ь (soft sign) is shown as ' : мать = mat'. Put your tongue toward a 'y' sound.",
    "At the end of a word Б Д Г З В Ж sound like П Т К С Ф Ш: хлеб = khlyep.",
]


def rows_interleaved(csv_dir):
    """Yields (file, header, row) alternating between files, so the most common words come first."""
    readers = []
    for f in FILES:
        fh = open(os.path.join(csv_dir, f + ".csv"), encoding="utf-8")
        r = csv.reader(fh, delimiter="\t")
        readers.append((f, next(r), r))
    alive = True
    while alive:
        alive = False
        for f, hdr, r in readers:
            row = next(r, None)
            if row is not None:
                alive = True
                yield f, hdr, row


# a few common words whose first dictionary sense is an odd one
MEANING_OVERRIDES = {"тут": "here", "стать": "become", "сам": "myself, oneself", "свой": "one's own",
                     "уж": "already (colloquial)", "ко": "to, towards", "за": "behind, for",
                     "по": "along, by", "из": "from, out of", "есть": "eat, there is"}


def short_meaning(t):
    t = (t or "").split(";")[0]
    parts = [p.strip() for p in t.split(",") if p.strip() and re.fullmatch(r"[A-Za-z0-9 .'()/!?-]+", p.strip())]
    s = ", ".join(parts[:2])
    return s[:40]


def main(csv_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    stress, vocab, seen = {}, [], set()
    for f, hdr, row in rows_interleaved(csv_dir):
        for k, c in enumerate(row):
            if k >= len(hdr) or hdr[k] in NOT_FORMS:
                continue
            for w in c.split(","):
                w = w.strip().lower()
                if not re.fullmatch(r"[а-яё']+", w) or w.count("'") != 1:
                    continue
                bare = w.replace("'", "")
                if sum(ch in VOWELS for ch in bare) < 2 or "ё" in bare:
                    continue
                idx = w.index("'") - 1
                if bare[idx] in VOWELS and bare not in stress:
                    stress[bare] = idx
        bare = row[0].strip().lower() if row else ""
        meaning = MEANING_OVERRIDES.get(bare) or short_meaning(row[2] if len(row) > 2 else "")
        if not re.fullmatch(r"[A-Za-z0-9 ,.'()/!?-]+", meaning or "-"):
            meaning = ""
        if re.fullmatch(r"[а-яё]+", bare) and meaning and bare not in seen and len(vocab) < 6000:
            seen.add(bare)
            vocab.append((bare, meaning))

    lines = sorted("%s\t%d" % kv for kv in stress.items())
    stress_path = os.path.join(out_dir, "stress.tsv")
    with open(stress_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    sd = StressDict(stress_path)

    alphabet = []
    for letter, name, sound, ex, meaning in ALPHABET:
        alphabet.append({"upper": letter, "lower": letter.lower(), "name": name, "sound": sound,
                         "example": ex, "example_pron": pronounce(ex, sd), "example_meaning": meaning})

    lessons, known = [], set()
    for title, letters, intro in LESSONS:
        new = set(letters.lower().split())
        known |= new
        words = []
        for w, m in vocab:
            if 2 <= len(w) <= 8 and set(w) <= known and set(w) & new:
                words.append({"word": w, "pron": pronounce(w, sd), "meaning": m})
                if len(words) >= 20:
                    break
        lessons.append({"title": title, "letters": letters.split(), "intro": intro, "words": words})

    data = {"source": "Word list and stress: OpenRussian.org dictionary, CC BY-SA 4.0",
            "tips": TIPS, "alphabet": alphabet, "lessons": lessons}
    with open(os.path.join(out_dir, "learn.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
    print("stress forms: %d, vocab: %d" % (len(lines), len(vocab)))
    for les in lessons:
        print(les["title"], len(les["words"]), ", ".join("%s=%s" % (x["word"], x["pron"]) for x in les["words"][:8]))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
