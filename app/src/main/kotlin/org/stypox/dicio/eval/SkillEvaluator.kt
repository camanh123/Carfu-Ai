package org.stypox.dicio.eval

import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.io.assist.CarfuAssistIntents
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import androidx.datastore.core.DataStore
import org.stypox.dicio.io.session.AudioCaptureConfig
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuCommandRouter
import org.stypox.dicio.io.session.CarfuLatencyLog
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CarfuPcmHub
import org.stypox.dicio.io.session.CarfuSessionGate
import org.stypox.dicio.io.session.CommandSessionOutcome
import org.stypox.dicio.io.session.CommandPcmStats
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.CanonicalActionGate
import org.stypox.dicio.io.session.CanonicalCommand
import org.stypox.dicio.io.session.CanonicalCommandExecutor
import org.stypox.dicio.io.session.CommandTranscriptNormalizer
import org.stypox.dicio.io.session.RoutedCommand
import org.stypox.dicio.io.session.RoutedMatch
import org.stypox.dicio.io.session.SemanticCompleteness
import org.stypox.dicio.io.session.SessionCommandDecision
import org.stypox.dicio.io.session.UnderstandingResult
import org.stypox.dicio.io.session.VietnameseCommandUnderstanding
import org.stypox.dicio.io.session.VietnameseTranscript
import org.stypox.dicio.io.session.VoiceOnlinePolicy
import org.stypox.dicio.io.session.VoiceSessionManager
import org.stypox.dicio.io.session.VoiceTriggerManager
import org.stypox.dicio.io.session.toCommandUnderstanding
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.skills.carfu.AndroidCarfuSkillPlatform
import org.stypox.dicio.skills.carfu.CarfuSpeechOutput
import org.stypox.dicio.skills.carfu.CarfuVietnameseSkillExecutor
import org.stypox.dicio.skills.carfu.SkillExecutionResult
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Singleton

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)

    /**
     * V2-CORE-1: OpenWakeWord ACCEPT is passive. Must not start ACK / command capture.
     */
    fun onWakeWordDetected()

    /**
     * Steering MODE / system Assist → [VoiceTriggerManager] HARDWARE_MODE → one VoiceSession.
     */
    fun onHardwareButtonDetected()

    /**
     * UI microphone / MODE → same [VoiceTriggerManager] path as hardware (UI_MODE).
     */
    fun onUiModeDetected() {}

    /**
     * Cancel an in-flight automatic (WAKE_WORD) session. A manual MODE session is left
     * to finish once. Default no-op for tests/fakes.
     */
    fun cancelActiveSession(reason: String) {}
}

class SkillEvaluatorImpl(
    private val skillContext: SkillContextInternal,
    private val skillHandler: SkillHandler,
    private val sttInputDevice: SttInputDeviceWrapper,
    private val commandSession: CommandSession,
    private val wakeDevice: WakeDeviceWrapper,
    userSettings: DataStore<UserSettings>,
) : SkillEvaluator {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val wakeSessionActive = AtomicBoolean(false)
    private val sessionHadTranscript = AtomicBoolean(false)
    private val sessionBestCandidates = AtomicReference<List<Pair<String, Float>>>(emptyList())
    private val platform = AndroidCarfuSkillPlatform(skillContext.android, userSettings)
    private val skillExecutor = CarfuVietnameseSkillExecutor(platform)
    private val canonicalExecutor = CanonicalCommandExecutor(platform)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceWatchSessionId: Long = 0L
    private val silenceWatchRunnable = Runnable {
        val sid = silenceWatchSessionId
        if (sid == 0L) return@Runnable
        if (!VoiceSessionManager.shouldSilentExit(sid)) return@Runnable
        scope.launch {
            handleSilentNoSpeech("product_silence_${VoiceSessionManager.NO_SPEECH_TIMEOUT_MS}ms")
        }
    }

    private val skillRanker: SkillRanker
        get() = skillHandler.skillRanker.value

    private val _state = MutableStateFlow(
        InteractionLog(
            interactions = listOf(),
            pendingQuestion = null,
        )
    )
    override val state: StateFlow<InteractionLog> = _state

    // must be kept up to date even when the activity is recreated, for this reason it is `var`
    override var permissionRequester: suspend (List<Permission>) -> Boolean = { false }

    override fun processInputEvent(event: InputEvent) {
        val sidAtReceive = commandSession.ui.value.sessionId
        scope.launch {
            if (VoiceSessionManager.shouldIgnoreCallback(sidAtReceive) ||
                !CarfuSessionGate.isCurrent(sidAtReceive)
            ) {
                CarfuLatencyLog.logSessionEvent(
                    "INPUT_EVENT_IGNORED",
                    "staleSession=$sidAtReceive event=${event::class.simpleName}",
                )
                return@launch
            }
            if (!wakeSessionActive.get()) {
                CarfuLatencyLog.logSessionEvent(
                    "INPUT_EVENT_IGNORED",
                    "inactiveSession=$sidAtReceive event=${event::class.simpleName}",
                )
                return@launch
            }
            suspendProcessInputEvent(event)
        }
    }

    override fun onWakeWordDetected() {
        // V2-CORE-1: OpenWakeWord ACCEPT is passive — log only, no command session.
        VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.WAKE_WORD,
            reason = "oww_accept_passive",
        )
        CarfuLog.i(
            CommandSession.TAG,
            "WAKE_ACCEPT_PASSIVE v2_core1 no_ack no_command_listener",
        )
        CarfuLatencyLog.logSessionEvent("WAKE_ACCEPT_PASSIVE", "v2_core1")
    }

    override fun onHardwareButtonDetected() {
        val triggerResult = VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.HARDWARE_MODE,
            reason = "hardware_mode",
        )
        if (triggerResult.decision == VoiceTriggerManager.Decision.CANCEL_CURRENT) {
            cancelUserInitiatedSession("mode_toggle_cancel")
            return
        }
        if (!triggerResult.accepted) return
        beginExternalSession(
            trigger = triggerResult.trigger!!,
            reason = "hardware_button",
            resumeWakeIfBeginFails = false,
        )
    }

    override fun onUiModeDetected() {
        val triggerResult = VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.UI_MODE,
            reason = "ui_mode",
        )
        if (triggerResult.decision == VoiceTriggerManager.Decision.CANCEL_CURRENT) {
            cancelUserInitiatedSession("ui_toggle_cancel")
            return
        }
        if (!triggerResult.accepted) return
        beginExternalSession(
            trigger = triggerResult.trigger!!,
            reason = "ui_mode",
            resumeWakeIfBeginFails = false,
        )
    }

    override fun cancelActiveSession(reason: String) {
        val origin = CarfuSessionGate.activeOrigin
        if (origin != null && origin != CarfuSessionGate.Origin.WAKE_WORD) {
            CarfuLog.i(
                CommandSession.TAG,
                "SESSION_CANCEL_SKIPPED origin=$origin reason=$reason",
            )
            return
        }
        val sid = CarfuSessionGate.cancel(reason)
        sttInputDevice.stopListening()
        try {
            skillContext.speechOutputDevice.stopSpeaking()
        } catch (_: Throwable) {
        }
        if (wakeSessionActive.compareAndSet(true, false) || sid != 0L) {
            if (sid != 0L) {
                VoiceSessionManager.terminate(sid, reason)
            }
            commandSession.endSession(reason)
            CarfuSessionGate.onSessionFinished(
                sessionId = sid,
                hadTranscript = false,
                origin = origin ?: CarfuSessionGate.Origin.WAKE_WORD,
            )
            WakeService.onlineCommandFinished()
        }
        WakeService.holdIdleWithoutWake()
    }

    /** MODE/UI toggle-off: cancel the live user session silently (no failure TTS). */
    private fun cancelUserInitiatedSession(reason: String) {
        val origin = CarfuSessionGate.activeOrigin
            ?: CarfuSessionGate.fromActivation(commandSession.activationOrigin)
        val sid = CarfuSessionGate.cancel(reason, onlyOrigin = null)
        cancelSilenceWatch()
        sttInputDevice.stopListening()
        try {
            skillContext.speechOutputDevice.stopSpeaking()
        } catch (_: Throwable) {
        }
        VoiceTriggerManager.clearHardwareDebounce()
        val endSid = when {
            sid != 0L -> sid
            else -> VoiceSessionManager.liveSession()?.sessionId
                ?: commandSession.ui.value.sessionId
        }
        if (endSid != 0L) {
            SessionCommandDecision.markCancelled(endSid)
            CanonicalActionGate.markCancelled(endSid)
        }
        wakeSessionActive.set(false)
        if (endSid != 0L) {
            VoiceSessionManager.terminate(endSid, reason)
        }
        commandSession.endSession(reason)
        CarfuSessionGate.onSessionFinished(
            sessionId = endSid,
            hadTranscript = sessionHadTranscript.getAndSet(false),
            origin = origin,
        )
        WakeService.onlineCommandFinished()
        if (CarfuSessionGate.backgroundWakeEnabled) {
            WakeService.resumeAfterInteraction(false)
        } else {
            WakeService.holdIdleWithoutWake()
        }
        CarfuLog.i(CommandSession.TAG, "USER_SESSION_CANCELLED reason=$reason origin=$origin")
        CarfuLatencyLog.logSessionEvent("MODE_TOGGLE_CANCEL", "reason=$reason")
    }

    private fun beginExternalSession(
        trigger: VoiceTriggerManager.Trigger,
        reason: String,
        resumeWakeIfBeginFails: Boolean,
    ) {
        val origin = VoiceTriggerManager.toActivationKind(trigger.origin)
            ?: run {
                VoiceTriggerManager.abandonOpenTrigger(trigger.triggerId, "bad_origin")
                return
            }
        val androidOnline = sttInputDevice.usesAndroidOnlineEngine()
        // Online-first: do not enter a broken listen loop without Internet.
        if (androidOnline &&
            !VoiceOnlinePolicy.mayEnterOnlineVoiceSession(
                VoiceOnlinePolicy.isUsableInternet(skillContext.android),
            )
        ) {
            VoiceTriggerManager.abandonOpenTrigger(trigger.triggerId, "offline")
            speakOfflineNeedInternet()
            return
        }
        if (!androidOnline) {
            sttInputDevice.ensureModelPipeline()
        }
        val modelReady = if (androidOnline) true else sttInputDevice.isRecognizerReady()
        val gateOrigin = CarfuSessionGate.fromActivation(origin)
        val result = CarfuSessionGate.requestStart(
            origin = gateOrigin,
            phase = commandSession.phase,
            modelReady = modelReady,
            startSession = {
                when (commandSession.phase) {
                    CommandSessionPhase.IDLE_WAKE -> {
                        if (!commandSession.tryBeginWakeSession(origin)) 0L
                        else commandSession.ui.value.sessionId
                    }
                    CommandSessionPhase.WAKE_DETECTED -> commandSession.ui.value.sessionId
                    else -> 0L
                }
            },
        )
        if (!result.accepted) {
            VoiceTriggerManager.abandonOpenTrigger(trigger.triggerId, result.decision.name)
            if (resumeWakeIfBeginFails &&
                result.decision != CarfuSessionGate.Decision.REJECTED_WAKE_OFF &&
                CarfuSessionGate.backgroundWakeEnabled
            ) {
                WakeService.resumeAfterInteraction()
            }
            return
        }
        val v2Session = VoiceSessionManager.createFromTrigger(trigger, result.sessionId)
        if (v2Session == null) {
            VoiceTriggerManager.abandonOpenTrigger(trigger.triggerId, "v2_bind_failed")
            CarfuSessionGate.onSessionFinished(
                sessionId = result.sessionId,
                hadTranscript = false,
                origin = gateOrigin,
            )
            commandSession.endSession("v2_bind_failed")
            return
        }
        if (!wakeSessionActive.compareAndSet(false, true)) {
            CarfuLog.i(CommandSession.TAG, "WAKE_CALLBACK_DUPLICATE origin=$origin ignored")
            VoiceSessionManager.terminate(result.sessionId, "duplicate_active")
            return
        }
        sessionHadTranscript.set(false)
        sessionBestCandidates.set(emptyList())
        SessionCommandDecision.bindSession(result.sessionId)
        CanonicalActionGate.bind(result.sessionId)
        CommandSessionOutcome.reset()
        CarfuLatencyLog.bindSession(result.sessionId)
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SESSION_ACCEPTED)
        when (origin) {
            CarfuActivationSource.Kind.AUTOMATIC_WAKE -> CarfuActivationSource.markAutomaticWake()
            CarfuActivationSource.Kind.MANUAL_MIC -> CarfuActivationSource.markManualMic()
            CarfuActivationSource.Kind.HARDWARE_BUTTON -> CarfuActivationSource.markHardwareButton()
        }
        // Stop any prior TTS (e.g. offline/action reply) so it cannot block mic startup.
        try {
            skillContext.speechOutputDevice.stopSpeaking()
        } catch (_: Throwable) {
        }
        sttInputDevice.stopListening()
        val sid = result.sessionId
        if (androidOnline) {
            WakeService.releaseHubForOnlineCommand()
            wakeDevice.resetDetectionState()
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.HUB_RELEASED)
            CarfuLog.i(
                CommandSession.TAG,
                "V2_SESSION session=$sid triggerId=${trigger.triggerId} " +
                    "origin=$gateOrigin engine=ANDROID_ONLINE hubReleased=true no_mode_ack=true",
            )
            scope.launch {
                startCommandListening(reason, sid, androidOnline = true)
            }
            return
        }
        if (origin == CarfuActivationSource.Kind.HARDWARE_BUTTON ||
            origin == CarfuActivationSource.Kind.MANUAL_MIC
        ) {
            if (origin == CarfuActivationSource.Kind.HARDWARE_BUTTON) {
                WakeService.pauseForHardwareButton()
            } else {
                WakeService.pauseForInteraction()
            }
            wakeDevice.resetDetectionState()
            CarfuLog.i(
                CommandSession.TAG,
                "V2_SESSION session=${result.sessionId} triggerId=${trigger.triggerId} " +
                    "origin=$gateOrigin sharedHub=true engine=VOSK_LEGACY no_mode_ack=true",
            )
        } else {
            WakeService.pauseForInteraction()
            wakeDevice.resetDetectionState()
        }
        scope.launch {
            if (!sttInputDevice.isRecognizerReady()) {
                if (origin == CarfuActivationSource.Kind.HARDWARE_BUTTON ||
                    origin == CarfuActivationSource.Kind.MANUAL_MIC
                ) {
                    withContext(Dispatchers.Main) {
                        skillContext.speechOutputDevice.speak(
                            skillContext.android.getString(R.string.carfu_vosk_downloading_tts),
                        )
                    }
                }
                val ready = awaitRecognizerReady(sid, 180_000L)
                if (!ready || !CarfuSessionGate.isCurrent(sid) || !wakeSessionActive.get()) {
                    endWakeSession("model_unavailable")
                    return@launch
                }
            }
            if (!CarfuSessionGate.isCurrent(sid) || !wakeSessionActive.get()) {
                endWakeSession("cancelled_before_listen")
                return@launch
            }
            if (!sttInputDevice.isRecognizerReady()) {
                endWakeSession("stt_not_ready")
                return@launch
            }
            startCommandListening(reason, sid, androidOnline = false)
        }
    }

    private fun speakOfflineNeedInternet() {
        runOnMain {
            skillContext.speechOutputDevice.stopSpeaking()
            skillContext.speechOutputDevice.speak(
                skillContext.android.getString(R.string.carfu_need_internet_service),
            )
            CarfuLatencyLog.logSessionEvent("OFFLINE_GATE", "no_session")
            CarfuLog.i(CommandSession.TAG, "OFFLINE_GATE tts=need_internet no_command_session")
        }
    }

    private fun cancelSilenceWatch() {
        mainHandler.removeCallbacks(silenceWatchRunnable)
        silenceWatchSessionId = 0L
    }

    private fun armSilenceWatch(sessionId: Long) {
        cancelSilenceWatch()
        silenceWatchSessionId = sessionId
        mainHandler.postDelayed(
            silenceWatchRunnable,
            VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private suspend fun awaitRecognizerReady(sessionId: Long, timeoutMs: Long): Boolean {
        if (sttInputDevice.isRecognizerReady()) return true
        var waited = 0L
        while (waited < timeoutMs) {
            if (!CarfuSessionGate.isCurrent(sessionId) || !wakeSessionActive.get()) return false
            if (sttInputDevice.isRecognizerReady()) return true
            when (sttInputDevice.uiState.value) {
                is SttState.ErrorDownloading,
                is SttState.ErrorUnzipping,
                is SttState.ErrorLoading,
                SttState.NotAvailable -> return false
                else -> {}
            }
            delay(200L)
            waited += 200L
        }
        return sttInputDevice.isRecognizerReady()
    }

    private suspend fun startCommandListening(
        reason: String,
        sessionId: Long,
        androidOnline: Boolean = sttInputDevice.usesAndroidOnlineEngine(),
    ) {
        // V2: no MODE ACK / echo-guard handoff — arm listen immediately.
        if (!CarfuSessionGate.isCurrent(sessionId) ||
            !wakeSessionActive.get() ||
            VoiceSessionManager.shouldIgnoreCallback(sessionId) ||
            !commandSession.canStartCommandRecognition()
        ) {
            if (wakeSessionActive.get()) {
                endWakeSession("stale_session_$reason")
            }
            return
        }
        if (androidOnline && !sttInputDevice.isRecognizerReady()) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_UNAVAILABLE no_silent_vosk_fallback")
            withContext(Dispatchers.Main) {
                skillContext.speechOutputDevice.stopSpeaking()
                skillContext.speechOutputDevice.speak(
                    skillContext.android.getString(R.string.carfu_stt_android_unavailable),
                )
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    endWakeSession("android_stt_unavailable")
                }
            }
            return
        }
        if (!VoiceSessionManager.requestListen(sessionId)) {
            endWakeSession("listen_already_armed")
            return
        }
        if (androidOnline) {
            if (CarfuPcmHub.isRecording()) {
                WakeService.releaseHubForOnlineCommand()
            }
            if (!CommandRecognitionPolicy.canStartAndroidRecognizer(CarfuPcmHub.isRecording())) {
                CarfuLog.e(CommandSession.TAG, "ANDROID_SR_BLOCKED hub_recording=true")
                endWakeSession("hub_not_released")
                return
            }
            val started = withContext(Dispatchers.Main) {
                sttInputDevice.stopListening()
                sttInputDevice.tryLoad(::processInputEvent)
            }
            if (!started) {
                CarfuLog.e(CommandSession.TAG, "android_stt_start_failed reason=$reason")
                endWakeSession("android_stt_not_started")
                return
            }
            commandSession.onCommandAudioStarted(
                sampleRate = 16000,
                bufferSize = 0,
                audioSource = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                modelPath = CommandRecognitionPolicy.ANDROID_MODEL_PATH,
                needsResample = false,
            )
            armSilenceWatch(sessionId)
            CarfuLog.i(
                CommandSession.TAG,
                "COMMAND_LISTENING_ARMED origin=${commandSession.activationOrigin} " +
                    "engine=ANDROID_ONLINE hubRecording=${CarfuPcmHub.isRecording()} " +
                    "silenceMs=${VoiceSessionManager.NO_SPEECH_TIMEOUT_MS} " +
                    "overlap=${CommandRecognitionPolicy.microphoneOwnersOverlap(
                        CarfuPcmHub.isRecording(),
                        true,
                    )}",
            )
            return
        }
        if (!sttInputDevice.isRecognizerReady()) {
            CarfuLog.e(CommandSession.TAG, "stt_not_ready after session start reason=$reason")
            endWakeSession("stt_not_ready")
            return
        }
        if (!WakeService.isInteractionPaused()) {
            CarfuLog.w(
                CommandSession.TAG,
                "COMMAND_STT_WAKE_NOT_PAUSED session=$sessionId",
            )
        }
        val capture = AudioCaptureConfig.detect()
        CarfuLog.i(
            CommandSession.TAG,
            "COMMAND_STT_START_ONCE reason=$reason session=$sessionId " +
                "origin=${commandSession.activationOrigin} " +
                "captureRate=${capture.captureRateHz} native16k=${AudioCaptureConfig.isNative16kHzSupported()} " +
                "hubRecording=${CarfuPcmHub.isRecording()} hubConsumer=${CarfuPcmHub.hasCommandConsumer()}",
        )
        val started = withContext(Dispatchers.Main) {
            sttInputDevice.stopListening()
            sttInputDevice.tryLoad(::processInputEvent)
        }
        if (!started || !sttInputDevice.isRecognizerReady()) {
            CarfuLog.e(CommandSession.TAG, "stt_not_ready after session start reason=$reason")
            endWakeSession("stt_not_ready")
            return
        }
        if (!CarfuPcmHub.hasCommandConsumer() && CarfuPcmHub.isRecording()) {
            awaitCommandConsumer(500L)
        }
        if (!CarfuSessionGate.isCurrent(sessionId) ||
            !wakeSessionActive.get() ||
            VoiceSessionManager.shouldIgnoreCallback(sessionId) ||
            !commandSession.canStartCommandRecognition()
        ) {
            if (wakeSessionActive.get()) {
                endWakeSession("stale_session_$reason")
            }
            return
        }
        commandSession.onCommandAudioStarted(
            sampleRate = capture.captureRateHz,
            bufferSize = capture.minBufferBytes,
            audioSource = capture.audioSource,
            modelPath = "vosk-model-small-vn-0.4",
            needsResample = capture.needsResample,
        )
        armSilenceWatch(sessionId)
        CarfuLog.i(
            CommandSession.TAG,
            "COMMAND_LISTENING_ARMED origin=${commandSession.activationOrigin} " +
                "hubConsumer=${CarfuPcmHub.hasCommandConsumer()} " +
                "pendingFrames=${CarfuPcmHub.pendingFrameCount()} " +
                "silenceMs=${VoiceSessionManager.NO_SPEECH_TIMEOUT_MS} " +
                CommandPcmStats.snapshot(),
        )
    }

    private suspend fun awaitCommandConsumer(timeoutMs: Long): Boolean {
        if (CarfuPcmHub.hasCommandConsumer()) return true
        if (!CarfuPcmHub.isRecording()) return false
        var waited = 0L
        while (waited < timeoutMs) {
            if (!wakeSessionActive.get()) return false
            if (CarfuPcmHub.hasCommandConsumer()) return true
            delay(40L)
            waited += 40L
        }
        return CarfuPcmHub.hasCommandConsumer()
    }

    private fun endWakeSession(
        reason: String,
        automaticFalseWake: Boolean = false,
        abandonAudioFocus: Boolean = true,
        deferWakeResumeUntilTtsDone: Boolean = true,
    ) {
        if (wakeSessionActive.compareAndSet(true, false)) {
            cancelSilenceWatch()
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SESSION_END)
            val sid = commandSession.ui.value.sessionId
            val origin = CarfuSessionGate.fromActivation(commandSession.activationOrigin)
            val hadTranscript = sessionHadTranscript.getAndSet(false)
            sttInputDevice.stopListening()
            VoiceSessionManager.onListenTerminal(sid, reason)
            VoiceSessionManager.terminate(sid, reason)
            CarfuLatencyLog.logSessionEvent(
                "SESSION_END",
                "reason=${sessionEndReasonTag(reason)} detail=$reason",
            )
            commandSession.endSession(reason, abandonAudioFocus = abandonAudioFocus)
            CarfuSessionGate.onSessionFinished(sid, hadTranscript, origin)
            CarfuLatencyLog.logPipelineStage("GATE_RELEASED")
            if (CarfuSessionGate.isModeReady()) {
                CarfuLatencyLog.logPipelineStage("MODE_READY")
            }
            if (deferWakeResumeUntilTtsDone) {
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    if (!abandonAudioFocus) {
                        commandSession.releaseAudioFocus()
                    }
                    resumeWakeAfterSession(sid, automaticFalseWake)
                }
            } else {
                if (!abandonAudioFocus) {
                    commandSession.releaseAudioFocus()
                }
                resumeWakeAfterSession(sid, automaticFalseWake)
            }
        }
    }

    private fun resumeWakeAfterSession(sessionId: Long, automaticFalseWake: Boolean) {
        if (commandSession.ui.value.sessionId != sessionId) {
            CarfuLog.i(
                CommandSession.TAG,
                "WAKE_RESUME_IGNORED staleSession=$sessionId current=${commandSession.ui.value.sessionId}",
            )
            return
        }
        WakeService.onlineCommandFinished()
        if (CarfuSessionGate.backgroundWakeEnabled) {
            WakeService.resumeAfterInteraction(automaticFalseWake)
        } else {
            WakeService.holdIdleWithoutWake()
        }
    }

    private fun sessionEndReasonTag(reason: String): String = when {
        reason.contains("unsupported", ignoreCase = true) ||
            reason == "unrecognized_user_command" ||
            reason == "skip_search_hardware" -> "UNSUPPORTED"
        reason == "error" || reason.startsWith("android_stt") -> "SR_ERROR"
        reason.contains("hard_timeout", ignoreCase = true) -> "HARD_TIMEOUT"
        reason.contains("timeout", ignoreCase = true) ||
            reason.contains("silence", ignoreCase = true) ||
            reason.contains("reject_noise", ignoreCase = true) ||
            reason.contains("unclear", ignoreCase = true) -> "NO_SPEECH"
        reason.contains("complete", ignoreCase = true) ||
            reason.contains("executed", ignoreCase = true) -> "EXECUTED"
        else -> reason
    }

    private suspend fun handleSilentNoSpeech(reason: String) {
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH)) {
            return
        }
        CarfuLatencyLog.logSessionEvent("NO_SPEECH_SILENT", "reason=$reason")
        CarfuLog.i(CommandSession.TAG, "NO_SPEECH_SILENT reason=$reason no_unclear_tts")
        endWakeSession(
            reason,
            automaticFalseWake = false,
            abandonAudioFocus = true,
            deferWakeResumeUntilTtsDone = false,
        )
    }

    private suspend fun handleEmptyOrUnclear(reason: String) {
        val rescued = tryRescueFromBestCandidates()
        if (rescued) return
        // V2: MODE/UI silence → silent terminal (no “Tôi chưa nghe rõ”).
        if (!VoiceSessionManager.shouldSpeakNoSpeechPrompt() ||
            !CarfuActivationSource.shouldSpeakUnclear(commandSession.activationOrigin)
        ) {
            handleSilentNoSpeech(reason)
            return
        }
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH)) {
            return
        }
        commandSession.onUnclear()
        val falseWake = CarfuActivationSource.shouldApplyFalseWakeCooldown(
            commandSession.activationOrigin,
        )
        CarfuLatencyLog.logSessionEvent("NO_SPEECH", "reason=$reason")
        withContext(Dispatchers.Main) {
            CarfuLatencyLog.logPipelineStage("FAILURE_TTS_START")
            commandSession.onTtsStarted()
            skillContext.speechOutputDevice.speak(
                skillContext.android.getString(R.string.carfu_state_unclear)
            )
            skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                CarfuLatencyLog.logPipelineStage("FAILURE_TTS_DONE")
            }
        }
        endWakeSession(
            reason,
            automaticFalseWake = falseWake,
            abandonAudioFocus = false,
            deferWakeResumeUntilTtsDone = true,
        )
    }

    private suspend fun handleUnsupportedCommand(transcript: String, reason: String) {
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.UNSUPPORTED)) {
            return
        }
        commandSession.onUnclear()
        val display = transcript.trim()
        CarfuLatencyLog.logSessionEvent(
            "UNSUPPORTED_COMMAND",
            "reason=$reason normalized=${VietnameseTranscript.foldForMatch(display)}",
        )
        withContext(Dispatchers.Main) {
            skillContext.speechOutputDevice.speak(
                skillContext.android.getString(
                    R.string.carfu_state_unsupported_command,
                    display,
                ),
            )
        }
        endWakeSession(reason)
    }

    private fun finishSessionWithoutWakeResume(reason: String) {
        if (wakeSessionActive.compareAndSet(true, false)) {
            cancelSilenceWatch()
            val sid = commandSession.ui.value.sessionId
            val origin = CarfuSessionGate.fromActivation(commandSession.activationOrigin)
            val hadTranscript = sessionHadTranscript.getAndSet(false)
            VoiceSessionManager.onListenTerminal(sid, reason)
            VoiceSessionManager.terminate(sid, reason)
            CarfuLatencyLog.logSessionEvent(
                "SESSION_END",
                "reason=${sessionEndReasonTag(reason)} detail=$reason",
            )
            commandSession.endSession(reason)
            CarfuSessionGate.onSessionFinished(sid, hadTranscript, origin)
            WakeService.onlineCommandFinished()
            WakeService.holdIdleWithoutWake()
        }
    }

    private suspend fun executeRoutedCommand(
        routed: RoutedCommand,
        understanding: UnderstandingResult? = null,
    ) {
        val sid = commandSession.ui.value.sessionId
        if (SessionCommandDecision.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            CarfuLatencyLog.logSessionEvent("EXECUTION_SKIPPED", "stale_or_cancelled session=$sid")
            return
        }
        if (!VoiceSessionManager.requestExecution(sid)) {
            CarfuLatencyLog.logSessionEvent("EXECUTION_SKIPPED", "duplicate_or_stale session=$sid")
            return
        }
        val resolved = understanding
            ?: routed.toCommandUnderstanding(
                rawTranscript = routed.canonicalVi,
                normalizedTranscript = routed.canonicalVi,
            ).let {
                UnderstandingResult(
                    sessionId = sid,
                    rawTranscript = it.rawTranscript,
                    normalizedTranscript = it.normalizedTranscript,
                    intent = it.intent,
                    entities = it.entities,
                    confidence = it.confidence,
                    completeness = it.completeness,
                    executable = it.executable,
                    command = it.command,
                    reason = "legacy_routed",
                )
            }
        VoiceSessionManager.onUnderstandingResult(
            sid,
            "intent=${resolved.intent} complete=${resolved.completeness} " +
                "exec=${resolved.executable} entities=${resolved.entities.keys}",
        )
        val result = try {
            withContext(Dispatchers.IO) {
                skillExecutor.execute(routed)
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            endWakeSession("skill_error")
            return
        }
        VoiceSessionManager.onExecutionDone(sid)
        // Same CanonicalCommand drives confirmation speech when available.
        val speech = resolved.command?.let {
            VietnameseCommandUnderstanding.confirmationSpeechVi(it)
        } ?: result.speechVi
        addInteractionFromPending(CarfuSpeechOutput(speech))
        commandSession.onReply(speech)
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.COMMAND_EXECUTE)
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.EXECUTE_START)
        CarfuLatencyLog.logPipelineStage("EXECUTE_START", "intent=${routed.intent}")
        withContext(Dispatchers.Main) {
            if (speech.isNotBlank()) {
                commandSession.onTtsStarted()
                VoiceSessionManager.onResponding(sid)
                skillContext.speechOutputDevice.speak(speech)
            }
            skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                result.afterTts?.invoke()
                if (result.resumeWakeAfter) {
                    endWakeSession("complete")
                } else {
                    finishSessionWithoutWakeResume("listening_disabled")
                }
            }
        }
    }

    /** Phase-3-only command: keep CanonicalCommand for TTS contract; no Android launch. */
    private suspend fun acknowledgeUnderstandingOnly(decision: UnderstandingResult) {
        val sid = decision.sessionId
        if (SessionCommandDecision.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            return
        }
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)) return
        if (!VoiceSessionManager.requestExecution(sid)) return
        val speech = decision.command?.let {
            VietnameseCommandUnderstanding.confirmationSpeechVi(it)
        }.orEmpty()
        VoiceSessionManager.onUnderstandingResult(
            sid,
            "phase3_pending intent=${decision.intent} reason=${decision.reason}",
        )
        VoiceSessionManager.onExecutionDone(sid)
        if (speech.isBlank()) {
            endWakeSession("understood_phase3")
            return
        }
        addInteractionFromPending(CarfuSpeechOutput(speech))
        commandSession.onReply(speech)
        withContext(Dispatchers.Main) {
            commandSession.onTtsStarted()
            VoiceSessionManager.onResponding(sid)
            skillContext.speechOutputDevice.speak(speech)
            skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                endWakeSession("understood_phase3")
            }
        }
    }

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.Error -> {
                if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.SR_ERROR)) {
                    return
                }
                addErrorInteractionFromPending(event.throwable)
                endWakeSession("error")
            }
            is InputEvent.Final -> {
                if (CommandSessionOutcome.peek() != CommandSessionOutcome.Kind.OPEN) {
                    CarfuLatencyLog.logSessionEvent(
                        "FINAL_IGNORED",
                        "terminal=${CommandSessionOutcome.peek()}",
                    )
                    return
                }
                cancelSilenceWatch()
                val sid = commandSession.ui.value.sessionId
                if (SessionCommandDecision.isCancelled(sid) ||
                    VoiceSessionManager.shouldIgnoreCallback(sid)
                ) {
                    CarfuLatencyLog.logSessionEvent("FINAL_IGNORED", "stale_or_cancelled")
                    return
                }
                VoiceSessionManager.onSpeechActivity(sid)
                VoiceSessionManager.onUnderstandingStart(sid)
                rememberCandidates(event.utterances)
                val merged = (sessionBestCandidates.get() + event.utterances)
                    .distinctBy { it.first }
                val decision = SessionCommandDecision.decideFinal(sid, merged)
                val displayRaw = event.utterances.maxByOrNull { it.first.length }?.first
                    ?: event.utterances.firstOrNull()?.first.orEmpty()
                // Live UI keeps the raw STT text — never overwrite with normalized labels.
                val original = displayRaw
                CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FINAL_RESULT)
                CarfuLatencyLog.logSessionEvent(
                    "FINAL_RESULT",
                    "candidates=${event.utterances.size} raw_len=${original.length} " +
                        "u_intent=${decision?.intent} u_complete=${decision?.completeness}",
                )
                if (VietnameseTranscript.isTooWeakToSubmit(original)) {
                    handleEmptyOrUnclear("reject_noise")
                    return
                }
                commandSession.onSpeechBegin()
                commandSession.onFinalText(original)
                sessionHadTranscript.set(true)
                sttInputDevice.stopListening()
                processUnderstandingDecision(decision, original, merged)
            }
            InputEvent.None -> {
                if (CommandSessionOutcome.peek() != CommandSessionOutcome.Kind.OPEN) {
                    CarfuLatencyLog.logSessionEvent(
                        "NO_SPEECH_IGNORED",
                        "hadTranscript=${sessionHadTranscript.get()} " +
                            "terminal=${CommandSessionOutcome.peek()}",
                    )
                    return
                }
                if (tryRescueFromBestCandidates()) return
                _state.value = _state.value.copy(pendingQuestion = null)
                handleEmptyOrUnclear("hard_timeout_or_silence")
            }
            is InputEvent.Partial -> {
                val sid = commandSession.ui.value.sessionId
                if (SessionCommandDecision.isCancelled(sid) ||
                    VoiceSessionManager.shouldIgnoreCallback(sid)
                ) {
                    return
                }
                cancelSilenceWatch()
                VoiceSessionManager.onLiveTranscript(sid, event.utterance)
                rememberCandidates(listOf(event.utterance to 1.0f))
                // Provisional understanding only — never execute from partial.
                val provisional = VietnameseCommandUnderstanding.understand(
                    raw = event.utterance,
                    sessionId = sid,
                    candidateIndex = 0,
                    recognizerConfidence = 1f,
                )
                SessionCommandDecision.onPartial(sid, provisional)
                commandSession.onPartial(event.utterance)
                sessionHadTranscript.set(true)
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
                logFastPartialHeld(event.utterance)
            }
        }
    }

    private fun rememberCandidates(candidates: List<Pair<String, Float>>) {
        if (candidates.isEmpty()) return
        sessionBestCandidates.updateAndGet { existing ->
            if (existing.isEmpty()) candidates else existing + candidates
        }
    }

    private suspend fun tryRescueFromBestCandidates(): Boolean {
        val sid = commandSession.ui.value.sessionId
        if (SessionCommandDecision.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            return true
        }
        val candidates = sessionBestCandidates.get().distinctBy { it.first }
        if (candidates.isEmpty()) return false
        val decision = SessionCommandDecision.decideFinal(sid, candidates) ?: return false
        if (decision.completeness != SemanticCompleteness.COMPLETE) return false
        if (decision.confidence < 0.85f && decision.recognizerConfidence < 0.85f) return false
        if (CommandSessionOutcome.peek() != CommandSessionOutcome.Kind.OPEN) return true
        CarfuLatencyLog.logSessionEvent(
            "TRANSCRIPT_RESCUED",
            "intent=${decision.intent} candidates=${candidates.size} " +
                "reason=${decision.reason}",
        )
        sttInputDevice.stopListening()
        processUnderstandingDecision(decision, decision.rawTranscript, candidates)
        return true
    }

    private fun logFastPartialHeld(utterance: String) {
        if (CommandSessionOutcome.peek() != CommandSessionOutcome.Kind.OPEN) return
        val folded = VietnameseTranscript.foldForMatch(utterance)
        val domain = CommandTranscriptNormalizer.detectDomain(folded)
        CarfuLatencyLog.logSessionEvent(
            "FAST_PARTIAL_HELD",
            "reason=listener_owns_sr domain=$domain folded_len=${folded.length}",
        )
    }

    /**
     * Kept for future Smart work / tests. Must not call [SttInputDevice.stopListening]
     * or otherwise terminate the active SpeechRecognizer session.
     */
    @Suppress("unused")
    private suspend fun tryFastPartialExecution(utterance: String) {
        // Runtime-disabled: known-good listener requires Google Final before execute.
        logFastPartialHeld(utterance)
    }

    private suspend fun processUnderstandingDecision(
        decision: UnderstandingResult?,
        original: String,
        utterances: List<Pair<String, Float>>,
    ) {
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.ROUTER_START)
        val sid = commandSession.ui.value.sessionId
        if (SessionCommandDecision.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            CarfuLatencyLog.logSessionEvent("UNDERSTAND_SKIPPED", "stale_or_cancelled")
            return
        }

        if (decision != null &&
            decision.completeness == SemanticCompleteness.INCOMPLETE &&
            decision.intent != org.stypox.dicio.io.session.VoiceIntent.UNKNOWN
        ) {
            // Incomplete NAVIGATE/PLAY_MEDIA/OPEN_APP must never execute particle destinations.
            CarfuLatencyLog.logSessionEvent(
                "UNDERSTAND_INCOMPLETE",
                "intent=${decision.intent} reason=${decision.reason}",
            )
            handleSilentNoSpeech("incomplete_${decision.reason}")
            return
        }

        if (decision != null &&
            decision.completeness == SemanticCompleteness.COMPLETE &&
            decision.command != null
        ) {
            when (val cmd = decision.command!!) {
                is CanonicalCommand.Navigate,
                is CanonicalCommand.OpenApp,
                is CanonicalCommand.PlayMedia,
                -> {
                    // V2 CanonicalCommand path — never re-parse transcript via router.
                    executeCanonicalCommand(decision, original)
                    return
                }
                is CanonicalCommand.Volume,
                CanonicalCommand.Time,
                is CanonicalCommand.CallContact,
                -> {
                    val routed = VietnameseCommandUnderstanding.toExecutableRoutedCommand(cmd)
                    if (routed != null) {
                        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)) return
                        commandSession.onIntentMatch(routed.skillId)
                        _state.value = _state.value.copy(
                            pendingQuestion = PendingQuestion(
                                userInput = original,
                                continuesLastInteraction = false,
                                skillBeingEvaluated = null,
                            )
                        )
                        executeRoutedCommand(routed, decision)
                        return
                    }
                }
            }
        }

        // Legacy fallback for timers/weather/etc. not yet in the V2 understanding surface.
        val best = CarfuCommandRouter.matchBest(utterances)
        processMatchedCommand(best, original, utterances)
    }

    /**
     * Phase-3: execute NAVIGATE / OPEN_APP / PLAY_MEDIA from [CanonicalCommand] only.
     * Exactly-once + cancel/stale guards. Never startListening / resurrect session.
     */
    private suspend fun executeCanonicalCommand(
        decision: UnderstandingResult,
        original: String,
    ) {
        val sid = decision.sessionId.takeIf { it != 0L }
            ?: commandSession.ui.value.sessionId
        val command = decision.command ?: return
        if (SessionCommandDecision.isCancelled(sid) ||
            CanonicalActionGate.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            CarfuLatencyLog.logSessionEvent("CANONICAL_EXEC_SKIPPED", "stale_or_cancelled")
            return
        }
        if (!CanonicalActionGate.tryClaim(sid)) {
            CarfuLatencyLog.logSessionEvent("CANONICAL_EXEC_SKIPPED", "already_claimed")
            return
        }
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)) {
            return
        }
        if (!VoiceSessionManager.requestExecution(sid)) {
            CarfuLatencyLog.logSessionEvent("CANONICAL_EXEC_SKIPPED", "execution_rejected")
            return
        }
        CarfuLatencyLog.logPipelineStage(
            "CANONICAL_EXECUTE",
            "intent=${decision.intent} reason=${decision.reason}",
        )
        CarfuLatencyLog.logSessionEvent(
            "INTENT_MATCHED",
            "canonical=${command} raw=${decision.rawTranscript}",
        )
        val skillId = when (command) {
            is CanonicalCommand.Navigate -> "navigation"
            is CanonicalCommand.OpenApp -> "open"
            is CanonicalCommand.PlayMedia -> "media"
            else -> "canonical"
        }
        commandSession.onIntentMatch(skillId)
        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = original,
                continuesLastInteraction = false,
                skillBeingEvaluated = null,
            )
        )
        VoiceSessionManager.onUnderstandingResult(
            sid,
            "canonical intent=${decision.intent} complete=${decision.completeness}",
        )
        val result = try {
            withContext(Dispatchers.IO) {
                // Re-check cancel before side effect (race: MODE cancel during IO schedule).
                if (CanonicalActionGate.isCancelled(sid) ||
                    SessionCommandDecision.isCancelled(sid) ||
                    VoiceSessionManager.shouldIgnoreCallback(sid)
                ) {
                    return@withContext SkillExecutionResult("", actionTaken = false)
                }
                canonicalExecutor.execute(command)
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            endWakeSession("skill_error")
            return
        }
        if (CanonicalActionGate.isCancelled(sid) ||
            SessionCommandDecision.isCancelled(sid) ||
            VoiceSessionManager.shouldIgnoreCallback(sid)
        ) {
            CarfuLatencyLog.logSessionEvent("CANONICAL_EXEC_ABORTED", "cancelled_after_work")
            endWakeSession("cancelled_after_work")
            return
        }
        CanonicalActionGate.markCompleted(sid)
        VoiceSessionManager.onExecutionDone(sid)
        val speech = result.speechVi.ifBlank {
            VietnameseCommandUnderstanding.confirmationSpeechVi(command).orEmpty()
        }
        addInteractionFromPending(CarfuSpeechOutput(speech))
        commandSession.onReply(speech)
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.COMMAND_EXECUTE)
        withContext(Dispatchers.Main) {
            if (speech.isNotBlank()) {
                commandSession.onTtsStarted()
                VoiceSessionManager.onResponding(sid)
                skillContext.speechOutputDevice.speak(speech)
            }
            skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                if (result.resumeWakeAfter) {
                    endWakeSession("complete")
                } else {
                    finishSessionWithoutWakeResume("listening_disabled")
                }
            }
        }
    }

    private suspend fun processMatchedCommand(
        best: RoutedMatch?,
        original: String,
        utterances: List<Pair<String, Float>>,
    ) {
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.ROUTER_START)
        if (best != null) {
            if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)) {
                return
            }
            CarfuLatencyLog.logPipelineStage(
                "SMART_NORMALIZE",
                "domain=${best.domain} idx=${best.candidateIndex}",
            )
            CarfuLatencyLog.logPipelineStage(
                "SMART_MATCH",
                "intent=${best.command.intent} skill=${best.command.skillId}",
            )
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.COMMAND_MATCH)
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.INTENT_MATCHED)
            CarfuLatencyLog.logSessionEvent(
                "INTENT_MATCHED",
                "intent=${best.command.intent} skill=${best.command.skillId} " +
                    "domain=${best.domain} idx=${best.candidateIndex}",
            )
            CarfuLatencyLog.logSessionEvent(
                "COMMAND_MATCH",
                "intent=${best.command.intent} skill=${best.command.skillId} " +
                    "place=${best.command.place.orEmpty()}",
            )
            commandSession.onIntentMatch(best.command.skillId)
            _state.value = _state.value.copy(
                pendingQuestion = PendingQuestion(
                    userInput = original,
                    continuesLastInteraction = false,
                    skillBeingEvaluated = null,
                )
            )
            executeRoutedCommand(best.command)
            return
        }
        val forMatch = utterances.map { (text, _) ->
            val folded = VietnameseTranscript.parse(text).folded
            folded.ifBlank { text }
        }
        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = original,
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = null,
            )
        )
        evaluateMatchingSkill(
            utterances = forMatch,
            displayInput = original,
            routedSkillId = null,
        )
    }

    private suspend fun evaluateMatchingSkill(
        utterances: List<String>,
        displayInput: String = utterances.firstOrNull().orEmpty(),
        routedSkillId: String? = null,
    ) {
        val (chosenInput, chosenSkill) = try {
            val ranked = utterances.firstNotNullOfOrNull { input: String ->
                skillContext.standardMatchHelper = MatchHelper(skillContext.parserFormatter, input)
                skillRanker.getBest(skillContext, input)?.let { skillWithResult ->
                    Pair(input, skillWithResult)
                }
            }
            if (ranked == null) {
                if (CarfuActivationSource.isUserInitiated()) {
                    handleUnsupportedCommand(displayInput, "unrecognized_user_command")
                    return
                }
                Pair(utterances[0], skillRanker.getFallbackSkill(skillContext, utterances[0]))
            } else if (
                !CarfuAssistIntents.shouldExecuteRankerSkill(
                    ranked.second.skill.correspondingSkillInfo.id,
                    CarfuActivationSource.kind,
                )
            ) {
                if (CarfuActivationSource.isUserInitiated()) {
                    handleUnsupportedCommand(displayInput, "skip_search_hardware")
                    return
                }
                handleEmptyOrUnclear("skip_search_hardware")
                return
            } else {
                ranked
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            endWakeSession("skill_match_error")
            return
        } finally {
            // standardMatchHelper only needs to be set while calling score() on skills, so once
            // all matching and scoring is done, free up the memory it uses (which may be
            // significant since the purpose of MatchHelper is to cache information about the input)
            skillContext.standardMatchHelper = null
        }
        val skillInfo = chosenSkill.skill.correspondingSkillInfo
        if (!CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)) {
            return
        }
        commandSession.onIntentMatch(routedSkillId ?: skillInfo.id)

        _state.value = _state.value.copy(
            pendingQuestion = PendingQuestion(
                userInput = displayInput,
                // the skill ranker would have discarded all batches, if the chosen skill was not
                // the continuation of the last interaction (since continuing an
                // interaction/conversation is done through the stack of batches)
                continuesLastInteraction = skillRanker.hasAnyBatches(),
                skillBeingEvaluated = skillInfo,
            )
        )

        try {
            val permissions = skillInfo.neededPermissions
            if (permissions.isNotEmpty() && !permissionRequester(permissions)) {
                // permissions were not granted, show message
                addInteractionFromPending(MissingPermissionsSkillOutput(skillInfo))
                endWakeSession("missing_permissions")
                return
            }

            skillContext.previousOutput =
                _state.value.interactions.lastOrNull()?.questionsAnswers?.lastOrNull()?.answer
            val output = chosenSkill.generateOutput(skillContext)

            val interactionPlan = output.getInteractionPlan(skillContext)
            addInteractionFromPending(output)
            val speech = output.getSpeechOutput(skillContext)
            if (speech.isNotBlank()) {
                commandSession.onReply(speech)
                withContext(Dispatchers.Main) {
                    commandSession.onTtsStarted()
                    skillContext.speechOutputDevice.speak(speech)
                }
            } else {
                commandSession.onReply("")
            }

            when (interactionPlan) {
                InteractionPlan.FinishInteraction -> {
                    // current conversation has ended, reset to the default batch of skills
                    skillRanker.removeAllBatches()
                }
                is InteractionPlan.FinishSubInteraction -> {
                    skillRanker.removeTopBatch()
                }
                is InteractionPlan.Continue -> {
                    // nothing to do, just continue with current batches
                }
                is InteractionPlan.StartSubInteraction -> {
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
                is InteractionPlan.ReplaceSubInteraction -> {
                    skillRanker.removeTopBatch()
                    skillRanker.addBatchToTop(interactionPlan.nextSkills)
                }
            }

            if (interactionPlan.reopenMicrophone) {
                val sid = commandSession.ui.value.sessionId
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    scope.launch {
                        if (!CarfuSessionGate.isCurrent(sid)) {
                            CarfuLog.i(
                                CommandSession.TAG,
                                "TTS_ON_DONE_IGNORED session=$sid kind=reopen",
                            )
                            return@launch
                        }
                        commandSession.onTtsCompleted()
                        startCommandListening("reopen", sid)
                    }
                }
            } else {
                endWakeSession("complete")
            }

        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            endWakeSession("skill_error")
            return
        }
    }

    private fun addErrorInteractionFromPending(throwable: Throwable) {
        Log.e(TAG, "Error while evaluating skills", throwable)
        addInteractionFromPending(ErrorSkillOutput(throwable, true))
    }

    private fun addInteractionFromPending(skillOutput: SkillOutput) {
        val log = _state.value
        val pendingUserInput = log.pendingQuestion?.userInput
        val pendingContinuesLastInteraction = log.pendingQuestion?.continuesLastInteraction
            ?: skillRanker.hasAnyBatches()
        val pendingSkill = log.pendingQuestion?.skillBeingEvaluated
        val questionAnswer = QuestionAnswer(pendingUserInput, skillOutput)

        _state.value = log.copy(
            interactions = log.interactions.toMutableList().also { inters ->
                if (pendingContinuesLastInteraction && inters.isNotEmpty()) {
                    inters[inters.size - 1] = inters[inters.size - 1].let { i -> i.copy(
                        questionsAnswers = i.questionsAnswers.toMutableList()
                            .apply { add(questionAnswer) }
                    ) }
                } else {
                    inters.add(
                        Interaction(
                            skill = pendingSkill,
                            questionsAnswers = listOf(questionAnswer)
                        )
                    )
                }
            },
            pendingQuestion = null,
        )
    }

    companion object {
        val TAG = SkillEvaluator::class.simpleName
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SkillEvaluatorModule {
    @Provides
    @Singleton
    fun provideSkillEvaluator(
        skillContext: SkillContextInternal,
        skillHandler: SkillHandler,
        sttInputDevice: SttInputDeviceWrapper,
        commandSession: CommandSession,
        wakeDevice: WakeDeviceWrapper,
        userSettings: DataStore<UserSettings>,
    ): SkillEvaluator {
        return SkillEvaluatorImpl(
            skillContext,
            skillHandler,
            sttInputDevice,
            commandSession,
            wakeDevice,
            userSettings,
        )
    }
}
