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
            String[] p = line.split("\t");
            assertEquals("words for " + p[0], p[1], RussianNumbers.toWords(Long.parseLong(p[0])));
            n++;
        }
        System.out.println("Numbers: " + n + " cases identical to Python, e.g. 2025 -> " + RussianNumbers.toWords(2025));
    }
}
