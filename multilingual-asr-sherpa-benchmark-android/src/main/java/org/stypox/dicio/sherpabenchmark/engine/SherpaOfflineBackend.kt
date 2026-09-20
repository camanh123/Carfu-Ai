package org.stypox.dicio.sherpabenchmark.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze

/**
 * Thin JNI wrapper. CPU provider only. greedy_search. No hotwords stream.
 */
class SherpaOfflineBackend : AsrBackend {
    private var recognizer: OfflineRecognizer? = null
    val counters = ResourceCounters()
    @Volatile
    var lastNativeOp: String = "none"
        private set

    @Volatile
    var loadedThreads: Int = -1
        private set

    val isReady: Boolean get() = recognizer != null

    fun init(config: RecognitionConfigSnapshot): Long {
        require(config.provider == HardFreeze.EXECUTION_PROVIDER) {
            "Phase 3B.1 requires CPU provider, got ${config.provider}"
        }
        require(!RecognitionConfigFactory.containsForbiddenProvider(config))
        require(config.hotwordsFile.isEmpty())
        require(config.ruleFsts.isEmpty())
        require(config.ruleFars.isEmpty())
        require(config.hrLexicon.isEmpty())
        require(config.hrRuleFsts.isEmpty())
        lastNativeOp = "release-previous"
        release()
        lastNativeOp = "newFromFile"
        counters.onRecognizerCreated()
        val t0 = System.nanoTime()
        val native = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = config.sampleRate,
                featureDim = config.featureDim,
            ),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = config.encoder,
                    decoder = config.decoder,
                    joiner = config.joiner,
                ),
                tokens = config.tokens,
                numThreads = config.numThreads,
                provider = HardFreeze.EXECUTION_PROVIDER,
                debug = false,
                modelType = config.modelType,
                modelingUnit = config.modelingUnit,
                bpeVocab = config.bpeVocab,
            ),
            hr = HomophoneReplacerConfig(),
            decodingMethod = HardFreeze.DECODING_METHOD,
            hotwordsFile = "",
            hotwordsScore = 0.0f,
            ruleFsts = "",
            ruleFars = "",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = native)
        lastNativeOp = "recognizer-ready"
        loadedThreads = config.numThreads
        return (System.nanoTime() - t0) / 1_000_000L
    }

    override fun decode(samples: FloatArray, sampleRate: Int): String {
        val rec = recognizer ?: throw IllegalStateException("recognizer not initialized")
        if (!counters.tryBeginDecode()) {
            throw IllegalStateException("overlapping native decode on same recognizer")
        }
        lastNativeOp = "createStream"
        counters.onStreamCreated()
        val stream = rec.createStream()
        try {
            lastNativeOp = "acceptWaveform samples=${samples.size}"
            stream.acceptWaveform(samples, sampleRate)
            lastNativeOp = "decode"
            rec.decode(stream)
            lastNativeOp = "getResult"
            return rec.getResult(stream).text
        } finally {
            lastNativeOp = "releaseStream"
            try {
                stream.release()
            } finally {
                counters.onStreamReleased()
                counters.endDecode()
                lastNativeOp = "decode-complete"
            }
        }
    }

    fun release() {
        lastNativeOp = "releaseRecognizer"
        val had = recognizer != null
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }
        recognizer = null
        loadedThreads = -1
        if (had) counters.onRecognizerReleased()
        lastNativeOp = "released"
    }
}
