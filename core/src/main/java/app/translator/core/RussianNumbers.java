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

    // ---------------------------------------------------------------- ordinals (mirror of laptop/ru_numbers.py)
    private static final String[] ORD_UNITS = {"нулевой", "первый", "второй", "третий", "четвёртый", "пятый", "шестой",
            "седьмой", "восьмой", "девятый"};
    private static final String[] ORD_TEENS = {"десятый", "одиннадцатый", "двенадцатый", "тринадцатый", "четырнадцатый",
            "пятнадцатый", "шестнадцатый", "семнадцатый", "восемнадцатый", "девятнадцатый"};
    private static final String[] ORD_TENS = {"", "", "двадцатый", "тридцатый", "сороковой", "пятидесятый", "шестидесятый",
            "семидесятый", "восьмидесятый", "девяностый"};
    private static final String[] ORD_HUNDREDS = {"", "сотый", "двухсотый", "трёхсотый", "четырёхсотый", "пятисотый",
            "шестисотый", "семисотый", "восьмисотый", "девятисотый"};
    private static final String[] GEN_UNITS = {"", "одно", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти"};
    private static final String[] GEN_TEENS = {"десяти", "одиннадцати", "двенадцати", "тринадцати", "четырнадцати",
            "пятнадцати", "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати"};
    private static final String[] GEN_TENS = {"", "", "двадцати", "тридцати", "сорока", "пятидесяти", "шестидесяти",
            "семидесяти", "восьмидесяти", "девяноста"};
    private static final String[] GEN_HUNDREDS = {"", "сто", "двухсот", "трёхсот", "четырёхсот", "пятисот", "шестисот",
            "семисот", "восьмисот", "девятисот"};
    private static final String[] SCALE_ORD = {"тысячный", "миллионный", "миллиардный"};

    private static String gender(String w, char g) {
        if (g == 'm') return w;
        String stem = w.substring(0, w.length() - 2);
        if (w.endsWith("ий")) return stem + (g == 'f' ? "ья" : "ье");
        return stem + (g == 'f' ? "ая" : "ое");
    }

    private static void ordTriple(int n, StringBuilder out) {
        int h = n / 100, rest = n % 100;
        if (rest == 0) { add(out, ORD_HUNDREDS[h]); return; }
        if (h > 0) add(out, HUNDREDS[h]);
        if (rest >= 10 && rest <= 19) add(out, ORD_TEENS[rest - 10]);
        else {
            int t = rest / 10, u = rest % 10;
            if (u == 0) add(out, ORD_TENS[t]);
            else {
                if (t > 0) add(out, TENS[t]);
                add(out, ORD_UNITS[u]);
            }
        }
    }

    private static String genPrefix(int n) {
        if (n == 1) return "";
        int h = n / 100, rest = n % 100;
        StringBuilder b = new StringBuilder(GEN_HUNDREDS[h]);
        if (rest >= 10 && rest <= 19) b.append(GEN_TEENS[rest - 10]);
        else b.append(GEN_TENS[rest / 10]).append(GEN_UNITS[rest % 10]);
        return b.toString();
    }

    /** 5 -> "пятый" ('m'), "пятая" ('f'), "пятое" ('n'); 2000 -> "двухтысячный". */
    public static String toOrdinal(long n, char g) {
        if (n < 0 || n > MAX) throw new IllegalArgumentException("number out of range");
        if (n == 0) return gender(ORD_UNITS[0], g);
        int[] groups = new int[4];
        int count = 0;
        for (long m = n; m > 0; m /= 1000) groups[count++] = (int) (m % 1000);
        int k = 0;
        while (groups[k] == 0) k++;
        long scale = 1;
        for (int i = 0; i <= k; i++) scale *= 1000;
        long upper = n - n % scale;
        StringBuilder out = new StringBuilder(upper > 0 ? toWords(upper) : "");
        if (k == 0) ordTriple(groups[0], out);
        else add(out, genPrefix(groups[k]) + SCALE_ORD[k - 1]);
        int sp = out.lastIndexOf(" ");
        String last = out.substring(sp + 1);
        return out.substring(0, sp + 1) + gender(last, g);
    }

    /** English "1st", "2nd", "23rd", "11th". */
    public static String ordinalSuffix(long n) {
        long n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 13) return n + "th";
        return n + (n10 == 1 ? "st" : n10 == 2 ? "nd" : n10 == 3 ? "rd" : "th");
    }
}
