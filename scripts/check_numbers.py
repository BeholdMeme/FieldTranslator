"""Checks Russian number words on hand-verified examples and writes a reference
(number \\t words) that the Java and JavaScript versions must reproduce.
usage: python check_numbers.py <out_reference.tsv>"""
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "laptop"))
from ru_numbers import to_words  # noqa: E402

EXPECTED = {
    0: "ноль", 1: "один", 2: "два", 11: "одиннадцать", 20: "двадцать", 21: "двадцать один",
    40: "сорок", 90: "девяносто", 100: "сто", 200: "двести", 345: "триста сорок пять",
    1000: "тысяча", 2000: "две тысячи", 5000: "пять тысяч", 2025: "две тысячи двадцать пять",
    11000: "одиннадцать тысяч", 21000: "двадцать одна тысяча", 22000: "двадцать две тысячи",
    20000: "двадцать тысяч", 1000000: "миллион", 2000000: "два миллиона", 5000000: "пять миллионов",
    1000000000: "миллиард", -5: "минус пять",
}


def main():
    bad = [(n, w, to_words(n)) for n, w in EXPECTED.items() if to_words(n) != w]
    for b in bad:
        print("MISMATCH", b)
    if bad:
        raise SystemExit("number words wrong")
    rnd = random.Random(11)
    nums = list(range(0, 2101)) + [rnd.randint(0, 10 ** k) for k in range(3, 13) for _ in range(300)]
    nums += list(EXPECTED) + [999999999999]
    with open(sys.argv[1], "w", encoding="utf-8", newline="\n") as f:
        for n in nums:
            f.write("%d\t%s\n" % (n, to_words(n)))
    print("number examples OK; wrote", len(nums))


if __name__ == "__main__":
    main()
