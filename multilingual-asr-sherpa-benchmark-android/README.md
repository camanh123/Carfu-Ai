# Phase 3B.1 — Sherpa-ONNX Zipformer VI INT8 standalone diagnostic

Nested standalone Gradle project. **Not** registered in the root `settings.gradle.kts`.
**Not** wired into production Voice / NLU / Phase 2A / Phase 2B / Phase 3A.

## Build

From this directory:

```bash
cd multilingual-asr-sherpa-benchmark-android
./gradlew assembleDebug testDebugUnitTest
```

`scripts/prepare-deps.sh` runs automatically. It downloads (and SHA256-verifies):

1. Official model archive `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2`
2. Official prebuilt `sherpa-onnx-v1.13.8-android.tar.bz2` (arm64-v8a `libsherpa-onnx-jni.so` + `libonnxruntime.so` only)
3. Pinned Kotlin JNI API sources from tag `v1.13.8` / commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`

APK output:

```
build/outputs/apk/debug/carfu-sherpa-zipformer-vi-benchmark-p3b1-1.apk
```

Audit that exact APK:

```bash
bash scripts/audit-apk.sh build/outputs/apk/debug/carfu-sherpa-zipformer-vi-benchmark-p3b1-1.apk
```

## What this measures

CPU-only **offline** Zipformer transducer with **simulated streaming**
(chunked re-decode of accumulated microphone audio). This is **not** a native
streaming model.

| Field | Value |
| --- | --- |
| ENGINE | sherpa-onnx |
| MODEL | sherpa-onnx-zipformer-vi-30M-int8-2026-02-09 |
| PROVIDER | cpu |
| DECODING | greedy_search |
| THREADS | 1 / 2 / 4 (default 1) |
| AUDIO | 16 kHz mono PCM16, manual START/STOP, no VAD |
| RAW_OUTPUT_UNMODIFIED | YES |

Do **not** claim CARFU RTF, Vietnamese accuracy, or code-switch quality from
desktop/emulator runs. Device pass is separate.

## Install

Ordinary sideload from CARFU File Manager → Android Package Installer.
`testOnly` is disabled. v1+v2 signing enabled for Android 10.

applicationId: `org.stypox.dicio.sherpabenchmark`

Phase 3B.1.1 adds a 15 s auto-stop, decode backpressure, a crash-surviving
session journal, and safer lifecycle/finalization. Model, hashes, sherpa-onnx
pin, CPU provider, and greedy_search are unchanged.

Published APK (do not commit the APK to git):

Phase 3B.1.1 (stability):

https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-1-sherpa-stability/carfu-sherpa-zipformer-vi-benchmark-p3b1-1.apk

Phase 3B.1 (original):

https://github.com/camanh123/Carfu-Ai/releases/download/phase-3b1-sherpa-zipformer-benchmark/carfu-sherpa-zipformer-vi-benchmark.apk
