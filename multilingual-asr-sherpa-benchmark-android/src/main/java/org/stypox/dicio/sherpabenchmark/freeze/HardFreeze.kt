package org.stypox.dicio.sherpabenchmark.freeze

/**
 * Hard freeze for Phase 3B.1. These values are the diagnostic contract.
 * They must never be flipped by recognition, UI, or test-corpus display.
 */
object HardFreeze {
    const val EXECUTION_PROVIDER: String = "cpu"
    const val DECODING_METHOD: String = "greedy_search"
    const val RECOGNITION_ARCHITECTURE: String = "offline-transducer-zipformer"
    const val RECOGNITION_MODE: String = "simulated-streaming-offline-transducer"
    const val NATIVE_STREAMING_MODEL: Boolean = false
    const val SIMULATED_STREAMING: Boolean = true
    const val RAW_OUTPUT_UNMODIFIED: Boolean = true
    const val HOTWORDS_CONNECTED: Boolean = false
    const val PHASE2A_CONNECTED: Boolean = false
    const val NLU_CONNECTED: Boolean = false
    const val PRODUCTION_CONNECTED: Boolean = false
    const val VAD_CONNECTED: Boolean = false
    const val NNAPI_CONNECTED: Boolean = false
    const val GPU_CONNECTED: Boolean = false
    const val AUDIO_FORMAT: String = "16 kHz mono PCM16 LE converted to float32 [-1,1]"
    const val SIMULATED_STREAMING_CHUNK_MS: Long = 200L

    const val HOTWORDS_FILE: String = ""
    const val RULE_FSTS: String = ""
    const val RULE_FARS: String = ""
    const val HR_LEXICON: String = ""
    const val HR_RULE_FSTS: String = ""

    val FORBIDDEN_PROVIDERS: Set<String> = setOf(
        "nnapi", "qnn", "htp", "rknn", "vulkan", "cuda", "gpu", "npu", "coreml",
    )

    fun yesNo(value: Boolean): String = if (value) "YES" else "NO"
}
