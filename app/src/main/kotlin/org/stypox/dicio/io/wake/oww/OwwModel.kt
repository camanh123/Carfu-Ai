package org.stypox.dicio.io.wake.oww

import org.tensorflow.lite.Interpreter
import java.io.File

class OwwModel(
    melSpectrogramPath: File,
    embeddingPath: File,
    wakeWordPath: File
) : AutoCloseable {
    @Suppress("JoinDeclarationAndAssignment")
    private val melInterpreter: Interpreter
    private val embInterpreter: Interpreter
    private val wakeInterpreter: Interpreter

    private var accumulatedMelOutputs: Array<Array<FloatArray>> = Array(EMB_INPUT_COUNT) { arrayOf() }
    private var accumulatedEmbOutputs: Array<FloatArray> = Array(WAKE_INPUT_COUNT) { floatArrayOf() }

    private var isClosed: Boolean = false // whether the model has just been closed

    init {
        melInterpreter = loadModel(melSpectrogramPath, intArrayOf(1, MEL_INPUT_COUNT))

        try {
            embInterpreter = loadModel(embeddingPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            throw t
        }

        try {
            wakeInterpreter = loadModel(wakeWordPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            embInterpreter.close()
            throw t
        }
    }

    fun processFrame(audio: FloatArray): Float {
        synchronized(this) {
            if (isClosed) {
                // there must have been a synchronization error, don't do anything
                return 0.0f
            }

            if (audio.size != MEL_INPUT_COUNT) {
                throw IllegalArgumentException(
                    "OwwModel can only process audio frames of $MEL_INPUT_COUNT samples"
                )
            }

            val melOutput = Array(MEL_OUTPUT_COUNT) { FloatArray(MEL_FEATURE_SIZE) }
            melInterpreter.run(arrayOf(audio), arrayOf(arrayOf(melOutput)))
            for (i in 0..<EMB_INPUT_COUNT) {
                accumulatedMelOutputs[i] = if (i < EMB_INPUT_COUNT - MEL_OUTPUT_COUNT) {
                    accumulatedMelOutputs[i + MEL_OUTPUT_COUNT]
                } else {
                    melOutput[i - EMB_INPUT_COUNT + MEL_OUTPUT_COUNT]
                        .map { floatArrayOf((it / 10.0f) + 2.0f) }
                        .toTypedArray()
                }
            }
            //println("melOutput[0]=${melOutput[0][0]}")
            if (accumulatedMelOutputs[0].isEmpty()) {
                return 0.0f // not fully initialized yet
            }

            val embOutput = Array(EMB_OUTPUT_COUNT) { FloatArray(EMB_FEATURE_SIZE) }
            embInterpreter.run(arrayOf(accumulatedMelOutputs), arrayOf(arrayOf(embOutput)))
            for (i in 0..<WAKE_INPUT_COUNT) {
                accumulatedEmbOutputs[i] = if (i < WAKE_INPUT_COUNT - EMB_OUTPUT_COUNT) {
                    accumulatedEmbOutputs[i + EMB_OUTPUT_COUNT]
                } else {
                    @Suppress("KotlinConstantConditions")
                    embOutput[i - WAKE_INPUT_COUNT + EMB_OUTPUT_COUNT]
                }
            }
            //println("embOutput[0]=${embOutput[0][0]}")
            if (accumulatedEmbOutputs[0].isEmpty()) {
                return 0.0f // not fully initialized yet
            }

            val wakeOutput = FloatArray(WAKE_OUTPUT_SIZE)
            wakeInterpreter.run(arrayOf(accumulatedEmbOutputs), arrayOf(wakeOutput))
            return sanitizeScore(wakeOutput[POSITIVE_OUTPUT_INDEX])
        }
    }


    fun resetAccumulators() {
        synchronized(this) {
            accumulatedMelOutputs = Array(EMB_INPUT_COUNT) { arrayOf() }
            accumulatedEmbOutputs = Array(WAKE_INPUT_COUNT) { floatArrayOf() }
        }
    }

    override fun close() {
        synchronized(this) {
            isClosed = true
            melInterpreter.close()
            embInterpreter.close()
            wakeInterpreter.close()
        }
    }

    companion object {
        // mel model shape is [1,x] -> [1,1,floor((x-512)/160)+1,32]
        const val MEL_INPUT_COUNT = 512 + 160 * 4 // chosen by us, 1152 samples @ 16kHz = 72ms
        const val MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) / 160 + 1 // formula obtained empirically
        const val MEL_FEATURE_SIZE = 32 // also the size of features received by the emb model

        // emb model shape is [1,76,32,1] -> [1,1,1,96]
        const val EMB_INPUT_COUNT = 76 // hardcoded in the model
        const val EMB_OUTPUT_COUNT = 1
        const val EMB_FEATURE_SIZE = 96 // also the size of features received by the wake model

        // wake model shape is [1,16,96] -> [1,1] (single-class CARFU classifier)
        const val WAKE_INPUT_COUNT = 16 // hardcoded in the model
        const val WAKE_OUTPUT_SIZE = 1
        /** Positive-class index of the wake-word output tensor. Single-class models use 0. */
        const val POSITIVE_OUTPUT_INDEX = 0
        /** Signed PCM16 → float in ~[-1, 1). OpenWakeWord expects this scale, not 32767. */
        const val PCM_SCALE = 32768.0f

        fun sanitizeScore(raw: Float): Float {
            if (!raw.isFinite()) return 0.0f
            return raw.coerceIn(0.0f, 1.0f)
        }

        fun tensorContractDescription(): String {
            return "pcmScale=$PCM_SCALE " +
                "melIn=[1,$MEL_INPUT_COUNT] " +
                "melOut=[1,1,$MEL_OUTPUT_COUNT,$MEL_FEATURE_SIZE] " +
                "embIn=[1,$EMB_INPUT_COUNT,$MEL_FEATURE_SIZE,1] " +
                "embOut=[1,1,1,$EMB_FEATURE_SIZE] " +
                "wakeIn=[1,$WAKE_INPUT_COUNT,$EMB_FEATURE_SIZE] " +
                "wakeOut=[1,$WAKE_OUTPUT_SIZE] " +
                "positiveIndex=$POSITIVE_OUTPUT_INDEX"
        }

        private fun loadModel(modelPath: File, inputDims: IntArray? = null): Interpreter {
            val interpreter = Interpreter(modelPath)

            if (inputDims != null) {
                interpreter.resizeInput(0, inputDims)
            }

            interpreter.allocateTensors()
            return interpreter
        }
    }
}

