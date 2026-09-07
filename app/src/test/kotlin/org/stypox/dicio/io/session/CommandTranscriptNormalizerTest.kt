package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CommandTranscriptNormalizerTest : StringSpec({
    "detects OPEN_APP domain from mo prefix" {
        CommandTranscriptNormalizer.detectDomain("mo smart tube") shouldBe
            CommandTranscriptNormalizer.Domain.OPEN_APP
        CommandTranscriptNormalizer.detectDomain("mo zalo") shouldBe
            CommandTranscriptNormalizer.Domain.OPEN_APP
    }

    "detects navigation without treating destination as app name" {
        CommandTranscriptNormalizer.detectDomain("chi duong den my dinh") shouldBe
            CommandTranscriptNormalizer.Domain.NAVIGATE
        CommandTranscriptNormalizer.matchAppInOpenDomain("my dinh").shouldBeNull()
    }

    "SmartTube phonetic variants match OPEN_APP domain" {
        val variants = listOf(
            "smart tube",
            "smart youtube",
            "smart took",
            "smart tool",
            "smarttube beta",
            "mo smart tool",
            "mo smart took",
        )
        for (variant in variants) {
            val folded = VietnameseTranscript.foldForMatch(variant)
            CommandTranscriptNormalizer.detectDomain(folded) shouldBe
                CommandTranscriptNormalizer.Domain.OPEN_APP
            CommandTranscriptNormalizer.matchAppInOpenDomain(folded)
                .shouldNotBeNull()
                .intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        }
    }

    "music look phonetic STT still opens MusicLoop via alias fuzzy match" {
        val folded = VietnameseTranscript.foldForMatch("mở music look")
        CommandTranscriptNormalizer.matchAppInOpenDomain(folded)
            .shouldNotBeNull()
            .intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("mở music look")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
    }

    "MusicLoop phonetic variants match" {
        val variants = listOf(
            "music loop",
            "music lup",
            "mo music lup",
            "mo may phat nhac",
        )
        for (variant in variants) {
            val folded = VietnameseTranscript.foldForMatch(variant)
            CommandTranscriptNormalizer.matchAppInOpenDomain(folded)
                .shouldNotBeNull()
                .intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        }
    }

    "volume commands detect correct domain" {
        CommandTranscriptNormalizer.detectDomain("tang am luong") shouldBe
            CommandTranscriptNormalizer.Domain.VOLUME_UP
        CommandTranscriptNormalizer.detectDomain("giam am luong") shouldBe
            CommandTranscriptNormalizer.Domain.VOLUME_DOWN
        CommandTranscriptNormalizer.detectDomain("tang loa") shouldBe
            CommandTranscriptNormalizer.Domain.VOLUME_UP
    }

    "token similarity is high for close phonetic variants" {
        CommandTranscriptNormalizer.tokenSimilarity("smart tool", "smart tube")
            .shouldBeGreaterThan(0.69f)
        CommandTranscriptNormalizer.tokenSimilarity("music lup", "music loop")
            .shouldBeGreaterThan(0.7f)
    }
})

class CommandSpeechEndpointTest : StringSpec({
    "fires endpoint after EndOfSpeech plus silence and stable transcript" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "may gio roi",
            lastPartialChangeMs = 1_200L,
            endOfSpeechMs = 1_200L,
            nowMs = 2_300L,
            silenceEndpointMs = 1_000L,
            stabilityMs = 150L,
            userSpeechStarted = true,
        ) shouldBe true
    }

    "does not fire endpoint while transcript is still changing" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "mo smarttube",
            lastPartialChangeMs = 2_200L,
            endOfSpeechMs = 1_200L,
            nowMs = 2_300L,
            silenceEndpointMs = 1_000L,
            stabilityMs = 150L,
            userSpeechStarted = true,
        ) shouldBe false
    }

    "does not fire from stalled partials without EndOfSpeech" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "may gio roi",
            lastPartialChangeMs = 500L,
            endOfSpeechMs = 0L,
            nowMs = 5_000L,
            silenceEndpointMs = 1_000L,
            stabilityMs = 150L,
            userSpeechStarted = true,
        ) shouldBe false
    }

    "does not fire before user speech evidence" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "may gio roi",
            lastPartialChangeMs = 500L,
            endOfSpeechMs = 500L,
            nowMs = 2_000L,
            silenceEndpointMs = 1_000L,
            stabilityMs = 150L,
            userSpeechStarted = false,
        ) shouldBe false
    }
})

class RealTimeCommandRouterTest : StringSpec({
    "domain-aware router matches SmartTube malformed STT variants" {
        CarfuCommandRouter.match("smart tool")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("mo smart youtube")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("smarttube beta")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
    }

    "domain-aware router matches MusicLoop variants" {
        CarfuCommandRouter.match("music lup")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("mo may phat nhac")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
    }

    "volume commands route reliably" {
        CarfuCommandRouter.match("tang loa")!!.intent shouldBe CarfuIntent.VOLUME_UP
        CarfuCommandRouter.match("giam loa")!!.intent shouldBe CarfuIntent.VOLUME_DOWN
        CarfuCommandRouter.match("Tăng âm lượng")!!.intent shouldBe CarfuIntent.VOLUME_UP
        CarfuCommandRouter.match("Giảm âm lượng")!!.intent shouldBe CarfuIntent.VOLUME_DOWN
        CarfuCommandRouter.match("tăng volume")!!.intent shouldBe CarfuIntent.VOLUME_UP
        CarfuCommandRouter.match("giảm volume")!!.intent shouldBe CarfuIntent.VOLUME_DOWN
    }

    "navigation preserves arbitrary Vietnamese destinations" {
        CarfuCommandRouter.match("chi duong den Benh vien Bach Mai")!!.let {
            it.intent shouldBe CarfuIntent.NAVIGATE_PLACE
            it.place shouldBe "Benh vien Bach Mai"
        }
        CarfuCommandRouter.match("chi duong den My Dinh")!!.place shouldBe "My Dinh"
    }

    "matchBest prefers domain-valid secondary candidate" {
        val best = CarfuCommandRouter.matchBest(
            listOf(
                "smart tool" to 0.45f,
                "mo smarttube" to 0.90f,
            ),
        )
        best!!.candidateIndex shouldBe 1
        best.command.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
    }

    "matchBest rejects domain-inconsistent winner" {
        val best = CarfuCommandRouter.matchBest(
            listOf(
                "tang am luong" to 0.95f,
                "mo smarttube" to 0.40f,
            ),
        )
        best!!.command.intent shouldBe CarfuIntent.VOLUME_UP
    }
})

class CommandSessionOutcomeLateCallbackTest : StringSpec({
    beforeTest { CommandSessionOutcome.resetForTests() }

    "EXECUTED blocks late NO_SPEECH after transcript rescue path" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBe(true)
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBe(false)
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "transcript followed by NO_MATCH does not reopen terminal" {
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBe(true)
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBe(false)
    }
})
