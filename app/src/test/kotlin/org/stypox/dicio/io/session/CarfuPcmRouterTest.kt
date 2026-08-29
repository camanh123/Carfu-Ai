package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CarfuPcmRouterTest : StringSpec({
    "IDLE_WAKE routes to OpenWakeWord" {
        CarfuPcmRouter.route(CommandSessionPhase.IDLE_WAKE) shouldBe
            CarfuPcmRoute.OPEN_WAKE_WORD
    }

    "ACKNOWLEDGING discards PCM" {
        CarfuPcmRouter.route(CommandSessionPhase.ACKNOWLEDGING) shouldBe
            CarfuPcmRoute.DISCARD
        CarfuPcmRouter.route(CommandSessionPhase.WAKE_DETECTED) shouldBe
            CarfuPcmRoute.DISCARD
        CarfuPcmRouter.route(CommandSessionPhase.PROCESSING) shouldBe
            CarfuPcmRoute.DISCARD
        CarfuPcmRouter.route(CommandSessionPhase.RESPONDING) shouldBe
            CarfuPcmRoute.DISCARD
    }

    "COMMAND_LISTENING routes to the recognizer even while paused" {
        CarfuPcmRouter.route(
            phase = CommandSessionPhase.COMMAND_LISTENING,
            interactionPaused = true,
            cooldownActive = true,
        ) shouldBe CarfuPcmRoute.COMMAND_RECOGNIZER
    }

    "RETURNING_TO_WAKE resets then routes to OpenWakeWord" {
        CarfuPcmRouter.shouldResetWakeDetectors(
            CarfuPcmRoute.DISCARD,
            CarfuPcmRoute.OPEN_WAKE_WORD,
        ).shouldBeTrue()
        CarfuPcmRouter.route(CommandSessionPhase.RETURNING_TO_WAKE) shouldBe
            CarfuPcmRoute.OPEN_WAKE_WORD
    }

    "cooldown and interaction pause discard idle PCM so TTS cannot self-trigger" {
        CarfuPcmRouter.route(
            phase = CommandSessionPhase.IDLE_WAKE,
            cooldownActive = true,
        ) shouldBe CarfuPcmRoute.DISCARD
        CarfuPcmRouter.route(
            phase = CommandSessionPhase.IDLE_WAKE,
            interactionPaused = true,
        ) shouldBe CarfuPcmRoute.DISCARD
    }

    "recognizer state is reset at command boundaries" {
        CarfuPcmRouter.shouldResetRecognizer(
            CarfuPcmRoute.OPEN_WAKE_WORD,
            CarfuPcmRoute.COMMAND_RECOGNIZER,
        ).shouldBeTrue()
        CarfuPcmRouter.shouldResetRecognizer(
            CarfuPcmRoute.COMMAND_RECOGNIZER,
            CarfuPcmRoute.DISCARD,
        ).shouldBeTrue()
        CarfuPcmRouter.shouldResetRecognizer(
            CarfuPcmRoute.OPEN_WAKE_WORD,
            CarfuPcmRoute.DISCARD,
        ).shouldBeFalse()
    }
})
