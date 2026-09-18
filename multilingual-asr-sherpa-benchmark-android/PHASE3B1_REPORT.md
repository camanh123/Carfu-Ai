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
  archive: 26442384 bytes
MODEL_FILE_SHA256:
  archive: da8b637947091829d7ee9eda23da2a4ec7caa399233a3f4e34eb719fb2ea6b9b
  encoder.int8.onnx: 8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68
  decoder.onnx: cf2aa385b82c9d5d40cd29c3188af52d0249b3b78f0d4b7eb84ad502d50c7e7f
  joiner.int8.onnx: 7311d2e17b810ecea515d79c71cc4668af8759256a06fa01d27047772320c821
  tokens.txt: ca8171f8bbd516c050b627582f2125c8f5f1f6ed967ab41b0fa9aae2cf61b492
  bpe.model: 002894e7a82d80ffa5e25008ec8c5496159db804005e2103de96b01b4c13d445

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

TEST_ONLY_FINAL: (filled after APK audit)
V1_SIGNING: (filled after APK audit)
V2_SIGNING: (filled after APK audit)
ZIPALIGN_VALID: (filled after APK audit)
ARM64_NATIVE_LIBS_VALID: (filled after APK audit)

TESTS: (filled after unit tests)
BUILD_RESULT: (filled after assembleDebug)

APK: carfu-sherpa-zipformer-vi-benchmark.apk
APK_SIZE: (filled after APK audit)
APK_SHA256: (filled after APK audit)

GITHUB_RELEASE: (filled after publication)
DIRECT_DOWNLOAD_URL: (filled after publication)

PRODUCTION_FILES_TOUCHED: NO
PHASE2A_TOUCHED: NO
PHASE2B_TOUCHED: NO
PHASE3A_TOUCHED: NO

READY_FOR_DEVICE_TEST: YES after source/build pass
DEVICE_INSTALL_PASS: NOT CLAIMED
DEVICE_ASR_PASS: NOT CLAIMED
DEVICE_PERFORMANCE_PASS: NOT CLAIMED
