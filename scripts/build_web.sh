#!/usr/bin/env bash
# Assembles the web app (iPhone / any browser) into a static site folder.
#   scripts/build_web.sh ASSETS_DIR SHERPA_WASM_DIR ORT_DIST_DIR OUT_DIR
# ASSETS_DIR       app/src/main/assets from fetch_models.sh (Russian ASR, MT, voice, learning data)
# SHERPA_WASM_DIR  folder with sherpa-onnx-wasm-web.{js,wasm} + sherpa-onnx-{asr,tts}.js
# ORT_DIST_DIR     onnxruntime-web 1.20.1 dist/ folder
set -euo pipefail
trap 'echo "::error::build_web.sh line $LINENO failed: $BASH_COMMAND"' ERR
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$1"; SHERPA="$2"; ORT="$3"; OUT="$4"
CACHE="${MODEL_CACHE:-$ROOT/.model-cache}"
PY="${PYTHON:-python3}"
mkdir -p "$CACHE"

rm -rf "$OUT"
mkdir -p "$OUT/vendor" "$OUT/models"
cp -r "$ROOT/web/." "$OUT/"
rm -rf "$OUT/test"

# --- engines
cp "$SHERPA/sherpa-onnx-wasm-web.js" "$SHERPA/sherpa-onnx-wasm-web.wasm" "$SHERPA/sherpa-onnx-asr.js" "$OUT/vendor/"
# both wrappers define a global freeConfig(); give the voice one its own name
sed -e 's/\bfreeConfig(/freeTtsConfig(/g' "$SHERPA/sherpa-onnx-tts.js" > "$OUT/vendor/sherpa-onnx-tts.js"
cp "$ORT/ort.wasm.min.js" "$ORT/ort-wasm-simd-threaded.wasm" "$ORT/ort-wasm-simd-threaded.mjs" "$OUT/vendor/"

# --- icons
"$PY" "$ROOT/scripts/make_icons.py" "$OUT/icons"

# --- models (same files as the phone app, plus a small streaming English model:
#     Vosk has no browser build, so the web app uses sherpa's 20M English Zipformer)
M="$OUT/models"
mkdir -p "$M/asr" "$M/mt" "$M/tts"
cp -r "$ASSETS/asr/ru-zipformer" "$M/asr/ru-zipformer"
cp -r "$ASSETS/mt/ru-en" "$ASSETS/mt/en-ru" "$M/mt/"
cp -r "$ASSETS/tts/ru" "$M/tts/ru"
cp -r "$ASSETS/learn" "$M/learn"

EN=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
if [ ! -s "$CACHE/$EN.tar.bz2" ]; then
  curl -fL --retry 5 -o "$CACHE/$EN.tar.bz2.part" "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$EN.tar.bz2"
  mv "$CACHE/$EN.tar.bz2.part" "$CACHE/$EN.tar.bz2"
fi
rm -rf "$CACHE/en-x"; mkdir -p "$CACHE/en-x" "$M/asr/en-zipformer"
tar xjf "$CACHE/$EN.tar.bz2" -C "$CACHE/en-x"
pick() {  # pattern-prefix -> int8 file if present, else float
  local f
  f="$(find "$CACHE/en-x" -name "$1*.int8.onnx" | head -1)"
  [ -n "$f" ] || f="$(find "$CACHE/en-x" -name "$1*.onnx" | head -1)"
  echo "$f"
}
cp "$(pick encoder)" "$M/asr/en-zipformer/encoder.int8.onnx"
cp "$(pick decoder)" "$M/asr/en-zipformer/decoder.int8.onnx"
cp "$(pick joiner)" "$M/asr/en-zipformer/joiner.int8.onnx"
cp "$(find "$CACHE/en-x" -name tokens.txt | head -1)" "$M/asr/en-zipformer/tokens.txt"
mkdir -p "$OUT/test"
cp "$(find "$CACHE/en-x" -name '*.wav' | sort | head -1)" "$OUT/test/en.wav"
rm -rf "$CACHE/en-x"

"$PY" "$ROOT/scripts/make_web_manifest.py" "$M"

# the service worker refreshes the app shell when this changes
VER="$(date -u +%Y%m%d%H%M)-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo dev)"
sed -i "s/__BUILD_VERSION__/$VER/" "$OUT/sw.js"
touch "$OUT/.nojekyll"
echo "web app built: $(du -sh "$OUT" | cut -f1) ($VER)"
