package app.fieldtranslator;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.IOException;

import app.translator.core.StreamingAsr;

/** Speech-to-text for one language. Sessions are used from the microphone thread only. */
interface SpeechEngine extends AutoCloseable {

    interface Session extends AutoCloseable {
        void accept(short[] pcm, int n, StreamingAsr.Sink sink);
        void finish(StreamingAsr.Sink sink);
        @Override void close();
    }

    Session open() throws IOException;

    @Override void close();

    int SAMPLE_RATE = 16000;

    /** Russian: small streaming Zipformer (sherpa-onnx). Much better with accents than the old Kaldi model. */
    final class Zipformer implements SpeechEngine {
        private final StreamingAsr asr;

        Zipformer(File dir, int threads) throws IOException {
            asr = new StreamingAsr(dir, threads, 3e-5f);
        }

        @Override public Session open() {
            final StreamingAsr.Session s = asr.newSession();
            return new Session() {
                @Override public void accept(short[] pcm, int n, StreamingAsr.Sink sink) { s.accept(pcm, n, SAMPLE_RATE, sink); }
                @Override public void finish(StreamingAsr.Sink sink) { s.finish(SAMPLE_RATE, sink); }
                @Override public void close() { s.close(); }
            };
        }

        @Override public void close() { asr.close(); }
    }

    /** English: Vosk small model. */
    final class Vosk implements SpeechEngine {
        private final Model model;

        Vosk(File dir) throws IOException {
            model = new Model(dir.getAbsolutePath());
        }

        @Override public Session open() throws IOException {
            final Recognizer rec = new Recognizer(model, SAMPLE_RATE);
            return new Session() {
                private String lastPartial = "";

                @Override public void accept(short[] pcm, int n, StreamingAsr.Sink sink) {
                    if (rec.acceptWaveForm(pcm, n)) {
                        String t = field(rec.getResult(), "text");
                        lastPartial = "";
                        if (!t.isEmpty()) sink.fin(t);
                    } else {
                        String t = field(rec.getPartialResult(), "partial");
                        if (!t.equals(lastPartial)) { lastPartial = t; sink.partial(t); }
                    }
                }

                @Override public void finish(StreamingAsr.Sink sink) {
                    String t = field(rec.getFinalResult(), "text");
                    if (!t.isEmpty()) sink.fin(t);
                }

                @Override public void close() { rec.close(); }
            };
        }

        @Override public void close() { model.close(); }

        private static String field(String json, String key) {
            try {
                return new JSONObject(json).optString(key, "").trim();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
