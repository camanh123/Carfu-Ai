PHASE_PATCH: 3B.1.2
PHASE_BASE: 58394f64a462bf001f75fa2e4a3ded1eaf18b51d
MODULE: multilingual-asr-sherpa-benchmark-android

DEVICE_EVIDENCE_ACCEPTED: YES (3B.1.1: 11 sessions completed; native heap rose then appeared to plateau. Not treated as a proven leak.)

MODEL_UNCHANGED: YES
MODEL_HASHES_UNCHANGED: YES
SHERPA_VERSION_UNCHANGED: YES
DECODING_UNCHANGED: YES

INTERACTIVE_MODE_UNCHANGED: YES
FIXED_AUDIO_BENCHMARK_MODE: offline-final-only

REFERENCE_AUDIO_IMPLEMENTED: YES (app-private PCM16; SHA256; DELETE REFERENCE; not committed; not uploaded)

THREAD_BENCHMARK: YES (sequential 1 then 2 then 4; identical PCM)
THREAD_OPTIONS: 1 / 2 / 4
WARMUP_RUNS: 1
MEASURED_RUNS: 3

MEMORY_SOAK: YES (sequential; default threads=1)
SOAK_OPTIONS: 10 / 30 / 50
DEFAULT_SOAK_ITERATIONS: 30

RESOURCE_ACCOUNTING: YES (RECOGNIZER_CREATED/RELEASED, STREAM_CREATED/RELEASED, ACTIVE_STREAMS, ACTIVE_DECODE, PENDING_DECODE)
PERSISTENT_BENCHMARK_JOURNAL: YES (abnormal leftover in-progress + last report)

HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO

TESTS: 43 passed, 0 failed (testDebugUnitTest; existing 3B.1.1 tests + ControlledBenchmarkLogicTest)
BUILD_RESULT: SUCCESS

TEST_ONLY_FINAL: ABSENT
V1_SIGNING: YES (META-INF/CERT.SF + CERT.RSA; apksigner --min-sdk-version 18 reports v1=true v2=true)
V2_SIGNING: YES
TARGET_ABI: arm64-v8a

APK: carfu-sherpa-zipformer-vi-benchmark-p3b1-2.apk
APK_SIZE: 57463759
APK_SHA256: 3e0d0d89c01b9505917655e4134a31adef13caa6239108e670180baab804ccc4

GITHUB_RELEASE: https://github.com/camanh123/Carfu-Ai/releases/tag/phase-3b1-2-sherpa-performance-memory
DIRECT_DOWNLOAD_URL: https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-2-sherpa-performance-memory/carfu-sherpa-zipformer-vi-benchmark-p3b1-2.apk

FILES_CHANGED: multilingual-asr-sherpa-benchmark-android/ only
COMMIT: addd30c1502590aada1ccd2e5dc016c5ba5d25a2
PR: https://github.com/camanh123/Carfu-Ai/pull/25

PRODUCTION_FILES_TOUCHED: NO
PHASE2A_TOUCHED: NO
PHASE2B_TOUCHED: NO
PHASE3A_TOUCHED: NO

READY_FOR_DEVICE_BENCHMARK: YES
DEVICE_PERFORMANCE_PASS: NOT CLAIMED
DEVICE_MEMORY_PASS: NOT CLAIMED

MEMORY_TREND_HEURISTIC: Conservative native-heap deltas only. PLATEAU_LIKE if late deltas are small and no tracked delta is large. CONTINUING_GROWTH if every tracked delta is large (need >=3). Otherwise INCONCLUSIVE. Never MEMORY_LEAK:YES. Does not claim ONNX allocator/cache.

THERMAL: PowerManager.getCurrentThermalStatus on API 29; otherwise unavailable. Thread order 1->2->4 may include thermal bias.

NATIVE LIBS IN FINAL APK (unchanged pins):
  libsherpa-onnx-jni.so SHA256=3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
  libonnxruntime.so SHA256=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
