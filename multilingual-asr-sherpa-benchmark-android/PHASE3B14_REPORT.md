PHASE_PATCH: 3B.1.4
PHASE_BASE: 58394f64a462bf001f75fa2e4a3ded1eaf18b51d
MODULE: multilingual-asr-sherpa-benchmark-android

MODEL_UNCHANGED: YES
MODEL_HASHES_UNCHANGED: YES
SHERPA_VERSION_UNCHANGED: YES (v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf)
DECODING_UNCHANGED: YES (greedy_search, CPU)
LEGACY_UNCHANGED: YES
FAILED_3B1_3_MODE_PRESERVED: YES (OPTIMIZED_3B1_3 = DEVICE FAIL BASELINE; scheduler not retuned)
BOUNDED_MODE_IMPLEMENTED: YES
PARTIAL_TRIGGER_POLICY: PARTIAL #1 at >=700 ms captured audio; PARTIAL #2+ only after >=900 ms NEW audio since the previous executed partial snapshot; timer poll cannot start a decode by itself; skip while native decode is active (no busy retry storm)
MAX_PARTIAL_DECODES: 5
STOP_FINAL_PRIORITY: YES (drop pending partials; exactly one FINAL on complete PCM)
RECOGNIZER_PERSISTENT: YES

INTERACTIVE_DEFAULT: BOUNDED, 4 threads
CONTROLLED_BENCHMARK_UNCHANGED: YES
MEMORY_SOAK_UNCHANGED: YES

RAW_OUTPUT_UNMODIFIED: YES
HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO
PRODUCTION_TOUCHED: NO

TESTS: 68 passed, 0 failed (testDebugUnitTest)
BUILD_RESULT: SUCCESS

TEST_ONLY_FINAL: ABSENT
V1_SIGNING: YES (META-INF/CERT.SF + CERT.RSA; apksigner --min-sdk-version 18 reports v1=true v2=true)
V2_SIGNING: YES
TARGET_ABI: arm64-v8a
MIN_SDK: 29
TARGET_SDK: 29

APK: carfu-sherpa-zipformer-vi-benchmark-p3b1-4.apk
APK_SIZE: 57313438
APK_SHA256: b3f1671c224cbc9f3bac9909b3e5a8eec2edc429a61470db8f04403c10a116cd

GITHUB_RELEASE: https://github.com/camanh123/Carfu-Ai/releases/tag/phase-3b1-4-bounded-partial
DIRECT_DOWNLOAD_URL: https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-4-bounded-partial/carfu-sherpa-zipformer-vi-benchmark-p3b1-4.apk

FILES_CHANGED: multilingual-asr-sherpa-benchmark-android/ only
COMMIT: c10e1f98
PR: https://github.com/camanh123/Carfu-Ai/pull/27

READY_FOR_DEVICE_AB_TEST: YES
DEVICE_OPTIMIZATION_PASS: NOT CLAIMED

NATIVE LIBS IN FINAL APK (unchanged pins):
  libsherpa-onnx-jni.so SHA256=3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
  libonnxruntime.so SHA256=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
