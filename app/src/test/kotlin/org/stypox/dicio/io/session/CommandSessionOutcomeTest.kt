package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CommandSessionOutcomeTest : StringSpec({
    beforeTest { CommandSessionOutcome.resetForTests() }

    "only one terminal outcome is accepted per session" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "reset opens a new session" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.UNSUPPORTED).shouldBeTrue()
        CommandSessionOutcome.reset()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeTrue()
    }

    "EXECUTED terminal blocks later NO_SPEECH" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "UNSUPPORTED terminal blocks later NO_SPEECH" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.UNSUPPORTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }

    "first NO_SPEECH terminal blocks duplicate NO_SPEECH" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }
})
