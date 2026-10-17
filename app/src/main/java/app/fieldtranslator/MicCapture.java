package app.fieldtranslator;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;

import app.translator.core.StreamingAsr;

/**
 * Microphone thread: reads 16 kHz mono audio and feeds the speech engine.
 *
 * Some cheap phones return pure silence from the VOICE_RECOGNITION source, so if the
 * first ~1.5 s is digital silence we automatically retry with other mic sources.
 * All callbacks are delivered on the UI handler.
 */
final class MicCapture extends Thread {

    interface Callback {
        void partial(String text);
        void fin(String text);
        void level(float level);          // 0..1 peak, ~10x per second
        void silent();                    // no sound at all from any mic source
        void error(String message);
    }

    private static final int RATE = SpeechEngine.SAMPLE_RATE;
    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
    };

    private final SpeechEngine.Session session;
    private final Handler ui;
    private final Callback cb;
    private volatile boolean running = true;

    MicCapture(SpeechEngine.Session session, Handler ui, Callback cb) {
        super("mic");
        this.session = session;
        this.ui = ui;
        this.cb = cb;
    }

    /** Stops recording; returns after the last phrase has been posted. */
    void shutdown() {
        running = false;
        try { join(4000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    @Override public void run() {
        final StreamingAsr.Sink sink = new StreamingAsr.Sink() {
            @Override public void partial(String text) { ui.post(() -> cb.partial(text)); }
            @Override public void fin(String text) { ui.post(() -> cb.fin(text)); }
        };
        short[] buf = new short[RATE / 10];  // 100 ms
        AudioRecord rec = null;
        int sourceIdx = 0;
        try {
            rec = open(SOURCES[sourceIdx]);
            if (rec == null) { post("Could not open the microphone. Is another app using it?"); return; }
            long started = System.currentTimeMillis();
            boolean heardSomething = false;
            boolean warned = false;
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) { post("Microphone read error " + n); break; }
                if (n == 0) continue;
                int peak = 0;
                for (int i = 0; i < n; i++) {
                    int v = Math.abs(buf[i]);
                    if (v > peak) peak = v;
                }
                final float lvl = peak / 32768f;
                ui.post(() -> cb.level(lvl));
                if (peak > 2) heardSomething = true;

                if (!heardSomething && System.currentTimeMillis() - started > 1500) {
                    // digital silence: try the next microphone source
                    if (sourceIdx + 1 < SOURCES.length) {
                        AudioRecord next = null;
                        rec.stop();
                        rec.release();
                        rec = null;
                        while (next == null && ++sourceIdx < SOURCES.length) next = open(SOURCES[sourceIdx]);
                        if (next == null) { post("Could not open the microphone."); return; }
                        rec = next;
                        started = System.currentTimeMillis();
                        continue;
                    } else if (!warned) {
                        warned = true;
                        ui.post(cb::silent);
                    }
                }
                session.accept(buf, n, sink);
            }
            session.finish(sink);
        } catch (Throwable t) {
            post("Speech recognizer error: " + t.getMessage());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            session.close();
        }
    }

    private void post(String msg) { ui.post(() -> cb.error(msg)); }

    private static AudioRecord open(int source) {
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = RATE / 5;
            AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 2));  // >= 250 ms buffer
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return null; }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return null; }
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
