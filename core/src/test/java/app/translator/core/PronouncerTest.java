package app.translator.core;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** The phone's pronunciation code must match the laptop's exactly. -Dpron.case=stress.tsv::reference.tsv */
public class PronouncerTest {
    @Test
    public void matchesPython() throws Exception {
        String c = System.getProperty("pron.case", "").trim();
        if (c.isEmpty()) { System.out.println("pron.case not set; skipping"); return; }
        String[] p = c.split("::");
        Pronouncer pr = new Pronouncer(new Pronouncer.StressDict(new File(p[0])));
        int n = 0;
        for (String line : Files.readAllLines(new File(p[1]).toPath(), StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            assertEquals("pronunciation of '" + line.substring(0, tab) + "'",
                    line.substring(tab + 1), pr.pronounce(line.substring(0, tab)));
            n++;
        }
        System.out.println("Pronunciation: " + n + " cases identical to Python, e.g. привет -> "
                + pr.pronounce("привет") + ", спасибо -> " + pr.pronounce("спасибо"));
    }
}
