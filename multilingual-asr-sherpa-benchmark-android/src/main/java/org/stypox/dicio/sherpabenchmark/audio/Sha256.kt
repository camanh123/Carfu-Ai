package org.stypox.dicio.sherpabenchmark.audio

import java.security.MessageDigest

object Sha256 {
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
