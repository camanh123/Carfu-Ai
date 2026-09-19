PHASE_PATCH: 3B.1.1
DEVICE_EVIDENCE_ACCEPTED: YES
SOURCE_ROOT_CAUSE_FOUND: YES
SOURCE_ROOT_CAUSE: UI decode ticker enqueued a full-utterance OfflineRecognizer decode every 200ms with no backpressure (`MainActivity.startTickers` → `worker.execute { maybePartial() }`). Each `maybePartial` copied all PCM and `SherpaOfflineBackend.decode` created a new OfflineStream, `acceptWaveform(ALL samples)`, decode, getResult, release. Decode time grew with duration so the single-thread executor queue and native work exploded (~36s device sessions). `onDestroy` used `shutdownNow()` then `backend.release()` during decode (use-after-free / SIGSEGV risk).

AUDIO_CHUNK_DURATION: AudioRecord minBuffer*4 (device-dependent, Pcm16kMonoRecorder); UI/decode tick 200ms (HardFreeze.SIMULATED_STREAMING_CHUNK_MS). 200ms is NOT the capture chunk.
AUDIO_BUFFER_GROWTH: YES until STOP or 15s auto-stop (PCM ByteArrayOutputStream in Pcm16kMonoRecorder)
DECODE_QUEUE_GROWTH: YES in 3B.1; NO after DecodeGate (pending<=1, DecodeGate.kt)
NATIVE_OBJECT_GROWTH: Stream recreated per decode then released in SherpaOfflineBackend.decode finally; recognizer reused
RESULT_HISTORY_GROWTH: YES in 3B.1; bounded to 32 partial events in 3B.1.1 (BenchmarkLimits.MAX_PARTIAL_EVENTS)
COROUTINE_OR_THREAD_GROWTH: NO (fixed mic thread + single worker + one peak-sampler thread)
OVERLAPPING_DECODE: NO native overlap on the single worker; 3B.1 queued many serial full-utterance decodes; 3B.1.1 DecodeGate keeps at most one in-flight + one coalesced pending
RECOGNIZER_RECREATION: NO per chunk (one OfflineRecognizer per selected thread count)
STREAM_RECREATION: YES per decode (createStream / acceptWaveform / decode / getResult / releaseStream)

BACKPRESSURE_STRATEGY: DecodeGate coalesce (max 1 in-flight + 1 pending); mic requestStop() immediately on STOP/auto-stop; 15s auto-stop bound. Simulated streaming still re-decodes ALL accumulated samples (unchanged architecture, bounded duration).
MAX_RECORDING_DURATION: 15000 ms
AUTO_STOP_FINALIZES_NORMALLY: YES (AUTO_STOP_REASON: BENCHMARK_MAX_DURATION; flush remaining accepted audio; FINAL from recognizer)

PERSISTENT_SESSION_JOURNAL: YES (filesDir/session-journal/current-session.txt overwritten; last-session.txt; no microphone audio)
UNCAUGHT_EXCEPTION_CAPTURE: YES (Java/Kotlin Thread.setDefaultUncaughtExceptionHandler; persists then delegates)
NATIVE_CRASH_CAPTURE_LIMITATION: SIGSEGV/SIGABRT are NOT captured by the Java uncaught-exception handler

MODEL_UNCHANGED: YES
MODEL_HASHES_UNCHANGED: YES
SHERPA_VERSION_UNCHANGED: YES
DECODING_UNCHANGED: YES
HOTWORDS_CONNECTED: NO
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO

TESTS: 32 passed, 0 failed (testDebugUnitTest; existing Phase 3B.1 tests + StabilityLogicTest)
BUILD_RESULT: SUCCESS (nested ./gradlew testDebugUnitTest assembleDebug)

TEST_ONLY_FINAL: ABSENT
V1_SIGNING: YES
  (enableV1Signing=true; META-INF/CERT.SF + CERT.RSA present; apksigner --min-sdk-version 18 reports v1=true v2=true.
   default/API-29 verification path uses v2, which is also present.)
V2_SIGNING: YES
TARGET_ABI: arm64-v8a
ZIPALIGN_VALID: YES (zipalign -c -v 4; extractNativeLibs=true so native .so may be compressed)
ARM64_NATIVE_LIBS_VALID: YES (only lib/arm64-v8a; SHA256 matches pinned official prebuilts)

APK: carfu-sherpa-zipformer-vi-benchmark-p3b1-1.apk
APK_SIZE: 57230303
APK_SHA256: dc503d9d78616647f023a18fc20c6f43c28be3cd3ba3b9f3a62f4a59dc091c2b

GITHUB_RELEASE: https://github.com/camanh123/Carfu-Ai/releases/tag/phase-3b1-1-sherpa-stability
DIRECT_DOWNLOAD_URL: https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-1-sherpa-stability/carfu-sherpa-zipformer-vi-benchmark-p3b1-1.apk

FILES_CHANGED: multilingual-asr-sherpa-benchmark-android/ only (nested standalone Gradle; see PR file list)
COMMIT: (recorded after push)
PR: https://github.com/camanh123/Carfu-Ai/pull/24

PRODUCTION_FILES_TOUCHED: NO
PHASE2A_TOUCHED: NO
PHASE2B_TOUCHED: NO
PHASE3A_TOUCHED: NO

READY_FOR_DEVICE_RETEST: YES
DEVICE_STABILITY_PASS: NOT CLAIMED
DEVICE_ASR_PASS: NOT CLAIMED
DEVICE_PERFORMANCE_PASS: NOT CLAIMED

MODEL FILE SHA256 (unchanged from Phase 3B.1 deps.lock):
  encoder.int8.onnx: 8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68
  decoder.onnx: cf2aa385b82c9d5d40cd29c3188af52d0249b3b78f0d4b7eb84ad502d50c7e7f
  joiner.int8.onnx: 7311d2e17b810ecea515d79c71cc4668af8759256a06fa01d27047772320c821
  tokens.txt: ca8171f8bbd516c050b627582f2125c8f5f1f6ed967ab41b0fa9aae2cf61b492
  bpe.model: 002894e7a82d80ffa5e25008ec8c5496159db804005e2103de96b01b4c13d445

SHERPA: v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
NATIVE LIBS IN FINAL APK:
  libsherpa-onnx-jni.so SHA256=3c6492ea91ea68fd3b72b9495efdde6dd913f6b58c387e3b07389cf335f4d26f
  libonnxruntime.so SHA256=33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1
