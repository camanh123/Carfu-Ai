package org.stypox.dicio.eval

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
import org.stypox.dicio.io.input.InputEvent
import androidx.datastore.core.DataStore
import org.stypox.dicio.io.session.AudioCaptureConfig
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuCommandRouter
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CarfuPcmHub
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.RoutedCommand
import org.stypox.dicio.io.session.VietnameseTranscript
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.skills.carfu.AndroidCarfuSkillPlatform
import org.stypox.dicio.skills.carfu.CarfuSpeechOutput
import org.stypox.dicio.skills.carfu.CarfuVietnameseSkillExecutor
import org.stypox.dicio.ui.home.Interaction
import org.stypox.dicio.ui.home.InteractionLog
import org.stypox.dicio.ui.home.PendingQuestion
import org.stypox.dicio.ui.home.QuestionAnswer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton

interface SkillEvaluator {
    val state: StateFlow<InteractionLog>

    var permissionRequester: suspend (List<Permission>) -> Boolean

    fun processInputEvent(event: InputEvent)

    /**
     * Called when the wake word is detected. Speaks the acknowledgment (e.g. "Tôi nghe đây")
     * and, once TTS has finished plus a short echo-guard, starts the STT microphone.
     */
    fun onWakeWordDetected()

    /**
     * Steering MODE / system Assist. Same CommandSession as wake, origin HARDWARE_BUTTON:
     * bypass wake-word, close the wake detector, say “Tôi nghe đây” once, then listen.
     */
    fun onHardwareButtonDetected()
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
    private val skillExecutor = CarfuVietnameseSkillExecutor(
        AndroidCarfuSkillPlatform(skillContext.android, userSettings),
    )

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
        scope.launch {
            suspendProcessInputEvent(event)
        }
    }

    override fun onWakeWordDetected() {
        beginExternalSession(
            origin = CarfuActivationSource.Kind.AUTOMATIC_WAKE,
            reason = "wake_ack",
            resumeWakeIfBeginFails = true,
        )
    }

    override fun onHardwareButtonDetected() {
        beginExternalSession(
            origin = CarfuActivationSource.Kind.HARDWARE_BUTTON,
            reason = "hardware_button",
            resumeWakeIfBeginFails = false,
        )
    }

    private fun beginExternalSession(
        origin: CarfuActivationSource.Kind,
        reason: String,
        resumeWakeIfBeginFails: Boolean,
    ) {
        if (commandSession.phase == CommandSessionPhase.IDLE_WAKE) {
            if (!commandSession.tryBeginWakeSession(origin)) {
                if (resumeWakeIfBeginFails) {
                    WakeService.resumeAfterInteraction()
                }
                return
            }
        } else if (commandSession.phase != CommandSessionPhase.WAKE_DETECTED) {
            CarfuLog.i(
                CommandSession.TAG,
                "WAKE_CALLBACK_IGNORED origin=$origin phase=${commandSession.phase}",
            )
            return
        }
        if (!wakeSessionActive.compareAndSet(false, true)) {
            CarfuLog.i(CommandSession.TAG, "WAKE_CALLBACK_DUPLICATE origin=$origin ignored")
            return
        }
        when (origin) {
            CarfuActivationSource.Kind.AUTOMATIC_WAKE -> CarfuActivationSource.markAutomaticWake()
            CarfuActivationSource.Kind.MANUAL_MIC -> CarfuActivationSource.markManualMic()
            CarfuActivationSource.Kind.HARDWARE_BUTTON -> CarfuActivationSource.markHardwareButton()
        }
        sttInputDevice.tryLoad(null)
        if (origin == CarfuActivationSource.Kind.HARDWARE_BUTTON) {
            WakeService.pauseForHardwareButton()
            wakeDevice.resetDetectionState()
            CarfuLog.i(
                CommandSession.TAG,
                "HARDWARE_BUTTON_SESSION session=${commandSession.ui.value.sessionId} " +
                    "origin=HARDWARE_BUTTON sharedHub=true",
            )
        }
        sttInputDevice.stopListening()
        scope.launch {
            val acknowledgment = skillContext.android.getString(R.string.wake_word_acknowledgment)
            withContext(Dispatchers.Main) {
                skillContext.speechOutputDevice.stopSpeaking()
                commandSession.onTtsStarted()
                CarfuLog.i(
                    CommandSession.TAG,
                    "TTS_STARTED session=${commandSession.ui.value.sessionId} text=ack origin=$origin",
                )
                skillContext.speechOutputDevice.speak(acknowledgment)
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    scope.launch {
                        commandSession.onTtsCompleted()
                        CarfuLog.i(
                            CommandSession.TAG,
                            "TTS_ON_DONE session=${commandSession.ui.value.sessionId} kind=ack origin=$origin",
                        )
                        startCommandListening(reason)
                    }
                }
            }
        }
    }

    private suspend fun startCommandListening(reason: String) {
        delay(CommandSession.POST_TTS_GUARD_MS)
        if (!wakeSessionActive.get() || !commandSession.canStartCommandRecognition()) {
            if (wakeSessionActive.get()) {
                endWakeSession("stale_session_$reason")
            }
            return
        }
        if (!WakeService.isInteractionPaused()) {
            CarfuLog.w(
                CommandSession.TAG,
                "COMMAND_STT_WAKE_NOT_PAUSED session=${commandSession.ui.value.sessionId}",
            )
        }
        val capture = AudioCaptureConfig.detect()
        CarfuLog.i(
            CommandSession.TAG,
            "COMMAND_STT_START_ONCE reason=$reason session=${commandSession.ui.value.sessionId} " +
                "origin=${commandSession.activationOrigin} " +
                "captureRate=${capture.captureRateHz} native16k=${AudioCaptureConfig.isNative16kHzSupported()} " +
                "hubRecording=${CarfuPcmHub.isRecording()} hubConsumer=${CarfuPcmHub.hasCommandConsumer()}",
        )
        val started = withContext(Dispatchers.Main) {
            sttInputDevice.stopListening()
            sttInputDevice.tryLoad(::processInputEvent)
        }
        if (!started) {
            CarfuLog.e(CommandSession.TAG, "stt_not_ready after TTS onDone reason=$reason")
            endWakeSession("stt_not_ready")
            return
        }
        if (!CarfuPcmHub.hasCommandConsumer() && CarfuPcmHub.isRecording()) {
            awaitCommandConsumer(500L)
        }
        if (!wakeSessionActive.get() || !commandSession.canStartCommandRecognition()) {
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
        CarfuLog.i(
            CommandSession.TAG,
            "COMMAND_LISTENING_ARMED origin=${commandSession.activationOrigin} " +
                "hubConsumer=${CarfuPcmHub.hasCommandConsumer()} " +
                "pendingFrames=${CarfuPcmHub.pendingFrameCount()}",
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

    private fun endWakeSession(reason: String, automaticFalseWake: Boolean = false) {
        if (wakeSessionActive.compareAndSet(true, false)) {
            skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                commandSession.endSession(reason)
                WakeService.resumeAfterInteraction(automaticFalseWake)
            }
        }
    }

    private suspend fun handleEmptyOrUnclear(reason: String) {
        commandSession.onUnclear()
        val origin = commandSession.activationOrigin
        val speak = CarfuActivationSource.shouldSpeakUnclear(origin)
        val falseWake = CarfuActivationSource.shouldApplyFalseWakeCooldown(origin)
        if (speak) {
            withContext(Dispatchers.Main) {
                skillContext.speechOutputDevice.speak(
                    skillContext.android.getString(R.string.carfu_state_unclear)
                )
            }
        } else {
            CarfuLog.i(
                CommandSession.TAG,
                "UNCLEAR_SILENT reason=$reason origin=$origin",
            )
        }
        endWakeSession(reason, automaticFalseWake = falseWake)
    }

    private fun finishSessionWithoutWakeResume(reason: String) {
        if (wakeSessionActive.compareAndSet(true, false)) {
            commandSession.endSession(reason)
        }
    }

    private suspend fun executeRoutedCommand(routed: RoutedCommand) {
        val result = try {
            withContext(Dispatchers.IO) {
                skillExecutor.execute(routed)
            }
        } catch (throwable: Throwable) {
            addErrorInteractionFromPending(throwable)
            endWakeSession("skill_error")
            return
        }
        addInteractionFromPending(CarfuSpeechOutput(result.speechVi))
        commandSession.onReply(result.speechVi)
        withContext(Dispatchers.Main) {
            if (result.speechVi.isNotBlank()) {
                commandSession.onTtsStarted()
                skillContext.speechOutputDevice.speak(result.speechVi)
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

    private suspend fun suspendProcessInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.Error -> {
                addErrorInteractionFromPending(event.throwable)
                endWakeSession("error")
            }
            is InputEvent.Final -> {
                val original = event.utterances[0].first
                if (VietnameseTranscript.isTooWeakToSubmit(original)) {
                    handleEmptyOrUnclear("reject_noise")
                    return
                }
                commandSession.onSpeechBegin()
                commandSession.onFinalText(original)
                val routed = CarfuCommandRouter.match(original)
                if (routed != null) {
                    commandSession.onIntentMatch(routed.skillId)
                    _state.value = _state.value.copy(
                        pendingQuestion = PendingQuestion(
                            userInput = original,
                            continuesLastInteraction = false,
                            skillBeingEvaluated = null,
                        )
                    )
                    executeRoutedCommand(routed)
                    return
                }
                val forMatch = event.utterances.map { (text, _) ->
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
            InputEvent.None -> {
                _state.value = _state.value.copy(pendingQuestion = null)
                handleEmptyOrUnclear("timeout_or_silence")
            }
            is InputEvent.Partial -> {
                commandSession.onPartial(event.utterance)
                _state.value = _state.value.copy(
                    pendingQuestion = PendingQuestion(
                        userInput = event.utterance,
                        continuesLastInteraction = skillRanker.hasAnyBatches(),
                        skillBeingEvaluated = null,
                    )
                )
            }
        }
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
                if (CarfuActivationSource.kind == CarfuActivationSource.Kind.HARDWARE_BUTTON) {
                    handleEmptyOrUnclear("unrecognized_hardware")
                    return
                }
                Pair(utterances[0], skillRanker.getFallbackSkill(skillContext, utterances[0]))
            } else if (
                !CarfuAssistIntents.shouldExecuteRankerSkill(
                    ranked.second.skill.correspondingSkillInfo.id,
                    CarfuActivationSource.kind,
                )
            ) {
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
                skillContext.speechOutputDevice.runWhenFinishedSpeaking {
                    scope.launch {
                        commandSession.onTtsCompleted()
                        startCommandListening("reopen")
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
