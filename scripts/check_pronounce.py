"""
Checks the pronunciation respelling on hand-verified examples and writes a reference
file (input \\t output) that the Java/phone version must reproduce exactly.

usage: python check_pronounce.py <stress.tsv> <out_reference.tsv>
"""
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "laptop"))
from pronounce import StressDict, pronounce  # noqa: E402

EXPECTED = {
    "привет": "pri-VYET", "спасибо": "spa-SEE-buh", "хорошо": "khuh-ra-SHO", "молоко": "muh-la-KO",
    "сделать": "ZDYE-luht'", "водка": "VOT-kuh", "хлеб": "khlyep", "семья": "sim-YA",
    "учиться": "oo-CHEE-tsuh", "здравствуйте": "ZDRAST-vooy-tye", "Привет, как дела?": "pri-VYET, kak di-LA?",
    "вокзал": "vag-ZAL", "окно": "ak-NO", "меня": "mi-NYA", "язык": "yi-ZYK", "всё": "fsyo",
    "нового": "NO-vuh-vuh", "много": "MNO-guh", "день": "dyen'", "Москва": "mas-KVA",
    "двухтысячный": "dvookh-TY-sich-niy", "сороковой": "suh-ruh-ka-VOY", "четвёртый": "chit-VYOR-tiy",
}


def main():
    sd = StressDict(sys.argv[1])
    bad = 0
    for k, v in EXPECTED.items():
        got = pronounce(k, sd)
        if got != v:
            bad += 1
            print("MISMATCH %s: expected %s, got %s" % (k, v, got))
    if bad:
        raise SystemExit("%d pronunciation examples wrong" % bad)
    print("pronunciation examples OK")

    words = [l.split("\t")[0] for l in open(sys.argv[1], encoding="utf-8")]
    rnd = random.Random(7)
    sample = list(EXPECTED) + rnd.sample(words, 4000) + [w[:-1] + "ф" for w in rnd.sample(words, 300)]
    from ru_numbers import to_ordinal  # noqa: E402
    sample += [to_ordinal(n, g) for n in list(range(0, 120)) + [rnd.randint(0, 10 ** k) * 10 ** rnd.randint(0, 9) % (10 ** 12)
               for k in range(1, 4) for _ in range(60)] for g in "mfn"]
    sample += ["Где находится вокзал?", "Я не понимаю.", "кто-то", "Тест abc 123", "ЖИЗНЬ", "объект"]
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
        for s in sample:
            f.write("%s\t%s\n" % (s, pronounce(s, sd)))
    print("wrote", len(sample), "reference lines")


if __name__ == "__main__":
    main()
