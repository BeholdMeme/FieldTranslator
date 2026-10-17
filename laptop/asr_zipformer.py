"""
Streaming Russian speech recognition (sherpa-onnx Zipformer, int8).
Mirror of core/src/main/java/app/translator/core/StreamingAsr.java.
"""
import os

import numpy as np


def clean(t):
    t = " ".join((t or "").split())
    if t and t == t.upper() and t != t.lower():
        t = t.lower()
    return t


class StreamingAsr:
    def __init__(self, model_dir, threads=2, dither=3e-5):
        import sherpa_onnx
        p = lambda f: os.path.join(model_dir, f)
        for f in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
            if not os.path.isfile(p(f)):
                raise FileNotFoundError("speech model file missing: " + f)
        self.rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=p("tokens.txt"), encoder=p("encoder.int8.onnx"),
            decoder=p("decoder.int8.onnx"), joiner=p("joiner.int8.onnx"),
            num_threads=max(1, threads), sample_rate=16000, feature_dim=80, dither=dither,
            enable_endpoint_detection=True,
            rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
            rule3_min_utterance_length=15.0,
            decoding_method="modified_beam_search", max_active_paths=4)

    def session(self):
        return Session(self.rec)


class Session:
    def __init__(self, rec):
        self.rec = rec
        self.stream = rec.create_stream()
        self.last_partial = ""

    def accept(self, pcm_int16, sample_rate, on_partial, on_final):
        f = np.asarray(pcm_int16, dtype=np.float32) / 32768.0
        self.stream.accept_waveform(sample_rate, f)
        self._drain(on_partial, on_final, last=False)

    def finish(self, sample_rate, on_partial, on_final):
        self.stream.accept_waveform(sample_rate, np.zeros(int(sample_rate * 0.6), dtype=np.float32))
        self.stream.input_finished()
        self._drain(on_partial, on_final, last=True)

    def _drain(self, on_partial, on_final, last):
        while self.rec.is_ready(self.stream):
            self.rec.decode_stream(self.stream)
        r = self.rec.get_result(self.stream)
        text = clean(r if isinstance(r, str) else getattr(r, "text", str(r)))
        if last or self.rec.is_endpoint(self.stream):
            if text:
                on_final(text)
            if not last:
                self.rec.reset(self.stream)
            self.last_partial = ""
        elif text != self.last_partial:
            self.last_partial = text
            on_partial(text)


def transcribe_wav(asr, path, chunk_ms=100):
    """Feed a 16-bit mono wav in mic-sized chunks; returns the list of final phrases."""
    import wave
    with wave.open(path) as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, "need 16-bit mono"
        rate = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    s = asr.session()
    finals = []
    step = int(rate * chunk_ms / 1000)
    for i in range(0, len(pcm), step):
        s.accept(pcm[i:i + step], rate, lambda t: None, finals.append)
    s.finish(rate, lambda t: None, finals.append)
    return finals
