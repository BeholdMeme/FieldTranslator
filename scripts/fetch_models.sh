#!/usr/bin/env bash
# Downloads the offline models and puts them where the apps expect them:
#   app/src/main/assets/asr/{ru,en}       Vosk small speech models   (~45 + 40 MB)
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

# --- speech recognition (Vosk) ---
declare -A VOSK=( [ru]=vosk-model-small-ru-0.22 [en]=vosk-model-small-en-us-0.15 )
for lang in ru en; do
  name="${VOSK[$lang]}"
  dl "https://alphacephei.com/vosk/models/$name.zip" "$CACHE/$name.zip"
  rm -rf "$ASSETS/asr/$lang" "$CACHE/unzip-$lang"
  mkdir -p "$ASSETS/asr" "$CACHE/unzip-$lang"
  unzip -q "$CACHE/$name.zip" -d "$CACHE/unzip-$lang"
  mv "$CACHE/unzip-$lang/$name" "$ASSETS/asr/$lang"
  rm -rf "$CACHE/unzip-$lang"
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

# total size, so the app can show install progress as a percentage
du -sb "$ASSETS/asr" "$ASSETS/mt" | awk '{s+=$1} END {print s}' > "$ASSETS/models_size.txt"
echo "models ready: $(du -sh "$ASSETS" | cut -f1)"
