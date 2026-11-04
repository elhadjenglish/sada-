#!/usr/bin/env bash
# Downloads the English keyword-spotting model and converts Sada's phrases into model tokens.
set -euo pipefail
: "${KWS_MODEL:?set KWS_MODEL}"
: "${SHERPA_VERSION:?set SHERPA_VERSION}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/kws"
rm -rf "$WORK"; mkdir -p "$WORK"

echo "== Keyword model: $KWS_MODEL"
curl -fsSL -o "$WORK/model.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${KWS_MODEL}.tar.bz2"
tar xjf "$WORK/model.tar.bz2" -C "$WORK"
M="$WORK/$KWS_MODEL"
ls -la "$M"
for f in tokens.txt bpe.model; do
  [ -f "$M/$f" ] || { echo "::error::$f is missing from $KWS_MODEL"; exit 1; }
done
ls "$M"/*.onnx >/dev/null 2>&1 || { echo "::error::No .onnx model files in $KWS_MODEL"; exit 1; }

echo "== Python tools (pinned, no fallback to other versions)"
python3 -m pip install --quiet --disable-pip-version-check "sherpa-onnx==${SHERPA_VERSION}" -r "$HERE/requirements-ci.txt"
python3 - <<'PY'
import importlib, sys
missing = []
for name in ["sherpa_onnx", "click", "sentencepiece", "pypinyin", "numpy"]:
    try:
        m = importlib.import_module(name)
        print(f"ok  {name} {getattr(m, '__version__', '')}")
    except Exception as e:
        missing.append(f"{name}: {e}")
if missing:
    print("::error::Missing Python modules: " + "; ".join(missing)); sys.exit(1)
PY
# Fails here, with the module name, if the CLI needs anything else.
sherpa-onnx-cli text2token --help >/dev/null

echo "== Converting phrases to tokens"
sherpa-onnx-cli text2token --tokens "$M/tokens.txt" --tokens-type bpe --bpe-model "$M/bpe.model" \
  keywords/keywords-input.txt "$WORK/keywords.txt"
echo "-- keywords.txt (tokens):"; cat "$WORK/keywords.txt"
lines_in=$(grep -cve '^\s*$' keywords/keywords-input.txt)
lines_out=$(grep -cve '^\s*$' "$WORK/keywords.txt" || true)
if [ "$lines_out" != "$lines_in" ]; then
  echo "::error::Expected $lines_in keyword lines, got $lines_out"; exit 1
fi

echo "== Checking the keywords against the model vocabulary (build fails if invalid)"
python3 "$HERE/validate_keywords.py" "$WORK/keywords.txt" "$M/tokens.txt" HEY_SADA,CONTINUE

echo "== Copying model into the app"
A="app/src/main/assets/$KWS_MODEL"
rm -rf app/src/main/assets; mkdir -p "$A"
cp "$M"/*.onnx "$M/tokens.txt" "$A"/
cp "$WORK/keywords.txt" "$A/keywords.txt"   # replaces the model's sample keywords
ls -la "$A"

echo "== Experiment keywords (phrase test only; never used by live detection; equal boost/threshold for a fair comparison)"
E="app/src/main/assets/experiment"
mkdir -p "$E"
if sherpa-onnx-cli text2token --tokens "$M/tokens.txt" --tokens-type bpe --bpe-model "$M/bpe.model" \
     keywords/experiment-input.txt "$WORK/experiment.txt" \
   && python3 "$HERE/validate_keywords.py" "$WORK/experiment.txt" "$M/tokens.txt" HEY_SADA,CONTINUE,KADA; then
  cp "$WORK/experiment.txt" "$E/keywords.txt"
  echo "-- experiment keywords (tokens):"; cat "$E/keywords.txt"
else
  echo "::warning::Experiment keywords could not be prepared; the phrase test will skip the comparison"
  rm -rf "$E"
fi

echo "== Self-test material: the model's own recorded speech samples and sample keywords"
S="app/src/main/assets/selftest"
mkdir -p "$S"
if [ -f "$M/keywords.txt" ] && ls "$M"/test_wavs/*.wav >/dev/null 2>&1; then
  cp "$M/keywords.txt" "$S/keywords.txt"
  cp "$M"/test_wavs/*.wav "$S"/
  echo "-- sample keywords:"; cat "$S/keywords.txt"
  ls -la "$S"
  if python3 "$HERE/reference_selftest.py" "$M" "$S" "$A/keywords.txt"; then
    echo "Reference results written for the phone's self-test."
  else
    rm -f "$S/expected.tsv"
    echo "::warning::Reference run failed; the phone's self-test will list detections without comparing them."
  fi
else
  echo "::warning::$KWS_MODEL has no sample keywords/recordings; the self-test will skip the recorded-speech check."
  ls -R "$M" | head -40
fi
echo "MODEL_DIR=$M" >> "${GITHUB_ENV:-/dev/null}"
echo "KEYWORDS_FILE=$WORK/keywords.txt" >> "${GITHUB_ENV:-/dev/null}"
