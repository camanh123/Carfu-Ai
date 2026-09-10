package org.stypox.dicio.io.session

/**
 * JVM-testable RECORD_AUDIO classification for CARFU MIC/MODE.
 *
 * Android 6+ runtime grant is independent of the manifest declaration.
 * Collapsing every failure into `missing_record_audio` hides whether the
 * permission was never requested, was denied, or is actually granted.
 */
object RecordAudioPermissionPolicy {
    const val MANIFEST_DECLARED = "MANIFEST_DECLARED_RECORD_AUDIO"
    const val MANIFEST_MISSING = "MANIFEST_MISSING_RECORD_AUDIO"
    const val RUNTIME_GRANTED = "RUNTIME_RECORD_AUDIO_GRANTED"
    const val RUNTIME_DENIED = "RUNTIME_RECORD_AUDIO_DENIED"
    const val RUNTIME_NOT_REQUESTED = "RUNTIME_RECORD_AUDIO_NOT_REQUESTED"

    enum class Runtime {
        GRANTED,
        DENIED,
        NOT_REQUESTED,
    }

    data class Snapshot(
        val manifestDeclared: Boolean,
        val runtime: Runtime,
        val checkSelfPermissionGranted: Boolean,
        val shouldShowRationale: Boolean?,
        val previouslyRequested: Boolean,
    ) {
        fun manifestLabel(): String =
            if (manifestDeclared) MANIFEST_DECLARED else MANIFEST_MISSING

        fun runtimeLabel(): String = when (runtime) {
            Runtime.GRANTED -> RUNTIME_GRANTED
            Runtime.DENIED -> RUNTIME_DENIED
            Runtime.NOT_REQUESTED -> RUNTIME_NOT_REQUESTED
        }

        fun mayStartSpeechRecognizer(): Boolean =
            RecordAudioPermissionPolicy.mayStartSpeechRecognizer(runtime)

        fun mayMarkProductListening(): Boolean =
            RecordAudioPermissionPolicy.mayMarkProductListening(runtime)

        fun shouldShowSystemPermissionDialog(): Boolean =
            RecordAudioPermissionPolicy.shouldShowSystemPermissionDialog(runtime)
    }

    fun classify(
        granted: Boolean,
        shouldShowRationale: Boolean?,
        previouslyRequested: Boolean,
    ): Runtime {
        if (granted) return Runtime.GRANTED
        if (shouldShowRationale == true) return Runtime.DENIED
        if (previouslyRequested) return Runtime.DENIED
        return Runtime.NOT_REQUESTED
    }

    fun snapshot(
        manifestDeclared: Boolean,
        granted: Boolean,
        shouldShowRationale: Boolean?,
        previouslyRequested: Boolean,
    ): Snapshot {
        val runtime = classify(granted, shouldShowRationale, previouslyRequested)
        return Snapshot(
            manifestDeclared = manifestDeclared,
            runtime = runtime,
            checkSelfPermissionGranted = granted,
            shouldShowRationale = shouldShowRationale,
            previouslyRequested = previouslyRequested,
        )
    }

    fun mayStartSpeechRecognizer(runtime: Runtime): Boolean = runtime == Runtime.GRANTED

    fun mayMarkProductListening(runtime: Runtime): Boolean = runtime == Runtime.GRANTED

    fun shouldShowSystemPermissionDialog(runtime: Runtime): Boolean =
        runtime == Runtime.NOT_REQUESTED || runtime == Runtime.DENIED

    fun reachesSpeechRecognizerStartListening(runtime: Runtime): Boolean =
        mayStartSpeechRecognizer(runtime)
}
