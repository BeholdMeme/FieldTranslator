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

SAMPLE_RATE = 16000

DIRS = {
    "ru-en": {"asr": "ru", "label": "RUSSIAN  →  ENGLISH", "color": "#C62828", "hint": "Speak Russian"},
    "en-ru": {"asr": "en", "label": "ENGLISH  →  RUSSIAN", "color": "#1565C0", "hint": "Speak English"},
}


def app_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def find_models():
    here = app_dir()
    for cand in (os.path.join(here, "models"),
                 os.path.join(here, "..", "app", "src", "main", "assets")):
        if os.path.isdir(os.path.join(cand, "mt", "ru-en")) and os.path.isdir(os.path.join(cand, "asr", "ru")):
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
    """Loads one direction at a time (keeps memory low)."""

    def __init__(self, models):
        self.models = models
        self.pair = None
        self.asr_model = None
        self.translator = None
        self.lock = threading.Lock()

    def load(self, pair):
        from vosk import Model, SetLogLevel
        SetLogLevel(-1)
        with self.lock:
            if self.pair == pair:
                return
            self.asr_model = None
            self.translator = None
            self.asr_model = Model(os.path.join(self.models, "asr", DIRS[pair]["asr"]))
            self.translator = MarianTranslator(os.path.join(self.models, "mt", pair),
                                               threads=min(4, os.cpu_count() or 2))
            self.translator.translate("ok")
            self.pair = pair

    def translate(self, pair, text):
        with self.lock:
            if self.pair != pair or self.translator is None:
                return None
            return self.translator.translate(text)


def selftest():
    out = []
    ok = True
    try:
        models = find_models()
        eng = Engine(models)
        for pair, phrase in (("ru-en", "где находится вокзал"), ("en-ru", "where is the train station")):
            t0 = time.time()
            eng.load(pair)
            res = eng.translate(pair, phrase)
            out.append("%s: %s -> %s (%.1fs incl. load)" % (pair, phrase, res, time.time() - t0))
            from vosk import KaldiRecognizer
            rec = KaldiRecognizer(eng.asr_model, SAMPLE_RATE)
            rec.AcceptWaveform(b"\x00\x00" * SAMPLE_RATE)
            out.append("  asr ok: " + json.loads(rec.FinalResult()).get("text", "") + "<silence>")
            if not res:
                ok = False
        import sounddevice  # noqa: F401  (checks the audio library is bundled)
        out.append("audio library ok")
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
    root = tk.Tk()
    root.title("RU ⇄ EN Translator (offline)")
    root.geometry("720x820")
    root.configure(bg="#101214")

    dir_btn = tk.Button(root, font=("Segoe UI", 20, "bold"), fg="white", bd=0, activeforeground="white",
                        command=lambda: toggle())
    dir_btn.pack(fill="x", padx=12, pady=(12, 0), ipady=14)
    tk.Label(root, text="click to switch language  (Tab key also switches, Space starts/stops)",
             bg="#101214", fg="#8A9099").pack(pady=(4, 6))

    body = tk.Frame(root, bg="#101214")
    body.pack(fill="both", expand=True, padx=12)
    txt = tk.Text(body, bg="#16191D", fg="white", wrap="word", bd=0, padx=12, pady=10,
                  insertbackground="white")
    sb = tk.Scrollbar(body, command=txt.yview)
    txt.configure(yscrollcommand=sb.set, state="disabled")
    sb.pack(side="right", fill="y")
    txt.pack(side="left", fill="both", expand=True)

    partial_var = tk.StringVar()
    preview_var = tk.StringVar()
    status_var = tk.StringVar(value="Starting…")
    partial_lbl = tk.Label(root, textvariable=partial_var, bg="#101214", fg="#B0B6BE",
                           wraplength=680, justify="left", anchor="w")
    partial_lbl.pack(fill="x", padx=14, pady=(8, 0))
    preview_lbl = tk.Label(root, textvariable=preview_var, bg="#101214", fg="#80DEEA",
                           wraplength=680, justify="left", anchor="w")
    preview_lbl.pack(fill="x", padx=14)
    tk.Label(root, textvariable=status_var, bg="#101214", fg="#8A9099").pack(pady=6)

    tools = tk.Frame(root, bg="#101214")
    tools.pack(fill="x", padx=12)
    for label, cmd in (("A−", lambda: set_scale(state["scale"] / 1.15)),
                       ("A+", lambda: set_scale(state["scale"] * 1.15)),
                       ("Clear", lambda: clear())):
        tk.Button(tools, text=label, command=cmd, bg="#2A2F36", fg="white", bd=0,
                  font=("Segoe UI", 12), activebackground="#3A3F46").pack(side="left", expand=True,
                                                                          fill="x", padx=4, ipady=6)
    mic_row = tk.Frame(root, bg="#101214")
    mic_row.pack(fill="x", padx=12, pady=(8, 0))
    tk.Label(mic_row, text="Mic:", bg="#101214", fg="#8A9099").pack(side="left")
    mic_names, mic_ids = list_mics()
    mic_var = tk.StringVar(value=mic_names[0] if mic_names else "(no microphone found)")
    mic_menu = tk.OptionMenu(mic_row, mic_var, *(mic_names or ["(no microphone found)"]),
                             command=lambda _v: restart_if_listening())
    mic_menu.configure(bg="#2A2F36", fg="white", activebackground="#3A3F46", activeforeground="white",
                       highlightthickness=0, bd=0, anchor="w")
    mic_menu["menu"].configure(bg="#2A2F36", fg="white")
    mic_menu.pack(side="left", fill="x", expand=True, padx=(6, 8))
    meter = tk.Canvas(mic_row, width=140, height=14, bg="#2A2F36", highlightthickness=0)
    meter.pack(side="right")
    meter_bar = meter.create_rectangle(0, 0, 0, 14, fill="#43A047", width=0)

    def mic_choice():
        name = mic_var.get()
        return mic_ids[mic_names.index(name)] if name in mic_names else None

    def restart_if_listening():
        if state["listening"]:
            stop_listening()
            start_listening()

    listen_btn = tk.Button(root, font=("Segoe UI", 22, "bold"), fg="white", bd=0, activeforeground="white",
                           command=lambda: toggle_listen())
    listen_btn.pack(fill="x", padx=12, pady=12, ipady=18)

    def apply_fonts():
        s = state["scale"]
        txt.tag_configure("src", foreground="#B0B6BE", font=("Segoe UI", int(12 * s)))
        txt.tag_configure("dst", foreground="white", font=("Segoe UI", int(18 * s), "bold"), spacing3=14)
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
        d = DIRS[state["pair"]]
        dir_btn.configure(text=d["label"], bg=d["color"], activebackground=d["color"])
        if state["listening"]:
            listen_btn.configure(text="■  STOP", bg="#D84315", activebackground="#D84315", state="normal")
        elif state["ready"]:
            listen_btn.configure(text="●  LISTEN", bg="#2E7D32", activebackground="#2E7D32", state="normal")
        else:
            listen_btn.configure(text="Loading…", bg="#3A3F45", activebackground="#3A3F45", state="disabled")

    entry_counter = [0]

    def add_entry(original):
        entry_counter[0] += 1
        mark = "dst%d" % entry_counter[0]
        txt.configure(state="normal")
        txt.insert("end", original + "\n", ("src",))
        txt.mark_set(mark, "end-1c")
        txt.mark_gravity(mark, "left")
        txt.insert("end", "…\n", ("dst",))
        txt.configure(state="disabled")
        txt.see("end")
        return mark

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
        from vosk import KaldiRecognizer
        dev = mic_choice()
        try:
            info = sd.query_devices(dev, "input")
            rate = int(info["default_samplerate"]) or SAMPLE_RATE
        except Exception as e:
            status_var.set("Microphone error: %s" % e)
            return
        # Vosk resamples internally, so record at the mic's own rate (most compatible)
        rec = KaldiRecognizer(engine.asr_model, rate)
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
                    if rec.AcceptWaveform(data):
                        events.put(("final", (pair, json.loads(rec.Result()).get("text", ""))))
                    else:
                        events.put(("partial", (pair, json.loads(rec.PartialResult()).get("partial", ""))))
                events.put(("final", (pair, json.loads(rec.FinalResult()).get("text", ""))))
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
                mark = add_entry(text)
                translate_async(pair, text, lambda r, m=mark: fill_entry(m, r))

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
        meter.coords(meter_bar, 0, 0, int(140 * min(1.0, lvl * 4)), 14)
        if (state["listening"] and time.time() - state.get("listen_started", 0) > 2.5
                and state.get("peak_since_start", 1) < 30 and not state.get("silence_warned")):
            state["silence_warned"] = True
            status_var.set("The microphone is sending silence. Pick another Mic above, or check Windows "
                           "Settings > Privacy > Microphone > 'Let desktop apps access your microphone'.")
        root.after(50, pump)

    root.bind("<space>", lambda e: toggle_listen())
    root.bind("<Tab>", lambda e: (toggle(), "break")[1])
    root.protocol("WM_DELETE_WINDOW", lambda: (stop_listening(), root.destroy()))

    apply_fonts()
    refresh()
    load(state["pair"])
    pump()
    try:
        root.mainloop()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
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
