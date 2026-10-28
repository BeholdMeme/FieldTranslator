package app.fieldtranslator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import app.translator.core.Pronouncer;
import app.translator.core.RussianNumbers;

/**
 * "Learn to read Russian": the alphabet, six reading lessons (each only uses letters you
 * have learned so far), flashcards and a 10-question quiz per lesson. All offline.
 */
public class LearnActivity extends Activity {

    private static final int BG = 0xFF0E1116, CARD = 0xFF171B22, BTN = 0xFF20252E, MUTED = 0xFF8B93A1,
            ACCENT = 0xFF3E8CFF, GOOD = 0xFF2FB36D, BAD = 0xFFE5484D, GOLD = 0xFFF5C451, BORDER = 0xFF2A303A;

    private JSONObject data;
    private ScrollView scroll;
    private LinearLayout page;
    private final Random rnd = new Random();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("learn", MODE_PRIVATE);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(page);
        setContentView(scroll);
        try {
            File f = new File(ModelInstaller.root(this), "learn/learn.json");
            try (InputStream in = new FileInputStream(f)) {
                byte[] all = new byte[(int) f.length()];
                int off = 0, n;
                while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
                data = new JSONObject(new String(all, StandardCharsets.UTF_8));
            }
            showHome();
        } catch (Exception e) {
            page.addView(text("Learning data not available yet: " + e.getMessage()
                    + "\n\nOpen the translator once so it can unpack its files.", 16, Color.WHITE, false));
        }
    }

    @Override
    public void onBackPressed() {
        Object tag = page.getTag();
        if ("home".equals(tag)) super.onBackPressed();
        else showHome();
    }

    // ------------------------------------------------------------------ screens

    private void clear(String tag) {
        page.removeAllViews();
        page.setTag(tag);
        scroll.scrollTo(0, 0);
    }

    private void showHome() {
        clear("home");
        page.addView(title("Learn to read Russian"));
        page.addView(text("33 letters. Tap a letter to hear how it sounds, then work through the lessons in order.",
                15, MUTED, false));

        page.addView(header("Alphabet"));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        JSONArray abc = data.optJSONArray("alphabet");
        for (int i = 0; i < abc.length(); i++) {
            final JSONObject l = abc.optJSONObject(i);
            Button btn = button(l.optString("upper") + l.optString("lower"), BTN, v -> showLetter(l));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = (getResources().getDisplayMetrics().widthPixels - dp(28)) / 6 - dp(4);
            lp.height = dp(52);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(btn, lp);
        }
        page.addView(grid);

        page.addView(header("Reading lessons"));
        JSONArray lessons = data.optJSONArray("lessons");
        for (int i = 0; i < lessons.length(); i++) {
            final int idx = i;
            JSONObject les = lessons.optJSONObject(i);
            int best = prefs.getInt("best_" + i, -1);
            String status = best < 0 ? "not started" : ("best quiz score " + best + "/10" + (best >= 8 ? "  ✓" : ""));
            Button btn = button((i + 1) + ". " + les.optString("title") + "\n" + join(les.optJSONArray("letters"))
                    + "   ·   " + status, best >= 8 ? GOOD : BTN, v -> showLesson(idx));
            btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            page.addView(btn, full(dp(76)));
        }

        page.addView(header("Numbers & colors"));
        if (data.optJSONObject("numbers") != null) {
            Button nb = button("Numbers   ·   say any number", BTN, v -> showNumbers());
            nb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.addView(nb, full(0));
        }
        if (data.optJSONObject("colors") != null) {
            Button cb = button("Colors   ·   " + data.optJSONObject("colors").optJSONArray("items").length(), BTN,
                    v -> showColors());
            cb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.addView(cb, full(0));
        }

        page.addView(header("Phrases & verses"));
        JSONArray phrases = data.optJSONArray("phrases");
        JSONArray verses = data.optJSONArray("verses");
        if (phrases != null) {
            Button pb = button("Common phrases   ·   " + phrases.length(), BTN, v -> showList("phrases"));
            pb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.addView(pb, full(0));
        }
        if (verses != null) {
            Button vb = button("Bible verses   ·   " + verses.length(), BTN, v -> showList("verses"));
            vb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.addView(vb, full(0));
        }

        JSONArray military = data.optJSONArray("military");
        if (military != null) {
            Button mb = button("Military phrases   ·   " + military.length(), BTN, v -> showList("military"));
            mb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            page.addView(mb, full(0));
        }

        page.addView(header("Pronunciation tips"));
        JSONArray tips = data.optJSONArray("tips");
        for (int i = 0; i < tips.length(); i++) page.addView(text("• " + tips.optString(i), 15, Color.WHITE, false));
        page.addView(text("\n" + data.optString("source"), 12, MUTED, false));
    }

    private void showLetter(JSONObject l) {
        clear("letter");
        page.addView(back());
        JSONArray abc = data.optJSONArray("alphabet");
        int idx = 0;
        for (int i = 0; i < abc.length(); i++) if (abc.optJSONObject(i).optString("upper").equals(l.optString("upper"))) idx = i;
        final int prevIdx = (idx + abc.length() - 1) % abc.length(), nextIdx = (idx + 1) % abc.length();
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = button("←  " + abc.optJSONObject(prevIdx).optString("upper"), BTN,
                v -> showLetter(abc.optJSONObject(prevIdx)));
        Button next = button(abc.optJSONObject(nextIdx).optString("upper") + "  →", ACCENT,
                v -> showLetter(abc.optJSONObject(nextIdx)));
        prev.setGravity(Gravity.CENTER);
        next.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMargins(0, dp(4), dp(6), dp(4));
        nav.addView(prev, nlp);
        LinearLayout.LayoutParams nlp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp2.setMargins(dp(6), dp(4), 0, dp(4));
        nav.addView(next, nlp2);
        page.addView(text("Letter " + (idx + 1) + " of " + abc.length(), 14, MUTED, false));
        TextView big = text(l.optString("upper") + " " + l.optString("lower"), 72, Color.WHITE, true);
        big.setGravity(Gravity.CENTER);
        page.addView(big);
        page.addView(speakButton("🔊  Hear the letter", l.optString("name_ru", l.optString("lower"))));
        page.addView(card("Name", l.optString("name")));
        page.addView(card("Sound", l.optString("sound")));
        page.addView(card("Example", l.optString("example") + "\n" + l.optString("example_pron")
                + "\n" + l.optString("example_meaning")));
        page.addView(speakButton("🔊  Hear the example word", l.optString("example")));
        page.addView(nav);
    }

    private void showLesson(int idx) {
        clear("lesson");
        JSONObject les = data.optJSONArray("lessons").optJSONObject(idx);
        page.addView(back());
        page.addView(title((idx + 1) + ". " + les.optString("title")));
        page.addView(text(les.optString("intro"), 16, Color.WHITE, false));
        page.addView(header("New letters"));
        JSONArray letters = les.optJSONArray("letters");
        for (int i = 0; i < letters.length(); i++) {
            JSONObject l = letter(letters.optString(i));
            if (l != null) page.addView(card(l.optString("upper") + " " + l.optString("lower"), l.optString("sound")));
        }
        page.addView(button("Practice words (flashcards)", ACCENT, v -> showCard(idx, 0)), full(dp(60)));
        page.addView(button("Take the quiz (10 questions)", GOOD, v -> startQuiz(idx)), full(dp(60)));
    }

    private void showCard(int lessonIdx, int i) {
        JSONArray words = data.optJSONArray("lessons").optJSONObject(lessonIdx).optJSONArray("words");
        if (words.length() == 0) { showLesson(lessonIdx); return; }
        clear("card");
        final JSONObject w = words.optJSONObject(i % words.length());
        page.addView(back());
        page.addView(text("Card " + (i % words.length() + 1) + " of " + words.length() + " - read it out loud, then tap Show",
                14, MUTED, false));
        TextView word = text(w.optString("word"), 54, Color.WHITE, true);
        word.setGravity(Gravity.CENTER);
        word.setPadding(0, dp(30), 0, dp(20));
        page.addView(word);
        final TextView answer = text(w.optString("pron") + "\n" + w.optString("meaning"), 26, GOLD, false);
        answer.setGravity(Gravity.CENTER);
        answer.setVisibility(View.INVISIBLE);
        page.addView(answer);
        page.addView(speakButton("🔊  Hear it", w.optString("word")));
        page.addView(button("Show", BTN, v -> answer.setVisibility(View.VISIBLE)), full(dp(60)));
        page.addView(button("Next  →", ACCENT, v -> showCard(lessonIdx, i + 1)), full(dp(60)));
    }

    // ------------------------------------------------------------------ numbers

    private Pronouncer pronouncer;

    private Pronouncer pron() {
        if (pronouncer == null) {
            try {
                pronouncer = new Pronouncer(new Pronouncer.StressDict(new File(ModelInstaller.root(this), "learn/stress.tsv")));
            } catch (Exception e) {
                pronouncer = new Pronouncer(null);
            }
        }
        return pronouncer;
    }

    private void showNumbers() {
        clear("numbers");
        JSONObject nums = data.optJSONObject("numbers");
        page.addView(back());
        page.addView(title("Numbers"));

        // --- say any number
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(0xFF15233A));
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.addView(text("Say any number", 14, 0xFF9CC3FF, true));
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Type a number, e.g. 2025");
        input.setHintTextColor(MUTED);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        box.addView(input);
        final boolean hasOrd = nums.optJSONObject("ordinals") != null;
        final boolean[] ordinal = {false};
        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        final Runnable update = () -> {
            results.removeAllViews();
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            long n;
            try { n = Long.parseLong(t); if (n > RussianNumbers.MAX) throw new NumberFormatException(); }
            catch (Exception ex) { results.addView(text("Up to 999,999,999,999", 18, Color.WHITE, true)); return; }
            if (!ordinal[0]) { results.addView(sayLine(RussianNumbers.toWords(n), null)); return; }
            results.addView(text(RussianNumbers.ordinalSuffix(n) + " - the ending matches what you count:", 13, MUTED, false));
            results.addView(sayLine(RussianNumbers.toOrdinal(n, 'm'), "он · he-words:\nдень, этаж"));
            results.addView(sayLine(RussianNumbers.toOrdinal(n, 'f'), "она · she-words:\nулица"));
            results.addView(sayLine(RussianNumbers.toOrdinal(n, 'n'), "оно · it-words\n& dates"));
        };
        if (hasOrd) {
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            final Button b5 = button("5  (five)", ACCENT, null), b5th = button("5th  (fifth)", BTN, null);
            b5.setGravity(Gravity.CENTER); b5th.setGravity(Gravity.CENTER);
            b5.setOnClickListener(v -> { ordinal[0] = false; b5.setBackground(round(ACCENT)); b5th.setBackground(round(BTN));
                input.setHint("Type a number, e.g. 2025"); update.run(); });
            b5th.setOnClickListener(v -> { ordinal[0] = true; b5th.setBackground(round(ACCENT)); b5.setBackground(round(BTN));
                input.setHint("Type a number, e.g. 5 for 5th"); update.run(); });
            LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            s1.setMargins(0, dp(6), dp(4), dp(2));
            LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            s2.setMargins(dp(4), dp(6), 0, dp(2));
            seg.addView(b5, s1);
            seg.addView(b5th, s2);
            box.addView(seg, 1);
        }
        box.addView(results);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { update.run(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(6), 0, dp(6));
        page.addView(box, blp);
        page.addView(button("Take the numbers quiz (10 questions)", GOOD, v -> startNumberQuiz(false)), full(0));

        // --- lesson groups
        JSONArray groups = nums.optJSONArray("groups");
        for (int g = 0; g < groups.length(); g++) {
            JSONObject grp = groups.optJSONObject(g);
            page.addView(header(grp.optString("title")));
            page.addView(text(grp.optString("note"), 14, MUTED, false));
            JSONArray items = grp.optJSONArray("items");
            for (int i = 0; i < items.length(); i++) page.addView(numberRow(items.optJSONObject(i)));
        }
        page.addView(header("How it works"));
        JSONArray tips = nums.optJSONArray("tips");
        for (int i = 0; i < tips.length(); i++) page.addView(text("• " + tips.optString(i), 15, Color.WHITE, false));
        if (hasOrd) showOrdinals(nums.optJSONObject("ordinals"));
    }

    private void showOrdinals(JSONObject ords) {
        TextView t = title("1st, 2nd, 3rd…");
        t.setPadding(0, dp(26), 0, dp(4));
        page.addView(t);
        page.addView(text("Ordinal numbers say which one in order. Tap any row to hear it.", 15, MUTED, false));
        page.addView(button("Take the ordinals quiz (10 questions)", GOOD, v -> startNumberQuiz(true)), full(0));
        JSONArray groups = ords.optJSONArray("groups");
        for (int g = 0; g < groups.length(); g++) {
            JSONObject grp = groups.optJSONObject(g);
            page.addView(header(grp.optString("title")));
            page.addView(text(grp.optString("note"), 14, MUTED, false));
            JSONArray items = grp.optJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                LinearLayout row = (LinearLayout) numberRow(it);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                col.addView(text("она " + it.optString("ru_f") + "  ·  оно " + it.optString("ru_n"), 13, MUTED, false));
                page.addView(row);
            }
        }
        page.addView(header("Dates"));
        page.addView(text("Day (the -ое form) + month: пятое мая = May 5th.", 14, MUTED, false));
        JSONArray dates = ords.optJSONArray("dates");
        for (int i = 0; i < dates.length(); i++) page.addView(itemCard(dates.optJSONObject(i), true));
        page.addView(header("Months"));
        page.addView(text("Name of the month · the form used in dates (пятое мая).", 14, MUTED, false));
        JSONArray months = ords.optJSONArray("months");
        for (int i = 0; i < months.length(); i++) {
            JSONObject m = months.optJSONObject(i);
            JSONObject row = new JSONObject();
            try {
                row.put("digits", m.optString("en"));
                row.put("ru", m.optString("of"));
                row.put("pron", m.optString("pron") + "  ·  " + m.optString("pron_of"));
            } catch (Exception ignored) { }
            LinearLayout r = (LinearLayout) numberRow(row);
            ((TextView) r.getChildAt(0)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            ((TextView) ((LinearLayout) r.getChildAt(1)).getChildAt(0)).setText(m.optString("ru") + "  ·  " + m.optString("of"));
            page.addView(r);
        }
        page.addView(header("In use"));
        JSONArray ex = ords.optJSONArray("examples");
        for (int i = 0; i < ex.length(); i++) page.addView(itemCard(ex.optJSONObject(i), true));
        page.addView(header("How ordinals work"));
        JSONArray tips = ords.optJSONArray("tips");
        for (int i = 0; i < tips.length(); i++) page.addView(text("• " + tips.optString(i), 15, Color.WHITE, false));
    }

    /** One tappable line in the say-any-number box: optional label, Russian, pronunciation, speaker. */
    private View sayLine(String russian, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        if (label != null) {
            TextView l = text(label, 12, 0xFF9CC3FF, false);
            l.setMinWidth(dp(84));
            l.setMaxWidth(dp(96));
            row.addView(l);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(russian, 22, Color.WHITE, true));
        col.addView(text(pron().pronounce(russian), 17, GOLD, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text("🔊", 20, 0xFF9CC3FF, false));
        row.setOnClickListener(v -> Voice.say(this, russian));
        return row;
    }

    private View numberRow(JSONObject it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(CARD));
        row.setPadding(dp(14), dp(10), dp(10), dp(10));
        TextView digits = text(it.optString("digits"), 22, ACCENT, true);
        digits.setMinWidth(dp(96));
        row.addView(digits);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(it.optString("ru"), 18, Color.WHITE, false));
        col.addView(text(it.optString("pron"), 14, GOLD, false));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView spk = text("🔊", 20, 0xFF9CC3FF, false);
        spk.setPadding(dp(8), 0, dp(6), 0);
        row.addView(spk);
        final String ru = it.optString("ru");
        row.setOnClickListener(v -> Voice.say(this, ru));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);
        return row;
    }

    private long randomQuizNumber() {
        int r = rnd.nextInt(10);
        if (r < 3) return rnd.nextInt(20);
        if (r < 5) return 20 + rnd.nextInt(80);
        if (r < 7) return 100 + rnd.nextInt(900);
        if (r < 9) return 1000 + rnd.nextInt(99000);
        return 100000L + rnd.nextInt(9900000);
    }

    private void startNumberQuiz(boolean ordinals) {
        List<Q> qs = new ArrayList<>();
        String[] gName = {"он", "она", "оно"};
        char[] gs = {'m', 'f', 'n'};
        for (int n = 0; n < 10; n++) {
            long num = ordinals ? (n < 6 ? 1 + rnd.nextInt(20) : Math.max(1, randomQuizNumber())) : randomQuizNumber();
            Q q = new Q();
            List<Long> opts = new ArrayList<>();
            opts.add(num);
            while (opts.size() < 4) {
                long d = Math.max(ordinals ? 1 : 0, num + (rnd.nextBoolean() ? 1 : -1) * (1 + rnd.nextInt((int) Math.max(3, Math.min(num / 3 + 3, 5000)))));
                if (rnd.nextInt(3) == 0) d = Math.max(ordinals ? 1 : 0, randomQuizNumber());
                if (ordinals && num <= 20) d = 1 + rnd.nextInt(20);
                if (!opts.contains(d)) opts.add(d);
            }
            Collections.shuffle(opts, rnd);
            q.choices = new ArrayList<>();
            if (ordinals) {
                int g = n % 3 == 2 ? (n % 2 == 1 ? 1 : 2) : 0;
                if (n % 2 == 0) {
                    q.prompt = "How do you say this (" + gName[g] + " form)?";
                    q.big = RussianNumbers.ordinalSuffix(num);
                    q.answer = RussianNumbers.toOrdinal(num, gs[g]);
                    for (long o : opts) q.choices.add(RussianNumbers.toOrdinal(o, gs[g]));
                } else {
                    q.prompt = "Which one is this?";
                    q.big = RussianNumbers.toOrdinal(num, gs[g]);
                    q.answer = RussianNumbers.ordinalSuffix(num);
                    for (long o : opts) q.choices.add(RussianNumbers.ordinalSuffix(o));
                }
            } else if (n % 2 == 0) {
                q.prompt = "How do you say this number?";
                q.big = RussianNumbers.formatDigits(num);
                q.answer = RussianNumbers.toWords(num);
                for (long o : opts) q.choices.add(RussianNumbers.toWords(o));
            } else {
                q.prompt = "Which number is this?";
                q.big = RussianNumbers.toWords(num);
                q.answer = RussianNumbers.formatDigits(num);
                for (long o : opts) q.choices.add(RussianNumbers.formatDigits(o));
            }
            qs.add(q);
        }
        showQuestion(ordinals ? -3 : -1, qs, 0, 0);
    }

    // ------------------------------------------------------------------ colors

    private void showColors() {
        clear("colors");
        JSONObject cols = data.optJSONObject("colors");
        page.addView(back());
        page.addView(title("Colors"));
        page.addView(text("Tap a color to hear it.", 14, MUTED, false));
        JSONArray items = cols.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject c = items.optJSONObject(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(CARD));
            row.setPadding(dp(12), dp(10), dp(10), dp(10));
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor(c.optString("hex")));
            g.setStroke(dp(1), 0x55FFFFFF);
            sw.setBackground(g);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
            slp.setMargins(0, 0, dp(14), 0);
            row.addView(sw, slp);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(c.optString("ru"), 20, Color.WHITE, true));
            col.addView(text(c.optString("pron") + "   ·   " + c.optString("en"), 14, GOLD, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text("🔊", 20, 0xFF9CC3FF, false));
            final String ru = c.optString("ru");
            row.setOnClickListener(v -> Voice.say(this, ru));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            page.addView(row, lp);
        }
        page.addView(button("Take the colors quiz (10 questions)", GOOD, v -> startColorQuiz()), full(0));
        page.addView(header("Good to know"));
        JSONArray tips = cols.optJSONArray("tips");
        for (int i = 0; i < tips.length(); i++) page.addView(text("• " + tips.optString(i), 15, Color.WHITE, false));
        page.addView(header("Examples"));
        JSONArray ex = cols.optJSONArray("examples");
        for (int i = 0; i < ex.length(); i++) page.addView(itemCard(ex.optJSONObject(i), true));
    }

    private void startColorQuiz() {
        JSONArray items = data.optJSONObject("colors").optJSONArray("items");
        List<JSONObject> all = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) all.add(items.optJSONObject(i));
        List<Q> qs = new ArrayList<>();
        for (int n = 0; n < 10; n++) {
            JSONObject c = all.get(rnd.nextInt(all.size()));
            Q q = new Q();
            if (n % 2 == 0) {
                q.prompt = "What color is this word?";
                q.big = c.optString("ru");
                q.answer = c.optString("en");
                q.choices = choices(q.answer, all, "en");
            } else {
                q.prompt = "How do you say \"" + c.optString("en") + "\" in Russian?";
                q.big = "●";
                q.swatch = c.optString("hex");
                q.answer = c.optString("ru");
                q.choices = choices(q.answer, all, "ru");
            }
            qs.add(q);
        }
        showQuestion(-2, qs, 0, 0);
    }

    // ------------------------------------------------------------------ phrases & verses

    private void showList(String key) {
        clear("list");
        JSONArray items = data.optJSONArray(key);
        boolean verses = key.equals("verses");
        page.addView(back());
        boolean military = key.equals("military");
        page.addView(title(verses ? "Bible verses" : military ? "Military phrases" : "Common phrases"));
        page.addView(text(verses ? "Russian Synodal translation with the King James Version. Gold = how to say it."
                : military ? "Short field commands, said loud and sharp. Gold = how to say it."
                : "Gold = how to say it. The CAPITAL syllable is stressed.", 14, MUTED, false));
        page.addView(button("Practice as flashcards", ACCENT, v -> showItemCard(key, 0, false)), full(0));
        String lastCat = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            String cat = it.optString("category", "");
            if (!cat.isEmpty() && !cat.equals(lastCat)) { page.addView(header(cat)); lastCat = cat; }
            page.addView(itemCard(it, true));
        }
    }

    private View itemCard(JSONObject it, boolean showAll) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD));
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        String ref = it.optString("ref", "");
        if (!ref.isEmpty()) box.addView(text(ref, 13, ACCENT, true));
        box.addView(text(it.optString("ru"), 20, Color.WHITE, false));
        if (showAll) {
            box.addView(text(it.optString("pron"), 16, GOLD, false));
            box.addView(text(it.optString("en"), 15, MUTED, false));
            TextView hear = text("🔊  Hear it", 15, ACCENT, true);
            hear.setPadding(0, dp(6), 0, 0);
            final String ru = it.optString("ru");
            hear.setOnClickListener(v -> Voice.say(this, ru));
            box.addView(hear);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        box.setLayoutParams(lp);
        return box;
    }

    private void showItemCard(String key, int i, boolean revealed) {
        JSONArray items = data.optJSONArray(key);
        int n = items.length();
        JSONObject it = items.optJSONObject(((i % n) + n) % n);
        clear("itemcard");
        Button backBtn = button("←  Back", BTN, v -> showList(key));
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        page.addView(backBtn);
        page.addView(text("Card " + (((i % n) + n) % n + 1) + " of " + n + " - read it out loud, then tap Show",
                14, MUTED, false));
        String ref = it.optString("ref", "");
        if (!ref.isEmpty()) page.addView(text(ref, 15, ACCENT, true));
        TextView ru = text(it.optString("ru"), key.equals("verses") ? 24 : 32, Color.WHITE, true);
        ru.setPadding(0, dp(16), 0, dp(16));
        page.addView(ru);
        final TextView ans = text(it.optString("pron") + "\n\n" + it.optString("en"), 19, GOLD, false);
        ans.setVisibility(revealed ? View.VISIBLE : View.INVISIBLE);
        page.addView(ans);
        page.addView(speakButton("🔊  Hear it", it.optString("ru")));
        page.addView(button("Show", BTN, v -> ans.setVisibility(View.VISIBLE)), full(0));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = button("←  Previous", BTN, v -> showItemCard(key, i - 1, false));
        Button next = button("Next  →", ACCENT, v -> showItemCard(key, i + 1, false));
        prev.setGravity(Gravity.CENTER);
        next.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setMargins(0, dp(5), dp(6), dp(5));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setMargins(dp(6), dp(5), 0, dp(5));
        nav.addView(prev, a);
        nav.addView(next, b);
        page.addView(nav);
    }

    // ------------------------------------------------------------------ quiz

    private static final class Q {
        String prompt, big, answer, swatch;
        List<String> choices;
    }

    private void startQuiz(int lessonIdx) {
        JSONObject les = data.optJSONArray("lessons").optJSONObject(lessonIdx);
        List<JSONObject> pool = new ArrayList<>();   // all words up to this lesson
        List<JSONObject> letters = new ArrayList<>();
        JSONArray lessons = data.optJSONArray("lessons");
        for (int i = 0; i <= lessonIdx; i++) {
            JSONArray ws = lessons.optJSONObject(i).optJSONArray("words");
            for (int k = 0; k < ws.length(); k++) pool.add(ws.optJSONObject(k));
            JSONArray ls = lessons.optJSONObject(i).optJSONArray("letters");
            for (int k = 0; k < ls.length(); k++) { JSONObject l = letter(ls.optString(k)); if (l != null) letters.add(l); }
        }
        List<JSONObject> newWords = new ArrayList<>();
        JSONArray ws = les.optJSONArray("words");
        for (int k = 0; k < ws.length(); k++) newWords.add(ws.optJSONObject(k));
        List<JSONObject> newLetters = new ArrayList<>();
        JSONArray ls = les.optJSONArray("letters");
        for (int k = 0; k < ls.length(); k++) { JSONObject l = letter(ls.optString(k)); if (l != null) newLetters.add(l); }

        List<Q> qs = new ArrayList<>();
        for (int n = 0; n < 10; n++) {
            Q q = new Q();
            int type = n % 3;
            if (type == 0 && newLetters.size() > 0 && letters.size() >= 4) {
                JSONObject l = newLetters.get(rnd.nextInt(newLetters.size()));
                q.prompt = "What sound does this letter make?";
                q.big = l.optString("upper") + " " + l.optString("lower");
                q.answer = l.optString("sound");
                q.choices = choices(q.answer, letters, "sound");
            } else if ((type == 1 || newLetters.isEmpty()) && newWords.size() > 0 && pool.size() >= 4) {
                JSONObject w = newWords.get(rnd.nextInt(newWords.size()));
                q.prompt = "How do you read this word?";
                q.big = w.optString("word");
                q.answer = w.optString("pron");
                q.choices = choices(q.answer, pool, "pron");
            } else if (newWords.size() > 0 && pool.size() >= 4) {
                JSONObject w = newWords.get(rnd.nextInt(newWords.size()));
                q.prompt = "What does this word mean?";
                q.big = w.optString("word");
                q.answer = w.optString("meaning");
                q.choices = choices(q.answer, pool, "meaning");
            } else {
                continue;
            }
            qs.add(q);
        }
        showQuestion(lessonIdx, qs, 0, 0);
    }

    private List<String> choices(String answer, List<JSONObject> pool, String field) {
        List<String> out = new ArrayList<>();
        out.add(answer);
        List<JSONObject> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, rnd);
        for (JSONObject o : shuffled) {
            String c = o.optString(field);
            if (!c.isEmpty() && !out.contains(c)) out.add(c);
            if (out.size() == 4) break;
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    private void showQuestion(int lessonIdx, List<Q> qs, int i, int score) {
        if (i >= qs.size()) { showResult(lessonIdx, score, qs.size()); return; }
        clear("quiz");
        Q q = qs.get(i);
        page.addView(back());
        page.addView(text("Question " + (i + 1) + " of " + qs.size() + "   ·   score " + score, 14, MUTED, false));
        page.addView(text(q.prompt, 18, Color.WHITE, false));
        TextView big = text(q.big, q.big.length() > 14 ? 30 : 52, Color.WHITE, true);
        if (q.swatch != null) { big.setTextColor(Color.parseColor(q.swatch)); big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 96); }
        big.setGravity(Gravity.CENTER);
        big.setPadding(0, dp(20), 0, dp(20));
        page.addView(big);
        final TextView feedback = text("", 18, Color.WHITE, true);
        final List<Button> btns = new ArrayList<>();
        for (final String c : q.choices) {
            Button btn = button(c, BTN, null);
            btn.setOnClickListener(v -> {
                boolean right = c.equals(q.answer);
                for (Button x : btns) {
                    x.setEnabled(false);
                    if (x.getText().toString().equals(q.answer)) x.setBackground(round(GOOD));
                }
                if (!right) v.setBackground(round(BAD));
                feedback.setText(right ? "Correct!" : "Not quite. It's: " + q.answer);
                Button next = button(i + 1 < qs.size() ? "Next  →" : "See score", ACCENT,
                        w -> showQuestion(lessonIdx, qs, i + 1, score + (right ? 1 : 0)));
                page.addView(next, full(dp(60)));
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
            btns.add(btn);
            page.addView(btn, full(dp(60)));
        }
        page.addView(feedback);
    }

    private void showResult(int lessonIdx, int score, int total) {
        clear("result");
        int best = prefs.getInt("best_" + lessonIdx, -1);
        if (score > best) prefs.edit().putInt("best_" + lessonIdx, score).apply();
        page.addView(title("Score: " + score + " / " + total));
        String good = lessonIdx == -1 ? "Great - your numbers are solid!" : lessonIdx == -2 ? "Great - you know your colors!"
                : lessonIdx == -3 ? "Great - you know your 1st, 2nd, 3rd!"
                : "Great - you've got these letters. On to the next lesson!";
        page.addView(text(score >= 8 ? good : "Keep practicing, then retake the quiz.", 18, Color.WHITE, false));
        page.addView(button("Retake quiz", BTN, v -> {
            if (lessonIdx == -1) startNumberQuiz(false);
            else if (lessonIdx == -3) startNumberQuiz(true);
            else if (lessonIdx == -2) startColorQuiz();
            else startQuiz(lessonIdx);
        }), full(dp(60)));
        page.addView(button("Back to lessons", ACCENT, v -> showHome()), full(dp(60)));
    }

    // ------------------------------------------------------------------ helpers

    private JSONObject letter(String upper) {
        JSONArray abc = data.optJSONArray("alphabet");
        for (int i = 0; i < abc.length(); i++) {
            if (abc.optJSONObject(i).optString("upper").equals(upper)) return abc.optJSONObject(i);
        }
        return null;
    }

    private static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) { if (i > 0) b.append(' '); b.append(a.optString(i)); }
        return b.toString();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        return g;
    }

    private LinearLayout.LayoutParams full(int ignored) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        return lp;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        GradientDrawable bg = round(color);
        if (color == BTN) bg.setStroke(dp(1), BORDER);
        b.setBackground(bg);
        b.setMinHeight(dp(52));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setStateListAnimator(null);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView title(String s) { return text(s, 26, Color.WHITE, true); }

    private TextView header(String s) {
        TextView t = text(s, 20, Color.WHITE, true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private View card(String label, String body) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(text(label, 14, MUTED, false));
        box.addView(text(body, 20, Color.WHITE, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        box.setLayoutParams(lp);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        return box;
    }

    private Button speakButton(String label, String russian) {
        Button b = button(label, 0xFF1B2A45, v -> Voice.say(this, russian));
        b.setTextColor(0xFF9CC3FF);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(full(0));
        return b;
    }

    private Button back() {
        Button b = button("←  Back", BTN, v -> onBackPressed());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return b;
    }
}
