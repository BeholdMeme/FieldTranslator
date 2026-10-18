#!/usr/bin/env bash
# Downloads the offline models and puts them where the apps expect them:
#   app/src/main/assets/asr/en            Vosk small English speech model (~40 MB)
#   app/src/main/assets/asr/ru-zipformer  Zipformer streaming Russian speech model (~28 MB)
#   app/src/main/assets/mt/{ru-en,en-ru}  Opus-MT int8 ONNX models  (~110 MB each)
# Needs internet once (run on a PC or in CI). Re-running skips finished downloads.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${MODEL_CACHE:-$ROOT/.model-cache}"
ASSETS="$ROOT/app/src/main/assets"
PY="${PYTHON:-python3}"
mkdir -p "$CACHE"

dl() {  # url dest
  if [ -s "$2" ]; then return 0; fi
  echo "downloading $1"
  curl -fL --retry 5 --retry-delay 5 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

# --- English speech recognition (Vosk small model) ---
dl "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip" "$CACHE/vosk-model-small-en-us-0.15.zip"
rm -rf "$ASSETS/asr/en" "$ASSETS/asr/ru" "$CACHE/unzip-en"
mkdir -p "$ASSETS/asr" "$CACHE/unzip-en"
unzip -q "$CACHE/vosk-model-small-en-us-0.15.zip" -d "$CACHE/unzip-en"
mv "$CACHE/unzip-en/vosk-model-small-en-us-0.15" "$ASSETS/asr/en"
rm -rf "$CACHE/unzip-en"

# --- Russian speech recognition (alphacep small streaming Zipformer, int8, ~28 MB) ---
RU="https://huggingface.co/alphacep/vosk-model-small-streaming-ru/resolve/main"
mkdir -p "$CACHE/ru-zipformer" "$ASSETS/asr/ru-zipformer"
for f in am-onnx/encoder.int8.onnx am-onnx/decoder.int8.onnx am-onnx/joiner.int8.onnx lang/tokens.txt test.wav; do
  dl "$RU/$f" "$CACHE/ru-zipformer/$(basename "$f")"
done
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  cp "$CACHE/ru-zipformer/$f" "$ASSETS/asr/ru-zipformer/$f"
done

# --- sherpa-onnx runtime (Java API + Android native libs + Linux lib for tests) ---
SO_VER=1.13.8
SO="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SO_VER"
mkdir -p "$ROOT/libs/test"
dl "$SO/sherpa-onnx-jvm-$SO_VER.jar" "$ROOT/libs/sherpa-onnx-jvm.jar"
dl "$SO/sherpa-onnx-native-lib-linux-x64-$SO_VER.jar" "$ROOT/libs/test/sherpa-onnx-native-lib-linux-x64.jar"
dl "$SO/sherpa-onnx-v$SO_VER-android-static-link-onnxruntime.tar.bz2" "$CACHE/sherpa-android.tar.bz2"
rm -rf "$CACHE/sherpa-android" "$ROOT/app/src/main/jniLibs"
mkdir -p "$CACHE/sherpa-android"
tar xjf "$CACHE/sherpa-android.tar.bz2" -C "$CACHE/sherpa-android"
for abi in armeabi-v7a arm64-v8a x86_64; do   # (32-bit x86 phones don't exist in practice)
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$(find "$CACHE/sherpa-android" -path "*/$abi/libsherpa-onnx-jni.so" | head -1)" "$ROOT/app/src/main/jniLibs/$abi/"
done

# --- translation (Opus-MT, int8 ONNX export by Xenova) ---
for pair in ru-en en-ru; do
  d="$CACHE/opus-mt-$pair"
  mkdir -p "$d"
  base="https://huggingface.co/Xenova/opus-mt-$pair/resolve/main"
  for f in config.json vocab.json source.spm target.spm; do dl "$base/$f" "$d/$f"; done
  for f in encoder_model_quantized.onnx decoder_model_merged_quantized.onnx; do dl "$base/onnx/$f" "$d/$f"; done
  rm -rf "$ASSETS/mt/$pair"
  "$PY" "$ROOT/scripts/prepare_mt.py" "$d" "$ASSETS/mt/$pair"
done

# --- Russian voice for "read aloud" (Piper "irina", int8, via sherpa-onnx) ---
dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium-int8.tar.bz2" "$CACHE/tts-irina.tar.bz2"
rm -rf "$CACHE/tts-x" "$ASSETS/tts"
mkdir -p "$CACHE/tts-x" "$ASSETS/tts/ru"
tar xjf "$CACHE/tts-irina.tar.bz2" -C "$CACHE/tts-x"
src="$(find "$CACHE/tts-x" -maxdepth 1 -mindepth 1 -type d | head -1)"
cp "$src/ru_RU-irina-medium.onnx" "$ASSETS/tts/ru/model.onnx"
cp "$src/tokens.txt" "$ASSETS/tts/ru/tokens.txt"
cp "$src/MODEL_CARD" "$ASSETS/tts/ru/MODEL_CARD" 2>/dev/null || true
cp -r "$src/espeak-ng-data" "$ASSETS/tts/ru/espeak-ng-data"
# keep only the Russian (and English, for Latin letters) pronunciation dictionaries
find "$ASSETS/tts/ru/espeak-ng-data" -maxdepth 1 -name "*_dict" ! -name "ru_dict" ! -name "en_dict" -delete
rm -rf "$CACHE/tts-x"

# --- learning data: alphabet, lessons, stress dictionary (OpenRussian.org, CC BY-SA 4.0) ---
OR_SHA=50e210c4803237779cb562bc1abcea529066031c
mkdir -p "$CACHE/openrussian"
for f in nouns verbs adjectives others; do
  dl "https://raw.githubusercontent.com/Badestrand/russian-dictionary/$OR_SHA/$f.csv" "$CACHE/openrussian/$f.csv"
done
rm -rf "$ASSETS/learn"
"$PY" "$ROOT/scripts/build_learning_data.py" "$CACHE/openrussian" "$ASSETS/learn"

# total size, so the app can show install progress as a percentage
du -sb "$ASSETS/asr" "$ASSETS/mt" "$ASSETS/learn" "$ASSETS/tts" | awk '{s+=$1} END {print s}' > "$ASSETS/models_size.txt"
echo "models ready: $(du -sh "$ASSETS" | cut -f1)"
