package org.stypox.dicio.asrbenchmark.model

import android.content.Context
import org.stypox.dicio.asrbenchmark.BuildConfig
import java.io.File
import java.security.MessageDigest

class ModelIntegrityException(message: String) : RuntimeException(message)

object ModelInstaller {
    val ASSET_PATH: String get() = "models/${BuildConfig.MODEL_NAME}"

    fun installedFile(context: Context): File =
        File(File(context.filesDir, "models"), BuildConfig.MODEL_NAME)

    fun ensureInstalled(context: Context): File {
        val dest = installedFile(context)
        if (dest.isFile && dest.length() == BuildConfig.MODEL_BYTES && sha256(dest) == BuildConfig.MODEL_SHA256) {
            return dest
        }
        dest.parentFile?.mkdirs()
        context.assets.open(ASSET_PATH).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        val actualSize = dest.length()
        val actualSha = sha256(dest)
        if (actualSize != BuildConfig.MODEL_BYTES || actualSha != BuildConfig.MODEL_SHA256) {
            dest.delete()
            throw ModelIntegrityException(
                "Model integrity check failed: size=$actualSize expected=${BuildConfig.MODEL_BYTES} " +
                    "sha256=$actualSha expected=${BuildConfig.MODEL_SHA256}",
            )
        }
        return dest
    }

    fun hexSha256(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02x".format(b) }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hexSha256(digest.digest())
    }
}
