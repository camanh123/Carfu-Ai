package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.io.input.AsrTestProfile

/**
 * Device-observed SpeechRecognizer forms "smartbook" / "smart book"
 * resolve to SmartTube only inside the existing media provider slot.
 */
class SmartTubeProviderSpeechAliasTest : StringSpec({
    fun u(raw: String) = VietnameseCommandUnderstanding.understand(raw, sessionId = 1L)

    fun assertSmartTube(raw: String) {
        val result = u(raw)
        result.rawTranscript shouldBe raw
        result.intent shouldBe VoiceIntent.PLAY_MEDIA
        result.completeness shouldBe SemanticCompleteness.COMPLETE
        result.executable.shouldBeTrue()
        result.query shouldBe "Đừng Xa Em Đêm Nay"
        result.provider shouldBe "SmartTube"
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "SmartTube")
        MediaProviderExecutor.build(result.query!!, result.provider)
            .shouldBe(MediaProviderExecutor.Result.SmartTubePlayAuto("Đừng Xa Em Đêm Nay"))
    }

    "provider slot smartbook resolves to SmartTube and keeps the title" {
        assertSmartTube("Mở bài Đừng Xa Em Đêm Nay trên smartbook")
    }

    "provider slot smart book resolves to SmartTube" {
        assertSmartTube("Mở bài Đừng Xa Em Đêm Nay trên smart book")
    }

    "canonical SmartTube provider still resolves" {
        assertSmartTube("Mở bài Đừng Xa Em Đêm Nay trên SmartTube")
    }

    "canonical smart tube provider still resolves" {
        assertSmartTube("Mở bài Đừng Xa Em Đêm Nay trên smart tube")
    }

    "YouTube provider slot is unchanged" {
        val result = u("Mở bài Đừng Xa Em Đêm Nay trên YouTube")
        result.query shouldBe "Đừng Xa Em Đêm Nay"
        result.provider shouldBe "YouTube"
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "YouTube")
        MediaProviderExecutor.build(result.query!!, result.provider)
            .shouldBe(MediaProviderExecutor.Result.YouTubePlayAuto("Đừng Xa Em Đêm Nay"))
    }

    "Smartbook inside the title stays the title when YouTube is the provider" {
        val raw = "Mở bài Smartbook trên YouTube"
        val result = u(raw)
        result.rawTranscript shouldBe raw
        result.query shouldBe "Smartbook"
        result.provider shouldBe "YouTube"
        result.command shouldBe CanonicalCommand.PlayMedia("Smartbook", "YouTube")
        result.query!! shouldNotContain "SmartTube"
    }

    "Tìm Smartbook is not rewritten to SmartTube" {
        val raw = "Tìm Smartbook"
        val result = u(raw)
        result.rawTranscript shouldBe raw
        result.normalizedTranscript shouldContain "smartbook"
        result.normalizedTranscript shouldNotContain "smarttube"
        result.provider.shouldBeNull()
        result.command.shouldBeNull()
        (result.command is CanonicalCommand.PlayMedia).shouldBeFalse()
    }

    "Smartbook là gì is not rewritten to SmartTube" {
        val raw = "Smartbook là gì"
        val result = u(raw)
        result.rawTranscript shouldBe raw
        result.normalizedTranscript shouldBe "smartbook la gi"
        result.provider.shouldBeNull()
        result.app.shouldBeNull()
        result.command.shouldBeNull()
    }

    "Mở bài Smartbook keeps Smartbook as the query and does not execute SmartTube" {
        val raw = "Mở bài Smartbook"
        val result = u(raw)
        result.rawTranscript shouldBe raw
        result.query shouldBe "Smartbook"
        result.provider.shouldBeNull()
        result.executable.shouldBeFalse()
        result.command.shouldBeNull()
    }

    "RAW_FINAL keeps smartbook after provider resolution" {
        AsrAbEvidence.resetForTests()
        val raw = "mở bài Đừng Xa Em Đêm Nay trên smartbook"
        AsrAbEvidence.begin(AsrTestProfile.B_SMARTTUBE_STYLE, "profile-b")
        AsrAbEvidence.noteRawFinal(raw).shouldBeTrue()
        val result = u(raw)
        result.provider shouldBe "SmartTube"
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "SmartTube")
        AsrAbEvidence.rawFinalForTests() shouldBe raw
        val section = AsrAbEvidence.copyableSection()
        section shouldContain "RAW_FINAL: $raw"
        section shouldContain "smartbook"
        AsrAbEvidence.resetForTests()
    }

    "smart youtube stays an unresolved media provider and is not SmartTube" {
        val result = u("Mở bài Đừng Xa Em Đêm Nay trên smart youtube")
        result.query shouldBe "Đừng Xa Em Đêm Nay"
        result.provider shouldBe "smart youtube"
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "smart youtube")
        MediaProviderExecutor.isSmartTubeProvider(result.provider).shouldBeFalse()
        MediaProviderExecutor.isYouTubeProvider(result.provider).shouldBeFalse()
        MediaProviderExecutor.build(result.query!!, result.provider)
            .shouldBe(MediaProviderExecutor.Result.Unsupported("unknown_provider:smart youtube"))
    }

    "bare smart youtube is the existing trailing YouTube provider, not SmartTube" {
        val result = u("smart youtube")
        result.command shouldBe CanonicalCommand.PlayMedia("smart", "YouTube")
        MediaProviderExecutor.isSmartTubeProvider(result.provider).shouldBeFalse()
    }

    "Mở smart youtube is trailing YouTube media, not the SmartTube open-app alias" {
        val result = u("Mở smart youtube")
        result.command shouldBe CanonicalCommand.PlayMedia("smart", "YouTube")
        result.provider shouldBe "YouTube"
        MediaProviderExecutor.isSmartTubeProvider(result.provider).shouldBeFalse()
        CommandTranscriptNormalizer.matchAppInOpenDomain("smart youtube")
            ?.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
    }
})
