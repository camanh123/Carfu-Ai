package org.stypox.dicio.io.wake

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * JVM unit tests must not instantiate OpenWakeWordDevice (it loads TFLite from
 * Android assets). Check the bundled files on disk, which is what APK packaging uses.
 */
class CarfuWakeAssetsTest : StringSpec({
    "bundled CARFU classifier and OpenWakeWord frontend are present" {
        val assetsDir = modulePath("src/main/assets/openwakeword")
        for (name in listOf("carfu.tflite", "melspectrogram.tflite", "embedding_model.tflite")) {
            val file = File(assetsDir, name)
            file.isFile shouldBe true
            file.length() shouldBeGreaterThan 0L
        }
    }

    "vietnamese sentence templates used after wake-word STT are present" {
        val sentencesDir = modulePath("src/main/sentences/vi")
        for (name in listOf(
            "volume.yml",
            "open.yml",
            "media.yml",
            "navigation.yml",
            "current_time.yml",
        )) {
            File(sentencesDir, name).isFile shouldBe true
        }
    }
})

private fun modulePath(relative: String): File {
    return listOf(File(relative), File("app/$relative"))
        .firstOrNull { it.isDirectory }
        ?: error("Missing module path $relative (cwd=${File(".").canonicalPath})")
}
