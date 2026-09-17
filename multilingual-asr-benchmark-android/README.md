# Phase 3A — whisper.cpp multilingual ASR benchmark (standalone)

Isolated diagnostic APK for the CARFU head unit (Android 10 / API 29 / ARM64 / UNISOC Tiger T610).

This is **not** production integration. It does **not** touch Voice, SpeechRecognizer, VoiceSession, Phase 2A, NLU, SmartTube, Maps, or any existing Dicio module.

## What it measures

Whether whisper.cpp **multilingual tiny** (`ggml-tiny.bin`, **not** `tiny.en`) can recognize Vietnamese + English code-switching better than Android `vi-VN` SpeechRecognizer.

Displayed text is **raw whisper.cpp output**. No Phase 2A normalizer, no repair, no NLU, no SmartTube canonicalization.

Failure (crash, OOM, extreme RTF, JNI load failure) is a valid result and is shown on screen.

## Build (from this directory)

```bash
cd multilingual-asr-benchmark-android
./scripts/prepare-deps.sh
./gradlew assembleDebug testDebugUnitTest
```

`scripts/prepare-deps.sh`:

1. Clones official [`ggml-org/whisper.cpp`](https://github.com/ggml-org/whisper.cpp) at pinned tag **v1.8.2** / commit `4979e04f5dcaccb36057e059bbaed8a2f5288315`
2. Downloads official multilingual tiny weights from Hugging Face and verifies SHA256

Pins live in `deps.lock`. Model binaries are **not** committed to git. They are staged into the APK as an asset at packaging time.

APK (debug-signed, native Release `-O3`):

```
build/outputs/apk/debug/carfu-whisper-asr-benchmark-debug.apk
```

ABI: **arm64-v8a only**. minSdk/targetSdk **29**.

Native code is compiled with Release `-O3` even for the debug-signed diagnostic APK. No `armv8.2-a+fp16` extra ISA flags (T610 must not assume unsupported instructions).

## Device use

1. Sideload the APK on the CARFU unit.
2. Grant microphone permission.
3. Wait for `IDLE — model loaded`.
4. Choose **AUTO** vs **VI**, **CONTEXT OFF/ON**, **2 or 4 threads**.
5. **START** → speak one utterance → **STOP** → wait for raw transcript + RTF.
6. **COPY DIAGNOSTIC** copies configuration, current result, and last 20 sessions.

CONTEXT ON uses only:

```
SmartTube YouTube MusicLoop Google Maps Vietmap
```

It does **not** include expected test sentences.

RTF = `transcription_time / audio_duration`. Do not call the engine real-time from subjective feel.

## Why this module is not in root `settings.gradle.kts`

A nested standalone Gradle project is sufficient. Including it in the Dicio root build would force CI to download a 75MB model and compile NDK code for an experiment that must not affect production modules.

## Licenses

See `licenses/` and `LICENSE_AUDIT.md`.
