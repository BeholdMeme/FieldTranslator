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
