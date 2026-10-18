package app.fieldtranslator;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.translator.core.Speaker;

/**
 * "Read aloud" for Russian text. The voice (~20 MB model) is loaded only the first time
 * a speaker button is tapped, and unloaded again after 2 minutes of not being used, so
 * it costs nothing while you're just translating.
 */
final class Voice {
    private static final long IDLE_UNLOAD_MS = 2 * 60 * 1000;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Speaker speaker;          // worker thread only
    private static AudioTrack track;         // worker thread only
    private static volatile int generation;  // newest request wins
    private static final Runnable unload = () -> worker.execute(Voice::release);

    private Voice() { }

    static void say(Context ctx, String russian) {
        if (russian == null || russian.trim().isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        final int gen = ++generation;
        ui.removeCallbacks(unload);
        worker.execute(() -> {
            try {
                if (gen != generation) return;           // a newer tap replaced this one
                if (speaker == null) {
                    speaker = new Speaker(new File(ModelInstaller.root(app), "tts/ru"), 2);
                }
                float[] pcm = speaker.synth(russian, Speaker.LEARNER_SPEED);
                if (gen != generation || pcm.length == 0) return;
                play(pcm, speaker.sampleRate());
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(app, "Voice unavailable: " + t.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                ui.removeCallbacks(unload);
                ui.postDelayed(unload, IDLE_UNLOAD_MS);
            }
        });
    }

    private static void play(float[] pcm, int rate) {
        stopTrack();
        short[] s = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            float v = Math.max(-1f, Math.min(1f, pcm[i]));
            s[i] = (short) (v * 32767);
        }
        AudioTrack t = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                s.length * 2, AudioTrack.MODE_STATIC, 0);
        t.write(s, 0, s.length);
        t.play();
        track = t;
    }

    private static void stopTrack() {
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }

    private static void release() {
        stopTrack();
        if (speaker != null) { speaker.close(); speaker = null; }
    }

    /** Free everything now (e.g. when the app closes). */
    static void shutdown() {
        generation++;
        ui.removeCallbacks(unload);
        worker.execute(Voice::release);
    }
}
