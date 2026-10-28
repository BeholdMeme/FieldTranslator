package app.translator.core;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** Phone number words == laptop number words. -Dnum.ref=reference.tsv */
public class NumbersTest {
    @Test
    public void matchesPython() throws Exception {
        String ref = System.getProperty("num.ref", "").trim();
        if (ref.isEmpty()) { System.out.println("num.ref not set; skipping"); return; }
        int n = 0;
        for (String line : Files.readAllLines(new File(ref).toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            long v = Long.parseLong(p[0]);
            assertEquals("words for " + p[0], p[1], RussianNumbers.toWords(v));
            if (v >= 0) {
                assertEquals("ordinal m " + p[0], p[2], RussianNumbers.toOrdinal(v, 'm'));
                assertEquals("ordinal f " + p[0], p[3], RussianNumbers.toOrdinal(v, 'f'));
                assertEquals("ordinal n " + p[0], p[4], RussianNumbers.toOrdinal(v, 'n'));
            }
            n++;
        }
        System.out.println("Numbers: " + n + " cases identical to Python (incl. ordinals), e.g. 2025 -> " + RussianNumbers.toWords(2025)
                + ", 2000th -> " + RussianNumbers.toOrdinal(2000, 'm'));
    }
}
