package org.stypox.dicio.sherpabenchmark.model

import android.content.Context
import org.stypox.dicio.sherpabenchmark.BuildConfig
import org.stypox.dicio.sherpabenchmark.engine.ModelPaths
import java.io.File
import java.security.MessageDigest

class ModelIntegrityException(message: String) : RuntimeException(message)

object ModelInstaller {
    private val required: List<Triple<String, String, Long>> = listOf(
        Triple(BuildConfig.MODEL_ENCODER, BuildConfig.MODEL_ENCODER_SHA256, BuildConfig.MODEL_ENCODER_BYTES),
        Triple(BuildConfig.MODEL_DECODER, BuildConfig.MODEL_DECODER_SHA256, BuildConfig.MODEL_DECODER_BYTES),
        Triple(BuildConfig.MODEL_JOINER, BuildConfig.MODEL_JOINER_SHA256, BuildConfig.MODEL_JOINER_BYTES),
        Triple(BuildConfig.MODEL_TOKENS, BuildConfig.MODEL_TOKENS_SHA256, BuildConfig.MODEL_TOKENS_BYTES),
        Triple(BuildConfig.MODEL_BPE, BuildConfig.MODEL_BPE_SHA256, BuildConfig.MODEL_BPE_BYTES),
    )

    fun installedDir(context: Context): File =
        File(File(context.filesDir, "models"), BuildConfig.MODEL_NAME)

    fun ensureInstalled(context: Context): ModelPaths {
        val destDir = installedDir(context)
        destDir.mkdirs()
        for ((name, sha, bytes) in required) {
            val dest = File(destDir, name)
            if (dest.isFile && dest.length() == bytes && sha256(dest) == sha) {
                continue
            }
            val assetPath = "models/${BuildConfig.MODEL_NAME}/$name"
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            val actualSize = dest.length()
            val actualSha = sha256(dest)
            if (actualSize != bytes || actualSha != sha) {
                dest.delete()
                throw ModelIntegrityException(
                    "Model integrity check failed for $name: size=$actualSize expected=$bytes " +
                        "sha256=$actualSha expected=$sha",
                )
            }
        }
        return ModelPaths(
            encoder = File(destDir, BuildConfig.MODEL_ENCODER).absolutePath,
            decoder = File(destDir, BuildConfig.MODEL_DECODER).absolutePath,
            joiner = File(destDir, BuildConfig.MODEL_JOINER).absolutePath,
            tokens = File(destDir, BuildConfig.MODEL_TOKENS).absolutePath,
            bpe = File(destDir, BuildConfig.MODEL_BPE).absolutePath,
        )
    }

    fun fileSizesBlock(): String = buildString {
        appendLine("${BuildConfig.MODEL_ENCODER}: ${BuildConfig.MODEL_ENCODER_BYTES} bytes")
        appendLine("${BuildConfig.MODEL_DECODER}: ${BuildConfig.MODEL_DECODER_BYTES} bytes")
        appendLine("${BuildConfig.MODEL_JOINER}: ${BuildConfig.MODEL_JOINER_BYTES} bytes")
        appendLine("${BuildConfig.MODEL_TOKENS}: ${BuildConfig.MODEL_TOKENS_BYTES} bytes")
        appendLine("${BuildConfig.MODEL_BPE}: ${BuildConfig.MODEL_BPE_BYTES} bytes")
    }.trimEnd()

    fun fileSha256Block(): String = buildString {
        appendLine("${BuildConfig.MODEL_ENCODER}: ${BuildConfig.MODEL_ENCODER_SHA256}")
        appendLine("${BuildConfig.MODEL_DECODER}: ${BuildConfig.MODEL_DECODER_SHA256}")
        appendLine("${BuildConfig.MODEL_JOINER}: ${BuildConfig.MODEL_JOINER_SHA256}")
        appendLine("${BuildConfig.MODEL_TOKENS}: ${BuildConfig.MODEL_TOKENS_SHA256}")
        appendLine("${BuildConfig.MODEL_BPE}: ${BuildConfig.MODEL_BPE_SHA256}")
        appendLine("archive ${BuildConfig.MODEL_NAME}.tar.bz2: ${BuildConfig.MODEL_ARCHIVE_SHA256}")
    }.trimEnd()

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
