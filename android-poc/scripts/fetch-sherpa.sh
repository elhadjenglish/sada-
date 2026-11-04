#!/usr/bin/env bash
# Fetches sherpa-onnx's official Kotlin API sources and Android native libraries at a pinned version,
# then checks that the functions Sada Stage 0 calls exist with the expected names.
set -euo pipefail
: "${SHERPA_VERSION:?set SHERPA_VERSION}"
TAG="v${SHERPA_VERSION}"
WORK="${RUNNER_TEMP:-/tmp}/sherpa"
rm -rf "$WORK"; mkdir -p "$WORK"

echo "== Kotlin API sources ($TAG)"
git clone --quiet --depth 1 --branch "$TAG" --filter=blob:none --sparse https://github.com/k2-fsa/sherpa-onnx.git "$WORK/src"
git -C "$WORK/src" sparse-checkout set sherpa-onnx/kotlin-api android/SherpaOnnxKws/app/src/main/java
API="$WORK/src/sherpa-onnx/kotlin-api"
DEMO=$(find "$WORK/src/android/SherpaOnnxKws/app/src/main/java" -type d -path '*com/k2fsa/sherpa/onnx' 2>/dev/null | head -1 || true)
DEST=app/src/main/java/com/k2fsa/sherpa/onnx
rm -rf "$DEST"; mkdir -p "$DEST"
# Preferred: exactly the files sherpa-onnx's own keyword-spotting Android app compiles (minus its screen).
if [ -n "$DEMO" ] && ls "$DEMO"/*.kt >/dev/null 2>&1; then
  echo "Using the file set of sherpa-onnx's keyword-spotting Android demo: $DEMO"
  for f in "$DEMO"/*.kt; do
    case "$(basename "$f")" in *Activity*.kt) continue ;; esac
    cp -L "$f" "$DEST"/ || { echo "::error::could not copy $f (broken link?)"; exit 1; }
  done
elif ls "$API"/*.kt >/dev/null 2>&1; then
  echo "::warning::Keyword-spotting demo not found at $TAG; using the whole Kotlin API folder"
  cp -L "$API"/*.kt "$DEST"/
else
  echo "::error::No sherpa-onnx Kotlin API found at $TAG. Repo root:"; ls "$WORK/src"; exit 1
fi
# Keep only files in the sherpa-onnx library package.
for f in "$DEST"/*.kt; do grep -q '^package com.k2fsa.sherpa.onnx$' "$f" || { echo "skip (other package): $(basename "$f")"; rm -f "$f"; }; done
ls "$DEST"

echo "== API check (names Sada uses)"
fail=0
need() { if ! grep -rqE "$1" "$DEST"; then echo "::error::Not found in sherpa-onnx Kotlin API: $2"; fail=1; else echo "ok: $2"; fi; }
need 'class KeywordSpotter\(' 'class KeywordSpotter'
need 'class KeywordSpotterConfig' 'class KeywordSpotterConfig'
need 'keywordsFile' 'KeywordSpotterConfig.keywordsFile'
need 'featConfig' 'KeywordSpotterConfig.featConfig'
need 'modelConfig' 'KeywordSpotterConfig.modelConfig'
need 'fun getFeatureConfig' 'getFeatureConfig()'
need 'fun getKwsModelConfig' 'getKwsModelConfig()'
need 'fun createStream' 'KeywordSpotter.createStream()'
need 'fun isReady' 'KeywordSpotter.isReady()'
need 'fun decode' 'KeywordSpotter.decode()'
need 'fun getResult' 'KeywordSpotter.getResult()'
need 'fun reset' 'KeywordSpotter.reset()'
need 'fun acceptWaveform' 'OnlineStream.acceptWaveform()'
need 'va[lr] keyword: String' 'KeywordSpotterResult.keyword'
# Sada uses getKwsModelConfig(type = 1) and expects it to be the English GigaSpeech model.
if ! awk '/fun getKwsModelConfig/{f=1} f&&/1 ->/{g=1} g&&/gigaspeech/{print; exit 0} END{}' "$DEST"/*.kt | grep -q gigaspeech; then
  echo "::error::getKwsModelConfig(type = 1) is not the GigaSpeech English model in $TAG. Its body:"
  awk '/fun getKwsModelConfig/{f=1} f{print} f&&/^}/{exit}' "$DEST"/*.kt
  fail=1
else
  echo "ok: getKwsModelConfig(type = 1) is GigaSpeech (English)"
fi
if grep -qE 'fun (release|delete)\(' "$DEST"/KeywordSpotter.kt "$DEST"/OnlineStream.kt 2>/dev/null; then
  echo "ok: native resources can be released explicitly (release()/delete())"
else
  echo "::warning::No public release()/delete() on KeywordSpotter/OnlineStream in $TAG: Sada bounds native memory by reusing them"
fi
echo "-- KeywordSpotterConfig as defined in $TAG:"
awk '/class KeywordSpotterConfig/{f=1} f{print} f&&/^\)/{exit}' "$DEST"/*.kt || true
[ "$fail" = 0 ] || exit 1

echo "== Android native libraries ($TAG)"
ASSETS=$(gh release view "$TAG" --repo k2-fsa/sherpa-onnx --json assets -q '.assets[].name')
echo "$ASSETS" | grep -iE 'android|\.aar$' || true
PICK=$(echo "$ASSETS" | grep -E "^sherpa-onnx-v?${SHERPA_VERSION}-android\.tar\.bz2$" | head -1 || true)
[ -n "$PICK" ] || PICK=$(echo "$ASSETS" | grep -E 'android.*\.tar\.bz2$' | grep -viE 'rknn|qnn|ascend|static|tts|apk' | head -1 || true)
[ -n "$PICK" ] || PICK=$(echo "$ASSETS" | grep -E '\.aar$' | head -1 || true)
if [ -z "$PICK" ]; then echo "::error::No Android native library asset found in release $TAG"; exit 1; fi
echo "Using asset: $PICK"
gh release download "$TAG" --repo k2-fsa/sherpa-onnx --pattern "$PICK" --dir "$WORK"
mkdir -p "$WORK/native"
case "$PICK" in
  *.tar.bz2) tar xjf "$WORK/$PICK" -C "$WORK/native" ;;
  *.aar) unzip -q "$WORK/$PICK" -d "$WORK/native" ;;
esac
JNI=$(find "$WORK/native" -type f -name 'libsherpa-onnx-jni.so' -path '*arm64-v8a*' | head -1)
if [ -z "$JNI" ]; then echo "::error::libsherpa-onnx-jni.so (arm64-v8a) not found in $PICK"; find "$WORK/native" -name '*.so' | head -20; exit 1; fi
OUT=app/src/main/jniLibs/arm64-v8a
rm -rf app/src/main/jniLibs; mkdir -p "$OUT"
cp "$(dirname "$JNI")"/*.so "$OUT"/
ls -la "$OUT"
