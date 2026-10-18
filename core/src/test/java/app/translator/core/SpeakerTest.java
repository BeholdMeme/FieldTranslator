package app.translator.core;

import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** The phone's read-aloud code produces real audio. -Dtts.dir=assets/tts/ru */
public class SpeakerTest {
    @Test
    public void speaksRussian() throws Exception {
        String dir = System.getProperty("tts.dir", "").trim();
        if (dir.isEmpty()) { System.out.println("tts.dir not set; skipping"); return; }
        long t0 = System.nanoTime();
        try (Speaker s = new Speaker(new File(dir), 2)) {
            long t1 = System.nanoTime();
            float[] a = s.synth("Привет, как дела?", Speaker.LEARNER_SPEED);
            long t2 = System.nanoTime();
            float peak = 0;
            for (float x : a) { assertTrue("NaN in audio", !Float.isNaN(x)); peak = Math.max(peak, Math.abs(x)); }
            double secs = a.length / (double) s.sampleRate();
            System.out.printf("Voice (Java): %.2f s audio, peak %.2f, load %.2f s, synth %.2f s%n",
                    secs, peak, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
            assertTrue("too short", secs > 0.5);
            assertTrue("silent", peak > 0.05);
        }
    }
}
