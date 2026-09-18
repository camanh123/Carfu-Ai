# CARFU Phase 3A — whisper.cpp multilingual ASR feasibility

Standalone diagnostic only. Not production integration.

```
PHASE_BASE: 58394f64a462bf001f75fa2e4a3ded1eaf18b51d
MODULE: multilingual-asr-benchmark-android
APPLICATION_ID: org.stypox.dicio.asrbenchmark

WHISPER_CPP_SOURCE: https://github.com/ggml-org/whisper.cpp
WHISPER_CPP_VERSION_OR_COMMIT: v1.8.2 / 4979e04f5dcaccb36057e059bbaed8a2f5288315
WHISPER_LICENSE: MIT

MODEL: ggml-tiny.bin
MODEL_MULTILINGUAL: YES
MODEL_SIZE: 77691713 bytes
MODEL_QUANTIZATION: none-ggml-F16 (not tiny.en; quantized tiny-q5_1/q8_0 not used)
MODEL_SHA256: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21
MODEL_PACKAGING_STRATEGY: scripts/prepare-deps.sh downloads official Hugging Face ggml weights, SHA256-verifies, Gradle stages into APK assets, runtime copies to filesDir before whisper_init_from_file. Model binary is gitignored.

ANDROID_MIN_SDK: 29
TARGET_ABI: arm64-v8a
NATIVE_OPTIMIZATION: CMake Release -O3 -DNDEBUG; libwhisper.so stripped; no host-native; no armv8.2-a+fp16 extra ISA

LANGUAGE_AUTO: YES
LANGUAGE_VI: YES
CONTEXT_TOGGLE: YES
CONTEXT_PROMPT: SmartTube YouTube MusicLoop Google Maps Vietmap

AUDIO_FORMAT: 16 kHz mono PCM16 LE converted to float32 [-1,1]
THREAD_OPTIONS: 2, 4 (default 4)

RAW_OUTPUT_UNMODIFIED: YES
PHASE2A_CONNECTED: NO
NLU_CONNECTED: NO
PRODUCTION_CONNECTED: NO

METRICS_IMPLEMENTED: ENGINE, MODEL, MODEL SIZE, LANGUAGE MODE, CONTEXT ON/OFF, RECORDING STATUS, AUDIO DURATION, RAW TRANSCRIPT, DETECTED LANGUAGE, LANGUAGE CONFIDENCE (best-effort, outside RTF timer), MODEL LOAD TIME, TRANSCRIPTION TIME, REAL-TIME FACTOR, APP MEMORY BEFORE/AFTER, PEAK MEMORY (PSS/java/native sampled), CPU/thread configuration, ERROR
HISTORY: last 20 sessions
COPY_DIAGNOSTIC: YES (plain text clipboard)

UNIT_TESTS: 10 tests, 0 failures (RTF spec examples, history cap 20, context prompt vocabulary-only, language auto!=en, PCM conversion, COPY DIAGNOSTIC flags)
BUILD_RESULT: SUCCESS ./gradlew assembleDebug testDebugUnitTest (module-local standalone Gradle)

APK: multilingual-asr-benchmark-android/build/outputs/apk/debug/carfu-whisper-asr-benchmark-debug.apk
APK_SIZE: 93464040 bytes
APK_SHA256: 26a2a0380a5b6047721d7dfc50ef271f6f6c955a4d13cff8649641e8c2d23d3c
RAW_URL: NOT PUBLISHED (diagnostic APK not committed; local/sideload artifact)

FILES_CHANGED: multilingual-asr-benchmark-android/**
ROOT_FILES_CHANGED: NONE
PRODUCTION_FILES_TOUCHED: NO
EXISTING_MODULES_TOUCHED: NO

COMMIT: d1d263c4cad2628dc3d36ee591fd9347b18b151a
READY_FOR_DEVICE_TEST: YES
DEVICE_PASS: NOT CLAIMED
```

Root `settings.gradle.kts` was **not** modified. This module is a nested standalone Gradle project so Dicio CI `./gradlew assembleDebug` does not download the 75MB model or compile NDK code.
