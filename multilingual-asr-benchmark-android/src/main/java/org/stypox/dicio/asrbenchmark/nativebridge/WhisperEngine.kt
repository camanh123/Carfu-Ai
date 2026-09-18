package org.stypox.dicio.asrbenchmark.nativebridge

import org.stypox.dicio.asrbenchmark.config.ContextPrompt
import org.stypox.dicio.asrbenchmark.config.LanguageMode

data class WhisperTranscribeRequest(
    val samples: FloatArray,
    val languageMode: LanguageMode,
    val contextEnabled: Boolean,
    val nThreads: Int,
)

data class WhisperTranscribeResult(
    val rawTranscript: String,
    val detectedLanguage: String,
    val languageConfidence: Float,
    val segmentCount: Int,
    val nativeError: String,
)

class WhisperLoadException(message: String) : RuntimeException(message)

class WhisperTranscribeException(message: String) : RuntimeException(message)

/**
 * Thin Kotlin wrapper. Does not normalize, repair, or canonicalize text.
 */
class WhisperEngine {
    @Volatile
    private var ptr: Long = 0L

    val isLoaded: Boolean get() = ptr != 0L

    fun systemInfo(): String = try {
        WhisperNative.systemInfo()
    } catch (t: Throwable) {
        "JNI systemInfo failed: ${t.message}"
    }

    @Synchronized
    fun load(modelPath: String) {
        if (ptr != 0L) return
        val loaded = WhisperNative.initContext(modelPath)
        if (loaded == 0L) {
            val err = WhisperNative.lastError().ifBlank { "native init returned 0" }
            throw WhisperLoadException(err)
        }
        ptr = loaded
    }

    @Synchronized
    fun transcribe(request: WhisperTranscribeRequest): WhisperTranscribeResult {
        if (ptr == 0L) {
            throw WhisperTranscribeException("model not loaded")
        }
        if (request.samples.isEmpty()) {
            throw WhisperTranscribeException("empty audio")
        }
        val prompt = if (request.contextEnabled) ContextPrompt.VOCABULARY_HINT else null
        val rc = WhisperNative.fullTranscribe(
            ptr,
            request.samples,
            request.languageMode.whisperLanguage,
            prompt,
            request.nThreads,
        )
        val nativeError = WhisperNative.lastError()
        if (rc != 0) {
            throw WhisperTranscribeException(
                nativeError.ifBlank { "whisper_full rc=$rc" },
            )
        }
        return WhisperTranscribeResult(
            rawTranscript = WhisperNative.lastTranscript(),
            detectedLanguage = WhisperNative.lastDetectedLanguage(),
            languageConfidence = -1f,
            segmentCount = WhisperNative.lastSegmentCount(),
            nativeError = nativeError,
        )
    }

    @Synchronized
    fun probeLanguageConfidence(nThreads: Int): Float {
        if (ptr == 0L) return -1f
        return try {
            WhisperNative.probeLanguageConfidence(ptr, nThreads)
        } catch (_: Throwable) {
            -1f
        }
    }

    @Synchronized
    fun release() {
        if (ptr != 0L) {
            WhisperNative.freeContext(ptr)
            ptr = 0L
        }
    }
}
