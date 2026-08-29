package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class CommandEndpointPolicyTest : StringSpec({
    "empty Vosk endpoints during the post-TTS grace window do not stop listening" {
        val clock = mutableListOf(1_000L)
        val policy = CommandEndpointPolicy(
            silencesAllowed = 5,
            graceMs = 2_500L,
            clockMs = { clock[0] },
        )
        policy.onListeningStarted()
        repeat(8) {
            policy.onEmptyOrWeakResult() shouldBe EmptyEndpointAction.IGNORE
        }
        policy.remaining shouldBe 5
    }

    "after grace, five empty endpoints stop listening" {
        val clock = mutableListOf(1_000L)
        val policy = CommandEndpointPolicy(
            silencesAllowed = 5,
            graceMs = 2_500L,
            clockMs = { clock[0] },
        )
        policy.onListeningStarted()
        clock[0] = 3_600L
        repeat(4) {
            policy.onEmptyOrWeakResult() shouldBe EmptyEndpointAction.COUNT
        }
        policy.onEmptyOrWeakResult() shouldBe EmptyEndpointAction.STOP
    }

    "a Vietnamese partial resets the silence counter" {
        val clock = mutableListOf(5_000L)
        val policy = CommandEndpointPolicy(
            silencesAllowed = 3,
            graceMs = 0L,
            clockMs = { clock[0] },
        )
        policy.onListeningStarted()
        policy.onEmptyOrWeakResult() shouldBe EmptyEndpointAction.COUNT
        policy.remaining shouldBe 2
        policy.onNonEmptyPartial()
        policy.remaining shouldBe 3
        policy.onEmptyOrWeakResult() shouldBe EmptyEndpointAction.COUNT
        policy.remaining shouldBe 2
    }

    "grace duration is long enough for a spoken Vietnamese command after ack" {
        CommandSession.COMMAND_ENDPOINT_GRACE_MS.shouldBeGreaterThan(1_000L)
    }
})
