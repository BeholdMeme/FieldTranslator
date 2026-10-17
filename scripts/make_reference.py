"""
Builds reference translations with the Python implementation and checks them.

usage: python make_reference.py <model_dir> <pair: ru-en|en-ru> <out.tsv> [--hf <download_dir>] [--no-sanity]

* --hf: also checks our tokenizer against HuggingFace's MarianTokenizer
* sanity: checks a handful of field phrases translate to something sensible
* writes  text \t source_ids \t output_ids  lines; the Java unit test must
  reproduce these exactly, which proves the phone app == the laptop app.
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "laptop"))
from translator_core import MarianTranslator, prepare_text  # noqa: E402

PHRASES = {
    "ru-en": [
        "где находится вокзал", "сколько это стоит", "мне нужен врач", "спасибо большое",
        "я не понимаю", "говорите медленнее пожалуйста", "доброе утро", "как вас зовут",
        "где здесь туалет", "у вас есть вода", "мы идём в гостиницу", "это очень далеко",
        "помогите мне пожалуйста", "я заблудился", "сегодня очень холодно", "сколько времени",
        "где можно купить билет", "поверните налево", "идите прямо", "я из америки",
        "привет как дела", "можно мне счёт", "здесь есть аптека", "вызовите полицию",
    ],
    "en-ru": [
        "where is the train station", "how much does this cost", "i need a doctor", "thank you very much",
        "i do not understand", "please speak more slowly", "good morning", "what is your name",
        "where is the bathroom", "do you have water", "we are going to the hotel", "this is very far",
        "please help me", "i am lost", "it is very cold today", "what time is it",
        "where can i buy a ticket", "turn left", "go straight", "i am from america",
        "hello how are you", "can i have the bill", "is there a pharmacy here", "call the police",
    ],
}

# (phrase index, any of these substrings expected in the lowercase output)
SANITY = {
    "ru-en": [(0, ["station"]), (1, ["cost", "how much"]), (2, ["doctor"]), (3, ["thank"]),
              (4, ["understand"]), (6, ["morning"]), (17, ["left"]), (23, ["police"])],
    "en-ru": [(0, ["вокзал", "станци"]), (1, ["сколько", "стоит"]), (2, ["врач", "доктор"]), (3, ["спасибо"]),
              (4, ["понима"]), (6, ["утро"]), (17, ["налево", "лев"]), (23, ["полиц"])],
}


def main():
    model_dir, pair, out_tsv = sys.argv[1], sys.argv[2], sys.argv[3]
    hf_dir = sys.argv[sys.argv.index("--hf") + 1] if "--hf" in sys.argv else None

    tr = MarianTranslator(model_dir)
    phrases = PHRASES[pair]

    if hf_dir:
        from transformers import MarianTokenizer
        hf = MarianTokenizer(source_spm=os.path.join(hf_dir, "source.spm"),
                             target_spm=os.path.join(hf_dir, "target.spm"),
                             vocab=os.path.join(hf_dir, "vocab.json"))
        extra = ["Hello, World!  It's 5 o'clock.", "Ёжик в тумане — 2024 год…", "ÄÖÜ ﬁ ①", "x"]
        bad = 0
        for t in [prepare_text(p) for p in phrases] + extra:
            ours = tr.encode_ids(t)
            ref = hf(t)["input_ids"]
            if ours != ref:
                bad += 1
                print("TOKENIZER MISMATCH", repr(t), ref, ours)
        if bad:
            raise SystemExit("tokenizer differs from HuggingFace on %d inputs" % bad)
        print("tokenizer matches HuggingFace MarianTokenizer")

    lines, outputs = [], []
    t0 = time.time()
    for p in phrases:
        src = tr.encode_ids(prepare_text(p))
        ids = tr.translate_ids(src)
        text = tr.decode_ids(ids)
        outputs.append(text)
        print("  %-40s -> %s" % (p, text))
        lines.append("%s\t%s\t%s" % (p, " ".join(map(str, src)), " ".join(map(str, ids))))
    print("avg %.0f ms per phrase on this machine" % ((time.time() - t0) * 1000 / len(phrases)))

    if pair in SANITY and "--no-sanity" not in sys.argv:
        fails = []
        for idx, keys in SANITY[pair]:
            if not any(k in outputs[idx].lower() for k in keys):
                fails.append((phrases[idx], outputs[idx], keys))
        for f in fails:
            print("SANITY MISS", f)
        if len(fails) > 2:
            raise SystemExit("translations look wrong (%d sanity misses)" % len(fails))

    os.makedirs(os.path.dirname(os.path.abspath(out_tsv)), exist_ok=True)
    with open(out_tsv, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote", out_tsv)


if __name__ == "__main__":
    main()
