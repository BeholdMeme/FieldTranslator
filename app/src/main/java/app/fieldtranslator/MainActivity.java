package app.fieldtranslator;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import app.translator.core.MarianTranslator;
import app.translator.core.Pronouncer;

/**
 * One screen:  [ direction toggle ]  [ transcript + translations ]  [ LISTEN ]
 *
 * Speech -> text: Russian = streaming Zipformer (sherpa-onnx), English = Vosk small model
 * Text -> text:   Opus-MT through ONNX Runtime (on each pause, plus live previews)
 * Everything runs on the phone. Only one language's models are in memory at a time.
 */
public class MainActivity extends Activity {

    enum Dir {
        RU_EN("ru-zipformer", "mt/ru-en", "RUSSIAN  →  ENGLISH", "speak Russian", 0xFFE5484D),
        EN_RU("en", "mt/en-ru", "ENGLISH  →  RUSSIAN", "speak English", 0xFF3E8CFF);

        final String asr, mt, label, hint;
        final int color;

        Dir(String asr, String mt, String label, String hint, int color) {
            this.asr = asr; this.mt = mt; this.label = label; this.hint = hint; this.color = color;
        }

        Dir other() { return this == RU_EN ? EN_RU : RU_EN; }
    }

    private static final int REQ_MIC = 1;
    private static final int MAX_ENTRIES = 150;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Single worker thread: model loading and all translations run here, in order. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingJobs = new AtomicInteger();

    // engine state (touched only on the worker thread)
    private SpeechEngine asrEngine;
    private MarianTranslator translator;
    private Dir loadedDir;

    // UI-thread state
    private Dir dir = Dir.RU_EN;
    private boolean installed, ready, listening, resumeAfterLoad;
    private SpeechEngine readyEngine;   // the engine the UI may open sessions on
    private MicCapture mic;
    private long lastPreviewAt;
    private String lastPreviewSrc = "";
    private float textScale = 1f;
    private boolean pronMode;
    private volatile Pronouncer pronouncer;
    /** pronunciation line of each entry + the Russian text it is for */
    private final java.util.List<Object[]> pronLines = new java.util.ArrayList<>();
    private Button pronButton;

    // views
    private Button listenButton;
    private TextView status, partialView, previewView;
    private View levelBar;
    private LinearLayout log;
    private ScrollView scroll;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        try {
            dir = Dir.valueOf(prefs.getString("dir", Dir.RU_EN.name()));
        } catch (IllegalArgumentException e) {
            dir = Dir.RU_EN;
        }
        textScale = prefs.getFloat("scale", 1f);
        pronMode = prefs.getBoolean("pron", false);
        buildUi();
        refreshUi();
        installAndLoad();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening) stopListening();   // release the mic when the app is not on screen
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
        worker.execute(this::releaseEngine);
        worker.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            setStatus("Microphone permission is needed. Allow it in Settings → Apps → " + getString(R.string.app_name));
        }
    }

    // ------------------------------------------------------------------ engine (worker thread)

    private void installAndLoad() {
        setStatus("Starting…");
        submit(() -> {
            try {
                ModelInstaller.ensureInstalled(this, (done, total) -> {
                    String msg = total > 0
                            ? String.format(java.util.Locale.US, "Preparing offline models (first launch only)… %d%%", done * 100 / total)
                            : String.format(java.util.Locale.US, "Preparing offline models (first launch only)… %d MB", done >> 20);
                    ui.post(() -> setStatus(msg));
                });
                ui.post(() -> installed = true);
                try {
                    pronouncer = new Pronouncer(new Pronouncer.StressDict(
                            new File(ModelInstaller.root(this), "learn/stress.tsv")));
                    ui.post(this::fillPronunciations);
                } catch (Throwable t) {
                    android.util.Log.w("FieldTranslator", "pronunciation unavailable", t);
                }
            } catch (Throwable t) {
                ui.post(() -> setStatus("Setup failed: " + t.getMessage()));
                return;
            }
            ui.post(() -> loadDirection(dir));
        });
    }

    /** UI thread: (re)load models for a direction on the worker. */
    private void loadDirection(Dir target) {
        ready = false;
        readyEngine = null;
        refreshUi();
        setStatus("Loading " + (target == Dir.RU_EN ? "Russian" : "English") + " models…");
        submit(() -> {
            try {
                if (loadedDir != target) {
                    releaseEngine();
                    File root = ModelInstaller.root(this);
                    long t0 = SystemClock.elapsedRealtime();
                    File asrDir = new File(root, "asr/" + target.asr);
                    asrEngine = target == Dir.RU_EN
                            ? new SpeechEngine.Zipformer(asrDir, 2)
                            : new SpeechEngine.Vosk(asrDir);
                    int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
                    translator = new MarianTranslator(new File(root, target.mt), threads);
                    translator.translate("ok");   // warm-up so the first real phrase is fast
                    loadedDir = target;
                    android.util.Log.i("FieldTranslator", "loaded " + target + " in "
                            + (SystemClock.elapsedRealtime() - t0) + " ms");
                }
                final SpeechEngine e = asrEngine;
                ui.post(() -> {
                    if (dir != target) return;   // user toggled again meanwhile
                    readyEngine = e;
                    ready = true;
                    setStatus("Ready. Tap LISTEN and " + target.hint + ".");
                    refreshUi();
                    if (resumeAfterLoad) {
                        resumeAfterLoad = false;
                        startListening();
                    }
                });
            } catch (Throwable t) {
                releaseEngine();
                ui.post(() -> setStatus("Could not load models: " + t.getMessage()));
            }
        });
    }

    private void releaseEngine() {
        if (translator != null) { translator.close(); translator = null; }
        if (asrEngine != null) { asrEngine.close(); asrEngine = null; }
        loadedDir = null;
    }

    private void submit(Runnable r) {
        pendingJobs.incrementAndGet();
        try {
            worker.execute(() -> {
                try { r.run(); } finally { pendingJobs.decrementAndGet(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pendingJobs.decrementAndGet();
        }
    }

    /** Translate on the worker; only if the loaded models are still for {@code d}. */
    private void translateAsync(Dir d, String text, Callback cb) {
        submit(() -> {
            String out;
            if (loadedDir != d || translator == null) return;
            try {
                out = translator.translate(text);
            } catch (Throwable t) {
                out = "[translation error: " + t.getMessage() + "]";
            }
            final String res = out;
            ui.post(() -> cb.done(res));
        });
    }

    interface Callback { void done(String result); }

    // ------------------------------------------------------------------ listening (UI thread)

    private void startListening() {
        if (!ready || listening || readyEngine == null) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        final Dir d = dir;
        try {
            SpeechEngine.Session session = readyEngine.open();
            mic = new MicCapture(session, ui, new MicCapture.Callback() {
                @Override public void partial(String text) {
                    if (d != dir || !listening) return;
                    partialView.setText(text);
                    partialView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                    maybePreview(d, text);
                }
                @Override public void fin(String text) { finalText(d, text); }
                @Override public void level(float lvl) { if (listening) setLevel(lvl); }
                @Override public void silent() {
                    setStatus("The microphone is sending silence. Check that no other app is using it, "
                            + "and that the phone's mic isn't covered or muted.");
                }
                @Override public void error(String message) {
                    setStatus(message);
                    stopListening();
                }
            });
            mic.start();
            listening = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("Listening… " + dir.hint);
        } catch (Exception e) {
            mic = null;
            setStatus("Microphone error: " + e.getMessage());
        }
        refreshUi();
    }

    private void stopListening() {
        if (mic != null) {
            mic.shutdown();   // the last phrase is posted to this thread's queue before it returns
            mic = null;
        }
        if (listening) {
            listening = false;
            setLevel(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (ready) setStatus("Stopped. Tap LISTEN to continue.");
        }
        refreshUi();
    }

    private void setLevel(float lvl) {
        if (levelBar == null) return;
        levelBar.setPivotX(0);
        levelBar.setScaleX(Math.max(0f, Math.min(1f, lvl * 4f)));
    }

    /** While someone is still talking, show a rough live translation of what's heard so far. */
    private void maybePreview(Dir d, String text) {
        long now = SystemClock.elapsedRealtime();
        int words = text.isEmpty() ? 0 : text.split(" ").length;
        if (words < 4 || pendingJobs.get() > 0 || now - lastPreviewAt < 1500 || text.equals(lastPreviewSrc)) return;
        lastPreviewAt = now;
        lastPreviewSrc = text;
        translateAsync(d, text, res -> {
            if (d != dir || partialView.getVisibility() != View.VISIBLE) return;
            previewView.setText(res);
            previewView.setVisibility(View.VISIBLE);
        });
    }

    private void finalText(Dir d, String text) {
        partialView.setText("");
        partialView.setVisibility(View.GONE);
        previewView.setText("");
        previewView.setVisibility(View.GONE);
        lastPreviewSrc = "";
        if (text.trim().isEmpty()) return;
        final TextView[] entry = addEntry(d, text);
        if (d == Dir.RU_EN) setPron(entry[2], text);
        translateAsync(d, text, res -> {
            entry[1].setText(res.isEmpty() ? "—" : res);
            if (d == Dir.EN_RU && !res.isEmpty()) setPron(entry[2], res);
        });
    }

    // ------------------------------------------------------------------ UI
    // Plain views + GradientDrawables only (no images, no extra libraries) -> tiny memory use.

    static final int BG = 0xFF0E1116, SURFACE = 0xFF171B22, SURFACE2 = 0xFF20252E, BORDER = 0xFF2A303A,
            TEXT = 0xFFE8EBF0, MUTED = 0xFF8B93A1, GREEN = 0xFF2FB36D, RED_STOP = 0xFFE5484D,
            GOLD = 0xFFF5C451, CYAN = 0xFF7FD1E8;

    private Button segRu, segEn;
    private TextView emptyHint;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color) { return rounded(color, 14); }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable outlined(int fill, int stroke, float radiusDp) {
        GradientDrawable g = rounded(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setStateListAnimator(null);
        return b;
    }

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));

        // --- header: title + offline chip
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Field Translator", 22, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip = label("●  Offline", 12, GREEN);
        chip.setBackground(outlined(0x1A2FB36D, 0x552FB36D, 12));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        header.addView(chip);
        root.addView(header);

        // --- direction: segmented control
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(SURFACE, 16));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        // two short lines per option so it never wraps or gets clipped on narrow phones
        segRu = bigButton("");
        segEn = bigButton("");
        segRu.setText(segLabel("RU  →  EN", "Russian to English"));
        segEn.setText(segLabel("EN  →  RU", "English to Russian"));
        for (Button b : new Button[]{segRu, segEn}) {
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            b.setMinHeight(dp(56));
            b.setMinimumHeight(dp(56));
            b.setPadding(dp(4), dp(8), dp(4), dp(8));
            b.setMaxLines(2);
            b.setLineSpacing(0, 1.05f);
        }
        segRu.setOnClickListener(v -> { if (dir != Dir.RU_EN) toggleDirection(); });
        segEn.setOnClickListener(v -> { if (dir != Dir.EN_RU) toggleDirection(); });
        seg.addView(segRu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seg.addView(segEn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.setMargins(0, dp(12), 0, dp(10));
        root.addView(seg, segLp);

        // --- conversation
        android.widget.FrameLayout convo = new android.widget.FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        convo.addView(scroll);
        emptyHint = label("Pick a direction, tap Listen,\nand start talking.\n\nTranslations appear here.", 16, MUTED);
        emptyHint.setGravity(Gravity.CENTER);
        convo.addView(emptyHint, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(convo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // --- live box (what is being heard right now)
        LinearLayout live = new LinearLayout(this);
        live.setOrientation(LinearLayout.VERTICAL);
        partialView = label("", 18, 0xFFB7BEC9);
        partialView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        partialView.setVisibility(View.GONE);
        partialView.setPadding(dp(4), dp(8), dp(4), 0);
        live.addView(partialView);
        previewView = label("", 20, CYAN);
        previewView.setVisibility(View.GONE);
        previewView.setPadding(dp(4), dp(2), dp(4), dp(4));
        live.addView(previewView);
        root.addView(live);

        // --- control panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(SURFACE, 20));
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.setMargins(0, dp(8), 0, 0);

        status = label("", 13, MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(8));
        panel.addView(status);

        levelBar = new View(this);
        android.widget.FrameLayout levelBox = new android.widget.FrameLayout(this);
        levelBox.setBackground(rounded(SURFACE2, 3));
        levelBar.setBackground(rounded(GREEN, 3));
        levelBox.addView(levelBar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        llp.setMargins(dp(2), 0, dp(2), dp(10));
        panel.addView(levelBox, llp);
        setLevel(0);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        pronButton = smallButton("", v -> togglePron());
        Button learn = smallButton("Learn", v -> startActivity(new android.content.Intent(this, LearnActivity.class)));
        Button smaller = smallButton("A−", v -> setScale(textScale / 1.15f));
        Button larger = smallButton("A+", v -> setScale(textScale * 1.15f));
        Button clear = smallButton("Clear", v -> { log.removeAllViews(); pronLines.clear(); updateEmpty(); });
        addChip(chips, pronButton, 1.7f);
        addChip(chips, learn, 1.2f);
        addChip(chips, smaller, 0.8f);
        addChip(chips, larger, 0.8f);
        addChip(chips, clear, 1.1f);
        panel.addView(chips);

        listenButton = bigButton("Listen");
        listenButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        listenButton.setOnClickListener(v -> {
            if (listening) stopListening(); else startListening();
        });
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        lbLp.setMargins(0, dp(10), 0, 0);
        panel.addView(listenButton, lbLp);
        root.addView(panel, panelLp);

        updatePronButton();
        setContentView(root);
        applyScale();
        updateEmpty();
    }

    private CharSequence segLabel(String big, String small) {
        android.text.SpannableString sp = new android.text.SpannableString(big + "\n" + small);
        sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, big.length(), 0);
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.7f), big.length() + 1, sp.length(), 0);
        return sp;
    }

    private void addChip(LinearLayout row, Button b, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), weight);
        lp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(b, lp);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(outlined(SURFACE2, BORDER, 20));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private void updateEmpty() {
        if (emptyHint != null) emptyHint.setVisibility(log.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void toggleDirection() {
        if (!installed) return;
        boolean wasListening = listening;
        stopListening();
        dir = dir.other();
        getPreferences(MODE_PRIVATE).edit().putString("dir", dir.name()).apply();
        resumeAfterLoad = wasListening;
        ready = false;
        refreshUi();
        // post (not call) so the old direction's final result, already queued by stop(),
        // is handled and queued for translation before the models are swapped
        ui.post(() -> loadDirection(dir));
    }

    private void refreshUi() {
        boolean ru = dir == Dir.RU_EN;
        segRu.setBackground(ru ? rounded(Dir.RU_EN.color, 12) : null);
        segEn.setBackground(!ru ? rounded(Dir.EN_RU.color, 12) : null);
        segRu.setTextColor(ru ? Color.WHITE : MUTED);
        segEn.setTextColor(!ru ? Color.WHITE : MUTED);
        listenButton.setEnabled(ready);
        if (listening) {
            listenButton.setText("■   Stop");
            listenButton.setBackground(rounded(RED_STOP, 36));
        } else {
            listenButton.setText(ready ? "●   Listen" : "Loading…");
            listenButton.setBackground(rounded(ready ? GREEN : SURFACE2, 36));
            listenButton.setTextColor(ready ? Color.WHITE : MUTED);
        }
        if (listening) listenButton.setTextColor(Color.WHITE);
    }

    private void setStatus(String s) { status.setText(s); }

    private TextView[] addEntry(Dir d, String original) {
        // card = colored stripe + content
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(rounded(SURFACE, 16));
        View stripe = new View(this);
        stripe.setBackground(rounded(d.color, 2));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT);
        stLp.setMargins(dp(10), dp(12), 0, dp(12));
        card.addView(stripe, stLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(14), dp(12));
        card.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView src = label(original, 15, MUTED);
        src.setTag("src");

        TextView dst = label("…", 22, TEXT);
        dst.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dst.setTag("dst");
        dst.setPadding(0, dp(4), 0, 0);
        dst.setLineSpacing(0, 1.1f);

        TextView pron = label("", 17, GOLD);
        pron.setTag("pron");
        pron.setPadding(0, dp(4), 0, 0);
        pron.setVisibility(View.GONE);

        box.addView(src);
        box.addView(dst);
        box.addView(pron);
        card.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("translation", dst.getText()));
                Toast.makeText(this, "Translation copied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        log.addView(card, lp);
        while (log.getChildCount() > MAX_ENTRIES) log.removeViewAt(0);
        styleEntry(card);
        updateEmpty();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return new TextView[]{src, dst, pron};
    }

    // ------------------------------------------------------------------ learning mode

    private void togglePron() {
        pronMode = !pronMode;
        getPreferences(MODE_PRIVATE).edit().putBoolean("pron", pronMode).apply();
        updatePronButton();
        fillPronunciations();
    }

    private void updatePronButton() {
        pronButton.setText(pronMode ? "Pronounce ✓" : "Pronounce");
        pronButton.setBackground(pronMode ? outlined(0x33F5C451, GOLD, 20) : outlined(SURFACE2, BORDER, 20));
        pronButton.setTextColor(pronMode ? GOLD : TEXT);
    }

    /** Remember which Russian text an entry's pronunciation line is for, and fill it in if possible. */
    private void setPron(TextView view, String russian) {
        pronLines.add(new Object[]{view, russian});
        if (pronLines.size() > MAX_ENTRIES * 2) pronLines.remove(0);
        fillPronunciations();
    }

    private void fillPronunciations() {
        Pronouncer p = pronouncer;
        for (Object[] e : pronLines) {
            TextView v = (TextView) e[0];
            if (p != null && v.getText().length() == 0) {
                try { v.setText(p.pronounce((String) e[1])); } catch (Exception ignored) { }
            }
            v.setVisibility(pronMode && v.getText().length() > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void setScale(float s) {
        textScale = Math.max(0.7f, Math.min(2.5f, s));
        getPreferences(MODE_PRIVATE).edit().putFloat("scale", textScale).apply();
        applyScale();
    }

    private void applyScale() {
        partialView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 * textScale);
        previewView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * textScale);
        for (int i = 0; i < log.getChildCount(); i++) styleEntry((ViewGroup) log.getChildAt(i));
    }

    private void styleEntry(ViewGroup box) {
        ((TextView) box.findViewWithTag("src")).setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * textScale);
        ((TextView) box.findViewWithTag("dst")).setTextSize(TypedValue.COMPLEX_UNIT_SP, 22 * textScale);
        ((TextView) box.findViewWithTag("pron")).setTextSize(TypedValue.COMPLEX_UNIT_SP, 17 * textScale);
    }
}
