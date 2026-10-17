package app.translator.core;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Streaming speech recognition with a small Zipformer transducer (sherpa-onnx),
 * used for Russian (alphacep "vosk-model-small-streaming-ru", int8).
 *
 * Mirrors laptop/asr_zipformer.py; CI checks both give the same text on a test recording.
 *
 * Folder layout: encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt
 */
public final class StreamingAsr implements AutoCloseable {

    public interface Sink {
        void partial(String text);
        void fin(String text);
    }

    private final OnlineRecognizer recognizer;

    /** @param dither use ~3e-5 on real microphones, 0 for reproducible tests */
    public StreamingAsr(File dir, int threads, float dither) throws IOException {
        for (String f : new String[]{"encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"}) {
            if (!new File(dir, f).isFile()) throw new IOException("speech model file missing: " + f);
        }
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(new File(dir, "encoder.int8.onnx").getAbsolutePath())
                .setDecoder(new File(dir, "decoder.int8.onnx").getAbsolutePath())
                .setJoiner(new File(dir, "joiner.int8.onnx").getAbsolutePath())
                .build();
        OnlineModelConfig model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setNumThreads(Math.max(1, threads))
                .setDebug(false)
                .build();
        FeatureConfig feat = FeatureConfig.builder()
                .setSampleRate(16000)
                .setFeatureDim(80)
                .setDither(dither)
                .build();
        EndpointConfig endpoint = EndpointConfig.builder()
                // nothing recognized yet: give up the segment after 2.4 s of silence
                .setRule1(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(2.4f).setMinUtteranceLength(0f).build())
                // after speech: a 0.8 s pause ends the phrase (then it gets translated)
                .setRule2(EndpointRule.builder().setMustContainNonSilence(true)
                        .setMinTrailingSilence(0.8f).setMinUtteranceLength(0f).build())
                // never let one phrase run longer than 15 s
                .setRule3(EndpointRule.builder().setMustContainNonSilence(false)
                        .setMinTrailingSilence(0f).setMinUtteranceLength(15f).build())
                .build();
        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(model)
                .setFeatureConfig(feat)
                .setEndpointConfig(endpoint)
                .setEnableEndpoint(true)
                .setDecodingMethod("modified_beam_search")
                .setMaxActivePaths(4)
                .build();
        recognizer = new OnlineRecognizer(config);
    }

    public Session newSession() { return new Session(); }

    /** Model output is lowercase; tolerate an ALL-CAPS model too. */
    static String clean(String t) {
        if (t == null) return "";
        t = t.trim().replaceAll("\\s+", " ");
        if (!t.isEmpty() && t.equals(t.toUpperCase(Locale.ROOT)) && !t.equals(t.toLowerCase(Locale.ROOT))) {
            t = t.toLowerCase(new Locale("ru"));
        }
        return t;
    }

    public final class Session implements AutoCloseable {
        private final OnlineStream stream = recognizer.createStream();
        private String lastPartial = "";

        /** Feed 16-bit mono PCM. Calls the sink with live partials and finished phrases. */
        public void accept(short[] pcm, int n, int sampleRate, Sink sink) {
            float[] f = new float[n];
            for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
            stream.acceptWaveform(f, sampleRate);
            drain(sink, false);
        }

        /** End of audio: flush whatever is still pending as a final phrase. */
        public void finish(int sampleRate, Sink sink) {
            stream.acceptWaveform(new float[(int) (sampleRate * 0.6)], sampleRate);  // tail padding
            stream.inputFinished();
            drain(sink, true);
        }

        private void drain(Sink sink, boolean last) {
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            String text = clean(recognizer.getResult(stream).getText());
            if (last || recognizer.isEndpoint(stream)) {
                if (!text.isEmpty()) sink.fin(text);
                if (!last) recognizer.reset(stream);
                lastPartial = "";
            } else if (!text.equals(lastPartial)) {
                lastPartial = text;
                sink.partial(text);
            }
        }

        @Override public void close() { stream.release(); }
    }

    @Override public void close() { recognizer.release(); }
}
