package app.translator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Runs the Russian streaming recognizer (same Java code as the phone) on a test
 * recording and checks it gives exactly what the Python/laptop version gives.
 * Enabled in CI with -Dasr.case=modelDir::test.wav::expected.txt
 */
public class StreamingAsrTest {

    @Test
    public void russianMatchesPython() throws Exception {
        String c = System.getProperty("asr.case", "").trim();
        if (c.isEmpty()) {
            System.out.println("asr.case not set; skipping");
            return;
        }
        String[] p = c.split("::");
        File model = new File(p[0]);
        short[] pcm;
        int rate;
        {
            byte[] wav = Files.readAllBytes(new File(p[1]).toPath());
            ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            rate = b.getInt(24);
            int channels = b.getShort(22);
            int bits = b.getShort(34);
            if (channels != 1 || bits != 16) throw new IllegalStateException("need 16-bit mono wav");
            // find the "data" chunk
            int pos = 12;
            int dataStart = -1, dataLen = 0;
            while (pos + 8 <= wav.length) {
                String id = new String(wav, pos, 4, StandardCharsets.US_ASCII);
                int len = b.getInt(pos + 4);
                if (id.equals("data")) { dataStart = pos + 8; dataLen = Math.min(len, wav.length - pos - 8); break; }
                pos += 8 + len + (len & 1);
            }
            if (dataStart < 0) throw new IllegalStateException("no data chunk");
            pcm = new short[dataLen / 2];
            for (int i = 0; i < pcm.length; i++) pcm[i] = b.getShort(dataStart + 2 * i);
        }

        List<String> finals = new ArrayList<>();
        StreamingAsr.Sink sink = new StreamingAsr.Sink() {
            @Override public void partial(String text) { }
            @Override public void fin(String text) { finals.add(text); }
        };
        long t0 = System.nanoTime();
        try (StreamingAsr asr = new StreamingAsr(model, 2, 0f);
             StreamingAsr.Session s = asr.newSession()) {
            int step = rate * 100 / 1000;
            short[] chunk = new short[step];
            for (int i = 0; i < pcm.length; i += step) {
                int n = Math.min(step, pcm.length - i);
                System.arraycopy(pcm, i, chunk, 0, n);
                s.accept(chunk, n, rate, sink);
            }
            s.finish(rate, sink);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        double audio = pcm.length / (double) rate;
        String got = String.join(" | ", finals);
        System.out.printf("Russian ASR (Java): \"%s\"  (%.1f s audio in %.2f s, RTF %.2f)%n", got, audio, secs, secs / audio);
        String expected = new String(Files.readAllBytes(new File(p[2]).toPath()), StandardCharsets.UTF_8).trim();
        assertFalse("nothing recognized", got.isEmpty());
        assertEquals(expected, got);
    }
}
