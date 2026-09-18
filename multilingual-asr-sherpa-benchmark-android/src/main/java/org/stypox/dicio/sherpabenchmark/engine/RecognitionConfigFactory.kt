package org.stypox.dicio.sherpabenchmark.engine

import org.stypox.dicio.sherpabenchmark.config.ThreadOption
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze

data class ModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val bpe: String,
)

/**
 * CPU-only greedy-search Offline Transducer config.
 * No hotwords, no FST, no HR, no test-corpus, no production wiring.
 */
data class RecognitionConfigSnapshot(
    val provider: String,
    val decodingMethod: String,
    val numThreads: Int,
    val sampleRate: Int,
    val featureDim: Int,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val hotwordsFile: String,
    val hotwordsScore: Float,
    val ruleFsts: String,
    val ruleFars: String,
    val hrLexicon: String,
    val hrRuleFsts: String,
    val modelType: String,
    val modelingUnit: String,
    val bpeVocab: String,
    val debug: Boolean,
) {
    fun asProbeString(): String = buildString {
        append("provider=").append(provider)
        append(";decoding=").append(decodingMethod)
        append(";threads=").append(numThreads)
        append(";encoder=").append(encoder)
        append(";decoder=").append(decoder)
        append(";joiner=").append(joiner)
        append(";tokens=").append(tokens)
        append(";hotwordsFile=").append(hotwordsFile)
        append(";ruleFsts=").append(ruleFsts)
        append(";ruleFars=").append(ruleFars)
        append(";hrLexicon=").append(hrLexicon)
        append(";hrRuleFsts=").append(hrRuleFsts)
        append(";modelType=").append(modelType)
        append(";modelingUnit=").append(modelingUnit)
        append(";bpeVocab=").append(bpeVocab)
    }
}

object RecognitionConfigFactory {
    fun create(threadCount: Int, paths: ModelPaths): RecognitionConfigSnapshot {
        val threads = ThreadOption.fromCount(threadCount).nThreads
        return RecognitionConfigSnapshot(
            provider = HardFreeze.EXECUTION_PROVIDER,
            decodingMethod = HardFreeze.DECODING_METHOD,
            numThreads = threads,
            sampleRate = 16_000,
            featureDim = 80,
            encoder = paths.encoder,
            decoder = paths.decoder,
            joiner = paths.joiner,
            tokens = paths.tokens,
            hotwordsFile = HardFreeze.HOTWORDS_FILE,
            hotwordsScore = 0.0f,
            ruleFsts = HardFreeze.RULE_FSTS,
            ruleFars = HardFreeze.RULE_FARS,
            hrLexicon = HardFreeze.HR_LEXICON,
            hrRuleFsts = HardFreeze.HR_RULE_FSTS,
            modelType = "",
            modelingUnit = "",
            // bpe.model is packaged and hashed but not passed into decoding.
            // Official CLI uses tokens/encoder/decoder/joiner only.
            bpeVocab = "",
            debug = false,
        )
    }

    fun containsForbiddenProvider(config: RecognitionConfigSnapshot): Boolean =
        HardFreeze.FORBIDDEN_PROVIDERS.any { it.equals(config.provider, ignoreCase = true) }
}
