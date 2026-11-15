# RU ⇄ EN Field Translator

Live Russian ↔ English translation **and a full learn-to-read-Russian course**, running **100% offline**
on iPhone, Android (built for cheap 3 GB-RAM phones like the Orbic Joy, works on Huawei) and a Windows laptop.

> **Work through the Learn section and you'll be able to read basic Russian** - signs, menus, short
> sentences and a short story - and know how to say what you read.

**📱 iPhone / any browser: <https://beholdmeme.github.io/FieldTranslator/>** - see [Install on iPhone](#install-on-iphone)

**🤖 Android / 💻 Windows: [download from Releases](https://github.com/BeholdMeme/FieldTranslator/releases/tag/latest)**

## Translator

* **Listen:** pick **Русский → English** or **English → Русский**, tap Listen, and words appear live
  as people talk. Each phrase is translated when the speaker pauses.
* **Type to translate:** type English or Russian - the app sees which alphabet you used and picks the direction.
* **Every message shows both languages** plus how to say the Russian, e.g. *Привет, как дела?* →
  **pri-VYET, kak di-LA?** (CAPITAL syllable = stressed), with 🔊 to hear it in an offline Russian voice.
* **📷 Camera reader:** photograph a sign, menu, label or page → Russian text, pronunciation and English.
  Photos are never saved; the text results are kept in Recent scans.
* **💬 Chats** keeps your conversations, **★ Saved** keeps the phrases you reuse.
* **Spoken numbers as digits:** "сорок пять тысяч рублей" also shows **45 000 рублей**.
* **₽ converter:** rubles ⇄ dollars with your own rate, and the amount said in Russian.
* No internet needed, no accounts, no Google services. The Android app has no internet permission at all.

## Learn Russian - from zero to reading

* **Alphabet:** all 33 letters - the letter's Russian name, how to say it in English letters, the sound it
  makes in words, an example word, and 🔊 for each.
* **Reading course (9 step-by-step lessons):** each lesson goes *letters → syllables → words → sentences → quiz*,
  using only letters you've already learned. Read each word or sentence yourself, then tap to check it.
  The last three lessons teach **how words really sound**, **reading real signs** (ВХОД, ВЫХОД, ОТКРЫТО,
  АПТЕКА, ОТ СЕБЯ / К СЕБЕ...) and finish with **your first short story** in Russian.
* **Grammar, Rookie → Amateur → Pro (15 lessons):** no "is / a / the", questions and "not", he/she/it nouns,
  "I have", left / right / straight (налево vs слева), where / where to / where from, why word endings change,
  plurals, verb endings, ты vs Вы, walking vs riding, finished vs unfinished verbs, past and future,
  1 рубль / 2 рубля / 5 рублей, and polite requests. Each lesson has examples with pronunciation and a quiz.
* **Numbers:** type any number up to 999,999,999,999 and see / hear it in Russian; ordinals (1st, 2nd, 25th...),
  dates and months.
* **Colors**, **52 everyday phrases**, **20 Bible verses** (Synodal + KJV), **military phrases and drone warnings** -
  all with pronunciation, 🔊, flashcards and quizzes with saved best scores.

## Getting the app

Every push to GitHub builds everything in the cloud (GitHub Actions) and publishes it on
the repo's **Releases** page under `latest`:

| File | For |
|---|---|
| `FieldTranslator.apk` | Any Android 7+ phone (Samsung, Xiaomi, Realme, Orbic, Huawei EMUI / HarmonyOS 2–4). Everything above, built in - no download step |
| `FieldTranslator-Windows.zip` | Windows 10/11 laptop. Unzip, run `FieldTranslator.exe` |

**Install on a phone:** copy the APK over (USB cable, SD card, USB-C stick, or download it)
→ tap it → allow "Install unknown apps" for your file manager/browser → Install.
The APK installs two icons: **RU ⇄ EN Translator** (the full app, identical to the iPhone version) and
**Classic Translator** (the original lightweight app, as a backup for very low-memory phones).

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
app/        Android app: WebActivity (full app, serves web/ from the APK) + classic MainActivity
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
9. The finished APK is installed on an Android emulator and runs a self-test: speech recognition,
   translation, voice and learning data, then the classic app is started too.

## Building locally (optional)

Needs internet once, Java 17, Android SDK, Python 3.11 with `sentencepiece onnxruntime numpy`:

```
scripts/fetch_models.sh          # Git Bash on Windows works
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Laptop from source: `pip install -r laptop/requirements.txt`, then `python laptop/app.py`.
