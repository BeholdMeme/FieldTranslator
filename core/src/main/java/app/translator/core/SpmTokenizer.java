package app.translator.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal SentencePiece *unigram* encoder (the algorithm Opus-MT / Marian uses).
 *
 * Reads a TSV exported from the .spm model by scripts/prepare_mt.py:
 *   piece \t score \t type      (type: 1=normal 2=unknown 3=control 4=user_defined 5=unused 6=byte)
 *
 * Encoding = normalize (NFKC, collapse whitespace, dummy "▁" prefix) and then a
 * Viterbi search for the highest-scoring segmentation, exactly like sentencepiece.
 */
public final class SpmTokenizer {
    public static final char SPACE = '▁'; // ▁
    private static final float UNK_PENALTY = 10.0f;

    private final Map<String, Float> scores = new HashMap<>();
    private final int maxPieceLen;
    private final float unkScore;

    public SpmTokenizer(InputStream tsv) throws IOException {
        float minScore = Float.MAX_VALUE;
        int maxLen = 1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(tsv, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                int type = Integer.parseInt(p[2]);
                float score = Float.parseFloat(p[1]);
                if (type == 1 || type == 4) { // normal or user-defined pieces are candidates
                    scores.put(p[0], type == 4 ? 0f : score);
                    maxLen = Math.max(maxLen, p[0].length());
                }
                if (type == 1) minScore = Math.min(minScore, score);
            }
        }
        if (scores.isEmpty()) throw new IOException("tokenizer file has no pieces");
        this.maxPieceLen = maxLen;
        this.unkScore = minScore - UNK_PENALTY;
    }

    /** sentencepiece nmt_nfkc-style normalization + dummy prefix. */
    public static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(s.length() + 1);
        boolean lastSpace = true; // leading whitespace is dropped
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!lastSpace) { b.append(' '); lastSpace = true; }
            } else if (Character.getType(c) == Character.CONTROL) {
                // drop control characters
            } else {
                b.append(c);
                lastSpace = false;
            }
        }
        int end = b.length();
        while (end > 0 && b.charAt(end - 1) == ' ') end--;
        b.setLength(end);
        if (b.length() == 0) return "";
        return (SPACE + b.toString()).replace(' ', SPACE);
    }

    /** Returns the pieces of the best segmentation. */
    public List<String> encode(String text) {
        String s = normalize(text);
        List<String> out = new ArrayList<>();
        int n = s.length();
        if (n == 0) return out;

        float[] best = new float[n + 1];
        int[] from = new int[n + 1];
        boolean[] unk = new boolean[n + 1];
        java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
        best[0] = 0f;

        for (int i = 0; i < n; i++) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue;
            // don't split surrogate pairs
            if (Character.isLowSurrogate(s.charAt(i)) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1))) continue;
            boolean matchedSingle = false;
            int charLen = Character.isHighSurrogate(s.charAt(i)) && i + 1 < n ? 2 : 1;
            int limit = Math.min(n, i + maxPieceLen);
            for (int j = i + 1; j <= limit; j++) {
                Float sc = scores.get(s.substring(i, j));
                if (sc == null) continue;
                if (j == i + charLen) matchedSingle = true;
                float v = best[i] + sc;
                if (v > best[j]) { best[j] = v; from[j] = i; unk[j] = false; }
            }
            if (!matchedSingle) {
                int j = i + charLen;
                float v = best[i] + unkScore;
                if (v > best[j]) { best[j] = v; from[j] = i; unk[j] = true; }
            }
        }

        // backtrack; consecutive unknown characters are merged into one piece like sentencepiece
        List<String> rev = new ArrayList<>();
        List<Boolean> revUnk = new ArrayList<>();
        for (int j = n; j > 0; j = from[j]) {
            rev.add(s.substring(from[j], j));
            revUnk.add(unk[j]);
        }
        boolean lastWasUnk = false;
        for (int k = rev.size() - 1; k >= 0; k--) {
            if (revUnk.get(k) && lastWasUnk) {
                out.set(out.size() - 1, out.get(out.size() - 1) + rev.get(k));
            } else {
                out.add(rev.get(k));
            }
            lastWasUnk = revUnk.get(k);
        }
        return out;
    }

    /** Joins target pieces back into text. */
    public static String detokenize(List<String> pieces) {
        StringBuilder b = new StringBuilder();
        for (String p : pieces) b.append(p);
        return b.toString().replace(SPACE, ' ').trim();
    }
}
