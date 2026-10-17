"""Learn-to-read window for the laptop app: alphabet, lessons, flashcards, quiz. Offline."""
import json
import os
import random
import tkinter as tk

BG, CARD, BTN, MUTED, ACCENT, GOOD, BAD, GOLD = ("#0E1116", "#171B22", "#20252E", "#8B93A1",
                                                 "#3E8CFF", "#2FB36D", "#E5484D", "#F5C451")
FONT = "Segoe UI"


class LearnWindow:
    def __init__(self, root, models_dir, settings, save_settings):
        with open(os.path.join(models_dir, "learn", "learn.json"), encoding="utf-8") as f:
            self.data = json.load(f)
        self.settings = settings
        self.save_settings = save_settings
        self.settings.setdefault("best", {})
        self.win = tk.Toplevel(root)
        self.win.title("Learn to read Russian")
        self.win.geometry("760x820")
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
        self.label("Pronunciation tips", 15, bold=True)
        for t in self.data.get("tips", []):
            self.label("• " + t, 12)
        self.label(self.data.get("source", ""), 9, MUTED)

    def show_letter(self, l):
        self.clear()
        self.button("←  Back", self.home)
        self.label(l["upper"] + " " + l["lower"], 54, bold=True, center=True)
        self.card("Name", l["name"])
        self.card("Sound", l["sound"])
        self.card("Example", "%s\n%s\n%s" % (l["example"], l["example_pron"], l["example_meaning"]))

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

    def question(self, i, qs, n, score):
        if n >= len(qs):
            return self.result(i, score, len(qs))
        prompt, big, answer, choices = qs[n]
        self.clear()
        self.button("←  Back", lambda: self.lesson(i))
        self.label("Question %d of %d   ·   score %d" % (n + 1, len(qs), score), 11, MUTED)
        self.label(prompt, 14)
        self.label(big, 40, bold=True, center=True)
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
        self.label("Great - you've got these letters. On to the next lesson!" if score >= 8
                   else "Keep practicing: try the flashcards again, then retake the quiz.", 14)
        self.button("Retake quiz", lambda: self.start_quiz(i))
        self.button("Back to lessons", self.home, ACCENT)
