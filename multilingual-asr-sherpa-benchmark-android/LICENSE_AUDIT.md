# Phase 3B.1 dependency / license audit

This diagnostic APK packages only software and weights from official sources.

## sherpa-onnx

| Field | Value |
| --- | --- |
| Source | https://github.com/k2-fsa/sherpa-onnx |
| Tag | v1.13.8 |
| Commit | 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf |
| License | Apache-2.0 (see `licenses/sherpa-onnx.Apache-2.0.txt`) |
| Android native | official prebuilt `sherpa-onnx-v1.13.8-android.tar.bz2` arm64-v8a |
| Kotlin JNI API | official `sherpa-onnx/kotlin-api` at the same tag |

Native libraries packaged (arm64-v8a only):

| Library | Origin | Notes |
| --- | --- | --- |
| libsherpa-onnx-jni.so | official android tar | ELF AArch64; NEEDED libonnxruntime.so |
| libonnxruntime.so | official android tar | ELF AArch64; CPU ONNX Runtime |

Not packaged: armeabi-v7a, x86, x86_64, libsherpa-onnx-c-api.so,
libsherpa-onnx-cxx-api.so, RKNN, QNN, NNAPI, GPU/NPU backends.

Execution provider forced to `cpu`.

## Model

| Field | Value |
| --- | --- |
| Name | sherpa-onnx-zipformer-vi-30M-int8-2026-02-09 |
| Architecture | offline transducer Zipformer |
| Language | Vietnamese |
| Quantization | official INT8 encoder + joiner; decoder float ONNX |
| Source archive | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2 |
| Upstream weights | https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h |
| Packaged files | encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt, bpe.model |

We did not quantize the model. Official INT8 artifacts only.
test_wavs are not packaged into the APK.

## AndroidX / Kotlin

AppCompat, Core KTX, Material, ConstraintLayout, Kotlin stdlib — Apache-2.0.
JUnit 4 for JVM unit tests — EPL-1.0; not packaged in the APK.
