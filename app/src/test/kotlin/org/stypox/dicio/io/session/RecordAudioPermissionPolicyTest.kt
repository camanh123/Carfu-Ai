package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class RecordAudioPermissionPolicyTest : StringSpec({
    "granted runtime reaches SpeechRecognizer.startListening" {
        val snap = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = true,
            shouldShowRationale = false,
            previouslyRequested = true,
        )
        snap.manifestLabel() shouldBe RecordAudioPermissionPolicy.MANIFEST_DECLARED
        snap.runtimeLabel() shouldBe RecordAudioPermissionPolicy.RUNTIME_GRANTED
        snap.mayStartSpeechRecognizer().shouldBeTrue()
        snap.mayMarkProductListening().shouldBeTrue()
        snap.shouldShowSystemPermissionDialog().shouldBeFalse()
        RecordAudioPermissionPolicy.reachesSpeechRecognizerStartListening(snap.runtime)
            .shouldBeTrue()
    }

    "not-requested must not fake LISTENING or start SpeechRecognizer" {
        val snap = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = false,
            shouldShowRationale = false,
            previouslyRequested = false,
        )
        snap.runtimeLabel() shouldBe RecordAudioPermissionPolicy.RUNTIME_NOT_REQUESTED
        snap.mayStartSpeechRecognizer().shouldBeFalse()
        snap.mayMarkProductListening().shouldBeFalse()
        snap.shouldShowSystemPermissionDialog().shouldBeTrue()
        RecordAudioPermissionPolicy.reachesSpeechRecognizerStartListening(snap.runtime)
            .shouldBeFalse()
    }

    "denied (rationale true) does not start recognizer and should request again" {
        val snap = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = false,
            shouldShowRationale = true,
            previouslyRequested = true,
        )
        snap.runtimeLabel() shouldBe RecordAudioPermissionPolicy.RUNTIME_DENIED
        snap.mayStartSpeechRecognizer().shouldBeFalse()
        snap.mayMarkProductListening().shouldBeFalse()
        snap.shouldShowSystemPermissionDialog().shouldBeTrue()
    }

    "denied after previous request without rationale still does not start recognizer" {
        val snap = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = false,
            shouldShowRationale = false,
            previouslyRequested = true,
        )
        snap.runtimeLabel() shouldBe RecordAudioPermissionPolicy.RUNTIME_DENIED
        snap.mayStartSpeechRecognizer().shouldBeFalse()
        snap.mayMarkProductListening().shouldBeFalse()
    }

    "classify never collapses granted and missing into one state" {
        RecordAudioPermissionPolicy.classify(
            granted = true,
            shouldShowRationale = null,
            previouslyRequested = false,
        ) shouldBe RecordAudioPermissionPolicy.Runtime.GRANTED
        RecordAudioPermissionPolicy.classify(
            granted = false,
            shouldShowRationale = null,
            previouslyRequested = false,
        ) shouldBe RecordAudioPermissionPolicy.Runtime.NOT_REQUESTED
        RecordAudioPermissionPolicy.classify(
            granted = false,
            shouldShowRationale = true,
            previouslyRequested = false,
        ) shouldBe RecordAudioPermissionPolicy.Runtime.DENIED
    }

    "manifest-missing is reported separately from runtime grant" {
        val snap = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = false,
            granted = false,
            shouldShowRationale = false,
            previouslyRequested = false,
        )
        snap.manifestLabel() shouldBe RecordAudioPermissionPolicy.MANIFEST_MISSING
        snap.runtimeLabel() shouldBe RecordAudioPermissionPolicy.RUNTIME_NOT_REQUESTED
    }

    "Phase 4.2 SR re-arm remains 0 and granted path is the only startListening gate" {
        org.stypox.dicio.io.input.CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        RecordAudioPermissionPolicy.mayStartSpeechRecognizer(
            RecordAudioPermissionPolicy.Runtime.GRANTED,
        ).shouldBeTrue()
        RecordAudioPermissionPolicy.mayStartSpeechRecognizer(
            RecordAudioPermissionPolicy.Runtime.NOT_REQUESTED,
        ).shouldBeFalse()
        RecordAudioPermissionPolicy.mayStartSpeechRecognizer(
            RecordAudioPermissionPolicy.Runtime.DENIED,
        ).shouldBeFalse()
    }
})
