# RU ⇄ EN Field Translator

Live Russian ↔ English speech-to-text translation that runs **fully offline** on cheap
Android phones (built for 3 GB-RAM phones like the Orbic Joy) and on a Windows laptop.

* Big toggle: **Russian → English** or **English → Russian**
* Press **LISTEN**: words appear live as they're spoken. Each phrase is translated when
  the speaker pauses, and a rough live translation shows while they're still talking.
* No internet permission, no Google services, no accounts. Works on Huawei.
* `A−` / `A+` change text size (handy for showing the screen to the other person),
  long-press a translation to copy it.

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

**Not supported:** iPhone (Apple doesn't allow sideloading) and the newest Huawei phones on
HarmonyOS NEXT / 5+ (they can't run Android apps). Use the laptop version there.

## How it works

| Part | Tech | Size |
|---|---|---|
| Speech → text (Russian) | [alphacep small streaming Zipformer](https://huggingface.co/alphacep/vosk-model-small-streaming-ru) (int8) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | ~28 MB |
| Speech → text (English) | [Vosk](https://alphacephei.com/vosk/) small model `en-us-0.15`, streaming | 40 MB |
| Text → text | [Opus-MT](https://github.com/Helsinki-NLP/Opus-MT) `ru-en` / `en-ru`, int8 ONNX, ONNX Runtime | ~110 MB each |
| App | Plain Java Android app (no AndroidX), Python/Tk on the laptop | |

Only the selected direction's models are loaded (~600–800 MB RAM peak), so 3 GB phones are fine.
Translation is greedy decoding with a KV cache; typically 0.3–1.5 s per phrase on a budget phone.

## Accuracy notes

* Small models: good on clear speech, weaker with background noise, heavy accents,
  or fast talkers. A cheap wired headset mic makes a big difference.
* Translation quality is "understandable and useful", not Google-level.

## Repo layout

```
app/        Android app (MainActivity, ModelInstaller)
core/       Pure-Java tokenizer + translator, shared by the app; unit tests
laptop/     Windows/Mac/Linux app (app.py) + Python mirror of the translator
scripts/    fetch_models.sh (downloads models), prepare_mt.py, make_reference.py
.github/    CI: tests, builds the APK + Windows app, publishes the release
```

## Quality checks in CI (a build fails if any of these fail)

1. Python tokenizer == HuggingFace `MarianTokenizer` on test phrases.
2. Field phrases translate sensibly in both directions (e.g. "где находится вокзал" → contains "station").
3. The Java (phone) code reproduces the Python results **token for token** on the real models.
4. The tokenizer matches SentencePiece on 800+ tricky strings.
5. The APK contains the native libraries for every phone CPU type and all models.
7. Russian speech recognition is run on a real Russian recording in Python and in the phone's Java code; both must produce the same text.
6. The built Windows app runs a self-test (loads models, translates both directions).

## Building locally (optional)

Needs internet once, Java 17, Android SDK, Python 3.11 with `sentencepiece onnxruntime numpy`:

```
scripts/fetch_models.sh          # Git Bash on Windows works
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Laptop from source: `pip install -r laptop/requirements.txt`, then `python laptop/app.py`.
