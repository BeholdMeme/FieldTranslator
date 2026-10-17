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

/**
 * One screen:  [ direction toggle ]  [ transcript + translations ]  [ LISTEN ]
 *
 * Speech -> text: Russian = streaming Zipformer (sherpa-onnx), English = Vosk small model
 * Text -> text:   Opus-MT through ONNX Runtime (on each pause, plus live previews)
 * Everything runs on the phone. Only one language's models are in memory at a time.
 */
public class MainActivity extends Activity {

    enum Dir {
        RU_EN("ru-zipformer", "mt/ru-en", "RUSSIAN  →  ENGLISH", "speak Russian", 0xFFC62828),
        EN_RU("en", "mt/en-ru", "ENGLISH  →  RUSSIAN", "speak English", 0xFF1565C0);

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

    // views
    private Button dirButton, listenButton;
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
        translateAsync(d, text, res -> entry[1].setText(res.isEmpty() ? "—" : res));
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private Button bigButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101214);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        dirButton = bigButton("");
        dirButton.setOnClickListener(v -> toggleDirection());
        root.addView(dirButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        TextView tip = new TextView(this);
        tip.setText("tap to switch language");
        tip.setTextColor(0xFF8A9099);
        tip.setGravity(Gravity.CENTER);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setPadding(0, dp(4), 0, dp(6));
        root.addView(tip);

        scroll = new ScrollView(this);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        partialView = new TextView(this);
        partialView.setTextColor(0xFFB0B6BE);
        partialView.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        partialView.setVisibility(View.GONE);
        partialView.setPadding(dp(4), dp(8), dp(4), 0);
        root.addView(partialView);

        previewView = new TextView(this);
        previewView.setTextColor(0xFF80DEEA);
        previewView.setVisibility(View.GONE);
        previewView.setPadding(dp(4), dp(2), dp(4), dp(4));
        root.addView(previewView);

        status = new TextView(this);
        status.setTextColor(0xFF8A9099);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        levelBar = new View(this);
        android.widget.FrameLayout levelBox = new android.widget.FrameLayout(this);
        levelBox.setBackgroundColor(0xFF2A2F36);
        levelBar.setBackgroundColor(0xFF43A047);
        levelBox.addView(levelBar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        llp.setMargins(dp(4), 0, dp(4), dp(8));
        root.addView(levelBox, llp);
        setLevel(0);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button smaller = smallButton("A−", v -> setScale(textScale / 1.15f));
        Button larger = smallButton("A+", v -> setScale(textScale * 1.15f));
        Button clear = smallButton("Clear", v -> log.removeAllViews());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), 0, dp(4), dp(8));
        tools.addView(smaller, lp);
        tools.addView(larger, new LinearLayout.LayoutParams(lp));
        tools.addView(clear, new LinearLayout.LayoutParams(lp));
        root.addView(tools);

        listenButton = bigButton("LISTEN");
        listenButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        listenButton.setOnClickListener(v -> {
            if (listening) stopListening(); else startListening();
        });
        root.addView(listenButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        setContentView(root);
        applyScale();
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setBackground(rounded(0xFF2A2F36));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
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
        dirButton.setText(dir.label);
        dirButton.setBackground(rounded(dir.color));
        listenButton.setEnabled(ready);
        if (listening) {
            listenButton.setText("■  STOP");
            listenButton.setBackground(rounded(0xFFD84315));
        } else {
            listenButton.setText(ready ? "●  LISTEN" : "Loading…");
            listenButton.setBackground(rounded(ready ? 0xFF2E7D32 : 0xFF3A3F45));
        }
    }

    private void setStatus(String s) { status.setText(s); }

    private TextView[] addEntry(Dir d, String original) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = rounded(0xFF1C2026);
        bg.setStroke(dp(2), d.color);
        box.setBackground(bg);

        TextView src = new TextView(this);
        src.setText(original);
        src.setTextColor(0xFFB0B6BE);
        src.setTag("src");

        TextView dst = new TextView(this);
        dst.setText("…");
        dst.setTextColor(Color.WHITE);
        dst.setTypeface(Typeface.DEFAULT_BOLD);
        dst.setTag("dst");
        dst.setPadding(0, dp(4), 0, 0);

        box.addView(src);
        box.addView(dst);
        box.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("translation", dst.getText()));
                Toast.makeText(this, "Translation copied", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        log.addView(box, lp);
        while (log.getChildCount() > MAX_ENTRIES) log.removeViewAt(0);
        styleEntry(box);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return new TextView[]{src, dst};
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
        ((TextView) box.findViewWithTag("src")).setTextSize(TypedValue.COMPLEX_UNIT_SP, 16 * textScale);
        ((TextView) box.findViewWithTag("dst")).setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * textScale);
    }
}
