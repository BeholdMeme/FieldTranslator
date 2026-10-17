"""
Converts a downloaded Opus-MT model into the simple files the apps read.

usage: python prepare_mt.py <download_dir> <out_dir>

download_dir must contain:
  source.spm  vocab.json  config.json
  encoder_model_quantized.onnx  decoder_model_merged_quantized.onnx
"""
import json
import os
import shutil
import sys

import sentencepiece as spm


def main(src, out):
    os.makedirs(out, exist_ok=True)

    # 1) SentencePiece model -> piece/score/type TSV (no protobuf needed on the phone)
    sp = spm.SentencePieceProcessor(model_file=os.path.join(src, "source.spm"))
    with open(os.path.join(out, "spm_source.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for i in range(sp.get_piece_size()):
            piece = sp.id_to_piece(i)
            if sp.is_unknown(i):
                t = 2
            elif sp.is_control(i):
                t = 3
            elif sp.is_unused(i):
                t = 5
            elif sp.is_byte(i):
                t = 6
            else:
                t = 1
            if "\t" in piece or "\n" in piece:
                raise SystemExit("piece contains tab/newline: %r" % piece)
            f.write("%s\t%r\t%d\n" % (piece, float(sp.get_score(i)), t))

    # 2) vocab.json -> vocab.txt (line number == token id)
    with open(os.path.join(src, "vocab.json"), encoding="utf-8") as f:
        vocab = json.load(f)
    size = max(vocab.values()) + 1
    id2piece = [None] * size
    for piece, idx in vocab.items():
        id2piece[idx] = piece
    missing = [i for i, p in enumerate(id2piece) if p is None]
    if missing:
        raise SystemExit("vocab ids not contiguous, missing e.g. %s" % missing[:5])
    for p in id2piece:
        if "\n" in p:
            raise SystemExit("vocab piece contains newline")
    with open(os.path.join(out, "vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(id2piece) + "\n")

    # 3) config -> model.properties
    with open(os.path.join(src, "config.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    props = {
        "eos_token_id": cfg["eos_token_id"],
        "pad_token_id": cfg["pad_token_id"],
        "decoder_start_token_id": cfg.get("decoder_start_token_id", cfg["pad_token_id"]),
        "unk_token_id": vocab["<unk>"],
        "decoder_attention_heads": cfg["decoder_attention_heads"],
        "decoder_layers": cfg["decoder_layers"],
        "d_model": cfg["d_model"],
        "vocab_size": size,
        "max_input_tokens": 200,
    }
    with open(os.path.join(out, "model.properties"), "w", encoding="utf-8", newline="\n") as f:
        for k, v in props.items():
            f.write("%s=%s\n" % (k, v))

    # 4) ONNX files
    shutil.copyfile(os.path.join(src, "encoder_model_quantized.onnx"), os.path.join(out, "encoder.onnx"))
    shutil.copyfile(os.path.join(src, "decoder_model_merged_quantized.onnx"), os.path.join(out, "decoder.onnx"))
    print("prepared", out, props)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
