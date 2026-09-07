package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Pure simulation of partial → endpoint/final finalization decisions.
 */
private object FastPartialSafetySim {
    data class Result(
        val fastPartialHits: List<String>,
        val finalCommand: RoutedCommand?,
        val executionCount: Int,
    )

    fun run(
        partials: List<String>,
        finalText: String,
        endOfSpeechBeforeFinal: Boolean = true,
    ): Result {
        val fastHits = mutableListOf<String>()
        var executed: RoutedCommand? = null
        var count = 0

        fun tryExecute(text: String, allowFast: Boolean) {
            if (count > 0) return
            if (allowFast) {
                val folded = VietnameseTranscript.foldForMatch(text)
                if (!CommandTranscriptNormalizer.isSafeForFastPartial(folded)) return
                fastHits += text
            }
            val match = CarfuCommandRouter.match(text) ?: return
            executed = match
            count += 1
        }

        for (partial in partials) {
            tryExecute(partial, allowFast = true)
        }

        if (endOfSpeechBeforeFinal && count == 0) {
            CommandEndpointLogic.shouldFire(
                lastPartialText = partials.lastOrNull().orEmpty().ifBlank { finalText },
                lastPartialChangeMs = 1_000L,
                endOfSpeechMs = 1_000L,
                nowMs = 2_100L,
                silenceEndpointMs = 1_000L,
                stabilityMs = 150L,
                userSpeechStarted = true,
            ).shouldBeTrue()
            tryExecute(finalText, allowFast = false)
        } else if (!endOfSpeechBeforeFinal) {
            CommandEndpointLogic.shouldFire(
                lastPartialText = partials.lastOrNull().orEmpty(),
                lastPartialChangeMs = 1_000L,
                endOfSpeechMs = 0L,
                nowMs = 3_000L,
                silenceEndpointMs = 1_000L,
                stabilityMs = 150L,
                userSpeechStarted = true,
            ).shouldBeFalse()
        }

        return Result(fastHits, executed, count)
    }
}

class FastPartialPrefixAmbiguityTest : StringSpec({
    "mo smarttube is prefix-ambiguous of mo smarttube beta" {
        CommandTranscriptNormalizer.isPrefixAmbiguous("mo smarttube").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("mo smarttube").shouldBeFalse()
    }

    "mo smarttube beta is complete and may fast-execute" {
        CommandTranscriptNormalizer.isPrefixAmbiguous("mo smarttube beta").shouldBeFalse()
        CommandTranscriptNormalizer.isSafeForFastPartial("mo smarttube beta").shouldBeTrue()
    }

    "navigation never fast-executes" {
        CommandTranscriptNormalizer.isSafeForFastPartial("chi duong den my").shouldBeFalse()
        CommandTranscriptNormalizer.isSafeForFastPartial("chi duong den my dinh").shouldBeFalse()
    }

    "deterministic time and volume may fast-execute when not extendable" {
        CommandTranscriptNormalizer.isSafeForFastPartial("may gio roi").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("tang loa").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("giam loa").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("tang am luong").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("giam am luong").shouldBeTrue()
        CommandTranscriptNormalizer.isSafeForFastPartial("may gio").shouldBeFalse()
        CommandTranscriptNormalizer.isSafeForFastPartial("mo music loop").shouldBeTrue()
    }

    "SmartTube then SmartTube Beta: short form never executes; exactly one execution" {
        var shortFormExecutions = 0
        var totalExecutions = 0
        var lastIntent: CarfuIntent? = null

        fun onPartial(text: String) {
            if (totalExecutions > 0) return
            val folded = VietnameseTranscript.foldForMatch(text)
            if (!CommandTranscriptNormalizer.isSafeForFastPartial(folded)) return
            if (folded == "mo smarttube") shortFormExecutions += 1
            val match = CarfuCommandRouter.match(text) ?: return
            lastIntent = match.intent
            totalExecutions += 1
        }

        fun onEndpointFinal(text: String) {
            if (totalExecutions > 0) return
            val match = CarfuCommandRouter.match(text) ?: return
            lastIntent = match.intent
            totalExecutions += 1
        }

        onPartial("Mở SmartTube")
        onPartial("Mở SmartTube Beta")
        // If Beta fast-executed, done; else endpoint finalizes once.
        if (totalExecutions == 0) {
            onEndpointFinal("Mở SmartTube Beta")
        }

        shortFormExecutions shouldBe 0
        totalExecutions shouldBe 1
        lastIntent shouldBe CarfuIntent.OPEN_SMARTTUBE
    }

    "navigation partials do not execute until completed endpoint destination" {
        val mid = "Chỉ đường đến Mỹ"
        val full = "Chỉ đường đến Mỹ Đình"
        CommandTranscriptNormalizer.isSafeForFastPartial(
            VietnameseTranscript.foldForMatch(mid),
        ).shouldBeFalse()
        CommandTranscriptNormalizer.isSafeForFastPartial(
            VietnameseTranscript.foldForMatch(full),
        ).shouldBeFalse()

        val result = FastPartialSafetySim.run(
            partials = listOf(mid, full),
            finalText = full,
        )
        result.fastPartialHits.shouldBeEmpty()
        result.executionCount shouldBe 1
        result.finalCommand.shouldNotBeNull().let {
            it.intent shouldBe CarfuIntent.NAVIGATE_PLACE
            it.place shouldBe "Mỹ Đình"
        }
    }

    "stalled partials without EndOfSpeech do not finalize endpoint" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "mo smarttube beta",
            lastPartialChangeMs = 500L,
            endOfSpeechMs = 0L,
            nowMs = 5_000L,
            silenceEndpointMs = 1_000L,
            stabilityMs = 150L,
            userSpeechStarted = true,
        ).shouldBeFalse()
    }

    "exactly once across partial then final" {
        CommandSessionOutcome.resetForTests()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "NO_MATCH rescue rejects weak unknown partials" {
        CommandTranscriptNormalizer.isRescueEligible("abc").shouldBeFalse()
        CommandTranscriptNormalizer.isRescueEligible(
            VietnameseTranscript.foldForMatch("Mở SmartTube Beta"),
        ).shouldBeTrue()
        CommandTranscriptNormalizer.isRescueEligible(
            VietnameseTranscript.foldForMatch("Chỉ đường đến Mỹ Đình"),
        ).shouldBeTrue()
        CommandTranscriptNormalizer.isRescueEligible("chi duong den my").shouldBeTrue()
        CommandTranscriptNormalizer.isRescueEligible("chi duong den ").shouldBeFalse()
    }
})
