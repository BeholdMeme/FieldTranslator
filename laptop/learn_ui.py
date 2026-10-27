"""Learn-to-read window for the laptop app: alphabet, lessons, flashcards, quiz. Offline."""
import json
import os
import random
import tkinter as tk

BG, CARD, BTN, MUTED, ACCENT, GOOD, BAD, GOLD = ("#0E1116", "#171B22", "#20252E", "#8B93A1",
                                                 "#3E8CFF", "#2FB36D", "#E5484D", "#F5C451")
FONT = "Segoe UI"


class LearnWindow:
    def __init__(self, root, models_dir, settings, save_settings, voice=None):
        self.voice = voice
        with open(os.path.join(models_dir, "learn", "learn.json"), encoding="utf-8") as f:
            self.data = json.load(f)
        self.settings = settings
        self.models_dir = models_dir
        self.save_settings = save_settings
        self.settings.setdefault("best", {})
        self.win = tk.Toplevel(root)
        self.win.title("Learn to read Russian")
        self.win.geometry("760x%d" % max(480, min(820, self.win.winfo_screenheight() - 90)))
        self.win.configure(bg=BG)
        # scrollable page
        canvas = tk.Canvas(self.win, bg=BG, highlightthickness=0)
        sb = tk.Scrollbar(self.win, command=canvas.yview)
        canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        self.page = tk.Frame(canvas, bg=BG)
        win_id = canvas.create_window((0, 0), window=self.page, anchor="nw")
        self.page.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(win_id, width=e.width))
        self.win.bind("<MouseWheel>", lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))
        self.canvas = canvas
        self.rnd = random.Random()
        self.home()

    # ---------- widgets ----------
    def clear(self):
        self.win.unbind("<Left>")
        self.win.unbind("<Right>")
        for w in self.page.winfo_children():
            w.destroy()
        self.canvas.yview_moveto(0)

    def label(self, text, size=13, color="white", bold=False, center=False, wrap=700):
        lbl = tk.Label(self.page, text=text, bg=BG, fg=color, font=(FONT, size, "bold" if bold else "normal"),
                       wraplength=wrap, justify="center" if center else "left", anchor="center" if center else "w")
        lbl.pack(fill="x", padx=16, pady=3)
        return lbl

    def button(self, text, cmd, color=BTN, parent=None, size=13):
        b = tk.Button(parent or self.page, text=text, command=cmd, bg=color, fg="white", bd=0,
                      activebackground=color, activeforeground="white", font=(FONT, size),
                      wraplength=680, justify="left", cursor="hand2", relief="flat",
                      highlightthickness=1 if color == BTN else 0, highlightbackground="#2A303A")
        if parent is None:
            b.pack(fill="x", padx=16, pady=4, ipady=8)
        return b

    def card(self, label, body):
        f = tk.Frame(self.page, bg=CARD)
        f.pack(fill="x", padx=16, pady=4)
        tk.Label(f, text=label, bg=CARD, fg=MUTED, font=(FONT, 11), anchor="w").pack(fill="x", padx=10, pady=(6, 0))
        tk.Label(f, text=body, bg=CARD, fg="white", font=(FONT, 15), anchor="w", justify="left",
                 wraplength=660).pack(fill="x", padx=10, pady=(0, 8))

    def hear(self, russian, parent=None):
        """'▶ Hear it' button that reads Russian aloud."""
        if not self.voice:
            return None
        b = tk.Button(parent or self.page, text="▶  Hear it", command=lambda: self.voice.say(russian),
                      bg="#1B2A45", fg="#9CC3FF", bd=0, activebackground="#24385C", activeforeground="#9CC3FF",
                      font=(FONT, 12, "bold"), cursor="hand2", relief="flat")
        if parent is None:
            b.pack(fill="x", padx=16, pady=4, ipady=6)
        return b

    def letter(self, upper):
        for l in self.data["alphabet"]:
            if l["upper"] == upper:
                return l
        return None

    # ---------- screens ----------
    def home(self):
        self.clear()
        self.label("Learn to read Russian", 20, bold=True)
        self.label("33 letters. Click a letter to see how it sounds, then do the lessons in order.", 12, MUTED)
        self.label("Alphabet", 15, bold=True)
        grid = tk.Frame(self.page, bg=BG)
        grid.pack(padx=16, pady=4, anchor="w")
        for i, l in enumerate(self.data["alphabet"]):
            b = tk.Button(grid, text=l["upper"] + l["lower"], width=4, command=lambda l=l: self.show_letter(l),
                          bg=BTN, fg="white", bd=0, font=(FONT, 16), activebackground="#2A303A",
                          activeforeground="white")
            b.grid(row=i // 9, column=i % 9, padx=3, pady=3, ipady=4)
        self.label("Reading lessons", 15, bold=True)
        for i, les in enumerate(self.data["lessons"]):
            best = self.settings["best"].get(str(i))
            status = "not started" if best is None else "best quiz score %d/10%s" % (best, "  ✓" if best >= 8 else "")
            self.button("%d. %s\n%s   ·   %s" % (i + 1, les["title"], " ".join(les["letters"]), status),
                        lambda i=i: self.lesson(i), GOOD if (best or 0) >= 8 else BTN)
        self.label("Numbers & colors", 15, bold=True)
        if self.data.get("numbers"):
            self.button("Numbers   ·   say any number", self.numbers)
        if self.data.get("colors"):
            self.button("Colors   ·   %d" % len(self.data["colors"]["items"]), self.colors)
        self.label("Phrases & verses", 15, bold=True)
        if self.data.get("phrases"):
            self.button("Common phrases   ·   %d" % len(self.data["phrases"]), lambda: self.item_list("phrases"))
        if self.data.get("verses"):
            self.button("Bible verses   ·   %d" % len(self.data["verses"]), lambda: self.item_list("verses"))
        if self.data.get("military"):
            self.button("Military phrases   ·   %d" % len(self.data["military"]), lambda: self.item_list("military"))
        self.label("Pronunciation tips", 15, bold=True)
        for t in self.data.get("tips", []):
            self.label("• " + t, 12)
        self.label(self.data.get("source", ""), 9, MUTED)

    def show_letter(self, l):
        abc = self.data["alphabet"]
        idx = next(i for i, x in enumerate(abc) if x["upper"] == l["upper"])
        prev_l, next_l = abc[(idx - 1) % len(abc)], abc[(idx + 1) % len(abc)]
        self.clear()
        self.button("←  Back", self.home)
        self.label("Letter %d of %d   (← / → keys also work)" % (idx + 1, len(abc)), 11, MUTED)
        self.win.bind("<Left>", lambda e: self.show_letter(prev_l))
        self.win.bind("<Right>", lambda e: self.show_letter(next_l))
        self.label(l["upper"] + " " + l["lower"], 54, bold=True, center=True)
        self.hear(l.get("say", l["example"]))
        self.card("Name", l["name"])
        self.card("Sound", l["sound"])
        self.card("Example", "%s\n%s\n%s" % (l["example"], l["example_pron"], l["example_meaning"]))
        nav = tk.Frame(self.page, bg=BG)
        nav.pack(fill="x", padx=16, pady=6)
        self.button("←  " + prev_l["upper"], lambda: self.show_letter(prev_l), parent=nav).pack(
            side="left", fill="x", expand=True, padx=(0, 6), ipady=8)
        self.button(next_l["upper"] + "  →", lambda: self.show_letter(next_l), ACCENT, parent=nav).pack(
            side="left", fill="x", expand=True, padx=(6, 0), ipady=8)

    def item_list(self, key):
        self.clear()
        verses = key == "verses"
        self.button("←  Back", self.home)
        titles = {"verses": "Bible verses", "military": "Military phrases", "phrases": "Common phrases"}
        notes = {"verses": "Russian Synodal translation with the King James Version. Gold = how to say it.",
                 "military": "Short field commands, said loud and sharp. Gold = how to say it.",
                 "phrases": "Gold = how to say it. The CAPITAL syllable is stressed."}
        self.label(titles.get(key, key), 20, bold=True)
        self.label(notes.get(key, ""), 11, MUTED)
        self.button("Practice as flashcards", lambda: self.item_card(key, 0), ACCENT)
        last = ""
        for it in self.data[key]:
            cat = it.get("category", "")
            if cat and cat != last:
                self.label(cat, 14, bold=True)
                last = cat
            f = tk.Frame(self.page, bg=CARD)
            f.pack(fill="x", padx=16, pady=4)
            if it.get("ref"):
                tk.Label(f, text=it["ref"], bg=CARD, fg=ACCENT, font=(FONT, 10, "bold"), anchor="w").pack(
                    fill="x", padx=12, pady=(8, 0))
            for txt_, col, size in ((it["ru"], "white", 15), (it["pron"], GOLD, 12), (it["en"], MUTED, 11)):
                tk.Label(f, text=txt_, bg=CARD, fg=col, font=(FONT, size), anchor="w", justify="left",
                         wraplength=660).pack(fill="x", padx=12, pady=1)
            if self.voice:
                h = tk.Label(f, text="▶  hear", bg=CARD, fg="#9CC3FF", font=(FONT, 10, "bold"), cursor="hand2",
                             anchor="w")
                h.pack(fill="x", padx=12, pady=(2, 0))
                h.bind("<Button-1>", lambda e, r=it["ru"]: self.voice.say(r))
            tk.Frame(f, bg=CARD, height=6).pack()

    def item_card(self, key, n):
        items = self.data[key]
        it = items[n % len(items)]
        self.clear()
        self.button("←  Back", lambda: self.item_list(key))
        self.label("Card %d of %d - read it out loud, then click Show" % (n % len(items) + 1, len(items)), 11, MUTED)
        if it.get("ref"):
            self.label(it["ref"], 13, ACCENT, bold=True)
        self.label(it["ru"], 18 if key == "verses" else 26, bold=True)
        ans = self.label("", 15, GOLD)
        self.hear(it["ru"])
        self.button("Show", lambda: ans.configure(text="%s\n\n%s" % (it["pron"], it["en"])))
        nav = tk.Frame(self.page, bg=BG)
        nav.pack(fill="x", padx=16, pady=6)
        self.button("←  Previous", lambda: self.item_card(key, n - 1), parent=nav).pack(
            side="left", fill="x", expand=True, padx=(0, 6), ipady=8)
        self.button("Next  →", lambda: self.item_card(key, n + 1), ACCENT, parent=nav).pack(
            side="left", fill="x", expand=True, padx=(6, 0), ipady=8)

    def lesson(self, i):
        les = self.data["lessons"][i]
        self.clear()
        self.button("←  Back", self.home)
        self.label("%d. %s" % (i + 1, les["title"]), 20, bold=True)
        self.label(les["intro"], 13)
        self.label("New letters", 15, bold=True)
        for u in les["letters"]:
            l = self.letter(u)
            if l:
                self.card(l["upper"] + " " + l["lower"], l["sound"])
        self.button("Practice words (flashcards)", lambda: self.flashcard(i, 0), ACCENT)
        self.button("Take the quiz (10 questions)", lambda: self.start_quiz(i), GOOD)

    def flashcard(self, i, n):
        words = self.data["lessons"][i]["words"]
        if not words:
            return self.lesson(i)
        w = words[n % len(words)]
        self.clear()
        self.button("←  Back", lambda: self.lesson(i))
        self.label("Card %d of %d - read it out loud, then click Show" % (n % len(words) + 1, len(words)), 11, MUTED)
        self.label(w["word"], 44, bold=True, center=True)
        ans = self.label("", 20, GOLD, center=True)
        self.hear(w["word"])
        self.button("Show", lambda: ans.configure(text="%s\n%s" % (w["pron"], w["meaning"])))
        self.button("Next  →", lambda: self.flashcard(i, n + 1), ACCENT)

    def start_quiz(self, i):
        lessons = self.data["lessons"]
        pool = [w for les in lessons[:i + 1] for w in les["words"]]
        letters = [self.letter(u) for les in lessons[:i + 1] for u in les["letters"]]
        letters = [l for l in letters if l]
        new_words = lessons[i]["words"]
        new_letters = [l for l in (self.letter(u) for u in lessons[i]["letters"]) if l]
        qs = []
        for n in range(10):
            t = n % 3
            if t == 0 and new_letters and len(letters) >= 4:
                l = self.rnd.choice(new_letters)
                qs.append(("What sound does this letter make?", l["upper"] + " " + l["lower"], l["sound"],
                           self.choices(l["sound"], [x["sound"] for x in letters])))
            elif new_words and len(pool) >= 4:
                w = self.rnd.choice(new_words)
                if t == 1 or not new_letters:
                    qs.append(("How do you read this word?", w["word"], w["pron"],
                               self.choices(w["pron"], [x["pron"] for x in pool])))
                else:
                    qs.append(("What does this word mean?", w["word"], w["meaning"],
                               self.choices(w["meaning"], [x["meaning"] for x in pool])))
        self.question(i, qs, 0, 0)

    def choices(self, answer, options):
        opts = [o for o in dict.fromkeys(options) if o and o != answer]
        self.rnd.shuffle(opts)
        out = [answer] + opts[:3]
        self.rnd.shuffle(out)
        return out

    # ---------- numbers ----------
    def pron(self, text):
        if not hasattr(self, "_sd"):
            from pronounce import StressDict
            path = os.path.join(self.models_dir, "learn", "stress.tsv")
            self._sd = StressDict(path) if os.path.isfile(path) else StressDict()
        from pronounce import pronounce
        return pronounce(text, self._sd)

    def numbers(self):
        from ru_numbers import to_words
        nums = self.data["numbers"]
        self.clear()
        self.button("←  Back", self.home)
        self.label("Numbers", 20, bold=True)
        box = tk.Frame(self.page, bg="#15233A")
        box.pack(fill="x", padx=16, pady=6)
        tk.Label(box, text="Say any number", bg="#15233A", fg="#9CC3FF", font=(FONT, 11, "bold"),
                 anchor="w").pack(fill="x", padx=12, pady=(10, 2))
        var = tk.StringVar()
        ent = tk.Entry(box, textvariable=var, font=(FONT, 20), bg="#0E1116", fg="white", insertbackground="white",
                       relief="flat")
        ent.pack(fill="x", padx=12, pady=4, ipady=4)
        words = tk.Label(box, text="Type a number, e.g. 2025", bg="#15233A", fg=MUTED, font=(FONT, 16, "bold"),
                         anchor="w", justify="left", wraplength=660)
        words.pack(fill="x", padx=12)
        pr = tk.Label(box, text="", bg="#15233A", fg=GOLD, font=(FONT, 13), anchor="w", justify="left", wraplength=660)
        pr.pack(fill="x", padx=12, pady=(0, 6))
        cur = {"w": ""}

        def update(*_):
            t = var.get().replace(",", "").replace(" ", "").strip()
            if not t:
                words.configure(text="Type a number, e.g. 2025", fg=MUTED)
                pr.configure(text="")
                cur["w"] = ""
                return
            try:
                w = to_words(int(t))
                cur["w"] = w
                words.configure(text=w, fg="white")
                pr.configure(text=self.pron(w))
            except Exception:
                words.configure(text="Whole numbers up to 999,999,999,999", fg=MUTED)
                pr.configure(text="")
                cur["w"] = ""
        var.trace_add("write", update)
        if self.voice:
            self.hear_btn = tk.Button(box, text="▶  Hear it", command=lambda: self.voice.say(cur["w"]), bg="#1B2A45",
                                      fg="#9CC3FF", bd=0, font=(FONT, 12, "bold"), cursor="hand2",
                                      activebackground="#24385C", activeforeground="#9CC3FF")
            self.hear_btn.pack(fill="x", padx=12, pady=(0, 10), ipady=4)
        ent.focus_set()
        self.button("Take the numbers quiz (10 questions)", self.number_quiz, GOOD)
        for grp in nums["groups"]:
            self.label(grp["title"], 15, bold=True)
            self.label(grp["note"], 11, MUTED)
            for it in grp["items"]:
                f = tk.Frame(self.page, bg=CARD, cursor="hand2")
                f.pack(fill="x", padx=16, pady=3)
                d = tk.Label(f, text=it["digits"], bg=CARD, fg=ACCENT, font=(FONT, 16, "bold"), width=13, anchor="w")
                d.pack(side="left", padx=(12, 6), pady=6)
                col = tk.Frame(f, bg=CARD)
                col.pack(side="left", fill="x", expand=True)
                a = tk.Label(col, text=it["ru"], bg=CARD, fg="white", font=(FONT, 13), anchor="w", justify="left",
                             wraplength=420)
                a.pack(fill="x")
                b = tk.Label(col, text=it["pron"], bg=CARD, fg=GOLD, font=(FONT, 11), anchor="w", justify="left",
                             wraplength=420)
                b.pack(fill="x", pady=(0, 4))
                if self.voice:
                    for wdg in (f, d, col, a, b):
                        wdg.bind("<Button-1>", lambda e, r=it["ru"]: self.voice.say(r))
        self.label("How it works", 15, bold=True)
        for t in nums.get("tips", []):
            self.label("• " + t, 12)

    def number_quiz(self):
        from ru_numbers import format_digits, to_words

        def rnum():
            r = self.rnd.randrange(10)
            if r < 3:
                return self.rnd.randrange(20)
            if r < 5:
                return 20 + self.rnd.randrange(80)
            if r < 7:
                return 100 + self.rnd.randrange(900)
            if r < 9:
                return 1000 + self.rnd.randrange(99000)
            return 100000 + self.rnd.randrange(9900000)
        qs = []
        for n in range(10):
            num = rnum()
            opts = [num]
            while len(opts) < 4:
                d = rnum() if self.rnd.randrange(3) == 0 else max(
                    0, num + self.rnd.choice((-1, 1)) * (1 + self.rnd.randrange(max(3, min(num // 3 + 3, 5000)))))
                if d not in opts:
                    opts.append(d)
            self.rnd.shuffle(opts)
            if n % 2 == 0:
                qs.append(("How do you say this number?", format_digits(num), to_words(num), [to_words(o) for o in opts]))
            else:
                qs.append(("Which number is this?", to_words(num), format_digits(num), [format_digits(o) for o in opts]))
        self.question("numbers", qs, 0, 0)

    # ---------- colors ----------
    def colors(self):
        cols = self.data["colors"]
        self.clear()
        self.button("←  Back", self.home)
        self.label("Colors", 20, bold=True)
        self.label("Click a color to hear it.", 11, MUTED)
        for c in cols["items"]:
            f = tk.Frame(self.page, bg=CARD, cursor="hand2")
            f.pack(fill="x", padx=16, pady=3)
            sw = tk.Canvas(f, width=36, height=36, bg=CARD, highlightthickness=0)
            sw.create_oval(2, 2, 34, 34, fill=c["hex"], outline="#555555")
            sw.pack(side="left", padx=12, pady=8)
            a = tk.Label(f, text=c["ru"], bg=CARD, fg="white", font=(FONT, 15, "bold"), anchor="w")
            a.pack(side="left")
            b = tk.Label(f, text="   %s   ·   %s" % (c["pron"], c["en"]), bg=CARD, fg=GOLD, font=(FONT, 11), anchor="w")
            b.pack(side="left", fill="x", expand=True)
            if self.voice:
                for wdg in (f, sw, a, b):
                    wdg.bind("<Button-1>", lambda e, r=c["ru"]: self.voice.say(r))
        self.button("Take the colors quiz (10 questions)", self.color_quiz, GOOD)
        self.label("Good to know", 15, bold=True)
        for t in cols.get("tips", []):
            self.label("• " + t, 12)
        self.label("Examples", 15, bold=True)
        for it in cols.get("examples", []):
            f = tk.Frame(self.page, bg=CARD)
            f.pack(fill="x", padx=16, pady=3)
            for txt_, col, size in ((it["ru"], "white", 14), (it["pron"], GOLD, 11), (it["en"], MUTED, 11)):
                tk.Label(f, text=txt_, bg=CARD, fg=col, font=(FONT, size), anchor="w").pack(fill="x", padx=12, pady=1)

    def color_quiz(self):
        items = self.data["colors"]["items"]
        qs = []
        for n in range(10):
            c = self.rnd.choice(items)
            if n % 2 == 0:
                qs.append(("What color is this word?", c["ru"], c["en"], self.choices(c["en"], [x["en"] for x in items])))
            else:
                qs.append(("How do you say this color in Russian?", ("swatch", c["hex"]), c["ru"],
                           self.choices(c["ru"], [x["ru"] for x in items])))
        self.question("colors", qs, 0, 0)

    def question(self, i, qs, n, score):
        if n >= len(qs):
            return self.result(i, score, len(qs))
        prompt, big, answer, choices = qs[n]
        self.clear()
        back = {"numbers": self.numbers, "colors": self.colors}.get(i) or (lambda: self.lesson(i))
        self.button("←  Back", back)
        self.label("Question %d of %d   ·   score %d" % (n + 1, len(qs), score), 11, MUTED)
        self.label(prompt, 14)
        if isinstance(big, tuple):
            sw = tk.Canvas(self.page, width=120, height=120, bg=BG, highlightthickness=0)
            sw.create_oval(4, 4, 116, 116, fill=big[1], outline="#555555")
            sw.pack(pady=10)
        else:
            self.label(big, 40 if len(big) <= 14 else 22, bold=True, center=True)
        fb = self.label("", 14, bold=True)
        btns = []

        def pick(choice, btn):
            right = choice == answer
            for b, c in btns:
                b.configure(state="disabled", disabledforeground="white")
                if c == answer:
                    b.configure(bg=GOOD)
            if not right:
                btn.configure(bg=BAD)
            fb.configure(text="Correct!" if right else "Not quite. It's: " + answer)
            self.button("Next  →" if n + 1 < len(qs) else "See score",
                        lambda: self.question(i, qs, n + 1, score + (1 if right else 0)), ACCENT)

        for c in choices:
            b = self.button(c, None)
            b.configure(command=lambda c=c, b=b: pick(c, b))
            btns.append((b, c))

    def result(self, i, score, total):
        best = self.settings["best"].get(str(i), -1)
        if score > best:
            self.settings["best"][str(i)] = score
            self.save_settings()
        self.clear()
        self.label("Score: %d / %d" % (score, total), 22, bold=True)
        good = {"numbers": "Great - your numbers are solid!", "colors": "Great - you know your colors!"}.get(
            i, "Great - you've got these letters. On to the next lesson!")
        self.label(good if score >= 8 else "Keep practicing, then retake the quiz.", 14)
        again = {"numbers": self.number_quiz, "colors": self.color_quiz}.get(i) or (lambda: self.start_quiz(i))
        self.button("Retake quiz", again)
        self.button("Back to lessons", self.home, ACCENT)
