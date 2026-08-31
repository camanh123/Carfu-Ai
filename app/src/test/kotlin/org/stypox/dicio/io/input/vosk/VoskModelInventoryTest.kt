package org.stypox.dicio.io.input.vosk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class VoskModelInventoryTest : StringSpec({
    "missing filesDir is MISSING and not valid" {
        val dir = createTempDirectory("vosk-missing").toFile()
        try {
            val snap = VoskModelInventory.inspect(
                filesDir = dir,
                expectedUrl = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip",
                lifecycle = VoskModelLifecycle.MISSING,
            )
            snap.dirExists.shouldBeFalse()
            snap.ivectorExists.shouldBeFalse()
            snap.fileCount shouldBe 0
            snap.totalBytes shouldBe 0L
            snap.validUnpacked.shouldBeFalse()
            VoskModelInventory.canEnterCommandListening(snap.lifecycle).shouldBeFalse()
        } finally {
            dir.deleteRecursively()
        }
    }

    "unpacked model is valid only with ivector, files and bytes" {
        val dir = createTempDirectory("vosk-ready").toFile()
        try {
            val model = File(dir, VoskModelInventory.MODEL_UNZIPPED_DIR)
            val ivector = File(model, VoskModelInventory.IVECTOR_DIR)
            ivector.mkdirs()
            File(ivector, "final.dubm").writeBytes(ByteArray(600_000))
            File(model, "am/final.mdl").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(600_000))
            File(model, "conf/model.conf").apply { parentFile?.mkdirs() }.writeText("ok")
            File(dir, VoskModelInventory.MODEL_URL_FILENAME)
                .writeText("https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip")

            val snap = VoskModelInventory.inspect(
                filesDir = dir,
                expectedUrl = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip",
                lifecycle = VoskModelLifecycle.LOADING,
            )
            snap.dirExists.shouldBeTrue()
            snap.ivectorExists.shouldBeTrue()
            snap.validUnpacked.shouldBeTrue()
            snap.urlMatchesExpected.shouldBeTrue()
            snap.fileCount shouldBe 3
            VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.LOADING).shouldBeFalse()
            VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.READY).shouldBeTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    "tiny or incomplete unpack is invalid" {
        val dir = createTempDirectory("vosk-tiny").toFile()
        try {
            val model = File(dir, VoskModelInventory.MODEL_UNZIPPED_DIR)
            model.mkdirs()
            File(model, "readme").writeText("no")
            VoskModelInventory.isValidUnpackedModel(model).shouldBeFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
})
