package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

private class RecordingConsumer : FallbackPcmConsumer {
    val frames = mutableListOf<ShortArray>()
    override fun onPcm(samples: ShortArray, length: Int) {
        frames.add(samples.copyOf(length))
    }
    override fun onReadError(error: Exception) = Unit
}

class CarfuPcmHubTest : StringSpec({
    beforeTest {
        CarfuPcmHub.resetForTests()
    }

    afterTest {
        CarfuPcmHub.resetForTests()
    }

    "command PCM is buffered until the Vosk consumer attaches" {
        CarfuPcmHub.markRecording(true)
        val reused = ShortArray(8) { 7 }
        CarfuPcmHub.feedCommand(reused, reused.size)
        reused[0] = 99
        CarfuPcmHub.pendingFrameCount() shouldBe 1
        CarfuPcmHub.hasCommandConsumer().shouldBeFalse()
        CarfuPcmHub.droppedWithoutConsumerCount().shouldBeGreaterThan(0)

        val consumer = RecordingConsumer()
        CarfuPcmHub.attachCommandConsumer(consumer).shouldBeTrue()
        consumer.frames.size shouldBe 1
        consumer.frames[0][0] shouldBe 7.toShort()
        CarfuPcmHub.pendingFrameCount() shouldBe 0
        CarfuPcmHub.hasCommandConsumer().shouldBeTrue()
    }

    "live hub frames reach Vosk after attach without a second recorder" {
        CarfuPcmHub.markRecording(true)
        val consumer = RecordingConsumer()
        CarfuPcmHub.attachCommandConsumer(consumer).shouldBeTrue()
        CarfuPcmHub.feedCommand(shortArrayOf(1, 2, 3), 3)
        consumer.frames.single().toList() shouldBe listOf<Short>(1, 2, 3)
    }
})
