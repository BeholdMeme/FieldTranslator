package app.translator.core;

/**
 * Any whole number -> Russian words (cardinal, nominative): 2025 -> "две тысячи двадцать пять".
 * Mirror of laptop/numbers.py and web/js/numbers.js (CI checks all three agree).
 */
public final class RussianNumbers {
    private static final String[] UNITS_M = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] UNITS_F = {"ноль", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
            "семьсот", "восемьсот", "девятьсот"};
    private static final String[][] SCALES = {
            {"тысяча", "тысячи", "тысяч"},
            {"миллион", "миллиона", "миллионов"},
            {"миллиард", "миллиарда", "миллиардов"},
    };
    public static final long MAX = 999_999_999_999L;

    private RussianNumbers() { }

    static int pluralForm(long n) {
        long n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 14) return 2;
        if (n10 == 1) return 0;
        if (n10 >= 2 && n10 <= 4) return 1;
        return 2;
    }

    private static void triple(int n, boolean feminine, StringBuilder out) {
        int h = n / 100, rest = n % 100;
        if (h > 0) add(out, HUNDREDS[h]);
        if (rest >= 10 && rest <= 19) {
            add(out, TEENS[rest - 10]);
        } else {
            int t = rest / 10, u = rest % 10;
            if (t > 0) add(out, TENS[t]);
            if (u > 0) add(out, (feminine ? UNITS_F : UNITS_M)[u]);
        }
    }

    private static void add(StringBuilder b, String w) {
        if (b.length() > 0) b.append(' ');
        b.append(w);
    }

    public static String toWords(long n) {
        if (n < 0) return "минус " + toWords(-n);
        if (n > MAX) throw new IllegalArgumentException("number too large");
        if (n == 0) return "ноль";
        int[] groups = new int[4];
        int count = 0;
        while (n > 0) { groups[count++] = (int) (n % 1000); n /= 1000; }
        StringBuilder out = new StringBuilder();
        for (int i = count - 1; i >= 0; i--) {
            int g = groups[i];
            if (g == 0) continue;
            if (i == 0) {
                triple(g, false, out);
            } else {
                String[] forms = SCALES[i - 1];
                if (g == 1) add(out, forms[0]);
                else {
                    triple(g, i == 1, out);
                    add(out, forms[pluralForm(g)]);
                }
            }
        }
        return out.toString();
    }

    /** 1234567 -> "1,234,567" */
    public static String formatDigits(long n) {
        return String.format(java.util.Locale.US, "%,d", n);
    }
}
