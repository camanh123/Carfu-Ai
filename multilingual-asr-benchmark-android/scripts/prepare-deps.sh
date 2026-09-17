#!/usr/bin/env bash
# Download pinned whisper.cpp sources and the official multilingual tiny model.
# Model binaries are NOT committed to git.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/deps.lock"

THIRD_PARTY="$ROOT/third_party/whisper.cpp"
MODELS_DIR="$ROOT/models"
MODEL_PATH="$MODELS_DIR/$MODEL_NAME"

mkdir -p "$ROOT/third_party" "$MODELS_DIR"

clone_whisper() {
  if [[ -d "$THIRD_PARTY/.git" ]]; then
    local have
    have="$(git -C "$THIRD_PARTY" rev-parse HEAD)"
    if [[ "$have" == "$WHISPER_CPP_COMMIT" ]]; then
      echo "whisper.cpp already at $WHISPER_CPP_COMMIT"
      return 0
    fi
    echo "whisper.cpp HEAD $have != pinned $WHISPER_CPP_COMMIT; recloning"
    rm -rf "$THIRD_PARTY"
  fi
  git clone --depth 1 --branch "$WHISPER_CPP_TAG" "$WHISPER_CPP_REPO" "$THIRD_PARTY"
  local got
  got="$(git -C "$THIRD_PARTY" rev-parse HEAD)"
  if [[ "$got" != "$WHISPER_CPP_COMMIT" ]]; then
    echo "ERROR: cloned commit $got does not match pinned $WHISPER_CPP_COMMIT" >&2
    exit 1
  fi
  echo "whisper.cpp cloned $WHISPER_CPP_TAG ($got)"
}

download_model() {
  if [[ -f "$MODEL_PATH" ]]; then
    local have
    have="$(sha256sum "$MODEL_PATH" | awk '{print $1}')"
    if [[ "$have" == "$MODEL_SHA256" ]]; then
      echo "model already present and SHA256 matches"
      return 0
    fi
    echo "model SHA256 $have != pinned $MODEL_SHA256; re-downloading"
    rm -f "$MODEL_PATH"
  fi
  local tmp="$MODEL_PATH.partial"
  curl -L --fail --retry 5 --retry-delay 3 -o "$tmp" "$MODEL_SOURCE_URL"
  local got
  got="$(sha256sum "$tmp" | awk '{print $1}')"
  if [[ "$got" != "$MODEL_SHA256" ]]; then
    echo "ERROR: model SHA256 $got does not match pinned $MODEL_SHA256" >&2
    rm -f "$tmp"
    exit 1
  fi
  mv "$tmp" "$MODEL_PATH"
  echo "model downloaded $MODEL_NAME ($got)"
}

clone_whisper
download_model

echo "prepare-deps: ok"
echo "  WHISPER_CPP_TAG=$WHISPER_CPP_TAG"
echo "  WHISPER_CPP_COMMIT=$WHISPER_CPP_COMMIT"
echo "  MODEL=$MODEL_PATH"
echo "  MODEL_SHA256=$MODEL_SHA256"
echo "  MODEL_BYTES=$(wc -c < "$MODEL_PATH" | tr -d ' ')"
