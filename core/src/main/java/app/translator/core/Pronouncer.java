package app.translator.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Russian -> English-friendly pronunciation respelling ("Привет, как дела?" -> "pri-VYET, kak di-LA?").
 * Line-for-line mirror of laptop/pronounce.py; CI checks both agree on thousands of words.
 * See pronounce.py for the rules.
 */
public final class Pronouncer {

    private static final String VOWELS = "аеёиоуыэюя";
    private static final String IOTATED = "еёюя";
    private static final Map<Character, String> CONS = new HashMap<>();
    private static final Map<Character, Character> DEVOICE = new HashMap<>();
    private static final Map<Character, Character> VOICE = new HashMap<>();
    private static final String VOICED_OBSTRUENTS = "бгджз";
    private static final String VOICELESS = "пфктшсхцчщ";
    private static final String NO_GLIDE_AFTER = "жшцчщй";
    private static final String HARD_ONLY = "жшц";
    private static final Map<String, String> EXCEPTIONS = new HashMap<>();
    private static final Set<String> OGO_KEEP_G = new HashSet<>();
    private static final Pattern WORD = Pattern.compile("[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)*");

    static {
        String[][] cons = {{"б", "b"}, {"в", "v"}, {"г", "g"}, {"д", "d"}, {"ж", "zh"}, {"з", "z"}, {"й", "y"},
                {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"},
                {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "sh"}};
        for (String[] c : cons) CONS.put(c[0].charAt(0), c[1]);
        String voiced = "бвгджз", unvoiced = "пфктшс";
        for (int i = 0; i < voiced.length(); i++) {
            DEVOICE.put(voiced.charAt(i), unvoiced.charAt(i));
            VOICE.put(unvoiced.charAt(i), voiced.charAt(i));
        }
        String[][] ex = {
                {"что", "shto"}, {"чтобы", "SHTO-by"}, {"ничто", "nish-TO"}, {"кое-что", "KO-ye-shto"},
                {"здравствуйте", "ZDRAST-vooy-tye"}, {"здравствуй", "ZDRAST-vooy"},
                {"пожалуйста", "pa-ZHA-luh-stuh"}, {"сегодня", "si-VOD-nya"}, {"сегодняшний", "si-VOD-nyash-niy"},
                {"конечно", "ka-NYESH-nuh"}, {"скучно", "SKOOSH-nuh"}, {"нарочно", "na-ROSH-nuh"},
                {"солнце", "SON-tse"}, {"сердце", "SYER-tse"}, {"чувство", "CHOOST-vuh"},
                {"его", "yi-VO"}, {"сейчас", "si-CHAS"}, {"счастье", "SHAS-tye"}, {"счёт", "shyot"},
                {"лёгкий", "LYOKH-kiy"}, {"легко", "likh-KO"}, {"мягкий", "MYAKH-kiy"},
                {"дела", "di-LA"}, {"все", "fsye"},
                {"до", "da"}, {"по", "pa"}, {"не", "ni"}, {"от", "at"}, {"о", "a"}, {"об", "ab"}, {"со", "sa"},
                {"во", "va"}, {"ко", "ka"}, {"бог", "bokh"}, {"буду", "BOO-doo"}, {"него", "ni-VO"},
                {"тобою", "ta-BO-yoo"}, {"отче", "OT-che"}, {"иисус", "i-ee-SOOS"}, {"иисусе", "i-ee-SOO-sye"},
                {"иисуса", "i-ee-SOO-suh"}, {"господе", "GOS-puh-dye"}, {"божией", "BO-zhy-yey"},
                {"есмь", "yesm'"}, {"сие", "si-YE"}, {"нашем", "NA-shem"},
                {"ожидаемого", "a-zhy-DA-ye-muh-vuh"}, {"долготерпит", "duhl-ga-TYER-pit"},
                {"превозносится", "pri-vaz-NO-si-tsuh"}, {"спасены", "spa-si-NY"},
                {"изволению", "iz-va-LYE-ni-yoo"}, {"труждающиеся", "TROOZH-da-yoo-shi-ye-sya"}, {"руки", "ROO-ki"},
        };
        for (String[] e : ex) EXCEPTIONS.put(e[0], e[1]);
        for (String w : new String[]{"много", "немного", "строго", "нестрого", "дорого", "недорого", "убого", "полого", "отлого"}) {
            OGO_KEEP_G.add(w);
        }
    }

    // ------------------------------------------------------------------ stress dictionary

    /** Sorted "word\tindex" file, searched in place (no big in-memory map on weak phones). */
    public static final class StressDict {
        private final MappedByteBuffer buf;
        private final int[] lineStarts;

        public StressDict(File tsv) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(tsv, "r"); FileChannel ch = raf.getChannel()) {
                buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            }
            int n = 0;
            int lim = buf.limit();
            for (int i = 0; i < lim; i++) if (buf.get(i) == '\n') n++;
            if (lim > 0 && buf.get(lim - 1) != '\n') n++;
            lineStarts = new int[n];
            int k = 0;
            if (lim > 0) lineStarts[k++] = 0;
            for (int i = 0; i < lim - 1 && k < n; i++) if (buf.get(i) == '\n') lineStarts[k++] = i + 1;
        }

        private String key(int line) {
            int s = lineStarts[line];
            int e = s;
            while (e < buf.limit() && buf.get(e) != '\t' && buf.get(e) != '\n') e++;
            byte[] b = new byte[e - s];
            for (int i = 0; i < b.length; i++) b[i] = buf.get(s + i);
            return new String(b, StandardCharsets.UTF_8);
        }

        private int value(int line) {
            int p = lineStarts[line];
            while (buf.get(p) != '\t') p++;
            int v = 0;
            for (p++; p < buf.limit() && buf.get(p) >= '0' && buf.get(p) <= '9'; p++) v = v * 10 + (buf.get(p) - '0');
            return v;
        }

        public Integer get(String w) {
            int lo = 0, hi = lineStarts.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = key(mid).compareTo(w);
                if (c == 0) return value(mid);
                if (c < 0) lo = mid + 1; else hi = mid - 1;
            }
            return null;
        }
    }

    private final StressDict dict;

    public Pronouncer(StressDict dict) { this.dict = dict; }

    static boolean isVowel(char c) { return VOWELS.indexOf(c) >= 0; }

    Integer stressOf(String w) {
        int yo = w.indexOf('ё');
        if (yo >= 0) return yo;
        List<Integer> vi = new ArrayList<>();
        for (int i = 0; i < w.length(); i++) if (isVowel(w.charAt(i))) vi.add(i);
        if (vi.size() == 1) return vi.get(0);
        if (vi.isEmpty()) return null;
        return dict == null ? null : dict.get(w);
    }

    static char[] assimilate(String word) {
        char[] w = word.toCharArray();
        Character nxt = null;   // null = word end, 'V' = vowel, else consonant letter
        for (int i = w.length - 1; i >= 0; i--) {
            char ch = w[i];
            if (ch == 'ь' || ch == 'ъ') continue;
            if (isVowel(ch)) { nxt = 'V'; continue; }
            if (CONS.containsKey(ch)) {
                if (nxt == null) {
                    if (DEVOICE.containsKey(ch)) w[i] = DEVOICE.get(ch);
                } else if (VOICELESS.indexOf(nxt) >= 0 && DEVOICE.containsKey(ch)) {
                    w[i] = DEVOICE.get(ch);
                } else if (VOICED_OBSTRUENTS.indexOf(nxt) >= 0 && VOICE.containsKey(ch)) {
                    w[i] = VOICE.get(ch);
                }
                nxt = w[i];
            }
        }
        return w;
    }

    private static final class Unit {
        final String text; final char kind; final boolean stressed;
        Unit(String text, char kind, boolean stressed) { this.text = text; this.kind = kind; this.stressed = stressed; }
    }

    String word(String word) {
        String lw = word.toLowerCase(new Locale("ru"));
        String ex = EXCEPTIONS.get(lw);
        if (ex != null) return ex;
        Integer st = stressOf(lw);
        boolean known = st != null;

        String w = lw;
        if ((w.endsWith("ого") || w.endsWith("его")) && w.length() > 3 && !OGO_KEEP_G.contains(w)) {
            w = w.substring(0, w.length() - 2) + "в" + w.charAt(w.length() - 1);
        }
        String[][] silent = {{"вств", "ств"}, {"здн", "зн"}, {"стн", "сн"}, {"лнц", "нц"}, {"рдц", "рц"}};
        for (String[] ab : silent) {
            int off = w.indexOf(ab[0]);
            if (off >= 0) {
                if (st != null && st > off) st = st - 1;
                w = w.substring(0, off) + ab[1] + w.substring(off + ab[0].length());
            }
        }
        for (String a : new String[]{"сч", "зч"}) {
            int off;
            while ((off = w.indexOf(a)) >= 0) {
                if (st != null && st > off) st = st - 1;
                w = w.substring(0, off) + "щ" + w.substring(off + 2);
            }
        }
        String suffix = "";
        for (String end : new String[]{"ться", "тся"}) {
            if (w.endsWith(end) && w.length() > end.length()) {
                w = w.substring(0, w.length() - end.length());
                suffix = "tsuh";
                break;
            }
        }

        char[] letters = assimilate(w);
        List<Integer> vowelPos = new ArrayList<>();
        for (int i = 0; i < letters.length; i++) if (isVowel(letters[i])) vowelPos.add(i);
        int stressedVn = (known && vowelPos.contains(st)) ? vowelPos.indexOf(st) : -1;

        List<Unit> units = new ArrayList<>();
        int vn = -1;
        for (int i = 0; i < letters.length; i++) {
            char ch = letters[i];
            char prev = i > 0 ? letters[i - 1] : '#';
            if (isVowel(ch)) {
                vn++;
                boolean stressed = stressedVn >= 0 && vn == stressedVn;
                boolean last = vn == vowelPos.size() - 1;
                boolean glide = (IOTATED.indexOf(ch) >= 0 && NO_GLIDE_AFTER.indexOf(prev) < 0) || (ch == 'и' && prev == 'ь');
                String v;
                if (known && !stressed) {
                    boolean pretonic = stressedVn >= 0 && vn == stressedVn - 1;
                    boolean initial = i == 0;
                    if (ch == 'о' || ch == 'а') {
                        if (prev == 'ч' || prev == 'щ') v = "i";
                        else v = (pretonic || initial) ? "a" : "uh";
                    } else if (ch == 'я') {
                        if (last) v = "a";
                        else {
                            v = "i";
                            if (CONS.containsKey(prev)) glide = false;
                        }
                    } else if (ch == 'е') {
                        v = last ? "e" : (HARD_ONLY.indexOf(prev) >= 0 ? "y" : "i");
                        if (!last && CONS.containsKey(prev)) glide = false;
                    } else if (ch == 'и') {
                        v = HARD_ONLY.indexOf(prev) >= 0 ? "y" : "i";
                    } else if (ch == 'ы' && last && i + 2 == letters.length && letters[letters.length - 1] == 'й') {
                        v = "i";
                    } else {
                        switch (ch) {
                            case 'у': case 'ю': v = "oo"; break;
                            case 'ы': v = "y"; break;
                            case 'э': v = "e"; break;
                            default: v = "o"; break;   // ё
                        }
                    }
                } else {
                    if (ch == 'и' && HARD_ONLY.indexOf(prev) >= 0) v = "y";
                    else if (ch == 'е' && HARD_ONLY.indexOf(prev) >= 0) v = "e";
                    else {
                        switch (ch) {
                            case 'а': case 'я': v = "a"; break;
                            case 'о': case 'ё': v = "o"; break;
                            case 'у': case 'ю': v = "oo"; break;
                            case 'ы': v = "y"; break;
                            case 'э': case 'е': v = "e"; break;
                            default: v = known ? "ee" : "i"; break;   // и
                        }
                    }
                }
                if (glide) v = "y" + v;
                units.add(new Unit(v, 'v', stressed));
            } else if (ch == 'ь' || ch == 'ъ') {
                char nxt = i + 1 < letters.length ? letters[i + 1] : '#';
                if (isVowel(nxt)) units.add(new Unit("", 'b', false));
                else if (ch == 'ь') units.add(new Unit("'", 's', false));
            } else if (CONS.containsKey(ch)) {
                units.add(new Unit(CONS.get(ch), 'c', false));
            } else {
                units.add(new Unit(String.valueOf(ch), 'c', false));
            }
        }

        List<List<Unit>> syl = new ArrayList<>();
        syl.add(new ArrayList<>());
        boolean seenVowel = false;
        List<Unit> pending = new ArrayList<>();
        for (Unit u : units) {
            if (u.kind == 'v') {
                if (seenVowel) {
                    int k = 0;
                    int consCount = 0;
                    for (Unit x : pending) if (x.kind == 'c') consCount++;
                    if (!pending.isEmpty() && pending.get(pending.size() - 1).kind == 'b') {
                        k = pending.size();
                    } else if (consCount >= 2) {
                        k = 1;
                        while (k < pending.size() && pending.get(k).kind == 's') k++;
                    }
                    syl.get(syl.size() - 1).addAll(pending.subList(0, k));
                    List<Unit> next = new ArrayList<>(pending.subList(k, pending.size()));
                    next.add(u);
                    syl.add(next);
                } else {
                    syl.get(syl.size() - 1).addAll(pending);
                    syl.get(syl.size() - 1).add(u);
                }
                pending = new ArrayList<>();
                seenVowel = true;
            } else {
                pending.add(u);
            }
        }
        syl.get(syl.size() - 1).addAll(pending);

        boolean oneSyllable = vowelPos.size() <= 1;
        StringBuilder out = new StringBuilder();
        for (List<Unit> s : syl) {
            if (s.isEmpty()) continue;
            StringBuilder t = new StringBuilder();
            boolean st2 = false;
            for (Unit x : s) { t.append(x.text); if (x.stressed) st2 = true; }
            String r = (!oneSyllable && st2) ? t.toString().toUpperCase(Locale.ROOT) : t.toString();
            if (out.length() > 0) out.append('-');
            out.append(r);
        }
        if (!suffix.isEmpty()) {
            boolean anyVowel = false;
            for (Unit u : units) if (u.kind == 'v') anyVowel = true;
            if (anyVowel) out.append('-');
            out.append(suffix);
        }
        return out.toString();
    }

    /** Respell every Cyrillic word in the text; everything else is kept as is. */
    public String pronounce(String text) {
        Matcher m = WORD.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = m.group();
            String ex = EXCEPTIONS.get(token.toLowerCase(new Locale("ru")));
            String rep;
            if (ex != null) rep = ex;
            else {
                StringBuilder b = new StringBuilder();
                for (String part : token.split("-")) {
                    if (b.length() > 0) b.append('-');
                    b.append(word(part));
                }
                rep = b.toString();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
