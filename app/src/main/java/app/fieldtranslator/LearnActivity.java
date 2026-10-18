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
        page.addView(card("Name", l.optString("name")));
        page.addView(card("Sound", l.optString("sound")));
        page.addView(card("Example", l.optString("example") + "\n" + l.optString("example_pron")
                + "\n" + l.optString("example_meaning")));
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
        page.addView(button("Show", BTN, v -> answer.setVisibility(View.VISIBLE)), full(dp(60)));
        page.addView(button("Next  →", ACCENT, v -> showCard(lessonIdx, i + 1)), full(dp(60)));
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
        String prompt, big, answer;
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
        TextView big = text(q.big, 52, Color.WHITE, true);
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
        page.addView(text(score >= 8 ? "Great - you've got these letters. On to the next lesson!"
                : "Keep practicing: try the flashcards again, then retake the quiz.", 18, Color.WHITE, false));
        page.addView(button("Retake quiz", BTN, v -> startQuiz(lessonIdx)), full(dp(60)));
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

    private Button back() {
        Button b = button("←  Back", BTN, v -> onBackPressed());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return b;
    }
}
