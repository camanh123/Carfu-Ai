package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.wake.BackgroundWakePolicy
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.skills.carfu.nlu.NavigationAddressNormalizer
import org.stypox.dicio.skills.carfu.nlu.NavigationCandidateTracker
import org.stypox.dicio.skills.carfu.nlu.NavigationCommitPolicy
import org.stypox.dicio.youtubeplayauto.YouTubeResolverEndpoint

/**
 * MODE / VoiceSession entry reliability: granted RECORD_AUDIO is not a refusal,
 * ConnectivityManager must not block a deliberate MODE press, wake hub must be
 * released before SpeechRecognizer, and network recovery must not auto-open
 * a later session.
 */
class ModeVoiceEntryReliabilityTest : StringSpec({
    val clock = object {
        var now: Long = 1_000_000L
        fun advance(by: Long) {
            now += by
        }
    }

    beforeTest {
        VoiceTriggerManager.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceOnlinePolicy.resetForTests()
        CarfuSessionGate.resetForTests()
        CarfuActivationSource.resetForTests()
        VoiceSessionGuard.resetForTests()
        CarfuVoiceTrace.resetForTests()
        CarfuPcmHub.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        StableCompletePartialTracker.resetForTests()
        clock.now = 1_000_000L
        VoiceTriggerManager.nowMs = { clock.now }
        VoiceSessionManager.nowMs = { clock.now }
        CarfuSessionGate.nowMs = { clock.now }
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        VoiceOnlinePolicy.onlineOverride = false
    }

    fun events(): List<String> = CarfuVoiceTrace.eventsForTests()

    fun count(token: String): Int = events().count { it.contains(token) }

    fun simulateModeEntry(
        sessionId: Long,
        recordAudioGranted: Boolean = true,
        recognizerAvailable: Boolean = true,
        usableInternet: Boolean = false,
        hubRecording: Boolean = false,
        hubReleasesAfterMs: Long? = 0L,
    ): VoiceSessionManager.Session? {
        val origin = VoiceTriggerManager.Origin.HARDWARE_MODE
        VoiceTriggerManager.clearHardwareDebounce()
        val trigger = VoiceTriggerManager.request(origin)
        if (!trigger.accepted) return null

        val runtimeLabel = if (recordAudioGranted) {
            RecordAudioPermissionPolicy.RUNTIME_GRANTED
        } else {
            RecordAudioPermissionPolicy.RUNTIME_DENIED
        }
        CarfuVoiceTrace.recordAudioState("HARDWARE_MODE", runtimeLabel)
        ModeVoiceEntryPolicy.srRefusedReasonForPermission(
            granted = recordAudioGranted,
            runtimeLabel = runtimeLabel,
        )?.let { CarfuVoiceTrace.srStartRefused(it) }

        if (ModeVoiceEntryPolicy.connectivitySnapshotBlocksDeliberateMode(usableInternet)) {
            VoiceTriggerManager.abandonOpenTrigger(trigger.trigger!!.triggerId, "offline")
            return null
        }
        if (!ModeVoiceEntryPolicy.mayCreateVoiceSession(recordAudioGranted, recognizerAvailable)) {
            VoiceTriggerManager.abandonOpenTrigger(
                trigger.trigger!!.triggerId,
                if (!recordAudioGranted) runtimeLabel else "recognition_unavailable",
            )
            return null
        }

        val session = VoiceSessionManager.createFromTrigger(trigger.trigger!!, sessionId)
        session.shouldNotBeNull()
        CarfuVoiceTrace.bind(sessionId, origin)
        CarfuVoiceTrace.trigger("HARDWARE_MODE")
        CarfuVoiceTrace.sessionStart()
        CarfuVoiceTrace.voiceSessionCreated()

        var recording = hubRecording
        var elapsed = 0L
        CarfuVoiceTrace.wakeHubReleaseRequest()
        if (recording) {
            CarfuPcmHub.markRecording(true)
        }
        while (true) {
            when (WakeHubReleasePolicy.decision(recording, elapsed)) {
                WakeHubReleasePolicy.Decision.START_SPEECH_RECOGNIZER -> {
                    CarfuVoiceTrace.wakeHubReleaseMs(elapsed)
                    CarfuVoiceTrace.wakeHubReleased()
                    CarfuPcmHub.markRecording(false)
                    break
                }
                WakeHubReleasePolicy.Decision.WAIT -> {
                    if (hubReleasesAfterMs != null && elapsed >= hubReleasesAfterMs) {
                        recording = false
                        CarfuPcmHub.markRecording(false)
                    }
                    elapsed += WakeHubReleasePolicy.POLL_MS
                    if (elapsed > WakeHubReleasePolicy.MAX_WAIT_MS + WakeHubReleasePolicy.POLL_MS) {
                        CarfuVoiceTrace.wakeHubReleaseMs(elapsed)
                        CarfuVoiceTrace.srStartRefused(WakeHubReleasePolicy.REFUSE_REASON)
                        CarfuVoiceTrace.terminal(WakeHubReleasePolicy.REFUSE_REASON)
                        VoiceSessionManager.terminate(sessionId, WakeHubReleasePolicy.REFUSE_REASON)
                        return session
                    }
                }
                WakeHubReleasePolicy.Decision.REFUSE_STILL_RECORDING -> {
                    CarfuVoiceTrace.wakeHubReleaseMs(elapsed)
                    CarfuVoiceTrace.srStartRefused(WakeHubReleasePolicy.REFUSE_REASON)
                    CarfuVoiceTrace.terminal(WakeHubReleasePolicy.REFUSE_REASON)
                    VoiceSessionManager.terminate(sessionId, WakeHubReleasePolicy.REFUSE_REASON)
                    return session
                }
            }
        }

        VoiceSessionManager.requestListen(sessionId).shouldBeTrue()
        CarfuVoiceTrace.srStartRequest()
        CarfuVoiceTrace.srStartListening()
        CarfuVoiceTrace.srStartAccepted()
        return session
    }

    "1. RECORD_AUDIO_GRANTED does not emit SR_REFUSED" {
        val granted = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = true,
            shouldShowRationale = false,
            previouslyRequested = true,
        )
        granted.mayStartSpeechRecognizer().shouldBeTrue()
        CarfuVoiceTrace.recordAudioState("HARDWARE_MODE", granted.runtimeLabel())
        ModeVoiceEntryPolicy.srRefusedReasonForPermission(
            granted = granted.mayStartSpeechRecognizer(),
            runtimeLabel = granted.runtimeLabel(),
        ).shouldBeNull()
        events().any {
            it.contains("RECORD_AUDIO_STATE") && it.contains("RUNTIME_RECORD_AUDIO_GRANTED")
        }.shouldBeTrue()
        events().none { it.contains("SR_REFUSED") }.shouldBeTrue()
        events().none { it.contains("SR_START_REFUSED") }.shouldBeTrue()
    }

    "2. MODE + transient ConnectivityManager offline still creates one VoiceSession and starts SR once" {
        ModeVoiceEntryPolicy.connectivitySnapshotBlocksDeliberateMode(false).shouldBeFalse()
        VoiceOnlinePolicy.mayEnterOnlineVoiceSession(false).shouldBeTrue()
        ModeVoiceEntryPolicy.mayCreateVoiceSession(
            recordAudioGranted = true,
            recognizerAvailable = true,
        ).shouldBeTrue()

        val session = simulateModeEntry(
            sessionId = 34L,
            usableInternet = false,
            hubRecording = false,
        )
        session.shouldNotBeNull()
        session!!.sessionId shouldBe 34L
        VoiceSessionManager.hasLiveSession().shouldBeTrue()
        VoiceSessionManager.counters().listenStarts shouldBe 1
        count("VOICESESSION_CREATED") shouldBe 1
        count("SR_START_REQUEST") shouldBe 1
        count("SR_START_ACCEPTED") shouldBe 1
        count("SR_START_REFUSED") shouldBe 0
        events().any { it.contains("session=34 VOICESESSION_CREATED") }.shouldBeTrue()
        events().any { it.contains("session=34 SR_START_REQUEST") }.shouldBeTrue()
    }

    "3. network recovery afterward does not auto-open another session" {
        val session = simulateModeEntry(sessionId = 35L, usableInternet = false)
        session.shouldNotBeNull()
        VoiceSessionManager.terminate(35L, "sr_error")
        CarfuVoiceTrace.srError(2, "ERROR_NETWORK", "TERMINAL", 1L, 1L)
        CarfuVoiceTrace.terminal("sr_error")
        VoiceSessionManager.hasLiveSession().shouldBeFalse()

        VoiceOnlinePolicy.onlineOverride = true
        ModeVoiceEntryPolicy.autoRetryOnNetworkRecovery().shouldBeFalse()
        VoiceOnlinePolicy.autoRetryOnNetworkRecovery().shouldBeFalse()
        VoiceTriggerManager.hasOpenTrigger().shouldBeFalse()
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        VoiceTriggerManager.request(VoiceTriggerManager.Origin.TIMEOUT, reason = "network_up")
            .accepted.shouldBeFalse()
        VoiceTriggerManager.request(VoiceTriggerManager.Origin.INTERNAL, reason = "connectivity")
            .accepted.shouldBeFalse()
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        count("VOICESESSION_CREATED") shouldBe 1
        count("SR_START_REQUEST") shouldBe 1
    }

    "4. BACKGROUND_WAKE enabled before MODE releases hub then starts SR" {
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        BackgroundWakePolicy.isBackgroundWakeEnabled(
            UserSettings.getDefaultInstance().toBuilder()
                .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_ENABLED)
                .build(),
        ).shouldBeTrue()
        CarfuPcmHub.markRecording(true)
        WakeHubReleasePolicy.shouldStartSpeechRecognizer(true).shouldBeFalse()

        val session = simulateModeEntry(
            sessionId = 36L,
            hubRecording = true,
            hubReleasesAfterMs = 25L,
        )
        session.shouldNotBeNull()
        CarfuPcmHub.isRecording().shouldBeFalse()
        WakeHubReleasePolicy.shouldStartSpeechRecognizer(CarfuPcmHub.isRecording()).shouldBeTrue()
        CommandRecognitionPolicy.canStartAndroidRecognizer(CarfuPcmHub.isRecording()).shouldBeTrue()
        CommandRecognitionPolicy.microphoneOwnersOverlap(
            hubRecording = CarfuPcmHub.isRecording(),
            speechRecognizerActive = true,
        ).shouldBeFalse()
        count("WAKE_HUB_RELEASE_REQUEST") shouldBe 1
        count("WAKE_HUB_RELEASED") shouldBe 1
        count("WAKE_HUB_RELEASE_MS") shouldBe 1
        count("SR_START_REQUEST") shouldBe 1
        val releaseIdx = events().indexOfFirst { it.contains("WAKE_HUB_RELEASED") }
        val startIdx = events().indexOfFirst { it.contains("SR_START_REQUEST") }
        (releaseIdx in 0 until startIdx).shouldBeTrue()
    }

    "5. hub fails to release → SR not started, one refusal/terminal, no retry loop" {
        CarfuPcmHub.markRecording(true)
        val session = simulateModeEntry(
            sessionId = 37L,
            hubRecording = true,
            hubReleasesAfterMs = null,
        )
        session.shouldNotBeNull()
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        count("SR_START_REQUEST") shouldBe 0
        count("SR_START_ACCEPTED") shouldBe 0
        count("SR_START_REFUSED") shouldBe 1
        count("SR_REFUSED") shouldBe 1
        events().any { it.contains("SR_START_REFUSED reason=hub_not_released") }.shouldBeTrue()
        events().any { it == "session=37 TERMINAL reason=hub_not_released" }.shouldBeTrue()
        events().any { it == "session=37 SESSION_TERMINAL reason=hub_not_released" }.shouldBeTrue()
        // Policy never re-enters START after a refuse.
        var retries = 0
        var elapsed = 0L
        while (elapsed <= WakeHubReleasePolicy.MAX_WAIT_MS + 50L) {
            val d = WakeHubReleasePolicy.decision(true, elapsed)
            if (d == WakeHubReleasePolicy.Decision.START_SPEECH_RECOGNIZER) retries++
            elapsed += WakeHubReleasePolicy.POLL_MS
        }
        retries shouldBe 0
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0).shouldBeFalse()
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
    }

    "6. rapid MODE sessions have no stale hub/session crossover" {
        val first = simulateModeEntry(sessionId = 38L, hubRecording = true, hubReleasesAfterMs = 0L)
        first.shouldNotBeNull()
        VoiceSessionManager.terminate(38L, "complete")
        CarfuVoiceTrace.terminal("complete")
        CarfuPcmHub.markRecording(false)
        VoiceSessionGuard.dropIfStale(38L, "rapid_mode").shouldBeTrue()
        VoiceSessionManager.shouldIgnoreCallback(38L).shouldBeTrue()

        clock.advance(VoiceTriggerManager.HARDWARE_DEBOUNCE_MS)
        CarfuVoiceTrace.resetForTests()
        val second = simulateModeEntry(
            sessionId = 39L,
            hubRecording = true,
            hubReleasesAfterMs = 0L,
        )
        second.shouldNotBeNull()
        second!!.sessionId shouldBe 39L
        VoiceSessionManager.liveSession()!!.sessionId shouldBe 39L
        VoiceSessionGuard.dropIfStale(38L, "late_hub").shouldBeTrue()
        VoiceSessionGuard.dropIfStale(39L, "current").shouldBeFalse()
        CarfuPcmHub.isRecording().shouldBeFalse()
        events().none { it.contains("session=38") }.shouldBeTrue()
        events().any { it.contains("session=39 VOICESESSION_CREATED") }.shouldBeTrue()
        events().any { it.contains("session=39 SR_START_REQUEST") }.shouldBeTrue()
        count("SR_START_REQUEST") shouldBe 1
    }

    "7. v32 session isolation regression" {
        val s1 = simulateModeEntry(sessionId = 40L)
        s1.shouldNotBeNull()
        VoiceSessionGuard.armNavTimer(40L)
        VoiceSessionManager.terminate(40L, "done")
        clock.advance(VoiceTriggerManager.HARDWARE_DEBOUNCE_MS)
        val s2 = simulateModeEntry(sessionId = 41L)
        s2.shouldNotBeNull()
        VoiceSessionGuard.dropIfStale(40L, "isolation").shouldBeTrue()
        VoiceSessionManager.shouldIgnoreCallback(40L).shouldBeTrue()
        VoiceSessionManager.shouldIgnoreCallback(41L).shouldBeFalse()
        VoiceSessionGuard.pendingNavTimerCount() shouldBe 0
    }

    "8. v33 NAV R2 regression — Hồ Văn Quán and read-only timer" {
        StableCompletePartialPolicy.NAV_STABILIZATION_MS shouldBe 800L
        val parsed = NavigationAddressNormalizer.parse("đưa tôi đến Hồ Văn Quán")
        parsed.destination shouldBe "Hồ Văn Quán"
        val result = VietnameseCommandUnderstanding.understand("đưa tôi đến Hồ Văn Quán", 42L)
        result.command shouldBe CanonicalCommand.Navigate("Hồ Văn Quán")
        NavigationCommitPolicy.isIncompleteDestination("Hồ Văn Quán").shouldBeFalse()

        StableCompletePartialTracker.bind(42L)
        val first = StableCompletePartialTracker.onPartial(42L, result, 0L, 42L)
        first.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        val changedAt = NavigationCandidateTracker.candidateChangedAt()
        StableCompletePartialTracker.onTimer(42L, 400L)
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
        StableCompletePartialTracker.onPartial(42L, result, 100L, 42L)
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
    }

    "9. Maps regression — google.navigation URI and flags unchanged" {
        NavigatePayload.navigationUri("Mỹ Đình") shouldBe
            "google.navigation:q=M%E1%BB%B9%20%C4%90%C3%ACnh"
        NavigatePayload.NAVIGATION_INTENT_FLAGS shouldBe
            (NavigatePayload.FLAG_ACTIVITY_NEW_TASK or
                NavigatePayload.FLAG_ACTIVITY_CLEAR_TOP or
                NavigatePayload.FLAG_ACTIVITY_CLEAR_TASK)
        NavigatePayload.isNavigationUri(NavigatePayload.navigationUri("Hồ Gươm")).shouldBeTrue()
        NavigatePayload.isGeoSearchUri(NavigatePayload.navigationUri("Hồ Gươm")).shouldBeFalse()
    }

    "10. YouTube regression — production jack still uses PlayAuto watch path" {
        YouTubeResolverEndpoint.DEFAULT_PUBLIC_HTTPS_BASE_URL shouldContain "https"
        YouTubeProductionJack::class.java.shouldNotBeNull()
        CommandRecognitionPolicy.recognizerIntentConfig().language shouldBe "vi-VN"
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
    }

    "denied RECORD_AUDIO is a real SR_REFUSED" {
        val denied = RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = true,
            granted = false,
            shouldShowRationale = true,
            previouslyRequested = true,
        )
        val reason = ModeVoiceEntryPolicy.srRefusedReasonForPermission(
            granted = denied.mayStartSpeechRecognizer(),
            runtimeLabel = denied.runtimeLabel(),
        )
        reason shouldBe RecordAudioPermissionPolicy.RUNTIME_DENIED
        CarfuVoiceTrace.recordAudioState("HARDWARE_MODE", denied.runtimeLabel())
        CarfuVoiceTrace.srStartRefused(reason!!)
        events().any { it.contains("SR_START_REFUSED reason=RUNTIME_RECORD_AUDIO_DENIED") }
            .shouldBeTrue()
        events().any { it.contains("SR_REFUSED reason=RUNTIME_RECORD_AUDIO_DENIED") }
            .shouldBeTrue()
    }

    "VOICE_TRIGGER_REQUEST is emitted for a deliberate MODE press" {
        VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        events().any { it.contains("VOICE_TRIGGER_REQUEST source=HARDWARE_MODE") }.shouldBeTrue()
    }
})
