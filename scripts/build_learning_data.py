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
from phrases_data import (COLOR_EXAMPLES, COLOR_TIPS, COLORS, DATES, DRONES, MILITARY, MONTHS, NUMBER_GROUPS,  # noqa: E402
                          GRAMMAR, NUMBER_TIPS, ORDINAL_EXAMPLES, ORDINAL_GROUPS, ORDINAL_TIPS, PHRASES, VERSES)
from ru_numbers import format_digits, ordinal_suffix, to_ordinal, to_words  # noqa: E402
from pronounce import EXCEPTIONS  # noqa: E402

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

# how each letter's name is written in Russian (what the voice reads)
NAME_RU = {"А": "а", "Б": "бэ", "В": "вэ", "Г": "гэ", "Д": "дэ", "Е": "е", "Ё": "ё", "Ж": "жэ", "З": "зэ",
           "И": "и", "Й": "и краткое", "К": "ка", "Л": "эль", "М": "эм", "Н": "эн", "О": "о", "П": "пэ",
           "Р": "эр", "С": "эс", "Т": "тэ", "У": "у", "Ф": "эф", "Х": "ха", "Ц": "цэ", "Ч": "чэ", "Ш": "ша",
           "Щ": "ща", "Ъ": "твёрдый знак", "Ы": "ы", "Ь": "мягкий знак", "Э": "э", "Ю": "ю", "Я": "я"}

# how to SAY each letter's name, in English letters (stressed part in capitals for 2-word names),
# with a short hint that ties it to an English sound
NAME_SAY = {
    "А": ("ah", "like the a in father"), "Б": ("beh", "rhymes with meh"), "В": ("veh", "rhymes with meh"),
    "Г": ("geh", "hard g as in get"), "Д": ("deh", "rhymes with meh"), "Е": ("yeh", "like yeah, cut short"),
    "Ё": ("yo", "like yo!"), "Ж": ("zheh", "zh = the s in pleasure"), "З": ("zeh", "rhymes with meh"),
    "И": ("ee", "like the ee in see"), "Й": ("ee KRAHT-kuh-yeh", 'means "short ee"'),
    "К": ("kah", "like ca in car"), "Л": ("el'", "like L, ending softly"), "М": ("em", "like M"),
    "Н": ("en", "like N"), "О": ("oh", "like the o in or"), "П": ("peh", "rhymes with meh"),
    "Р": ("ehr", "with a quick rolled r"), "С": ("es", "like S"), "Т": ("teh", "rhymes with meh"),
    "У": ("oo", "like the oo in boot"), "Ф": ("ef", "like F"), "Х": ("khah", "breathy kh, like Bach"),
    "Ц": ("tseh", "ts as in cats + eh"), "Ч": ("cheh", "like the che in check"), "Ш": ("shah", "like sha in shah"),
    "Щ": ("shchyah", "a long, soft shh + ya"), "Ъ": ("TVYOR-diy znahk", 'means "hard sign" - no sound of its own'),
    "Ы": ("ih", "like the i in bit, tongue pulled back"), "Ь": ("MYAKH-kiy znahk", 'means "soft sign" - no sound of its own'),
    "Э": ("eh", "like the e in bet"), "Ю": ("yoo", "like you"), "Я": ("yah", "like yah"),
}

# Reading course. Each lesson adds letters, then practises them as syllables -> words -> sentences,
# using ONLY letters taught so far (checked at build time). The last lessons read real signs and a short story.
LESSONS = [
    {"title": "Look the same, sound the same", "letters": "А К М О Т",
     "intro": "Five letters that look and sound almost like English. With just these you can already read real words!",
     "syllables": ["ма", "ка", "та", "мо", "ко", "то", "ам", "ак", "от"],
     "words": [("мама", "mom"), ("кот", "cat"), ("там", "there"), ("как", "how"), ("так", "so"), ("кто", "who"),
               ("атом", "atom"), ("том", "volume (book)"), ("мак", "poppy")],
     "sentences": [("Мама там.", "Mom is there."), ("Кот там.", "The cat is there."), ("Кто там?", "Who's there?"),
                   ("Как так?", "How so?")]},
    {"title": "False friends", "letters": "В Е Н Р С У Х",
     "intro": "These look like English letters but sound different. В = v, Е = ye, Н = n, Р = r (rolled), "
              "С = s, У = oo, Х = kh. Say them out loud until the English sound stops popping up.",
     "syllables": ["ва", "не", "ра", "са", "ну", "ру", "ху", "во", "се", "ре"],
     "words": [("нет", "no"), ("он", "he"), ("она", "she"), ("нос", "nose"), ("сок", "juice"), ("метро", "metro"),
               ("театр", "theater"), ("ресторан", "restaurant"), ("утро", "morning"), ("вот", "here is / there is"),
               ("касса", "ticket office / checkout"), ("сахар", "sugar"), ("Москва", "Moscow"), ("сестра", "sister"),
               ("урок", "lesson"), ("тут", "here")],
     "sentences": [("Вот метро.", "Here's the metro."), ("Нет, там театр.", "No, the theater is there."),
                   ("Сестра тут.", "My sister is here."), ("Вот ресторан.", "Here's the restaurant."),
                   ("Он там, она тут.", "He's there, she's here."), ("Вот касса.", "Here's the checkout.")]},
    {"title": "New shapes, familiar sounds", "letters": "Б Г Д З И Л П",
     "intro": "New shapes with easy sounds: Б = b, Г = g, Д = d, З = z, И = ee (a backwards N), Л = l, П = p. "
              "Now you can read a lot of signs and places.",
     "syllables": ["ба", "га", "да", "за", "ли", "пи", "до", "би", "ги", "ла"],
     "words": [("да", "yes"), ("дом", "house"), ("где", "where"), ("вода", "water"), ("банк", "bank"),
               ("билет", "ticket"), ("папа", "dad"), ("парк", "park"), ("аптека", "pharmacy"), ("магазин", "store"),
               ("вокзал", "train station"), ("такси", "taxi"), ("автобус", "bus"), ("суп", "soup"), ("хлеб", "bread"),
               ("друг", "friend"), ("город", "city")],
     "sentences": [("Где банк?", "Where is the bank?"), ("Вот аптека.", "Here's the pharmacy."),
                   ("Где вокзал?", "Where is the train station?"), ("Да, вот магазин.", "Yes, here's the store."),
                   ("Папа дома.", "Dad is at home."), ("Мама в парке.", "Mom is in the park."),
                   ("Вода и хлеб.", "Water and bread."), ("Где такси?", "Where is the taxi?")]},
    {"title": "The hissing letters", "letters": "Ж Й Ф Ц Ч Ш Щ",
     "intro": "Ж = zh (s in pleasure), Ц = ts, Ч = ch, Ш = sh, Щ = a long soft shh, Ф = f, and Й = a short y (as in boy). "
              "With these you can say please and thank you!",
     "syllables": ["жа", "ча", "ша", "ща", "ца", "фа", "ой", "ай", "жу", "чи"],
     "words": [("чай", "tea"), ("пожалуйста", "please"), ("спасибо", "thank you"), ("хорошо", "good / OK"),
               ("что", "what"), ("кафе", "café"), ("центр", "center"), ("улица", "street"), ("фото", "photo"),
               ("борщ", "borscht"), ("шапка", "hat"), ("врач", "doctor"), ("мой", "my"), ("час", "hour"),
               ("шоколад", "chocolate"), ("душ", "shower")],
     "sentences": [("Чай, пожалуйста.", "Tea, please."), ("Где кафе?", "Where is the café?"),
                   ("Хорошо, спасибо.", "Fine, thank you."), ("Где центр?", "Where is the center?"),
                   ("Что там?", "What's there?"), ("Мой друг врач.", "My friend is a doctor."),
                   ("Борщ и хлеб, пожалуйста.", "Borscht and bread, please.")]},
    {"title": "Vowels with a Y", "letters": "Ё Ы Э Ю Я",
     "intro": "Я = ya, Ю = yoo, Ё = yo (always stressed) - like А, У, О with a y in front. Э = e as in bet. "
              "Ы is a deep 'i' with the tongue pulled back. Now you know almost every letter.",
     "syllables": ["я", "ю", "ё", "мы", "ты", "вы", "э", "ля", "лю", "ня"],
     "words": [("я", "I"), ("это", "this / it is"), ("мы", "we"), ("ты", "you (friend)"), ("вы", "you (polite)"),
               ("сыр", "cheese"), ("юг", "south"), ("меню", "menu"), ("моя", "my (feminine)"), ("яблоко", "apple"),
               ("музей", "museum"), ("рынок", "market"), ("выход", "exit"), ("Россия", "Russia"), ("ёлка", "fir tree"),
               ("ясно", "clear / got it")],
     "sentences": [("Это моя сумка.", "This is my bag."), ("Я турист.", "I'm a tourist."), ("Где выход?", "Where is the exit?"),
                   ("Меню, пожалуйста.", "The menu, please."), ("Мы из Америки.", "We're from America."),
                   ("Это музей?", "Is this a museum?"), ("Ясно, спасибо.", "Got it, thanks.")]},
    {"title": "The two silent signs", "letters": "Ь Ъ",
     "intro": "Ь (soft sign) and Ъ (hard sign) make no sound. Ь softens the letter before it, like a tiny 'y' glide "
              "(день = dyen'). Ъ is rare - it just makes a small break. That's the whole alphabet!",
     "syllables": ["ть", "нь", "ль", "дь", "сь", "мь"],
     "words": [("день", "day"), ("соль", "salt"), ("рубль", "ruble"), ("есть", "to eat / there is"),
               ("дверь", "door"), ("площадь", "square"), ("жизнь", "life"), ("словарь", "dictionary"),
               ("подъезд", "building entrance"), ("объявление", "announcement")],
     "sentences": [("Добрый день!", "Good afternoon!"), ("Сто рублей.", "100 rubles."),
                   ("Я хочу есть.", "I'm hungry."), ("Где подъезд?", "Where is the entrance?"),
                   ("Соль, пожалуйста.", "Salt, please."), ("Красная площадь", "Red Square")]},
    {"title": "How words really sound", "letters": "",
     "intro": "Russian isn't always read letter by letter. Learn these few rules and your reading will sound natural. "
              "The gold pronunciation guide in the app always follows them.",
     "points": ["Unstressed О sounds like 'a' or 'uh': молоко = muh-la-KO, хорошо = khuh-ra-SHO.",
                "Unstressed Е and Я sound like 'i': сестра = sis-TRA, язык = ee-ZYK.",
                "At the end of a word, voiced letters go hard: хлеб = khlyep, город = GO-ruht.",
                "Г in -ого / -его sounds like в: сегодня = si-VOD-nya, его = ye-VO.",
                "что is said 'shto'. Some letters are silent: здравствуйте = ZDRAST-vooy-tye."],
     "words": [("молоко", "milk"), ("хорошо", "good"), ("сестра", "sister"), ("язык", "language"), ("хлеб", "bread"),
               ("город", "city"), ("сегодня", "today"), ("его", "his / him"), ("что", "what"),
               ("здравствуйте", "hello (polite)"), ("спасибо", "thank you"), ("вокзал", "train station")],
     "sentences": [("Сегодня хорошо.", "It's nice today."), ("Молоко и хлеб, пожалуйста.", "Milk and bread, please."),
                   ("Что это?", "What is this?"), ("Здравствуйте!", "Hello!")]},
    {"title": "Reading real signs", "letters": "",
     "intro": "Signs are often in CAPITAL letters. These are the ones you'll see everywhere - in the metro, shops, "
              "stations and buildings. Try reading each before you look at the answer.",
     "words": [("ВХОД", "entrance"), ("ВЫХОД", "exit"), ("ТУАЛЕТ", "toilet"), ("АПТЕКА", "pharmacy"), ("МЕТРО", "metro"),
               ("КАССА", "ticket office / checkout"), ("ОТКРЫТО", "open"), ("ЗАКРЫТО", "closed"),
               ("ПРОДУКТЫ", "groceries"), ("БОЛЬНИЦА", "hospital"), ("ПОЛИЦИЯ", "police"), ("ВОКЗАЛ", "train station"),
               ("ОСТОРОЖНО", "caution"), ("НЕ КУРИТЬ", "no smoking"), ("ОТ СЕБЯ", "push"), ("К СЕБЕ", "pull"),
               ("ПЕРЕХОД", "crossing / underpass"), ("ВХОДА НЕТ", "no entry")],
     "sentences": [("ВЫХОД В ГОРОД", "Exit to the city (metro)"), ("ОСТОРОЖНО, ДВЕРИ ЗАКРЫВАЮТСЯ", "Caution, the doors are closing (metro)"),
                   ("МАГАЗИН ОТКРЫТ", "The store is open"), ("ВХОД ЗАПРЕЩЁН", "No entry")]},
    {"title": "Your first story", "letters": "",
     "intro": "Read this short story line by line. Try each line yourself first, then tap it to check the "
              "pronunciation and meaning. If you can read this, you can read basic Russian!",
     "words": [("меня зовут", "my name is"), ("живу", "I live"), ("утром", "in the morning"), ("пью", "I drink"),
               ("потом", "then"), ("еду", "I go (by vehicle)"), ("вечером", "in the evening"), ("гуляю", "I walk"),
               ("люблю", "I love")],
     "sentences": [("Меня зовут Анна.", "My name is Anna."), ("Я живу в Москве.", "I live in Moscow."),
                   ("Утром я пью чай.", "In the morning I drink tea."),
                   ("Потом я еду на работу на метро.", "Then I go to work by metro."),
                   ("Вечером я гуляю в парке.", "In the evening I walk in the park."),
                   ("Я очень люблю Москву!", "I really love Moscow!")]},
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
        alphabet.append({"upper": letter, "lower": letter.lower(), "name": name, "name_ru": NAME_RU[letter],
                         "name_say": NAME_SAY[letter][0], "name_hint": NAME_SAY[letter][1],
                         "say": "%s. %s." % (NAME_RU[letter], ex), "sound": sound,
                         "example": ex, "example_pron": pronounce(ex, sd), "example_meaning": meaning})

    lessons, known = [], set()
    # a syllable on its own is read letter by letter (no unstressed-vowel changes)
    snd = {"а": "a", "о": "o", "у": "oo", "ы": "y", "и": "ee", "э": "e", "е": "ye", "я": "ya", "ю": "yoo", "ё": "yo",
           "б": "b", "в": "v", "г": "g", "д": "d", "ж": "zh", "з": "z", "й": "y", "к": "k", "л": "l", "м": "m",
           "н": "n", "п": "p", "р": "r", "с": "s", "т": "t", "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh",
           "щ": "shch", "ь": "'", "ъ": ""}
    soft = {"е": "e", "я": "ya", "ю": "yu", "ё": "yo"}

    def syllable_sound(x):
        out = ""
        for i, c in enumerate(x):
            if i > 0 and c in soft and x[i - 1] not in "аоуыиэеяюёьъ":
                out += {"е": "ye", "я": "ya", "ю": "yu", "ё": "yo"}[c]
            else:
                out += snd.get(c, c)
        return out

    alphabet_all = set("абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
    for les in LESSONS:
        letters = les["letters"].split()
        new = set(x.lower() for x in letters)
        known |= new
        allowed = known if letters else alphabet_all
        def check(text):
            bad = set(c for c in text.lower() if c.isalpha()) - allowed
            if bad:
                sys.exit("lesson '%s' uses letters not taught yet %s in: %s" % (les["title"], sorted(bad), text))
            return text
        words = [{"word": check(w), "pron": pronounce(w.lower(), sd), "meaning": m} for w, m in les["words"]]
        if letters:   # top up with common words that only use known letters
            have = set(x["word"].lower() for x in words)
            for w, m in vocab:
                if len(words) >= 20:
                    break
                if 2 <= len(w) <= 8 and set(w) <= known and set(w) & new and w not in have:
                    words.append({"word": w, "pron": pronounce(w, sd), "meaning": m})
        lessons.append({"title": les["title"], "letters": letters, "intro": les["intro"],
                        "points": les.get("points", []),
                        "syllables": [{"s": check(x), "pron": syllable_sound(x)} for x in les.get("syllables", [])],
                        "words": words,
                        "sentences": [{"ru": check(ru), "en": en, "pron": pronounce(ru.lower(), sd)}
                                      for ru, en in les.get("sentences", [])]})

    phrases = [{"category": c, "ru": ru, "en": en, "pron": pronounce(ru, sd)} for c, ru, en in PHRASES]
    verses = [{"ref": ref, "ru": ru, "en": en, "pron": pronounce(ru, sd)} for ref, ru, en in VERSES]
    military = [{"category": "Commands", "ru": ru, "en": en, "pron": pronounce(ru, sd)} for ru, en in MILITARY]
    military += [{"category": "Drones", "ru": ru, "en": en, "pron": pronounce(ru, sd)} for ru, en in DRONES]
    def num(n):
        w = to_words(n)
        return {"n": n, "digits": format_digits(n), "ru": w, "pron": pronounce(w, sd)}
    def ordn(n):
        it = {"n": n, "digits": ordinal_suffix(n)}
        for g, key in (("m", ""), ("f", "_f"), ("n", "_n")):
            w = to_ordinal(n, g)
            it["ru" + key] = w
            it["pron" + key] = pronounce(w, sd)
        return it
    pr = lambda items: [{"ru": ru, "en": en, "pron": pronounce(ru, sd)} for ru, en in items]  # noqa: E731
    grammar = [{"level": g["level"], "title": g["title"], "intro": g["intro"], "points": g["points"],
                "examples": [{"ru": ru, "en": en, "note": note, "pron": pronounce(ru, sd)} for ru, en, note in g["examples"]],
                "quiz": [{"prompt": q, "shown": shown, "answer": a, "wrong": w} for q, shown, a, w in g["quiz"]]}
               for g in GRAMMAR]
    numbers = {"groups": [{"title": t, "note": note, "items": [num(n) for n in ns]} for t, note, ns in NUMBER_GROUPS],
               "tips": NUMBER_TIPS,
               "ordinals": {"groups": [{"title": t, "note": note, "items": [ordn(n) for n in ns]} for t, note, ns in ORDINAL_GROUPS],
                            "examples": pr(ORDINAL_EXAMPLES), "dates": pr(DATES),
                            "months": [{"ru": a, "of": b, "en": en, "pron": pronounce(a, sd), "pron_of": pronounce(b, sd)}
                                       for a, b, en in MONTHS],
                            "tips": ORDINAL_TIPS}}
    colors = {"items": [{"ru": ru, "en": en, "hex": hx, "pron": pronounce(ru, sd)} for ru, en, hx in COLORS],
              "tips": COLOR_TIPS,
              "examples": [{"ru": ru, "en": en, "pron": pronounce(ru, sd)} for ru, en in COLOR_EXAMPLES]}
    data = {"source": "Word list and stress: OpenRussian.org dictionary, CC BY-SA 4.0. "
                      "Bible verses: Russian Synodal translation and King James Version (both public domain).",
            "tips": TIPS, "alphabet": alphabet, "lessons": lessons, "phrases": phrases, "verses": verses,
            "military": military, "numbers": numbers, "colors": colors, "grammar": grammar,
            # the web app's pronunciation code reads its exception list from here
            "pron_exceptions": EXCEPTIONS}
    with open(os.path.join(out_dir, "learn.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
    print("stress forms: %d, vocab: %d, phrases: %d, verses: %d, military: %d" % (
        len(lines), len(vocab), len(phrases), len(verses), len(military)))
    for les in lessons:
        print(les["title"], len(les["words"]), ", ".join("%s=%s" % (x["word"], x["pron"]) for x in les["words"][:8]))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
