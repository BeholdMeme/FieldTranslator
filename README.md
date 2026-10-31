# RU ⇄ EN Field Translator

Live Russian ↔ English speech-to-text translation that runs **fully offline** on iPhone,
cheap Android phones (built for 3 GB-RAM phones like the Orbic Joy) and a Windows laptop.

**📱 iPhone / any browser: <https://beholdmeme.github.io/FieldTranslator/>** — see [Install on iPhone](#install-on-iphone)

**🤖 Android / 💻 Windows: [download from Releases](https://github.com/BeholdMeme/FieldTranslator/releases/tag/latest)**

* Big toggle: **Russian → English** or **English → Russian**
* Press **LISTEN**: words appear live as they're spoken. Each phrase is translated when
  the speaker pauses, and a rough live translation shows while they're still talking.
* No internet permission, no Google services, no accounts. Works on Huawei.
* `A−` / `A+` change text size (handy for showing the screen to the other person),
  long-press a translation to copy it.
* **Pronounce** (learning mode): shows how to say the Russian under each phrase, e.g.
  *Привет, как дела?* → **pri-VYET, kak di-LA?** (capital syllable = stressed). Off by default.
* **Read aloud** (🔊 / ▶ hear): an offline Russian voice (Piper "irina") reads letters, words, phrases,
  verses and the Russian lines in the translator. Loaded only when tapped, unloaded after 2 minutes idle.
* **Learn to read**: the alphabet (33 letters with sounds and examples), six reading lessons that only
  use letters you've learned so far, flashcards, and a 10-question quiz per lesson with saved best scores.
* **Learn** also has 52 common phrases, 20 Bible verses (Synodal + KJV), military phrases and drone
  warnings, Numbers (say any number, ordinals, dates) and Colors, each with flashcards and quizzes.
* **iPhone / web extras:** **camera reader** (photo of a Russian sign or menu → English, with
  pronunciation and voice; photos are never stored), **type to translate** (the alphabet you type picks
  the direction), conversations saved in **Chats**, ★ phrases into **Saved**, and a **rubles converter**.

## Getting the app

Every push to GitHub builds everything in the cloud (GitHub Actions) and publishes it on
the repo's **Releases** page under `latest`:

| File | For |
|---|---|
| `FieldTranslator.apk` | Any ARM Android 7+ phone (Samsung, Xiaomi, Realme, Orbic, Huawei EMUI / HarmonyOS 2–4) |
| `FieldTranslator-Windows.zip` | Windows 10/11 laptop. Unzip, run `FieldTranslator.exe` |

**Install on a phone:** copy the APK over (USB cable, SD card, USB-C stick, or download it)
→ tap it → allow "Install unknown apps" for your file manager/browser → Install.
First launch spends ~20–60 s unpacking models; after that it starts in a few seconds.

**Not supported as an app file:** the newest Huawei phones on HarmonyOS NEXT / 5+ (they can't
run Android apps). Use the web version below in their browser, or the laptop version.

## Install on iPhone

iPhone can't install APK files, so it uses the offline web app instead. Do this **at home on Wi-Fi,
before you travel**:

1. Open **Safari** (not Chrome) and go to <https://beholdmeme.github.io/FieldTranslator/>
2. Tap the **Share** button (square with an arrow) → **Add to Home Screen** → **Add**.
3. Open it **from the new Home Screen icon**, not from Safari.
4. Leave it open on Wi-Fi until it finishes downloading the speech, translation and voice files
   (a few hundred MB; the first time can take several minutes). Allow the microphone when asked.
5. Test it: turn on **Airplane Mode**, open the icon, and try LISTEN, typing, the voice and the camera reader.
   If it all works in Airplane Mode, it will work anywhere.

Tips: don't delete the Home Screen icon or clear Safari's website data, or the offline files go
with it. If iOS offers storage warnings, keep the app. Updates install automatically next time
you open it with internet.

The same link works in Chrome/Edge on Android, Windows or Mac (use "Install app" in the menu).

## How it works

| Part | Tech | Size |
|---|---|---|
| Speech → text (Russian) | [alphacep small streaming Zipformer](https://huggingface.co/alphacep/vosk-model-small-streaming-ru) (int8) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | ~28 MB |
| Speech → text (English) | [Vosk](https://alphacephei.com/vosk/) small model `en-us-0.15`, streaming | 40 MB |
| Text → text | [Opus-MT](https://github.com/Helsinki-NLP/Opus-MT) `ru-en` / `en-ru`, int8 ONNX, ONNX Runtime | ~110 MB each |
| App | Plain Java Android app (no AndroidX), Python/Tk on the laptop, offline web app (WebAssembly) for iPhone | |

Only the selected direction's models are loaded (~600–800 MB RAM peak), so 3 GB phones are fine.
Translation is greedy decoding with a KV cache; typically 0.3–1.5 s per phrase on a budget phone.

## Credits

Word list and stress marks for the pronunciation guide and lessons: [OpenRussian.org dictionary](https://github.com/Badestrand/russian-dictionary),
CC BY-SA 4.0 (the generated `learn/` data is shared under the same license).

## Accuracy notes

* Small models: good on clear speech, weaker with background noise, heavy accents,
  or fast talkers. A cheap wired headset mic makes a big difference.
* Translation quality is "understandable and useful", not Google-level.

## Repo layout

```
app/        Android app (MainActivity, ModelInstaller)
core/       Pure-Java tokenizer + translator, shared by the app; unit tests
laptop/     Windows/Mac/Linux app (app.py) + Python mirror of the translator
web/        Offline web app for iPhone / any browser (hosted on GitHub Pages)
scripts/    fetch_models.sh (downloads models), prepare_mt.py, make_reference.py
.github/    CI: tests, builds the APK + Windows app + web app, publishes the release and the site
```

## Quality checks in CI (a build fails if any of these fail)

1. Python tokenizer == HuggingFace `MarianTokenizer` on test phrases.
2. Field phrases translate sensibly in both directions (e.g. "где находится вокзал" → contains "station").
3. The Java (phone) code reproduces the Python results **token for token** on the real models.
4. The tokenizer matches SentencePiece on 800+ tricky strings.
5. The APK contains the native libraries for every phone CPU type and all models.
6. Russian speech recognition is run on a real Russian recording in Python and in the phone's Java code; both must produce the same text.
7. The built Windows app runs a self-test (loads models, translates both directions).
8. The web app is tested in Chromium and WebKit (Safari's engine), including offline.

## Building locally (optional)

Needs internet once, Java 17, Android SDK, Python 3.11 with `sentencepiece onnxruntime numpy`:

```
scripts/fetch_models.sh          # Git Bash on Windows works
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Laptop from source: `pip install -r laptop/requirements.txt`, then `python laptop/app.py`.
