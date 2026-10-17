"""
Offline Opus-MT translator (ONNX Runtime + numpy only).

This is a line-for-line mirror of core/src/main/java/app/translator/core/*.java
so CI can check that the Android app and the laptop app behave identically.

Model folder layout (made by scripts/prepare_mt.py):
    encoder.onnx  decoder.onnx  spm_source.tsv  vocab.txt  model.properties
"""
import os
import unicodedata

import numpy as np
import onnxruntime as ort

SPACE = "▁"
UNK_PENALTY = 10.0


def _is_ws(c):
    return c.isspace() or unicodedata.category(c) == "Zs"


class SpmTokenizer:
    def __init__(self, tsv_path):
        self.scores = {}
        min_score = float("inf")
        max_len = 1
        with open(tsv_path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line:
                    continue
                p = line.split("\t")
                if len(p) < 3:
                    continue
                typ = int(p[2])
                score = float(p[1])
                if typ in (1, 4):
                    self.scores[p[0]] = 0.0 if typ == 4 else score
                    max_len = max(max_len, len(p[0]))
                if typ == 1:
                    min_score = min(min_score, score)
        self.max_len = max_len
        self.unk_score = min_score - UNK_PENALTY

    @staticmethod
    def normalize(text):
        s = unicodedata.normalize("NFKC", text)
        out = []
        last_space = True
        for c in s:
            if _is_ws(c):
                if not last_space:
                    out.append(" ")
                    last_space = True
            elif unicodedata.category(c) == "Cc":
                pass
            else:
                out.append(c)
                last_space = False
        s = "".join(out).rstrip(" ")
        if not s:
            return ""
        return (SPACE + s).replace(" ", SPACE)

    def encode(self, text):
        s = self.normalize(text)
        n = len(s)
        if n == 0:
            return []
        best = [float("-inf")] * (n + 1)
        frm = [0] * (n + 1)
        unk = [False] * (n + 1)
        best[0] = 0.0
        for i in range(n):
            if best[i] == float("-inf"):
                continue
            matched_single = False
            for j in range(i + 1, min(n, i + self.max_len) + 1):
                sc = self.scores.get(s[i:j])
                if sc is None:
                    continue
                if j == i + 1:
                    matched_single = True
                v = best[i] + sc
                if v > best[j]:
                    best[j], frm[j], unk[j] = v, i, False
            if not matched_single:
                j = i + 1
                v = best[i] + self.unk_score
                if v > best[j]:
                    best[j], frm[j], unk[j] = v, i, True
        rev = []
        j = n
        while j > 0:
            rev.append((s[frm[j]:j], unk[j]))
            j = frm[j]
        out = []
        last_unk = False
        for piece, is_unk in reversed(rev):
            if is_unk and last_unk:
                out[-1] += piece
            else:
                out.append(piece)
            last_unk = is_unk
        return out

    @staticmethod
    def detokenize(pieces):
        return "".join(pieces).replace(SPACE, " ").strip()


def load_properties(path):
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def prepare_text(text):
    """Speech recognizers give lowercase text with no punctuation. Opus-MT does
    noticeably better with a capital letter and a final period."""
    t = " ".join(text.split())
    if not t:
        return t
    t = t[0].upper() + t[1:]
    if t[-1] not in ".!?…":
        t += "."
    return t


class MarianTranslator:
    def __init__(self, model_dir, threads=None):
        p = load_properties(os.path.join(model_dir, "model.properties"))
        self.eos = int(p["eos_token_id"])
        self.pad = int(p["pad_token_id"])
        self.unk = int(p["unk_token_id"])
        self.start = int(p["decoder_start_token_id"])
        self.heads = int(p["decoder_attention_heads"])
        self.head_dim = int(p["d_model"]) // self.heads
        self.max_tokens = int(p.get("max_input_tokens", "200"))

        self.tok = SpmTokenizer(os.path.join(model_dir, "spm_source.tsv"))
        with open(os.path.join(model_dir, "vocab.txt"), encoding="utf-8") as f:
            self.id2piece = [l.rstrip("\n") for l in f]
        self.piece2id = {pc: i for i, pc in enumerate(self.id2piece)}

        so = ort.SessionOptions()
        if threads:
            so.intra_op_num_threads = threads
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.enc = ort.InferenceSession(os.path.join(model_dir, "encoder.onnx"), so,
                                        providers=["CPUExecutionProvider"])
        self.dec = ort.InferenceSession(os.path.join(model_dir, "decoder.onnx"), so,
                                        providers=["CPUExecutionProvider"])
        self.dec_inputs = [i.name for i in self.dec.get_inputs()]
        self.dec_outputs = [o.name for o in self.dec.get_outputs()]

    def encode_ids(self, text):
        ids = [self.piece2id.get(pc, self.unk) for pc in self.tok.encode(text)]
        ids = ids[: self.max_tokens - 1]
        return ids + [self.eos]

    def translate_ids(self, src_ids):
        ids = np.array([src_ids], dtype=np.int64)
        mask = np.ones_like(ids)
        enc_out = self.enc.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": mask})[0]

        max_len = min(256, 3 * len(src_ids) + 10)
        past = {}
        out = []
        last = self.start
        for step in range(max_len):
            feeds = {}
            for name in self.dec_inputs:
                if name == "input_ids":
                    feeds[name] = np.array([[last]], dtype=np.int64)
                elif name == "encoder_attention_mask":
                    feeds[name] = mask
                elif name == "encoder_hidden_states":
                    feeds[name] = enc_out
                elif name == "use_cache_branch":
                    feeds[name] = np.array([step > 0], dtype=bool)
                elif name.startswith("past_key_values"):
                    if step == 0:
                        # ignored by the no-cache branch; length 1 instead of 0 avoids
                        # zero-sized tensors, which some runtimes handle badly
                        feeds[name] = np.zeros((1, self.heads, 1, self.head_dim), dtype=np.float32)
                    else:
                        feeds[name] = past[name]
                else:
                    raise RuntimeError("unexpected decoder input " + name)
            res = dict(zip(self.dec_outputs, self.dec.run(self.dec_outputs, feeds)))
            for oname, val in res.items():
                if not oname.startswith("present"):
                    continue
                pname = oname.replace("present", "past_key_values", 1)
                # encoder cross-attention cache is computed once at step 0; in the
                # cached branch those outputs are dummies, so keep the step-0 values
                if step > 0 and ".encoder." in oname:
                    continue
                past[pname] = val
            logits = res["logits"][0, -1].copy()
            logits[self.pad] = -np.inf
            nxt = int(np.argmax(logits))
            if nxt == self.eos:
                break
            out.append(nxt)
            last = nxt
        return out

    def decode_ids(self, ids):
        pieces = [self.id2piece[i] for i in ids if i not in (self.pad, self.eos, self.unk)]
        return SpmTokenizer.detokenize(pieces)

    def translate(self, text):
        text = prepare_text(text)
        if not text:
            return ""
        return self.decode_ids(self.translate_ids(self.encode_ids(text)))
