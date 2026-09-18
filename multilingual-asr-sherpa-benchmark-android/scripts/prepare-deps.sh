#!/usr/bin/env bash
# Download pinned sherpa-onnx official Android prebuilts, Kotlin JNI API, and
# the official Zipformer VI INT8 model. Large binaries are NOT committed to git.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/deps.lock"

MODELS_DIR="$ROOT/models"
MODEL_DIR="$MODELS_DIR/$MODEL_NAME"
ARCHIVE_PATH="$MODELS_DIR/${MODEL_NAME}.tar.bz2"
JNI_DIR="$ROOT/third_party/sherpa-onnx-android/jniLibs/arm64-v8a"
KOTLIN_DIR="$ROOT/third_party/sherpa-onnx-kotlin-api"
ANDROID_TAR="$ROOT/third_party/sherpa-onnx-android/${MODEL_NAME}-unused"
ANDROID_TAR_PATH="$ROOT/third_party/sherpa-onnx-android/sherpa-onnx-${SHERPA_ONNX_TAG}-android.tar.bz2"

mkdir -p "$MODELS_DIR" "$JNI_DIR" "$KOTLIN_DIR" "$ROOT/third_party/sherpa-onnx-android"

sha256_of() {
  sha256sum "$1" | awk '{print $1}'
}

assert_file() {
  local path="$1"
  local expected_sha="$2"
  local expected_bytes="$3"
  local label="$4"
  if [[ ! -f "$path" ]]; then
    echo "ERROR: missing $label: $path" >&2
    exit 1
  fi
  local got_bytes got_sha
  got_bytes="$(wc -c < "$path" | tr -d ' ')"
  got_sha="$(sha256_of "$path")"
  if [[ "$got_bytes" != "$expected_bytes" ]]; then
    echo "ERROR: $label size $got_bytes != pinned $expected_bytes ($path)" >&2
    exit 1
  fi
  if [[ "$got_sha" != "$expected_sha" ]]; then
    echo "ERROR: $label SHA256 $got_sha != pinned $expected_sha ($path)" >&2
    exit 1
  fi
  echo "ok $label bytes=$got_bytes sha256=$got_sha"
}

download() {
  local url="$1"
  local dest="$2"
  local tmp="${dest}.partial"
  curl -L --fail --retry 5 --retry-delay 3 -o "$tmp" "$url"
  mv "$tmp" "$dest"
}

download_model_archive() {
  if [[ -f "$ARCHIVE_PATH" ]]; then
    local have
    have="$(sha256_of "$ARCHIVE_PATH")"
    if [[ "$have" == "$MODEL_ARCHIVE_SHA256" ]]; then
      echo "model archive already present and SHA256 matches"
      return 0
    fi
    echo "model archive SHA256 $have != pinned $MODEL_ARCHIVE_SHA256; re-downloading"
    rm -f "$ARCHIVE_PATH"
  fi
  download "$MODEL_ARCHIVE_URL" "$ARCHIVE_PATH"
  assert_file "$ARCHIVE_PATH" "$MODEL_ARCHIVE_SHA256" "$MODEL_ARCHIVE_BYTES" "model-archive"
}

extract_model() {
  local encoder="$MODEL_DIR/$MODEL_ENCODER"
  if [[ -f "$encoder" ]]; then
    local have
    have="$(sha256_of "$encoder")"
    if [[ "$have" == "$MODEL_ENCODER_SHA256" ]]; then
      echo "model files already extracted"
      assert_file "$MODEL_DIR/$MODEL_ENCODER" "$MODEL_ENCODER_SHA256" "$MODEL_ENCODER_BYTES" "$MODEL_ENCODER"
      assert_file "$MODEL_DIR/$MODEL_DECODER" "$MODEL_DECODER_SHA256" "$MODEL_DECODER_BYTES" "$MODEL_DECODER"
      assert_file "$MODEL_DIR/$MODEL_JOINER" "$MODEL_JOINER_SHA256" "$MODEL_JOINER_BYTES" "$MODEL_JOINER"
      assert_file "$MODEL_DIR/$MODEL_TOKENS" "$MODEL_TOKENS_SHA256" "$MODEL_TOKENS_BYTES" "$MODEL_TOKENS"
      assert_file "$MODEL_DIR/$MODEL_BPE" "$MODEL_BPE_SHA256" "$MODEL_BPE_BYTES" "$MODEL_BPE"
      return 0
    fi
  fi
  rm -rf "$MODEL_DIR"
  tar -xjf "$ARCHIVE_PATH" -C "$MODELS_DIR"
  assert_file "$MODEL_DIR/$MODEL_ENCODER" "$MODEL_ENCODER_SHA256" "$MODEL_ENCODER_BYTES" "$MODEL_ENCODER"
  assert_file "$MODEL_DIR/$MODEL_DECODER" "$MODEL_DECODER_SHA256" "$MODEL_DECODER_BYTES" "$MODEL_DECODER"
  assert_file "$MODEL_DIR/$MODEL_JOINER" "$MODEL_JOINER_SHA256" "$MODEL_JOINER_BYTES" "$MODEL_JOINER"
  assert_file "$MODEL_DIR/$MODEL_TOKENS" "$MODEL_TOKENS_SHA256" "$MODEL_TOKENS_BYTES" "$MODEL_TOKENS"
  assert_file "$MODEL_DIR/$MODEL_BPE" "$MODEL_BPE_SHA256" "$MODEL_BPE_BYTES" "$MODEL_BPE"
}

download_android_prebuilts() {
  mkdir -p "$(dirname "$ANDROID_TAR_PATH")"
  if [[ -f "$ANDROID_TAR_PATH" ]]; then
    local have
    have="$(sha256_of "$ANDROID_TAR_PATH")"
    if [[ "$have" == "$SHERPA_ONNX_ANDROID_TAR_SHA256" ]]; then
      echo "android tar already present and SHA256 matches"
    else
      echo "android tar SHA256 $have != pinned; re-downloading"
      rm -f "$ANDROID_TAR_PATH"
      download "$SHERPA_ONNX_ANDROID_TAR_URL" "$ANDROID_TAR_PATH"
    fi
  else
    download "$SHERPA_ONNX_ANDROID_TAR_URL" "$ANDROID_TAR_PATH"
  fi
  assert_file "$ANDROID_TAR_PATH" "$SHERPA_ONNX_ANDROID_TAR_SHA256" "$SHERPA_ONNX_ANDROID_TAR_BYTES" "sherpa-android-tar"

  local jni="$JNI_DIR/$LIB_JNI_NAME"
  local ort="$JNI_DIR/$LIB_ORT_NAME"
  if [[ -f "$jni" && -f "$ort" ]] && [[ "$(sha256_of "$jni")" == "$LIB_JNI_SHA256" ]] && [[ "$(sha256_of "$ort")" == "$LIB_ORT_SHA256" ]]; then
    echo "arm64-v8a native libs already present"
    assert_file "$jni" "$LIB_JNI_SHA256" "$LIB_JNI_BYTES" "$LIB_JNI_NAME"
    assert_file "$ort" "$LIB_ORT_SHA256" "$LIB_ORT_BYTES" "$LIB_ORT_NAME"
    return 0
  fi

  local tmp
  tmp="$(mktemp -d)"
  tar -xjf "$ANDROID_TAR_PATH" -C "$tmp"
  mkdir -p "$JNI_DIR"
  # Official prebuilt layout: ./jniLibs/arm64-v8a/*.so
  cp -f "$tmp"/jniLibs/arm64-v8a/"$LIB_JNI_NAME" "$JNI_DIR/"
  cp -f "$tmp"/jniLibs/arm64-v8a/"$LIB_ORT_NAME" "$JNI_DIR/"
  rm -rf "$tmp"
  # Intentionally do NOT copy armeabi-v7a / x86 / x86_64 / c-api / cxx-api / rknn.
  assert_file "$JNI_DIR/$LIB_JNI_NAME" "$LIB_JNI_SHA256" "$LIB_JNI_BYTES" "$LIB_JNI_NAME"
  assert_file "$JNI_DIR/$LIB_ORT_NAME" "$LIB_ORT_SHA256" "$LIB_ORT_BYTES" "$LIB_ORT_NAME"
}

download_kotlin_api() {
  fetch_kt() {
    local name="$1"
    local sha="$2"
    local bytes="$3"
    local dest="$KOTLIN_DIR/$name"
    if [[ -f "$dest" ]] && [[ "$(sha256_of "$dest")" == "$sha" ]]; then
      assert_file "$dest" "$sha" "$bytes" "$name"
      return 0
    fi
    download "$KOTLIN_API_BASE_URL/$name" "$dest"
    assert_file "$dest" "$sha" "$bytes" "$name"
  }
  fetch_kt "OfflineRecognizer.kt" "$KOTLIN_OFFLINE_RECOGNIZER_SHA256" "$KOTLIN_OFFLINE_RECOGNIZER_BYTES"
  fetch_kt "OfflineStream.kt" "$KOTLIN_OFFLINE_STREAM_SHA256" "$KOTLIN_OFFLINE_STREAM_BYTES"
  fetch_kt "FeatureConfig.kt" "$KOTLIN_FEATURE_CONFIG_SHA256" "$KOTLIN_FEATURE_CONFIG_BYTES"
  fetch_kt "HomophoneReplacerConfig.kt" "$KOTLIN_HR_CONFIG_SHA256" "$KOTLIN_HR_CONFIG_BYTES"
  fetch_kt "QnnConfig.kt" "$KOTLIN_QNN_CONFIG_SHA256" "$KOTLIN_QNN_CONFIG_BYTES"
}

download_model_archive
extract_model
download_android_prebuilts
download_kotlin_api

echo "prepare-deps: ok"
echo "  SHERPA_ONNX_TAG=$SHERPA_ONNX_TAG"
echo "  SHERPA_ONNX_COMMIT=$SHERPA_ONNX_COMMIT"
echo "  MODEL=$MODEL_DIR"
echo "  JNI=$JNI_DIR"
echo "  KOTLIN_API=$KOTLIN_DIR"
