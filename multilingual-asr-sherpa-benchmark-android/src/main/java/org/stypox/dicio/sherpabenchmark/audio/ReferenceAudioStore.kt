package org.stypox.dicio.sherpabenchmark.audio

import java.io.File

/**
 * App-private PCM16 store. Never uploads. Never commits. Overwrites a single reference.
 */
class ReferenceAudioStore(private val dir: File) {
    private val pcmFile get() = File(dir, PCM_NAME)
    private val metaFile get() = File(dir, META_NAME)

    @Volatile
    var current: ReferenceAudio? = null
        private set

    fun load(): ReferenceAudio? {
        if (!pcmFile.isFile) {
            current = null
            return null
        }
        val pcm = pcmFile.readBytes()
        val meta = if (metaFile.isFile) parseMeta(metaFile.readText()) else emptyMap()
        val sha = Sha256.hex(pcm)
        val stored = meta["sha256"]
        if (!stored.isNullOrBlank() && stored != sha) {
            current = null
            return null
        }
        val audio = ReferenceAudio(
            pcm16 = pcm,
            sampleRateHz = meta["sampleRate"]?.toIntOrNull() ?: Pcm16kMonoRecorder.SAMPLE_RATE_HZ,
            sha256 = sha,
            recordedEpochMs = meta["recordedEpochMs"]?.toLongOrNull() ?: pcmFile.lastModified(),
        )
        current = audio
        return audio
    }

    fun save(pcm16: ByteArray, recordedEpochMs: Long = System.currentTimeMillis()): ReferenceAudio {
        require(pcm16.isNotEmpty()) { "empty reference PCM" }
        dir.mkdirs()
        val sha = Sha256.hex(pcm16)
        val tmp = File(dir, PCM_NAME + ".tmp")
        tmp.writeBytes(pcm16)
        if (!tmp.renameTo(pcmFile)) {
            pcmFile.writeBytes(pcm16)
            tmp.delete()
        }
        val audio = ReferenceAudio(
            pcm16 = pcm16.copyOf(),
            sha256 = sha,
            recordedEpochMs = recordedEpochMs,
        )
        metaFile.writeText(
            "sha256=$sha\n" +
                "bytes=${pcm16.size}\n" +
                "samples=${audio.sampleCount}\n" +
                "durationMs=${audio.durationMs}\n" +
                "sampleRate=${audio.sampleRateHz}\n" +
                "recordedEpochMs=$recordedEpochMs\n",
        )
        current = audio
        return audio
    }

    fun delete() {
        pcmFile.delete()
        metaFile.delete()
        current = null
    }

    companion object {
        const val PCM_NAME: String = "reference.pcm"
        const val META_NAME: String = "reference.meta.txt"

        private fun parseMeta(text: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            return map
        }
    }
}
