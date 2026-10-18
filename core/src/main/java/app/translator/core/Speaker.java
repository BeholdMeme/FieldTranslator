package app.translator.core;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.IOException;

/**
 * Offline Russian text-to-speech (Piper "irina" voice through sherpa-onnx).
 * Folder: model.onnx, tokens.txt, espeak-ng-data/
 */
public final class Speaker implements AutoCloseable {
    /** A little slower than normal speech, for learners. */
    public static final float LEARNER_SPEED = 0.85f;

    private final OfflineTts tts;

    public Speaker(File dir, int threads) throws IOException {
        for (String f : new String[]{"model.onnx", "tokens.txt", "espeak-ng-data"}) {
            if (!new File(dir, f).exists()) throw new IOException("voice file missing: " + f);
        }
        OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder()
                .setModel(new File(dir, "model.onnx").getAbsolutePath())
                .setTokens(new File(dir, "tokens.txt").getAbsolutePath())
                .setDataDir(new File(dir, "espeak-ng-data").getAbsolutePath())
                .build();
        OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(Math.max(1, threads))
                .setDebug(false)
                .build();
        tts = new OfflineTts(OfflineTtsConfig.builder().setModel(model).setMaxNumSentences(1).build());
    }

    public int sampleRate() { return tts.getSampleRate(); }

    /** Returns mono float samples in [-1, 1]. */
    public float[] synth(String text, float speed) {
        GeneratedAudio a = tts.generate(text, 0, speed);
        return a.getSamples();
    }

    @Override public void close() { tts.release(); }
}
