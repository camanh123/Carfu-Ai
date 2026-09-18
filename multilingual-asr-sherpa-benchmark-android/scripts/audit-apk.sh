#!/usr/bin/env bash
# Audit the EXACT final diagnostic APK (not Gradle source).
set -euo pipefail

APK="${1:?usage: audit-apk.sh <apk>}"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n1)"
AAPT="$BT/aapt"
AAPT2="$BT/aapt2"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign"

echo "=== PHASE 3B.1 APK AUDIT ==="
echo "APK=$APK"
ls -l "$APK"
echo "APK_SIZE=$(wc -c < "$APK" | tr -d ' ')"
echo "APK_SHA256=$(sha256sum "$APK" | awk '{print $1}')"
echo "BUILD_TOOLS=$BT"

echo
echo "=== badging ==="
"$AAPT" dump badging "$APK"

echo
echo "=== merged/final manifest (aapt xmltree) ==="
"$AAPT" dump xmltree "$APK" AndroidManifest.xml

echo
echo "=== testOnly ==="
if "$AAPT" dump xmltree "$APK" AndroidManifest.xml | grep -E 'testOnly|TEST_ONLY'; then
  echo "TEST_ONLY_FINAL: PRESENT (FAIL if true)"
else
  echo "TEST_ONLY_FINAL: ABSENT"
fi

echo
echo "=== signing ==="
"$APKSIGNER" verify --verbose --print-certs "$APK" || true
echo "--- min-sdk 18 (v1 expected) ---"
"$APKSIGNER" verify --verbose --min-sdk-version 18 "$APK" || true
echo "--- min-sdk 29 ---"
"$APKSIGNER" verify --verbose --min-sdk-version 29 "$APK" || true

echo
echo "=== zipalign ==="
"$ZIPALIGN" -c -v 4 "$APK" || true

echo
echo "=== native libs ==="
unzip -l "$APK" | awk '/lib\// {print}'
echo
for so in lib/arm64-v8a/libsherpa-onnx-jni.so lib/arm64-v8a/libonnxruntime.so; do
  tmp="$(mktemp)"
  unzip -p "$APK" "$so" > "$tmp"
  echo "--- $so ---"
  file "$tmp"
  readelf -h "$tmp" | awk '/Class:|Machine:|Data:/'
  echo "SHA256=$(sha256sum "$tmp" | awk '{print $1}')"
  rm -f "$tmp"
done

echo
echo "=== unexpected ABIs ==="
if unzip -l "$APK" | grep -E 'lib/(armeabi|x86)'; then
  echo "UNEXPECTED_ABI: FAIL"
else
  echo "UNEXPECTED_ABI: none"
fi

echo
echo "=== audit done ==="
