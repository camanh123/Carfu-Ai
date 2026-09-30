package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.io.session.AsrAbEvidence
import org.stypox.dicio.io.session.CommandSessionPhase

class AsrAbProfileTest : StringSpec({
    beforeEach {
        AsrAbEvidence.resetForTests()
        AsrRecognizerLease.resetForTests()
        AsrProductPhaseMirror.resetForTests()
    }

    "profile A reproduces the existing VIA RecognizerIntent config" {
        val callingPackage = "org.stypox.dicio"
        val spec = AsrRecognizerIntentProfiles.specFor(
            AsrTestProfile.A_VIA_CURRENT,
            callingPackage,
        )
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        spec.action shouldBe "android.speech.action.RECOGNIZE_SPEECH"
        spec.action shouldBe cfg.action
        spec.extras shouldBe listOf(
            AsrIntentExtra.Str(
                "android.speech.extra.LANGUAGE_MODEL",
                CommandRecognitionPolicy.LANGUAGE_MODEL_FREE_FORM,
            ),
            AsrIntentExtra.Str(
                "android.speech.extra.LANGUAGE",
                CommandRecognitionPolicy.LANGUAGE_VI_VN,
            ),
            AsrIntentExtra.Str(
                "android.speech.extra.LANGUAGE_PREFERENCE",
                CommandRecognitionPolicy.LANGUAGE_VI_VN,
            ),
            AsrIntentExtra.Bool(
                "android.speech.extra.PARTIAL_RESULTS",
                CommandRecognitionPolicy.PARTIAL_RESULTS,
            ),
            AsrIntentExtra.IntVal(
                "android.speech.extra.MAX_RESULTS",
                CommandRecognitionPolicy.MAX_RESULTS,
            ),
            AsrIntentExtra.Bool(
                "android.speech.extra.PREFER_OFFLINE",
                CommandRecognitionPolicy.PREFER_OFFLINE,
            ),
            AsrIntentExtra.Str("calling_package", callingPackage),
        )
        spec.explicitLanguage() shouldBe "vi-VN"
        spec.forcesExplicitLanguage().shouldBeTrue()
        cfg.language shouldBe "vi-VN"
        cfg.languageModel shouldBe "free_form"
        cfg.partialResults.shouldBeTrue()
        cfg.maxResults shouldBe 3
        cfg.preferOffline.shouldBeFalse()
    }

    "profile B reproduces the source-proven SmartTube SearchBar config" {
        val spec = AsrRecognizerIntentProfiles.specFor(
            AsrTestProfile.B_SMARTTUBE_STYLE,
            "org.stypox.dicio",
        )
        spec.action shouldBe "android.speech.action.RECOGNIZE_SPEECH"
        spec.extras shouldBe listOf(
            AsrIntentExtra.Str("android.speech.extra.LANGUAGE_MODEL", "free_form"),
            AsrIntentExtra.Bool("android.speech.extra.PARTIAL_RESULTS", true),
        )
    }

    "profile B does not explicitly force vi-VN" {
        val spec = AsrRecognizerIntentProfiles.specFor(
            AsrTestProfile.B_SMARTTUBE_STYLE,
            "org.stypox.dicio",
        )
        spec.forcesExplicitLanguage().shouldBeFalse()
        spec.explicitLanguage() shouldBe null
        spec.extras.map { it.key } shouldNotContainKeys listOf(
            "android.speech.extra.LANGUAGE",
            "android.speech.extra.LANGUAGE_PREFERENCE",
        )
        AsrRecognizerIntentProfiles.describe(spec) shouldNotContain "vi-VN"
    }

    "profile selection persists and defaults to A" {
        val store = MemoryAsrProfileStore()
        AsrProfileSelection.read(store) shouldBe AsrTestProfile.A_VIA_CURRENT
        val selected = AsrProfileSelection.trySelect(
            profile = AsrTestProfile.B_SMARTTUBE_STYLE,
            uiPhase = CommandSessionPhase.IDLE_WAKE,
            mirroredPhase = CommandSessionPhase.IDLE_WAKE,
            recognizerOwnsMicrophone = false,
            store = store,
        )
        selected.shouldBeTrue()
        AsrProfileSelection.read(store) shouldBe AsrTestProfile.B_SMARTTUBE_STYLE
        AsrTestProfile.fromPersisted(store.readRaw()) shouldBe AsrTestProfile.B_SMARTTUBE_STYLE
        AsrTestProfile.fromPersisted(null) shouldBe AsrTestProfile.A_VIA_CURRENT
        AsrTestProfile.fromPersisted("not-a-profile") shouldBe AsrTestProfile.A_VIA_CURRENT
    }

    "cannot switch profile during an active session" {
        val store = MemoryAsrProfileStore()
        store.writeRaw(AsrTestProfile.A_VIA_CURRENT.persistedValue)
        AsrProfileSelection.trySelect(
            profile = AsrTestProfile.B_SMARTTUBE_STYLE,
            uiPhase = CommandSessionPhase.COMMAND_LISTENING,
            mirroredPhase = CommandSessionPhase.IDLE_WAKE,
            recognizerOwnsMicrophone = false,
            store = store,
        ).shouldBeFalse()
        AsrProfileSelection.trySelect(
            profile = AsrTestProfile.B_SMARTTUBE_STYLE,
            uiPhase = CommandSessionPhase.IDLE_WAKE,
            mirroredPhase = CommandSessionPhase.PROCESSING,
            recognizerOwnsMicrophone = false,
            store = store,
        ).shouldBeFalse()
        AsrProfileSelection.trySelect(
            profile = AsrTestProfile.B_SMARTTUBE_STYLE,
            uiPhase = CommandSessionPhase.IDLE_WAKE,
            mirroredPhase = CommandSessionPhase.IDLE_WAKE,
            recognizerOwnsMicrophone = true,
            store = store,
        ).shouldBeFalse()
        AsrProfileSelection.read(store) shouldBe AsrTestProfile.A_VIA_CURRENT
        AsrProfileSessionRules.canSwitch(
            recognizerOwnsMicrophone = false,
            productPhase = CommandSessionPhase.IDLE_WAKE,
        ).shouldBeTrue()
    }

    "only one recognizer session is active" {
        AsrProfileSessionRules.allowsConcurrentRecognizers().shouldBeFalse()
        AsrProfileSessionRules.occupancyAfterRetiredArm() shouldBe 1
        val lease = AsrRecognizerLease
        lease.markOwned()
        lease.markOwned()
        lease.activeCount() shouldBe 1
        lease.ownsMicrophone().shouldBeTrue()
        lease.markIdle()
        lease.activeCount() shouldBe 0
        lease.ownsMicrophone().shouldBeFalse()
    }

    "A and B share the same downstream consumer" {
        val pipelineA = AsrRecognizerIntentProfiles.downstreamPipeline(AsrTestProfile.A_VIA_CURRENT)
        val pipelineB = AsrRecognizerIntentProfiles.downstreamPipeline(AsrTestProfile.B_SMARTTUBE_STYLE)
        pipelineA shouldBe pipelineB
        AsrRecognizerIntentProfiles.downstreamConsumer(AsrTestProfile.A_VIA_CURRENT) shouldBe
            AsrRecognizerIntentProfiles.downstreamConsumer(AsrTestProfile.B_SMARTTUBE_STYLE)
        AsrRecognizerIntentProfiles.downstreamConsumer(AsrTestProfile.A_VIA_CURRENT) shouldBe
            "org.stypox.dicio.eval.SkillEvaluator"
        val a = AsrRecognizerIntentProfiles.specFor(AsrTestProfile.A_VIA_CURRENT, "pkg")
        val b = AsrRecognizerIntentProfiles.specFor(AsrTestProfile.B_SMARTTUBE_STYLE, "pkg")
        a.action shouldBe b.action
    }

    "raw final is delivered exactly once" {
        AsrAbEvidence.begin(AsrTestProfile.A_VIA_CURRENT, "config")
        AsrAbEvidence.noteRawPartial("xin chào")
        AsrAbEvidence.noteRawFinal("Mở See You Again trên YouTube").shouldBeTrue()
        AsrAbEvidence.noteRawFinal("stale second final").shouldBeFalse()
        AsrAbEvidence.rawFinalCountForTests() shouldBe 1
        AsrAbEvidence.duplicateRawFinalRejectedForTests() shouldBe 1
        AsrAbEvidence.rawFinalForTests() shouldBe "Mở See You Again trên YouTube"
        val section = AsrAbEvidence.copyableSection()
        section.contains("RAW_FINAL: Mở See You Again trên YouTube").shouldBeTrue()
        section.contains("stale second final").shouldBeFalse()
    }

    "stale callback rejection is preserved for both profiles" {
        val productMs = org.stypox.dicio.io.session.VoiceSessionManager.NO_SPEECH_TIMEOUT_MS
        AsrTestProfile.entries.forEach { _ ->
            SpeechRecognizerSessionPolicy.onPartial(
                generationMatches = false,
                textBlank = false,
            ).shouldBeFalse()
            SpeechRecognizerSessionPolicy.onResults(
                utteranceCount = 1,
                generationMatches = false,
                sawSpeechOrPartial = true,
                elapsedMs = 40L,
                productTimeoutMs = productMs,
            ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
            SpeechRecognizerSessionPolicy.onError(
                code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
                generationMatches = false,
                sawReady = true,
                sawSpeechOrPartial = true,
                elapsedMs = 40L,
                productTimeoutMs = productMs,
            ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        }
    }
})

private class MemoryAsrProfileStore : AsrProfilePersistence {
    private var raw: String? = null
    override fun readRaw(): String? = raw
    override fun writeRaw(value: String) {
        raw = value
    }
}

private infix fun List<String>.shouldNotContainKeys(forbidden: List<String>) {
    val present = filter { it in forbidden }
    present.shouldBeEmpty()
}
