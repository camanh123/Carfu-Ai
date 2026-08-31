package org.stypox.dicio.di

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.datastore.core.DataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.stypox.dicio.R
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.android.AndroidSpeechInputDevice
import org.stypox.dicio.io.input.vosk.VoskInputDevice
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.CommandRecognitionEngine
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import org.stypox.dicio.settings.datastore.SttPlaySound
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import org.stypox.dicio.util.toStateFlowDistinctBlockingFirst


interface SttInputDeviceWrapper {
    val uiState: StateFlow<SttState?>

    fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean

    fun stopListening()

    fun onClick(eventListener: (InputEvent) -> Unit)

    fun reinitializeToReleaseResources()

    /** True only when the Vietnamese recognizer is constructed (Loaded/Listening). */
    fun isRecognizerReady(): Boolean = false

    /** Start download/unzip/load if needed. Never treats Loading as ready. */
    fun ensureModelPipeline() {}

    fun usesAndroidOnlineEngine(): Boolean = false

    fun commandEngine(): CommandRecognitionEngine =
        CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE
}

class SttInputDeviceWrapperImpl(
    @param:ApplicationContext private val appContext: Context,
    dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val okHttpClient: OkHttpClient,
    private val activityForResultManager: ActivityForResultManager,
    private val commandSession: CommandSession,
) : SttInputDeviceWrapper {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var inputDeviceSetting: InputDevice
    @Volatile
    private var commandEngineSetting: CommandRecognitionEngine
    private var sttPlaySoundSetting: SttPlaySound
    private val silencesBeforeStop: StateFlow<Int>
    private var sttInputDevice: SttInputDevice?

    // null means that the user has not enabled any STT input device
    private val _uiState: MutableStateFlow<SttState?> = MutableStateFlow(null)
    override val uiState: StateFlow<SttState?> = _uiState
    private var uiStateJob: Job? = null


    init {
        // Run blocking, because the data store is always available right away since LocaleManager
        // also initializes in a blocking way from the same data store.
        val (firstSettings, nextSettingsFlow) = dataStore.data
            .map {
                Triple(
                    it.inputDevice,
                    it.sttPlaySound,
                    CommandRecognitionPolicy.resolveEngine(it),
                )
            }
            .distinctUntilChangedBlockingFirst()

        inputDeviceSetting = firstSettings.first
        sttPlaySoundSetting = firstSettings.second
        commandEngineSetting = firstSettings.third
        silencesBeforeStop = dataStore.data.map(SttInputDevice::getSttSilenceDurationOrDefault)
            .toStateFlowDistinctBlockingFirst(scope)
        sttInputDevice = buildInputDevice(inputDeviceSetting, commandEngineSetting)
        scope.launch {
            restartUiStateJob()
        }

        scope.launch {
            nextSettingsFlow.collect { (inputDevice, sttPlaySound, engine) ->
                sttPlaySoundSetting = sttPlaySound
                val deviceChanged = inputDeviceSetting != inputDevice ||
                    commandEngineSetting != engine
                inputDeviceSetting = inputDevice
                commandEngineSetting = engine
                if (deviceChanged) {
                    changeInputDeviceTo(inputDevice, engine)
                }
            }
        }
    }

    private suspend fun changeInputDeviceTo(
        setting: InputDevice,
        engine: CommandRecognitionEngine,
    ) {
        val prevSttInputDevice = sttInputDevice
        inputDeviceSetting = setting
        commandEngineSetting = engine
        sttInputDevice = buildInputDevice(setting, engine)
        prevSttInputDevice?.destroy()
        restartUiStateJob()
    }

    private fun buildInputDevice(
        setting: InputDevice,
        engine: CommandRecognitionEngine,
    ): SttInputDevice? {
        return when (resolveCarfuInputDevice(setting)) {
            INPUT_DEVICE_NOTHING -> null
            UNRECOGNIZED,
            INPUT_DEVICE_UNSET,
            INPUT_DEVICE_VOSK,
            INPUT_DEVICE_EXTERNAL_POPUP -> {
                if (CommandRecognitionPolicy.shouldConstructVosk(engine)) {
                    CarfuLog.i(CommandSession.TAG, "STT_ENGINE engine=VOSK_LEGACY")
                    VoskInputDevice(appContext, okHttpClient, localeManager, silencesBeforeStop)
                } else {
                    CarfuLog.i(CommandSession.TAG, "STT_ENGINE engine=ANDROID_ONLINE vosk=false")
                    AndroidSpeechInputDevice(appContext)
                }
            }
        }
    }

    override fun usesAndroidOnlineEngine(): Boolean =
        CommandRecognitionPolicy.isAndroidOnline(commandEngineSetting)

    override fun commandEngine(): CommandRecognitionEngine =
        CommandRecognitionPolicy.resolveEngine(commandEngineSetting)

    override fun isRecognizerReady(): Boolean {
        val device = sttInputDevice
        return when (device) {
            is AndroidSpeechInputDevice -> device.isRecognizerReady()
            is VoskInputDevice -> device.isRecognizerReady()
            else -> false
        }
    }

    override fun ensureModelPipeline() {
        if (usesAndroidOnlineEngine()) return
        val device = sttInputDevice
        if (device is VoskInputDevice) {
            device.ensureModelPipeline()
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        if (commandSession.isBusy) {
            CarfuLog.i(CommandSession.TAG, "UI_CLICK_IGNORED session_busy=true")
            return
        }
        if (!commandSession.isBusy) {
            CarfuActivationSource.markManualMic()
        }
        if (usesAndroidOnlineEngine()) {
            WakeService.releaseHubForOnlineCommand()
        }
        sttInputDevice?.onClick(wrapEventListener(eventListener))
    }

    private suspend fun restartUiStateJob() {
        uiStateJob?.cancel()
        val newSttInputDevice = sttInputDevice
        if (newSttInputDevice == null) {
            uiStateJob = null
            _uiState.emit(null)
        } else {
            uiStateJob = scope.launch {
                newSttInputDevice.uiState.collect {
                    _uiState.emit(it)
                    if (it == SttState.Listening &&
                        commandSession.phase != CommandSessionPhase.COMMAND_LISTENING &&
                        commandSession.phase != CommandSessionPhase.ACKNOWLEDGING
                    ) {
                        playSound(R.raw.listening_sound)
                    }
                }
            }
        }
    }

    private fun playSound(resid: Int) {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (sttPlaySoundSetting) {
                    SttPlaySound.UNRECOGNIZED,
                    SttPlaySound.STT_PLAY_SOUND_UNSET,
                    SttPlaySound.STT_PLAY_SOUND_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    SttPlaySound.STT_PLAY_SOUND_ALARM -> AudioAttributes.USAGE_ALARM
                    SttPlaySound.STT_PLAY_SOUND_MEDIA -> AudioAttributes.USAGE_MEDIA
                    SttPlaySound.STT_PLAY_SOUND_NONE -> return // do not play any sound
                }
            )
            .build()
        val mediaPlayer = MediaPlayer.create(appContext, resid, attributes, 0)
        mediaPlayer.setVolume(0.75f, 0.75f)
        mediaPlayer.start()
    }

    private fun wrapEventListener(eventListener: (InputEvent) -> Unit): (InputEvent) -> Unit = {
        if (it is InputEvent.None && CarfuActivationSource.isUserInitiated()) {
            scope.launch {
                playSound(R.raw.listening_no_input_sound)
            }
        }
        eventListener(it)
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (!usesAndroidOnlineEngine()) {
            ensureModelPipeline()
        }
        return sttInputDevice?.tryLoad(if (thenStartListeningEventListener != null) {
            wrapEventListener(thenStartListeningEventListener)
        } else { null }) ?: false
    }

    override fun stopListening() {
        sttInputDevice?.stopListening()
    }

    override fun reinitializeToReleaseResources() {
        scope.launch { changeInputDeviceTo(inputDeviceSetting, commandEngineSetting) }
    }
}

private fun resolveCarfuInputDevice(setting: InputDevice): InputDevice {
    return when (setting) {
        INPUT_DEVICE_EXTERNAL_POPUP -> INPUT_DEVICE_VOSK
        else -> setting
    }
}

@Module
@InstallIn(SingletonComponent::class)
class SttInputDeviceWrapperModule {
    @Provides
    @Singleton
    fun provideInputDeviceWrapper(
        @ApplicationContext appContext: Context,
        dataStore: DataStore<UserSettings>,
        localeManager: LocaleManager,
        okHttpClient: OkHttpClient,
        activityForResultManager: ActivityForResultManager,
        commandSession: CommandSession,
    ): SttInputDeviceWrapper {
        return SttInputDeviceWrapperImpl(
            appContext, dataStore, localeManager, okHttpClient, activityForResultManager,
            commandSession,
        )
    }
}
