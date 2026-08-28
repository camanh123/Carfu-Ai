package org.stypox.dicio.io.wake.oww

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.wake.WakeAcceptancePolicy
import org.stypox.dicio.io.wake.WakeDevice
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.io.IOException

class OpenWakeWordDevice(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) : WakeDevice {
    private val _state: MutableStateFlow<WakeState>
    override val state: StateFlow<WakeState>

    private val cacheDir: File = appContext.cacheDir
    private val owwFolder = File(appContext.filesDir, "openWakeWord")
    private val melFile = FileToDownload(MEL_URL, File(owwFolder, "melspectrogram.tflite"))
    private val embFile = FileToDownload(EMB_URL, File(owwFolder, "embedding.tflite"))
    private val wakeFile = File(owwFolder, WAKE_MODEL_FILENAME)
    private val userWakeFile = userWakeFile(appContext)
    private val userWakeFileExists = userWakeFile.exists()
    private val bundledModelsInstalled = installBundledModels()
    // The CARFU classifier is bundled in APK assets; only the OWW frontend may fall back to HTTP.
    private val allModelFiles = listOf(melFile, embFile)

    private val audio = FloatArray(OwwModel.MEL_INPUT_COUNT)
    private var model: OwwModel? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        _state = if (hasWakeClassifier() &&
            (bundledModelsInstalled || !allModelFiles.any(FileToDownload::needsToBeDownloaded))
        ) {
            MutableStateFlow(WakeState.NotLoaded)
        } else {
            MutableStateFlow(WakeState.NotDownloaded)
        }
        state = _state
    }

    override fun download() {
        if (bundledModelsInstalled && hasWakeClassifier()) {
            _state.value = WakeState.NotLoaded
            return
        }

        _state.value = WakeState.Downloading(Progress.UNKNOWN)

        scope.launch {
            try {
                owwFolder.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = allModelFiles,
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress ->
                    _state.value = WakeState.Downloading(progress)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Can't download OpenWakeWord model", e)
                _state.value = WakeState.ErrorDownloading(e)
                return@launch
            }

            _state.value = if (hasWakeClassifier()) {
                WakeState.NotLoaded
            } else {
                WakeState.ErrorDownloading(IOException("Bundled CARFU wake model is missing"))
            }
        }
    }

    private fun hasWakeClassifier(): Boolean = userWakeFileExists || wakeFile.exists()

    override fun processFrame(audio16bitPcm: ShortArray): Boolean {
        if (audio16bitPcm.size != OwwModel.MEL_INPUT_COUNT) {
            throw IllegalArgumentException(
                "OwwModel can only process audio frames of ${OwwModel.MEL_INPUT_COUNT} samples"
            )
        }

        if (model == null) {
            if (_state.value.let { it != WakeState.NotLoaded && it !is WakeState.ErrorLoading }) {
                throw IOException("Model has not been downloaded yet")
            }

            try {
                _state.value = WakeState.Loading
                model = OwwModel(
                    melFile.file,
                    embFile.file,
                    if (userWakeFileExists) userWakeFile else wakeFile,
                )
                _state.value = WakeState.Loaded
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load model", t)
                _state.value = WakeState.ErrorLoading(t)
                throw t
            }
        }

        for (i in 0..<OwwModel.MEL_INPUT_COUNT) {
            audio[i] = audio16bitPcm[i].toFloat() / 32768.0f
        }

        val threshold = if (userWakeFileExists) {
            WakeAcceptancePolicy.CUSTOM_WAKE_THRESHOLD
        } else {
            WakeAcceptancePolicy.CARFU_WAKE_THRESHOLD
        }
        val score = model!!.processFrame(audio)
        if (score >= 0.40f) {
            CarfuLog.i(
                "CarfuWake",
                "score=${"%.3f".format(score)} threshold=$threshold " +
                    "range=[${WakeAcceptancePolicy.SCORE_RANGE_MIN}," +
                    "${WakeAcceptancePolicy.SCORE_RANGE_MAX}]",
            )
        }
        return score > threshold
    }

    override fun frameSize(): Int {
        return OwwModel.MEL_INPUT_COUNT
    }

    override fun isOccupyingResources(): Boolean = model != null

    override fun resetDetectionState() {
        model?.resetAccumulators()
    }

    override fun destroy() {
        model?.close()
        model = null
        scope.cancel()
    }

    override fun isHeyDicio(): Boolean = !userWakeFileExists

    /**
     * Copies the bundled CARFU OpenWakeWord models from APK assets into app storage so
     * [OwwModel] can load them as files. Returns false if assets are missing (download fallback
     * for the OWW frontend only).
     */
    private fun installBundledModels(): Boolean {
        return try {
            owwFolder.mkdirs()
            copyAsset("melspectrogram.tflite", melFile.file)
            copyAsset("embedding_model.tflite", embFile.file)
            if (!userWakeFileExists) {
                copyAsset(WAKE_MODEL_FILENAME, wakeFile)
            }
            try {
                melFile.lastDownloadedUrlFile.writeText(MEL_URL)
                embFile.lastDownloadedUrlFile.writeText(EMB_URL)
            } catch (_: IOException) {
                // url markers are best-effort; files themselves were copied
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Bundled CARFU OpenWakeWord assets not installed", e)
            false
        }
    }

    private fun copyAsset(fileName: String, dest: File) {
        appContext.assets.open("$ASSET_DIR/$fileName").use { input ->
            val partial = File(dest.parentFile, dest.name + ".part")
            partial.outputStream().use { output ->
                input.copyTo(output)
            }
            dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        }
    }

    companion object {
        val TAG = OpenWakeWordDevice::class.simpleName
        const val ASSET_DIR = "openwakeword"
        const val MEL_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite"
        const val EMB_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite"
        const val WAKE_MODEL_FILENAME = "carfu.tflite"

        private fun userWakeFile(context: Context) =
            File(context.filesDir, "openWakeWord/userwake.tflite")

        suspend fun addUserWakeFile(context: Context, source: Uri) {
            // Use a partial file to ensure atomicity
            val userWakeFile = userWakeFile(context)
            withContext(Dispatchers.IO) {
                val partialFile = File.createTempFile(userWakeFile.name, ".part", context.cacheDir)
                val inputStream = context.contentResolver.openInputStream(source)
                if (inputStream != null) {
                    inputStream.use { source ->
                        partialFile.outputStream().use {
                            source.copyTo(it)
                        }
                    }

                    // Remove the previous file, if it already exists
                    userWakeFile.delete()
                    userWakeFile.parentFile?.mkdirs()
                    val renameOk = partialFile.renameTo(userWakeFile)
                    if (!renameOk) {
                        throw IOException("Cannot rename partial file $partialFile to actual file $userWakeFile")
                    }
                }
            }
        }

        suspend fun removeUserWakeFile(context: Context) {
            withContext(Dispatchers.IO) {
                userWakeFile(context).delete()
            }
        }
    }
}
