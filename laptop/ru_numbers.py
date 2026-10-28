"""
Any whole number -> Russian words (cardinal, nominative), e.g. 2025 -> "две тысячи двадцать пять".
Mirror of core/.../RussianNumbers.java and web/js/numbers.js (CI checks all three agree).
"""

UNITS_M = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"]
UNITS_F = ["ноль", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"]
TEENS = ["десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
         "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"]
TENS = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят",
        "восемьдесят", "девяносто"]
HUNDREDS = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот",
            "восемьсот", "девятьсот"]
# (one, two-to-four, five-and-up), feminine?
SCALES = [
    (("тысяча", "тысячи", "тысяч"), True),
    (("миллион", "миллиона", "миллионов"), False),
    (("миллиард", "миллиарда", "миллиардов"), False),
]
MAX = 999_999_999_999


def plural_form(n):
    """0 = 'one' form, 1 = 'two-four' form, 2 = 'five+' form (Russian counting rule)."""
    n100 = n % 100
    n10 = n % 10
    if 11 <= n100 <= 14:
        return 2
    if n10 == 1:
        return 0
    if 2 <= n10 <= 4:
        return 1
    return 2


def _triple(n, feminine):
    words = []
    h, rest = divmod(n, 100)
    if h:
        words.append(HUNDREDS[h])
    if 10 <= rest <= 19:
        words.append(TEENS[rest - 10])
    else:
        t, u = divmod(rest, 10)
        if t:
            words.append(TENS[t])
        if u:
            words.append((UNITS_F if feminine else UNITS_M)[u])
    return words


def to_words(n):
    n = int(n)
    if n < 0:
        return "минус " + to_words(-n)
    if n > MAX:
        raise ValueError("number too large")
    if n == 0:
        return "ноль"
    groups = []
    while n:
        n, g = divmod(n, 1000)
        groups.append(g)
    words = []
    for i in range(len(groups) - 1, -1, -1):
        g = groups[i]
        if not g:
            continue
        if i == 0:
            words += _triple(g, False)
        else:
            forms, feminine = SCALES[i - 1]
            if g == 1:
                words.append(forms[0])          # "тысяча", "миллион" (not "одна тысяча")
            else:
                words += _triple(g, feminine)
                words.append(forms[plural_form(g)])
    return " ".join(words)


def format_digits(n):
    """1234567 -> '1,234,567'"""
    return "{:,}".format(int(n))


# ---------------------------------------------------------------- ordinals (1st, 2nd, 25th, 1000th ...)
# Only the last word becomes ordinal ("двадцать пятый"). A number ending in a round thousand /
# million / billion fuses into one word ("двухтысячный", "пятимиллионный").
# Gender: "m" = пятый (день, этаж), "f" = пятая (улица), "n" = пятое (число - dates: пятое мая).
ORD_UNITS = ["нулевой", "первый", "второй", "третий", "четвёртый", "пятый", "шестой", "седьмой",
             "восьмой", "девятый"]
ORD_TEENS = ["десятый", "одиннадцатый", "двенадцатый", "тринадцатый", "четырнадцатый", "пятнадцатый",
             "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый"]
ORD_TENS = ["", "", "двадцатый", "тридцатый", "сороковой", "пятидесятый", "шестидесятый",
            "семидесятый", "восьмидесятый", "девяностый"]
ORD_HUNDREDS = ["", "сотый", "двухсотый", "трёхсотый", "четырёхсотый", "пятисотый", "шестисотый",
                "семисотый", "восьмисотый", "девятисотый"]
GEN_UNITS = ["", "одно", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти"]
GEN_TEENS = ["десяти", "одиннадцати", "двенадцати", "тринадцати", "четырнадцати", "пятнадцати",
             "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати"]
GEN_TENS = ["", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти", "семидесяти",
            "восьмидесяти", "девяноста"]
GEN_HUNDREDS = ["", "сто", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот", "семисот",
                "восьмисот", "девятисот"]
SCALE_ORD = ["тысячный", "миллионный", "миллиардный"]


def _gender(word, g):
    if g == "m":
        return word
    if word.endswith("ий"):                    # третий -> третья / третье
        return word[:-2] + ("ья" if g == "f" else "ье")
    return word[:-2] + ("ая" if g == "f" else "ое")   # -ый / -ой


def _ord_triple(n):
    """Words for 1..999 with the last word ordinal."""
    h, rest = divmod(n, 100)
    if rest == 0:
        return [ORD_HUNDREDS[h]]
    words = [HUNDREDS[h]] if h else []
    if 10 <= rest <= 19:
        words.append(ORD_TEENS[rest - 10])
    else:
        t, u = divmod(rest, 10)
        if u == 0:
            words.append(ORD_TENS[t])
        else:
            if t:
                words.append(TENS[t])
            words.append(ORD_UNITS[u])
    return words


def _gen_prefix(n):
    """1..999 in the fused genitive form used before -тысячный: 21 -> 'двадцатиодно'."""
    if n == 1:
        return ""
    h, rest = divmod(n, 100)
    out = GEN_HUNDREDS[h]
    if 10 <= rest <= 19:
        out += GEN_TEENS[rest - 10]
    else:
        t, u = divmod(rest, 10)
        out += GEN_TENS[t] + GEN_UNITS[u]
    return out


def to_ordinal(n, gender="m"):
    n = int(n)
    if n < 0 or n > MAX:
        raise ValueError("number out of range")
    if n == 0:
        return _gender(ORD_UNITS[0], gender)
    groups = []
    m = n
    while m:
        m, g = divmod(m, 1000)
        groups.append(g)
    k = next(i for i, g in enumerate(groups) if g)          # lowest non-zero group
    upper = n - n % (1000 ** (k + 1))                         # the part said as a normal number
    words = to_words(upper).split() if upper else []
    if k == 0:
        last = _ord_triple(groups[0])
    else:
        last = [_gen_prefix(groups[k]) + SCALE_ORD[k - 1]]
    words += last
    words[-1] = _gender(words[-1], gender)
    return " ".join(words)


def ordinal_suffix(n):
    """English '1st', '2nd', '23rd', '11th'."""
    n = int(n)
    if 11 <= n % 100 <= 13:
        return "%dth" % n
    return "%d%s" % (n, {1: "st", 2: "nd", 3: "rd"}.get(n % 10, "th"))
