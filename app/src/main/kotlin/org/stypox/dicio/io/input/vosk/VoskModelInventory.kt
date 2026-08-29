package org.stypox.dicio.io.input.vosk

import java.io.File

/**
 * On-disk inventory for vosk-model-small-vn-0.4. Pure JVM so tests can prove
 * MISSING vs unpacked vs invalid without constructing a Recognizer.
 */
enum class VoskModelLifecycle {
    MISSING,
    DOWNLOADING,
    UNPACKING,
    LOADING,
    READY,
    ERROR,
}

data class VoskModelSnapshot(
    val filesDir: String,
    val modelDir: String,
    val dirExists: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    val ivectorExists: Boolean,
    val zipExists: Boolean,
    val zipBytes: Long,
    val urlMarker: String?,
    val urlMatchesExpected: Boolean,
    val lifecycle: VoskModelLifecycle,
    val validUnpacked: Boolean,
) {
    fun logLine(): String {
        return "VOSK_MODEL_INVENTORY filesDir=$filesDir modelDir=$modelDir " +
            "dirExists=$dirExists fileCount=$fileCount totalBytes=$totalBytes " +
            "ivectorExists=$ivectorExists zipExists=$zipExists zipBytes=$zipBytes " +
            "urlMarker=${urlMarker ?: "(none)"} urlMatchesExpected=$urlMatchesExpected " +
            "validUnpacked=$validUnpacked lifecycle=$lifecycle"
    }
}

object VoskModelInventory {
    const val MODEL_ZIP_FILENAME = "vosk-model.zip"
    const val MODEL_URL_FILENAME = "vosk-model-url"
    const val MODEL_UNZIPPED_DIR = "vosk-model"
    const val IVECTOR_DIR = "ivector"
    const val MIN_VALID_FILE_COUNT = 3
    const val MIN_VALID_BYTES = 1_000_000L

    fun inspect(
        filesDir: File,
        expectedUrl: String?,
        lifecycle: VoskModelLifecycle,
    ): VoskModelSnapshot {
        val zip = File(filesDir, MODEL_ZIP_FILENAME)
        val urlFile = File(filesDir, MODEL_URL_FILENAME)
        val modelDir = File(filesDir, MODEL_UNZIPPED_DIR)
        val ivector = File(modelDir, IVECTOR_DIR)
        val (fileCount, totalBytes) = directoryStats(modelDir)
        val urlMarker = try {
            urlFile.takeIf { it.isFile }?.readText()?.trim()
        } catch (_: Throwable) {
            null
        }
        val valid = isValidUnpackedModel(modelDir, fileCount, totalBytes, ivector.isDirectory)
        return VoskModelSnapshot(
            filesDir = filesDir.absolutePath,
            modelDir = modelDir.absolutePath,
            dirExists = modelDir.isDirectory,
            fileCount = fileCount,
            totalBytes = totalBytes,
            ivectorExists = ivector.isDirectory,
            zipExists = zip.isFile,
            zipBytes = if (zip.isFile) zip.length() else 0L,
            urlMarker = urlMarker,
            urlMatchesExpected = expectedUrl != null && urlMarker == expectedUrl,
            lifecycle = lifecycle,
            validUnpacked = valid,
        )
    }

    fun isValidUnpackedModel(
        modelDir: File,
        fileCount: Int = directoryStats(modelDir).first,
        totalBytes: Long = directoryStats(modelDir).second,
        ivectorExists: Boolean = File(modelDir, IVECTOR_DIR).isDirectory,
    ): Boolean {
        return modelDir.isDirectory &&
            ivectorExists &&
            fileCount >= MIN_VALID_FILE_COUNT &&
            totalBytes >= MIN_VALID_BYTES
    }

    fun canEnterCommandListening(lifecycle: VoskModelLifecycle): Boolean =
        lifecycle == VoskModelLifecycle.READY

    fun lifecycleFromVoskStateName(stateName: String): VoskModelLifecycle = when (stateName) {
        "NotDownloaded" -> VoskModelLifecycle.MISSING
        "Downloading" -> VoskModelLifecycle.DOWNLOADING
        "Downloaded", "Unzipping" -> VoskModelLifecycle.UNPACKING
        "NotLoaded", "Loading" -> VoskModelLifecycle.LOADING
        "Loaded", "Listening" -> VoskModelLifecycle.READY
        "ErrorDownloading", "ErrorUnzipping", "ErrorLoading", "NotAvailable" ->
            VoskModelLifecycle.ERROR
        else -> VoskModelLifecycle.MISSING
    }

    fun directoryStats(dir: File): Pair<Int, Long> {
        if (!dir.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                count += 1
                bytes += file.length()
            }
        }
        return count to bytes
    }
}
