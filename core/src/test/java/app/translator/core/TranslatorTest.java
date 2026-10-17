package app.translator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Proves the Java (phone) implementation produces exactly the same token ids as
 * the Python (laptop) implementation, which CI checks against HuggingFace.
 *
 * Always runs on the tiny random model in src/test/resources/tiny.
 * In CI it also runs on the real models via -Dmt.cases=dir::ref.tsv;dir2::ref2.tsv
 */
public class TranslatorTest {

    private static File tinyDir() throws Exception {
        URL u = TranslatorTest.class.getResource("/tiny/model.properties");
        assertTrue("tiny fixture missing", u != null);
        return new File(u.toURI()).getParentFile();
    }

    private static List<String> lines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1)) {
            out.add(l);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    @Test
    public void tokenizerMatchesSentencepiece() throws Exception {
        File dir = tinyDir();
        SpmTokenizer tok;
        try (FileInputStream in = new FileInputStream(new File(dir, "spm_source.tsv"))) {
            tok = new SpmTokenizer(in);
        }
        int n = 0;
        for (String line : lines(new File(dir, "pieces.tsv"))) {
            int tab = line.lastIndexOf('\t');
            String text = line.substring(0, tab);
            String expected = line.substring(tab + 1);
            assertEquals("pieces for '" + text + "'", expected, String.join("\u001f", tok.encode(text)));
            n++;
        }
        assertTrue(n > 500);
    }

    @Test
    public void tinyModelMatchesPython() throws Exception {
        File dir = tinyDir();
        checkCase(dir, new File(dir, "reference.tsv"));
    }

    @Test
    public void realModelsMatchPython() throws Exception {
        String cases = System.getProperty("mt.cases", "").trim();
        if (cases.isEmpty()) {
            System.out.println("mt.cases not set; skipping real-model check");
            return;
        }
        for (String c : cases.split(";")) {
            String[] p = c.split("::");
            checkCase(new File(p[0]), new File(p[1]));
        }
    }

    private static void checkCase(File modelDir, File ref) throws Exception {
        try (MarianTranslator tr = new MarianTranslator(modelDir, 2)) {
            long total = 0;
            int n = 0;
            for (String line : lines(ref)) {
                String[] p = line.split("\t", -1);
                String text = p[0];
                String expectedSrc = p[1];
                String expectedOut = p[2];
                long[] src = tr.encodeIds(MarianTranslator.prepareText(text));
                assertEquals("source ids for '" + text + "'", expectedSrc, join(src));
                long t0 = System.nanoTime();
                List<Integer> out = tr.translateIds(src);
                total += System.nanoTime() - t0;
                StringBuilder b = new StringBuilder();
                for (int id : out) { if (b.length() > 0) b.append(' '); b.append(id); }
                assertEquals("output ids for '" + text + "'", expectedOut, b.toString());
                System.out.println("  " + text + " -> " + tr.decodeIds(out));
                n++;
            }
            assertTrue("no cases in " + ref, n > 0);
            System.out.printf("%s: %d cases identical to Python, avg %.0f ms%n", modelDir.getName(), n, total / 1e6 / n);
        }
    }

    private static String join(long[] a) {
        StringBuilder b = new StringBuilder();
        for (long x : a) { if (b.length() > 0) b.append(' '); b.append(x); }
        return b.toString();
    }
}
