package app.translator.core;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Offline Opus-MT (Marian) translation with ONNX Runtime: greedy decoding with
 * a key/value cache through optimum's "merged" decoder.
 *
 * Mirrors laptop/translator_core.py exactly (CI checks they produce identical ids).
 */
public final class MarianTranslator implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final SpmTokenizer tokenizer;
    private final List<String> id2piece = new ArrayList<>();
    private final Map<String, Integer> piece2id = new HashMap<>();
    private final int eos, pad, unk, start, heads, headDim, maxInputTokens;

    public MarianTranslator(File dir, int threads) throws IOException, OrtException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(dir, "model.properties"))) {
            p.load(in);
        }
        eos = intProp(p, "eos_token_id");
        pad = intProp(p, "pad_token_id");
        unk = intProp(p, "unk_token_id");
        start = intProp(p, "decoder_start_token_id");
        heads = intProp(p, "decoder_attention_heads");
        headDim = intProp(p, "d_model") / heads;
        maxInputTokens = Integer.parseInt(p.getProperty("max_input_tokens", "200").trim());

        try (InputStream in = new FileInputStream(new File(dir, "spm_source.tsv"))) {
            tokenizer = new SpmTokenizer(in);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(dir, "vocab.txt")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                piece2id.putIfAbsent(line, id2piece.size());
                id2piece.add(line);
            }
        }

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        if (threads > 0) so.setIntraOpNumThreads(threads);
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        encoder = env.createSession(new File(dir, "encoder.onnx").getAbsolutePath(), so);
        try {
            decoder = env.createSession(new File(dir, "decoder.onnx").getAbsolutePath(), so);
        } catch (OrtException e) {
            encoder.close();
            throw e;
        }
    }

    private static int intProp(Properties p, String k) throws IOException {
        String v = p.getProperty(k);
        if (v == null) throw new IOException("model.properties missing " + k);
        return Integer.parseInt(v.trim());
    }

    /** Capitalize + final period: ASR output has no casing/punctuation, MT likes both. */
    public static String prepareText(String text) {
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return t;
        int first = t.codePointAt(0);
        t = new String(Character.toChars(Character.toUpperCase(first))) + t.substring(Character.charCount(first));
        char last = t.charAt(t.length() - 1);
        if (".!?…".indexOf(last) < 0) t += ".";
        return t;
    }

    public long[] encodeIds(String text) {
        List<String> pieces = tokenizer.encode(text);
        int n = Math.min(pieces.size(), maxInputTokens - 1);
        long[] ids = new long[n + 1];
        for (int i = 0; i < n; i++) {
            Integer id = piece2id.get(pieces.get(i));
            ids[i] = id == null ? unk : id;
        }
        ids[n] = eos;
        return ids;
    }

    public List<Integer> translateIds(long[] src) throws OrtException {
        List<Integer> out = new ArrayList<>();
        List<OnnxTensor> owned = new ArrayList<>();
        OrtSession.Result encResult = null;
        OrtSession.Result firstStep = null;
        OrtSession.Result prevStep = null;
        try {
            long[] mask = new long[src.length];
            java.util.Arrays.fill(mask, 1L);
            OnnxTensor idsT = OnnxTensor.createTensor(env, new long[][]{src});
            OnnxTensor maskT = OnnxTensor.createTensor(env, new long[][]{mask});
            owned.add(idsT);
            owned.add(maskT);

            Map<String, OnnxTensor> encIn = new HashMap<>();
            encIn.put("input_ids", idsT);
            encIn.put("attention_mask", maskT);
            encResult = encoder.run(encIn);
            OnnxTensor hidden = (OnnxTensor) encResult.get("last_hidden_state")
                    .orElseThrow(() -> new IllegalStateException("encoder output missing"));

            // dummy cache for step 0 (ignored by the no-cache branch)
            OnnxTensor dummy = OnnxTensor.createTensor(env, FloatBuffer.allocate(heads * headDim),
                    new long[]{1, heads, 1, headDim});
            owned.add(dummy);
            OnnxTensor cacheFalse = OnnxTensor.createTensor(env, new boolean[]{false});
            OnnxTensor cacheTrue = OnnxTensor.createTensor(env, new boolean[]{true});
            owned.add(cacheFalse);
            owned.add(cacheTrue);

            int maxLen = Math.min(256, 3 * src.length + 10);
            long last = start;
            for (int step = 0; step < maxLen; step++) {
                Map<String, OnnxTensor> in = new HashMap<>();
                OnnxTensor stepIds = OnnxTensor.createTensor(env, new long[][]{{last}});
                try {
                    for (String name : decoder.getInputNames()) {
                        if (name.equals("input_ids")) {
                            in.put(name, stepIds);
                        } else if (name.equals("encoder_attention_mask")) {
                            in.put(name, maskT);
                        } else if (name.equals("encoder_hidden_states")) {
                            in.put(name, hidden);
                        } else if (name.equals("use_cache_branch")) {
                            in.put(name, step > 0 ? cacheTrue : cacheFalse);
                        } else if (name.startsWith("past_key_values")) {
                            if (step == 0) {
                                in.put(name, dummy);
                            } else {
                                String present = "present" + name.substring("past_key_values".length());
                                // cross-attention cache is only real at step 0; later outputs are dummies
                                OrtSession.Result src2 = present.contains(".encoder.") ? firstStep : prevStep;
                                in.put(name, tensor(src2, present));
                            }
                        } else {
                            throw new IllegalStateException("unexpected decoder input " + name);
                        }
                    }
                    OrtSession.Result res = decoder.run(in);
                    if (step == 0) {
                        firstStep = res;
                    } else if (prevStep != null && prevStep != firstStep) {
                        prevStep.close();
                    }
                    prevStep = res;
                } finally {
                    stepIds.close();
                }

                OnnxTensor logitsT = tensor(prevStep, "logits");
                long[] shape = logitsT.getInfo().getShape();
                int vocab = (int) shape[shape.length - 1];
                FloatBuffer fb = logitsT.getFloatBuffer();
                int base = fb.limit() - vocab; // last position
                int best = -1;
                float bestV = Float.NEGATIVE_INFINITY;
                for (int v = 0; v < vocab; v++) {
                    if (v == pad) continue;
                    float x = fb.get(base + v);
                    if (x > bestV) { bestV = x; best = v; }
                }
                if (best == eos || best < 0) break;
                out.add(best);
                last = best;
            }
            return out;
        } finally {
            if (prevStep != null && prevStep != firstStep) prevStep.close();
            if (firstStep != null) firstStep.close();
            if (encResult != null) encResult.close();
            for (OnnxTensor t : owned) t.close();
        }
    }

    private static OnnxTensor tensor(OrtSession.Result r, String name) {
        Optional<OnnxValue> v = r.get(name);
        if (!v.isPresent()) throw new IllegalStateException("decoder output missing: " + name);
        return (OnnxTensor) v.get();
    }

    public String decodeIds(List<Integer> ids) {
        List<String> pieces = new ArrayList<>();
        for (int id : ids) {
            if (id == pad || id == eos || id == unk || id < 0 || id >= id2piece.size()) continue;
            pieces.add(id2piece.get(id));
        }
        return SpmTokenizer.detokenize(pieces);
    }

    /** Thread-safe for one caller at a time; call from a single worker thread. */
    public synchronized String translate(String text) throws OrtException {
        String t = prepareText(text);
        if (t.isEmpty()) return "";
        return decodeIds(translateIds(encodeIds(t)));
    }

    @Override
    public void close() {
        try { decoder.close(); } catch (OrtException ignored) { }
        try { encoder.close(); } catch (OrtException ignored) { }
    }
}
