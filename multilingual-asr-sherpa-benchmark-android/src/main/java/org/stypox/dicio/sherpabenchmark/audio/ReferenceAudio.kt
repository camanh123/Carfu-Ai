package org.stypox.dicio.sherpabenchmark.audio

data class ReferenceAudio(
    val pcm16: ByteArray,
    val sampleRateHz: Int = Pcm16kMonoRecorder.SAMPLE_RATE_HZ,
    val sha256: String,
    val recordedEpochMs: Long,
) {
    val byteCount: Int get() = pcm16.size
    val sampleCount: Int get() = pcm16.size / Pcm16kMonoRecorder.BYTES_PER_SAMPLE
    val durationMs: Long get() = Pcm16kMonoRecorder.durationMs(sampleCount)

    fun toFloat32(): FloatArray = Pcm16kMonoRecorder.pcm16leToFloat32(pcm16)

    fun summaryLine(): String = buildString {
        append("REFERENCE AUDIO:\n")
        append("duration: ").append(durationMs).append(" ms\n")
        append("samples: ").append(sampleCount).append('\n')
        append("bytes: ").append(byteCount).append('\n')
        append("SHA256: ").append(sha256)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReferenceAudio) return false
        return sha256 == other.sha256 && pcm16.contentEquals(other.pcm16)
    }

    override fun hashCode(): Int = sha256.hashCode()
}
