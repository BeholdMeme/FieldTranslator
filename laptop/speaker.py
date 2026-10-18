"""Offline Russian read-aloud (Piper "irina" voice via sherpa-onnx). Loaded on first use,
unloaded after 2 minutes idle. Mirror of core/.../Speaker.java + app/.../Voice.java."""
import os
import threading

LEARNER_SPEED = 0.85


class Speaker:
    def __init__(self, voice_dir, threads=2):
        import sherpa_onnx
        p = lambda f: os.path.join(voice_dir, f)
        for f in ("model.onnx", "tokens.txt", "espeak-ng-data"):
            if not os.path.exists(p(f)):
                raise FileNotFoundError("voice file missing: " + f)
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                    model=p("model.onnx"), tokens=p("tokens.txt"), data_dir=p("espeak-ng-data")),
                num_threads=threads, debug=False),
            max_num_sentences=1)
        self.tts = sherpa_onnx.OfflineTts(cfg)

    def synth(self, text, speed=LEARNER_SPEED):
        a = self.tts.generate(text, sid=0, speed=speed)
        return a.samples, a.sample_rate


class Voice:
    """Background, newest-request-wins playback."""

    def __init__(self, voice_dir, on_error=None):
        self.dir = voice_dir
        self.on_error = on_error
        self.speaker = None
        self.lock = threading.Lock()
        self.gen = 0
        self.timer = None

    def say(self, text):
        if not text or not text.strip():
            return
        self.gen += 1
        gen = self.gen
        threading.Thread(target=self._run, args=(text, gen), daemon=True).start()

    def _run(self, text, gen):
        try:
            with self.lock:
                if gen != self.gen:
                    return
                if self.speaker is None:
                    self.speaker = Speaker(self.dir)
                samples, rate = self.speaker.synth(text)
            if gen != self.gen:
                return
            import numpy as np
            import sounddevice as sd
            sd.stop()
            sd.play(np.asarray(samples, dtype=np.float32), rate)
        except Exception as e:
            if self.on_error:
                self.on_error("Voice unavailable: %s" % e)
        finally:
            self._schedule_unload()

    def _schedule_unload(self):
        if self.timer:
            self.timer.cancel()
        self.timer = threading.Timer(120, self._unload)
        self.timer.daemon = True
        self.timer.start()

    def _unload(self):
        with self.lock:
            self.speaker = None
