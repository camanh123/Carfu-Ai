PHASE_PATCH: 3B.1.3
PHASE_BASE: 58394f64a462bf001f75fa2e4a3ded1eaf18b51d
MODULE: multilingual-asr-sherpa-benchmark-android

MODEL_UNCHANGED: YES
MODEL_HASHES_UNCHANGED: YES
SHERPA_VERSION_UNCHANGED: YES (v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf)
DECODING_UNCHANGED: YES (greedy_search, CPU)
LEGACY_MODE_UNCHANGED: YES (200 ms tick → DecodeGate → full accumulated re-decode)
OPTIMIZED_MODE_IMPLEMENTED: YES
OPTIMIZATION_STRATEGY: first partial after ~600 ms of audio; later partials only when previous decode is complete AND adaptive new audio (last decode duration, clamped 200–800 ms) has arrived; PENDING<=1 newest-snapshot coalesce; no phrase-specific behavior
FINAL_PRIORITY_IMPLEMENTED: YES (STOP discards pending PARTIAL; exactly one FINAL on complete PCM)
RECOGNIZER_PERSISTENT: YES (recreate only if not ready or thread count changed)

INTERACTIVE_DEFAULT: OPTIMIZED, 4 threads
CONTROLLED_BENCHMARK_UNCHANGED: YES (Phase 3B.1.2 identical-PCM 1/2/4 + soak)
MEMORY_SOAK_UNCHANGED: YES

RAW_OUTPUT_UNMODIFIED: YES
HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO
PRODUCTION_TOUCHED: NO

TESTS: 55 passed, 0 failed (testDebugUnitTest; existing 3B.1.1/3B.1.2 + OptimizedInteractiveTest)
BUILD_RESULT: SUCCESS

TEST_ONLY_FINAL: ABSENT
V1_SIGNING: YES (META-INF/CERT.SF + CERT.RSA; apksigner --min-sdk-version 18 reports v1=true v2=true)
V2_SIGNING: YES
TARGET_ABI: arm64-v8a
MIN_SDK: 29
TARGET_SDK: 29

APK: carfu-sherpa-zipformer-vi-benchmark-p3b1-3.apk
APK_SIZE: 57293980
APK_SHA256: 2c8b850187f3c46fe036132ffc86f35259faefc33fc70111932d619cdf190564

GITHUB_RELEASE: https://github.com/camanh123/Carfu-Ai/releases/tag/phase-3b1-3-interactive-optimize
DIRECT_DOWNLOAD_URL: https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-3-interactive-optimize/carfu-sherpa-zipformer-vi-benchmark-p3b1-3.apk

FILES_CHANGED: multilingual-asr-sherpa-benchmark-android/ only
COMMIT: 8932e1c5
PR: https://github.com/camanh123/Carfu-Ai/pull/26

READY_FOR_DEVICE_AB_TEST: YES
DEVICE_OPTIMIZATION_PASS: NOT CLAIMED

NATIVE LIBS IN FINAL APK (unchanged pins):
  libsherpa-onnx-jni.so SHA256=3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
  libonnxruntime.so SHA256=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
