PHASE_BASE: 58394f64a462bf001f75fa2e4a3ded1eaf18b51d

MODULE: multilingual-asr-sherpa-benchmark-android
APPLICATION_ID: org.stypox.dicio.sherpabenchmark

SHERPA_ONNX_SOURCE: https://github.com/k2-fsa/sherpa-onnx
SHERPA_ONNX_VERSION_OR_COMMIT: v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
SHERPA_ONNX_LICENSE: Apache-2.0

MODEL: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09
MODEL_ARCHITECTURE: offline-transducer-zipformer
MODEL_LANGUAGE: vi
MODEL_QUANTIZATION: official-int8-encoder-joiner

MODEL_FILES: encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt, bpe.model
MODEL_FILE_SIZES:
  encoder.int8.onnx: 27699063 bytes
  decoder.onnx: 5165084 bytes
  joiner.int8.onnx: 1033417 bytes
  tokens.txt: 23238 bytes
  bpe.model: 268106 bytes
  archive sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2: 26442384 bytes
MODEL_FILE_SHA256:
  archive: da8b637947091829d7ee9eda23da2a4ec7caa399233a3f4e34eb719fb2ea6b9b
  encoder.int8.onnx: 8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68
  decoder.onnx: cf2aa385b82c9d5d40cd29c3188af52d0249b3b78f0d4b7eb84ad502d50c7e7f
  joiner.int8.onnx: 7311d2e17b810ecea515d79c71cc4668af8759256a06fa01d27047772320c821
  tokens.txt: ca8171f8bbd516c050b627582f2125c8f5f1f6ed967ab41b0fa9aae2cf61b492
  bpe.model: 002894e7a82d80ffa5e25008ec8c5496159db804005e2103de96b01b4c13d445

NATIVE_LIBS_ARM64:
  libsherpa-onnx-jni.so: 4771760 bytes sha256=3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
  libonnxruntime.so: 22249560 bytes sha256=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
  source: official sherpa-onnx-v1.13.8-android.tar.bz2
  ELF: AArch64

EXECUTION_PROVIDER: CPU

RECOGNITION_ARCHITECTURE: offline-transducer-zipformer
RECOGNITION_MODE: simulated-streaming-offline-transducer
NATIVE_STREAMING_MODEL: NO
SIMULATED_STREAMING: YES
DECODING_METHOD: greedy_search

ANDROID_MIN_SDK: 29
ANDROID_TARGET_SDK: 29
TARGET_ABI: arm64-v8a

THREAD_OPTIONS: 1, 2, 4
DEFAULT_THREADS: 1

RAW_OUTPUT_UNMODIFIED: YES
HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO

TEST_ONLY_FINAL: ABSENT
V1_SIGNING: YES
  (enableV1Signing=true; apksigner --min-sdk-version 18 reports v1=true v2=true;
   jarsigner verified; META-INF/CERT.SF + CERT.RSA present.
   apksigner default/API-29 path uses v2, which is also present.)
V2_SIGNING: YES
ZIPALIGN_VALID: YES (zipalign -c -v 4; extractNativeLibs=true so native .so may be compressed)
ARM64_NATIVE_LIBS_VALID: YES (only lib/arm64-v8a; SHA256 matches pinned official prebuilts)

TESTS: 20 passed, 0 failed (testDebugUnitTest)
BUILD_RESULT: SUCCESS (nested ./gradlew assembleDebug testDebugUnitTest)

APK: carfu-sherpa-zipformer-vi-benchmark.apk
APK_SIZE: 57082699
APK_SHA256: 06bf2f3c84d181c1a8c39b1ba0c72b84fbc7e5836f3db19c7780b6e23a965761

GITHUB_RELEASE: https://github.com/camanh123/Carfu-Ai/releases/tag/phase-3b1-sherpa-zipformer-benchmark
DIRECT_DOWNLOAD_URL: https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-sherpa-zipformer-benchmark/carfu-sherpa-zipformer-vi-benchmark.apk

FILES_CHANGED: multilingual-asr-sherpa-benchmark-android/ only (nested standalone Gradle)
COMMIT: (see git log on cursor/sherpa-zipformer-vi-benchmark-d5e4; publication commit recorded after push)
PR: https://github.com/camanh123/Carfu-Ai/pull/23

PRODUCTION_FILES_TOUCHED: NO
PHASE2A_TOUCHED: NO
PHASE2B_TOUCHED: NO
PHASE3A_TOUCHED: NO

READY_FOR_DEVICE_TEST: YES
DEVICE_INSTALL_PASS: NOT CLAIMED
DEVICE_ASR_PASS: NOT CLAIMED
DEVICE_PERFORMANCE_PASS: NOT CLAIMED

Build command:
  cd multilingual-asr-sherpa-benchmark-android && ./gradlew assembleDebug testDebugUnitTest
