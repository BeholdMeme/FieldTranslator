"""
RU <-> EN live translator for the laptop (Windows/Mac/Linux). Fully offline.

Same models and same translation code path as the Android app.
Run from source:   python app.py
Self-test:         python app.py --selftest   (writes selftest.txt next to the app)
"""
import json
import os
import queue
import sys
import threading
import time
import traceback

from translator_core import MarianTranslator
from pronounce import StressDict, pronounce

SAMPLE_RATE = 16000

DIRS = {
    "ru-en": {"asr": "ru-zipformer", "label": "RUSSIAN  →  ENGLISH", "color": "#E5484D", "hint": "Speak Russian"},
    "en-ru": {"asr": "en", "label": "ENGLISH  →  RUSSIAN", "color": "#3E8CFF", "hint": "Speak English"},
}


def app_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def find_models():
    here = app_dir()
    for cand in (os.path.join(here, "models"),
                 os.path.join(here, "..", "app", "src", "main", "assets")):
        if os.path.isdir(os.path.join(cand, "mt", "ru-en")) and os.path.isdir(os.path.join(cand, "asr", "en")):
            return os.path.abspath(cand)
    raise SystemExit("Models not found. Put the 'models' folder next to the app "
                     "(or run scripts/fetch_models.sh in the repo).")


def list_mics():
    """Input devices, system default first. Uses the MME host API on Windows when
    available (it resamples, so any rate works)."""
    try:
        import sounddevice as sd
        devs = sd.query_devices()
        apis = sd.query_hostapis()
        default_in = sd.default.device[0]
    except Exception:
        return [], []
    preferred_api = None
    for i, a in enumerate(apis):
        if "MME" in a["name"]:
            preferred_api = i
    names, ids, seen = [], [], set()
    order = sorted(range(len(devs)), key=lambda i: (i != default_in, devs[i]["hostapi"] != preferred_api))
    for i in order:
        d = devs[i]
        if d["max_input_channels"] < 1:
            continue
        if preferred_api is not None and d["hostapi"] != preferred_api and i != default_in:
            continue
        label = d["name"].strip()
        if i == default_in:
            label += "  (Windows default)" if sys.platform == "win32" else "  (default)"
        if label in seen:
            continue
        seen.add(label)
        names.append(label)
        ids.append(i)
    return names, ids


class Engine:
    """Loads one direction at a time (keeps memory low).
    Russian speech: streaming Zipformer (sherpa-onnx). English speech: Vosk small model."""

    def __init__(self, models):
        self.models = models
        self.pair = None
        self.asr = None
        self.translator = None
        self.lock = threading.Lock()

    def load(self, pair):
        with self.lock:
            if self.pair == pair:
                return
            self.asr = None
            self.translator = None
            asr_dir = os.path.join(self.models, "asr", DIRS[pair]["asr"])
            if pair == "ru-en":
                from asr_zipformer import StreamingAsr
                self.asr = StreamingAsr(asr_dir, threads=2)
            else:
                from vosk import Model, SetLogLevel
                SetLogLevel(-1)
                self.asr = Model(asr_dir)
            self.translator = MarianTranslator(os.path.join(self.models, "mt", pair),
                                               threads=min(4, os.cpu_count() or 2))
            self.translator.translate("ok")
            self.pair = pair

    def open_session(self, rate, on_partial, on_final):
        """Returns (accept(bytes), finish()) for the loaded speech model."""
        import numpy as np
        asr = self.asr
        if self.pair == "ru-en":
            s = asr.session()
            return (lambda data: s.accept(np.frombuffer(data, dtype=np.int16), rate, on_partial, on_final),
                    lambda: s.finish(rate, on_partial, on_final))
        from vosk import KaldiRecognizer
        rec = KaldiRecognizer(asr, rate)

        def accept(data):
            if rec.AcceptWaveform(data):
                t = json.loads(rec.Result()).get("text", "").strip()
                if t:
                    on_final(t)
            else:
                on_partial(json.loads(rec.PartialResult()).get("partial", "").strip())

        def finish():
            t = json.loads(rec.FinalResult()).get("text", "").strip()
            if t:
                on_final(t)
        return accept, finish

    def translate(self, pair, text):
        with self.lock:
            if self.pair != pair or self.translator is None:
                return None
            return self.translator.translate(text)


def selftest(wav=None):
    out = []
    ok = True
    try:
        import numpy as np
        models = find_models()
        eng = Engine(models)
        for pair, phrase in (("ru-en", "где находится вокзал"), ("en-ru", "where is the train station")):
            t0 = time.time()
            eng.load(pair)
            res = eng.translate(pair, phrase)
            out.append("%s: %s -> %s (%.1fs incl. load)" % (pair, phrase, res, time.time() - t0))
            if not res:
                ok = False
            heard = []
            accept, finish = eng.open_session(SAMPLE_RATE, lambda t: None, heard.append)
            if pair == "ru-en" and wav:
                import wave
                with wave.open(wav) as w:
                    pcm = w.readframes(w.getnframes())
                    rate = w.getframerate()
                accept, finish = eng.open_session(rate, lambda t: None, heard.append)
                step = rate // 10 * 2
                for i in range(0, len(pcm), step):
                    accept(pcm[i:i + step])
                finish()
                text = " ".join(heard)
                out.append("  Russian speech test: " + text)
                if sum(1 for ch in text.lower() if "а" <= ch <= "я") < 5:
                    ok = False
                    out.append("  Russian speech test FAILED (no Russian text)")
            else:
                accept(np.zeros(SAMPLE_RATE, dtype=np.int16).tobytes())
                finish()
                out.append("  speech engine ok (silence -> %r)" % " ".join(heard))
        import sounddevice  # noqa: F401  (checks the audio library is bundled)
        out.append("audio library ok")
        sd = StressDict(os.path.join(models, "learn", "stress.tsv"))
        p = pronounce("Привет, как дела?", sd)
        out.append("pronunciation: " + p)
        if p != "pri-VYET, kak di-LA?":
            ok = False
        with open(os.path.join(models, "learn", "learn.json"), encoding="utf-8") as fh:
            learn = json.load(fh)
        out.append("learning data: %d letters, %d lessons" % (len(learn["alphabet"]), len(learn["lessons"])))
        import learn_ui  # noqa: F401  (checks the Learn window is bundled)
    except Exception:
        ok = False
        out.append(traceback.format_exc())
    out.append("SELFTEST " + ("PASSED" if ok else "FAILED"))
    with open(os.path.join(app_dir(), "selftest.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
    print("\n".join(out))
    sys.exit(0 if ok else 1)


def run_gui():
    import tkinter as tk
    from tkinter import messagebox

    models = find_models()
    engine = Engine(models)
    stress_path = os.path.join(models, "learn", "stress.tsv")
    stress = StressDict(stress_path) if os.path.isfile(stress_path) else StressDict()
    settings_path = os.path.join(os.path.expanduser("~"), ".fieldtranslator.json")
    try:
        with open(settings_path, encoding="utf-8") as fh:
            settings = json.load(fh)
    except Exception:
        settings = {}
    events = queue.Queue()     # (kind, payload) -> UI thread
    jobs = queue.Queue()       # translation/load jobs -> worker thread
    pending = [0]
    state = {"pair": "ru-en", "ready": False, "listening": False, "stream": None,
             "rec_thread": None, "stop": threading.Event(), "last_preview": 0.0, "last_src": "",
             "resume": False, "scale": 1.0}

    def worker():
        while True:
            fn = jobs.get()
            try:
                fn()
            except Exception as e:  # keep the worker alive
                events.put(("status", "Error: %s" % e))
            finally:
                pending[0] -= 1

    threading.Thread(target=worker, daemon=True).start()

    def submit(fn):
        pending[0] += 1
        jobs.put(fn)

    # ---------- UI ----------
    BG, SURF, SURF2, BORDER, TEXT, MUTED = "#0E1116", "#171B22", "#20252E", "#2A303A", "#E8EBF0", "#8B93A1"
    GREEN, STOP, GOLD, CYAN = "#2FB36D", "#E5484D", "#F5C451", "#7FD1E8"
    F = "Segoe UI"

    root = tk.Tk()
    root.title("Field Translator")
    root.geometry("760x860")
    root.minsize(560, 640)
    root.configure(bg=BG)

    def chip(parent, text, cmd, width=None):
        b = tk.Button(parent, text=text, command=cmd, bg=SURF2, fg=TEXT, bd=0, relief="flat",
                      font=(F, 11), activebackground=BORDER, activeforeground=TEXT, cursor="hand2",
                      highlightthickness=1, highlightbackground=BORDER, padx=12, pady=6)
        if width:
            b.configure(width=width)
        return b

    # header
    header = tk.Frame(root, bg=BG)
    header.pack(fill="x", padx=20, pady=(16, 0))
    tk.Label(header, text="Field Translator", bg=BG, fg=TEXT, font=(F, 20, "bold")).pack(side="left")
    tk.Label(header, text="●  Offline", bg="#13261D", fg=GREEN, font=(F, 10), padx=10, pady=3).pack(side="right")

    # direction: segmented control
    seg = tk.Frame(root, bg=SURF, padx=4, pady=4)
    seg.pack(fill="x", padx=20, pady=(14, 10))
    seg_ru = tk.Button(seg, text="Русский  →  English", command=lambda: state["pair"] != "ru-en" and toggle(),
                       bd=0, font=(F, 13, "bold"), cursor="hand2", activeforeground="white")
    seg_en = tk.Button(seg, text="English  →  Русский", command=lambda: state["pair"] != "en-ru" and toggle(),
                       bd=0, font=(F, 13, "bold"), cursor="hand2", activeforeground="white")
    seg_ru.pack(side="left", fill="x", expand=True, ipady=10)
    seg_en.pack(side="left", fill="x", expand=True, ipady=10)

    # conversation
    body = tk.Frame(root, bg=SURF)
    body.pack(fill="both", expand=True, padx=20)
    txt = tk.Text(body, bg=SURF, fg=TEXT, wrap="word", bd=0, padx=18, pady=12, highlightthickness=0,
                  insertbackground=TEXT, selectbackground=BORDER, cursor="arrow")
    sb = tk.Scrollbar(body, command=txt.yview, width=10, bd=0, troughcolor=SURF)
    txt.configure(yscrollcommand=sb.set, state="disabled")
    sb.pack(side="right", fill="y")
    txt.pack(side="left", fill="both", expand=True)
    txt.configure(state="normal")
    txt.insert("end", "Pick a direction, press Listen (or Space), and start talking.\n"
                      "Translations appear here.  Tab switches language.\n", ("hint",))
    txt.configure(state="disabled")

    partial_var = tk.StringVar()
    preview_var = tk.StringVar()
    status_var = tk.StringVar(value="Starting…")
    partial_lbl = tk.Label(root, textvariable=partial_var, bg=BG, fg="#B7BEC9",
                           wraplength=700, justify="left", anchor="w")
    partial_lbl.pack(fill="x", padx=22, pady=(8, 0))
    preview_lbl = tk.Label(root, textvariable=preview_var, bg=BG, fg=CYAN,
                           wraplength=700, justify="left", anchor="w")
    preview_lbl.pack(fill="x", padx=22)

    # control panel
    panel = tk.Frame(root, bg=SURF, padx=14, pady=12)
    panel.pack(fill="x", padx=20, pady=(8, 18))
    tk.Label(panel, textvariable=status_var, bg=SURF, fg=MUTED, font=(F, 10), wraplength=680).pack(pady=(0, 8))

    mic_row = tk.Frame(panel, bg=SURF)
    mic_row.pack(fill="x")
    tk.Label(mic_row, text="Mic", bg=SURF, fg=MUTED, font=(F, 10)).pack(side="left")
    mic_names, mic_ids = list_mics()
    mic_var = tk.StringVar(value=mic_names[0] if mic_names else "(no microphone found)")
    mic_menu = tk.OptionMenu(mic_row, mic_var, *(mic_names or ["(no microphone found)"]),
                             command=lambda _v: restart_if_listening())
    mic_menu.configure(bg=SURF2, fg=TEXT, activebackground=BORDER, activeforeground=TEXT,
                       highlightthickness=0, bd=0, anchor="w", font=(F, 10), relief="flat")
    mic_menu["menu"].configure(bg=SURF2, fg=TEXT, font=(F, 10))
    mic_menu.pack(side="left", fill="x", expand=True, padx=(8, 10))
    meter = tk.Canvas(mic_row, width=150, height=6, bg=SURF2, highlightthickness=0)
    meter.pack(side="right")
    meter_bar = meter.create_rectangle(0, 0, 0, 6, fill=GREEN, width=0)

    chips = tk.Frame(panel, bg=SURF)
    chips.pack(fill="x", pady=(10, 0))
    pron_btn = chip(chips, "Pronounce", lambda: toggle_pron())
    pron_btn.pack(side="left", padx=(0, 6))

    def open_learn():
        from learn_ui import LearnWindow
        LearnWindow(root, models, settings, save_settings)
    chip(chips, "Learn to read", open_learn).pack(side="left", padx=6)
    chip(chips, "Clear", lambda: clear()).pack(side="right", padx=(6, 0))
    chip(chips, "A+", lambda: set_scale(state["scale"] * 1.15), 3).pack(side="right", padx=6)
    chip(chips, "A−", lambda: set_scale(state["scale"] / 1.15), 3).pack(side="right", padx=6)

    def mic_choice():
        name = mic_var.get()
        return mic_ids[mic_names.index(name)] if name in mic_names else None

    def restart_if_listening():
        if state["listening"]:
            stop_listening()
            start_listening()

    listen_btn = tk.Button(panel, font=(F, 18, "bold"), fg="white", bd=0, activeforeground="white",
                           command=lambda: toggle_listen(), cursor="hand2")
    listen_btn.pack(fill="x", pady=(12, 0), ipady=14)

    def apply_fonts():
        s = state["scale"]
        txt.tag_configure("hint", foreground=MUTED, font=(F, int(12 * s)), justify="center", spacing1=40)
        txt.tag_configure("src", foreground=MUTED, font=(F, int(11 * s)), spacing1=18)
        txt.tag_configure("dst", foreground=TEXT, font=(F, int(17 * s), "bold"), spacing1=3)
        txt.tag_configure("pron", foreground=GOLD, font=(F, int(13 * s)), spacing1=3,
                          elide=not settings.get("pron", False))
        partial_lbl.configure(font=("Segoe UI", int(13 * s), "italic"))
        preview_lbl.configure(font=("Segoe UI", int(15 * s)))

    def set_scale(s):
        state["scale"] = max(0.7, min(2.5, s))
        apply_fonts()

    def clear():
        txt.configure(state="normal")
        txt.delete("1.0", "end")
        txt.configure(state="disabled")

    def refresh():
        ru = state["pair"] == "ru-en"
        for b, on, col in ((seg_ru, ru, DIRS["ru-en"]["color"]), (seg_en, not ru, DIRS["en-ru"]["color"])):
            b.configure(bg=col if on else SURF, fg="white" if on else MUTED, activebackground=col if on else SURF2)
        if state["listening"]:
            listen_btn.configure(text="■   Stop", bg=STOP, activebackground=STOP, state="normal")
        elif state["ready"]:
            listen_btn.configure(text="●   Listen", bg=GREEN, activebackground=GREEN, state="normal")
        else:
            listen_btn.configure(text="Loading…", bg=SURF2, activebackground=SURF2, state="disabled",
                                 disabledforeground=MUTED)

    entry_counter = [0]

    def add_entry(original):
        entry_counter[0] += 1
        mark = "dst%d" % entry_counter[0]
        pmark = "pron%d" % entry_counter[0]
        txt.configure(state="normal")
        hint = txt.tag_ranges("hint")
        if hint:
            txt.delete(hint[0], hint[-1])
        txt.insert("end", original + "\n", ("src",))
        txt.mark_set(mark, "end-1c")
        txt.mark_gravity(mark, "left")
        txt.insert("end", "…\n", ("dst",))
        txt.mark_set(pmark, "end-1c")
        txt.mark_gravity(pmark, "left")
        txt.insert("end", "\n", ("pron",))
        txt.configure(state="disabled")
        txt.see("end")
        return mark, pmark

    def fill_pron(pmark, russian):
        try:
            p = pronounce(russian, stress)
        except Exception:
            return
        txt.configure(state="normal")
        txt.insert(pmark, p, ("pron",))
        txt.configure(state="disabled")
        txt.see("end")

    def save_settings():
        try:
            with open(settings_path, "w", encoding="utf-8") as fh:
                json.dump(settings, fh)
        except Exception:
            pass

    def update_pron_btn():
        on = settings.get("pron", False)
        pron_btn.configure(text="Pronounce  ✓" if on else "Pronounce",
                           bg="#3A3218" if on else SURF2, fg=GOLD if on else TEXT,
                           highlightbackground=GOLD if on else BORDER)
        txt.tag_configure("pron", elide=not on)

    def toggle_pron():
        settings["pron"] = not settings.get("pron", False)
        save_settings()
        update_pron_btn()

    def fill_entry(mark, result):
        txt.configure(state="normal")
        txt.delete(mark, "%s lineend" % mark)
        txt.insert(mark, result or "—", ("dst",))
        txt.configure(state="disabled")
        txt.see("end")

    # ---------- engine ----------
    def load(pair):
        state["ready"] = False
        refresh()
        status_var.set("Loading %s models…" % ("Russian" if pair == "ru-en" else "English"))

        def job():
            engine.load(pair)
            events.put(("loaded", pair))
        submit(job)

    def translate_async(pair, text, cb):
        def job():
            res = engine.translate(pair, text)
            if res is not None:
                events.put(("call", lambda: cb(res)))
        submit(job)

    # ---------- audio ----------
    def start_listening():
        if not state["ready"] or state["listening"]:
            return
        import sounddevice as sd
        dev = mic_choice()
        try:
            info = sd.query_devices(dev, "input")
            rate = int(info["default_samplerate"]) or SAMPLE_RATE
        except Exception as e:
            status_var.set("Microphone error: %s" % e)
            return
        # Vosk resamples internally, so record at the mic's own rate (most compatible)
        pair = state["pair"]
        accept, finish = engine.open_session(
            rate,
            lambda t: events.put(("partial", (pair, t))),
            lambda t: events.put(("final", (pair, t))))
        audio = queue.Queue()
        stop = threading.Event()
        pair = state["pair"]
        state["level"] = 0.0
        state["peak_since_start"] = 0
        state["listen_started"] = time.time()

        def cb(indata, frames, t, status):
            data = bytes(indata)
            if data:
                samples = memoryview(data).cast("h")
                peak = max(abs(min(samples)), abs(max(samples)))
                state["level"] = peak / 32768.0
                if peak > state["peak_since_start"]:
                    state["peak_since_start"] = peak
            audio.put(data)

        try:
            stream = sd.RawInputStream(samplerate=rate, blocksize=int(rate * 0.1), dtype="int16",
                                       channels=1, callback=cb, device=dev)
            stream.start()
        except Exception as e:
            status_var.set("Microphone error: %s" % e)
            return

        def loop():
            try:
                while not stop.is_set():
                    try:
                        data = audio.get(timeout=0.2)
                    except queue.Empty:
                        continue
                    accept(data)
                finish()
            except Exception as e:
                events.put(("status", "Speech recognizer error: %s" % e))

        th = threading.Thread(target=loop, daemon=True)
        th.start()
        state.update(listening=True, stream=stream, stop=stop, rec_thread=th, silence_warned=False)
        status_var.set("Listening… " + DIRS[pair]["hint"].lower())
        refresh()

    def stop_listening():
        if not state["listening"]:
            return
        try:
            state["stream"].stop()
            state["stream"].close()
        except Exception:
            pass
        state["stop"].set()
        state["rec_thread"].join(timeout=3)   # its final result is queued before we continue
        state.update(listening=False, stream=None)
        if state["ready"]:
            status_var.set("Stopped. Press LISTEN to continue.")
        refresh()

    def toggle_listen():
        stop_listening() if state["listening"] else start_listening()

    def toggle():
        was = state["listening"]
        stop_listening()
        drain_events()   # translate the old direction's last words before swapping models
        state["pair"] = "en-ru" if state["pair"] == "ru-en" else "ru-en"
        state["resume"] = was
        load(state["pair"])

    # ---------- event pump ----------
    def handle(kind, payload):
        if kind == "status":
            status_var.set(payload)
        elif kind == "loaded":
            if payload != state["pair"]:
                return
            state["ready"] = True
            status_var.set("Ready. Press LISTEN and %s." % DIRS[payload]["hint"].lower())
            refresh()
            if state["resume"]:
                state["resume"] = False
                start_listening()
        elif kind == "call":
            payload()
        elif kind == "partial":
            pair, text = payload
            if pair != state["pair"]:
                return
            partial_var.set(text)
            now = time.time()
            if (len(text.split()) >= 4 and pending[0] == 0 and now - state["last_preview"] > 1.5
                    and text != state["last_src"]):
                state["last_preview"], state["last_src"] = now, text
                translate_async(pair, text, lambda r: preview_var.set(r) if partial_var.get() else None)
        elif kind == "final":
            pair, text = payload
            partial_var.set("")
            preview_var.set("")
            state["last_src"] = ""
            if text.strip():
                mark, pmark = add_entry(text)
                if pair == "ru-en":
                    fill_pron(pmark, text)

                def done(r, m=mark, pm=pmark, p=pair):
                    fill_entry(m, r)
                    if p == "en-ru" and r:
                        fill_pron(pm, r)
                translate_async(pair, text, done)

    def drain_events():
        while True:
            try:
                kind, payload = events.get_nowait()
            except queue.Empty:
                return
            handle(kind, payload)

    def pump():
        drain_events()
        lvl = state.get("level", 0.0) if state["listening"] else 0.0
        meter.coords(meter_bar, 0, 0, int(150 * min(1.0, lvl * 4)), 6)
        if (state["listening"] and time.time() - state.get("listen_started", 0) > 2.5
                and state.get("peak_since_start", 1) < 30 and not state.get("silence_warned")):
            state["silence_warned"] = True
            status_var.set("The microphone is sending silence. Pick another Mic, or check Windows "
                           "Settings > Privacy > Microphone > 'Let desktop apps access your microphone'.")
        root.after(50, pump)

    root.bind("<space>", lambda e: toggle_listen())
    root.bind("<Tab>", lambda e: (toggle(), "break")[1])
    root.protocol("WM_DELETE_WINDOW", lambda: (stop_listening(), root.destroy()))

    apply_fonts()
    update_pron_btn()
    refresh()
    load(state["pair"])
    pump()
    try:
        root.mainloop()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        i = sys.argv.index("--selftest")
        selftest(sys.argv[i + 1] if len(sys.argv) > i + 1 else None)
    else:
        try:
            run_gui()
        except SystemExit as e:
            if e.code and not isinstance(e.code, int):
                try:
                    import tkinter.messagebox as mb
                    mb.showerror("Translator", str(e.code))
                except Exception:
                    pass
            raise
