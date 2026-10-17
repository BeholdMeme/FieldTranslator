"""
Runs the Russian streaming recognizer (laptop/Python version) on a test recording,
checks it recognizes Russian, and writes the expected text for the Java test.

usage: python check_asr.py <model_dir> <test.wav> <out_expected.txt>
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "laptop"))
from asr_zipformer import StreamingAsr, transcribe_wav  # noqa: E402


def main():
    model, wav, out = sys.argv[1:4]
    t0 = time.time()
    asr = StreamingAsr(model, threads=2, dither=0.0)
    finals = transcribe_wav(asr, wav)
    text = " | ".join(finals)
    print("Russian ASR (Python): %r  (%.2f s incl. load)" % (text, time.time() - t0))
    if len(re.findall("[а-яё]", text.lower())) < 5:
        raise SystemExit("Russian recognizer produced no Russian text")
    with open(out, "w", encoding="utf-8") as f:
        f.write(text + "\n")


if __name__ == "__main__":
    main()
