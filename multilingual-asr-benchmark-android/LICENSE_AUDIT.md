# Phase 3A dependency / license audit

This diagnostic APK packages only software and weights from official sources.

## whisper.cpp

| Field | Value |
| --- | --- |
| Source | https://github.com/ggml-org/whisper.cpp |
| Tag | v1.8.2 |
| Commit | 4979e04f5dcaccb36057e059bbaed8a2f5288315 |
| License | MIT (see `licenses/whisper.cpp.MIT.txt`) |
| Android/JNI reference | `examples/whisper.android` (architecture only; JNI symbols are this app's) |

No precompiled `.so` binaries were copied from third-party GitHub dumps. `libwhisper.so` is built from the pinned source with the NDK.

## Model

| Field | Value |
| --- | --- |
| File | ggml-tiny.bin |
| Multilingual | YES (not tiny.en) |
| Quantization | none (ggml F16 / unquantized tiny) |
| Source | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin |
| HF git SHA-1 | bd577a113a864445d4c299885e0cb97d4ba92b5f |
| SHA256 | be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 |
| Size | 77691713 bytes |
| License | MIT (OpenAI Whisper weights; ggml conversion published on the official Hugging Face repo, `license: mit`) |

Quantized official variants (`tiny-q5_1`, `tiny-q8_0`) exist. They were **not** used for this first benchmark so quantization is not a confounding variable for Vietnamese + English code-switch quality.

## Native libraries packaged

| Library | Origin | ABI |
| --- | --- | --- |
| libwhisper.so | built from whisper.cpp `src/whisper.cpp` + this module's `jni.c`, statically linking ggml CPU backend | arm64-v8a |
| libc++_shared.so | Android NDK STL | arm64-v8a |

Not packaged: `libwhisper_v8fp16_va.so`, `libwhisper_vfpv4.so`, GPU backends, OpenMP.

## AndroidX / Kotlin (Maven Central)

AppCompat, Core KTX, Material, ConstraintLayout, Kotlin stdlib — Apache-2.0.

JUnit 4 for JVM unit tests — EPL-1.0; not packaged in the APK.
