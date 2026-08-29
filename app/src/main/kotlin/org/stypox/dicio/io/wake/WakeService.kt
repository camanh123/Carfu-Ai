package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SCREEN_OFF
import android.content.Intent.ACTION_SCREEN_ON
import android.content.Intent.ACTION_USER_PRESENT
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.datastore.core.DataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.stypox.dicio.MainActivity
import org.stypox.dicio.MainActivity.Companion.ACTION_WAKE_WORD
import org.stypox.dicio.R
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuDiag
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CarfuPcmHub
import org.stypox.dicio.io.session.CarfuPcmRoute
import org.stypox.dicio.io.session.CarfuPcmRouter
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.PcmHealthMonitor
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.UserSettings
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class WakeService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val listening = AtomicBoolean(false)
    private val repairRequested = AtomicBoolean(false)
    private val lastSuccessfulReadElapsed = AtomicLong(0L)

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper
    @Inject
    lateinit var commandSession: CommandSession
    @Inject
    lateinit var userSettings: DataStore<UserSettings>

    private val handler = Handler(Looper.getMainLooper())
    private val releaseSttResourcesRunnable = Runnable {
        if (MainActivity.isCreated <= 0) {
            // if the main activity is neither visible nor in the background,
            // then unload the STT after a while because it would be using resources uselessly
            sttInputDevice.reinitializeToReleaseResources()
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = when (intent?.action) {
                ACTION_SCREEN_OFF -> BackgroundWakePolicy.ScreenAction.OFF
                ACTION_SCREEN_ON -> BackgroundWakePolicy.ScreenAction.ON
                ACTION_USER_PRESENT -> BackgroundWakePolicy.ScreenAction.USER_PRESENT
                else -> return
            }
            val recording = lastSuccessfulReadElapsed.get() > 0L &&
                SystemClock.elapsedRealtime() - lastSuccessfulReadElapsed.get() <
                BackgroundWakePolicy.STALE_READ_MS
            val decision = BackgroundWakePolicy.onScreenEvent(
                action = action,
                listening = listening.get(),
                commandSessionBusy = commandSession.isBusy,
                recording = recording,
                lastSuccessfulReadAgeMs = if (lastSuccessfulReadElapsed.get() == 0L) {
                    Long.MAX_VALUE
                } else {
                    SystemClock.elapsedRealtime() - lastSuccessfulReadElapsed.get()
                },
                lastReadFailed = false,
            )
            if (decision.openReplacementRecord &&
                !acceptancePolicy.shouldHoldWakeRecorderClosed(commandSession.isBusy)
            ) {
                repairRequested.set(true)
            }
            publishForegroundNotification()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instanceAlive.set(true)
        notificationManager = getSystemService(this, NotificationManager::class.java)!!
        registerScreenReceiver()

        scope.launch {
            combine(commandSession.ui, userSettings.data, wakeDevice.state) { _, settings, _ ->
                settings
            }.collect { settings ->
                rememberBackgroundWakeEnabled(
                    BackgroundWakePolicy.isBackgroundWakeEnabled(settings)
                )
                publishForegroundNotification()
            }
        }
        scope.launch {
            // Recreate the notification so that it says the correct thing (i.e. there is a
            // different string for the bundled "CARFU ơi" wake word and for a custom one).
            // Ignore the first one (i.e. the current value), which is handled in onStartCommand.
            wakeDevice.isHeyDicio.drop(1).collect {
                publishForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            publishForegroundNotification()
        } catch (t: Throwable) {
            stopWithMessage("could not create WakeService foreground notification", t)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_WAKE_SERVICE ||
            intent?.action == ACTION_DISABLE_BACKGROUND_WAKE
        ) {
            listening.set(false)
            resumeAfterInteraction()
            scope.launch {
                persistBackgroundWakeEnabled(false)
                commandSession.endSession("background_wake_disabled")
                stopWithMessage()
            }
            return START_NOT_STICKY
        }

        scope.launch {
            val settings = try {
                userSettings.data.first()
            } catch (t: Throwable) {
                Log.e(TAG, "Could not read background-wake preference", t)
                null
            }
            if (settings != null) {
                rememberBackgroundWakeEnabled(
                    BackgroundWakePolicy.isBackgroundWakeEnabled(settings)
                )
                if (!BackgroundWakePolicy.isBackgroundWakeEnabled(settings)) {
                    listening.set(false)
                    resumeAfterInteraction()
                    stopWithMessage()
                    return@launch
                }
            }
            startListeningIfNeeded()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the microphone foreground service after the task is swiped away.
    }

    override fun onDestroy() {
        listening.set(false)
        resumeAfterInteraction()
        unregisterScreenReceiver()
        instanceAlive.set(false)
        job.cancel()
        wakeDevice.reinitializeToReleaseResources()
        super.onDestroy()
    }

    private fun startListeningIfNeeded() {
        if (listening.getAndSet(true)) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
            listening.set(false)
            publishForegroundNotification()
            return
        }

        when (wakeDevice.state.value) {
            WakeState.NotLoaded,
            WakeState.Loading,
            WakeState.Loaded,
            is WakeState.ErrorLoading -> {}
            else -> {
                listening.set(false)
                stopWithMessage("Could not start WakeService: wake word device not ready")
                return
            }
        }

        scope.launch {
            try {
                listenForWakeWord()
                stopWithMessage()
            } catch (t: Throwable) {
                stopWithMessage("Cannot continue listening for wake word", t)
            }
        }
    }

    private suspend fun persistBackgroundWakeEnabled(enabled: Boolean) {
        try {
            userSettings.updateData {
                it.toBuilder()
                    .setBackgroundWake(
                        if (enabled) BackgroundWake.BACKGROUND_WAKE_ENABLED
                        else BackgroundWake.BACKGROUND_WAKE_DISABLED
                    )
                    .build()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not persist background-wake preference", t)
        }
    }

    private fun stopWithMessage(message: String = "", throwable: Throwable? = null) {
        listening.set(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else if (message.isNotEmpty()) {
            Log.e(TAG, message)
        }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_SCREEN_OFF)
            addAction(ACTION_SCREEN_ON)
            addAction(ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        screenReceiverRegistered = false
    }

    private fun publishForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_label),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.wake_service_foreground_notification_summary)
            notificationManager.createNotificationChannel(channel)
        }

        val granted = ContextCompat.checkSelfPermission(this, RECORD_AUDIO) == PERMISSION_GRANTED
        val settingsEnabled = try {
            // Preference is collected on another coroutine; use last known UI/service intent.
            lastKnownBackgroundWakeEnabled.get()
        } catch (_: Throwable) {
            true
        }
        val kind = BackgroundWakePolicy.notificationKind(
            recordAudioGranted = granted,
            backgroundWakeEnabled = settingsEnabled,
            wakeDeviceEnabled = wakeDevice.state.value != null,
            phase = commandSession.phase,
        )
        val status = getString(
            when (kind) {
                WakeNotificationKind.WAITING_WAKE ->
                    R.string.carfu_wake_notification_waiting
                WakeNotificationKind.LISTENING_COMMAND ->
                    R.string.carfu_wake_notification_listening
                WakeNotificationKind.PROCESSING ->
                    R.string.carfu_wake_notification_processing
                WakeNotificationKind.MIC_OFF ->
                    R.string.carfu_wake_notification_mic_off
                WakeNotificationKind.NEED_PERMISSION ->
                    R.string.carfu_wake_notification_need_permission
            }
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disablePending = PendingIntent.getService(
            this,
            0,
            Intent(this, WakeService::class.java).apply {
                action = ACTION_DISABLE_BACKGROUND_WAKE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(getString(R.string.carfu_wake_notification_title))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_stop_circle_white,
                    getString(R.string.carfu_wake_notification_disable),
                    disablePending,
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_hearing_white,
                    getString(R.string.carfu_wake_notification_open),
                    openPending,
                )
            )
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, FOREGROUND_NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground with microphone type failed, retrying basic", t)
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun listenForWakeWord() {
        @SuppressLint("MissingPermission")
        var ar: AudioRecord? = null

        var audio = ShortArray(0)
        var recording = false
        var retryDelayMs = BackgroundWakePolicy.INITIAL_OPEN_RETRY_MS
        var warmupResetPending = false
        var lastRoute: CarfuPcmRoute? = null
        val healthMonitor = PcmHealthMonitor()
        val voiceActivity = AdaptiveVoiceActivity()
        lastSuccessfulReadElapsed.set(0L)

        try {
            while (listening.get()) {
                if (wakeDevice.state.value == null) {
                    listening.set(false)
                    break
                }
                if (ContextCompat.checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
                    ar = releaseWakeRecorder(ar)
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    publishForegroundNotification()
                    interruptibleSleep(retryDelayMs)
                    retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                    continue
                }

                val repair = repairRequested.getAndSet(false)
                val lastOk = lastSuccessfulReadElapsed.get()
                val age = if (lastOk == 0L) Long.MAX_VALUE
                else SystemClock.elapsedRealtime() - lastOk
                val decision = BackgroundWakePolicy.onScreenEvent(
                    action = if (repair) {
                        BackgroundWakePolicy.ScreenAction.ON
                    } else {
                        BackgroundWakePolicy.ScreenAction.OFF
                    },
                    listening = true,
                    commandSessionBusy = commandSession.isBusy,
                    recording = recording,
                    lastSuccessfulReadAgeMs = age,
                    lastReadFailed = false,
                )
                val mayReplace = repair &&
                    decision.openReplacementRecord &&
                    acceptancePolicy.mayOpenReplacementRecorder(
                        commandSessionBusy = commandSession.isBusy,
                        alreadyRecordingHealthy = recording &&
                            age < BackgroundWakePolicy.STALE_READ_MS,
                    )
                if (mayReplace) {
                    ar = releaseWakeRecorder(ar)
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    CarfuDiag.wake("WAKE_RECORDER_REPAIR recreate=true")
                }

                if (!recording) {
                    try {
                        val existing = ar
                        if (existing == null || existing.state != AudioRecord.STATE_INITIALIZED) {
                            ar = releaseWakeRecorder(ar)
                            ar = createAudioRecord()
                        }
                        val recorder = ar
                        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                            interruptibleSleep(retryDelayMs)
                            retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                            continue
                        }
                        recorder.startRecording()
                        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            interruptibleSleep(retryDelayMs)
                            retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                            continue
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Restarting AudioRecord after wake-mic pause", t)
                        ar = releaseWakeRecorder(ar)
                        CarfuPcmHub.markRecording(false)
                        interruptibleSleep(retryDelayMs)
                        retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                        continue
                    }
                    recording = true
                    retryDelayMs = BackgroundWakePolicy.INITIAL_OPEN_RETRY_MS
                    acceptancePolicy.onRecorderStarted()
                    wakeDevice.resetDetectionState()
                    acceptancePolicy.onDetectorAndPcmReset()
                    healthMonitor.onRecorderOpened()
                    voiceActivity.reset()
                    warmupResetPending = true
                    CarfuPcmHub.markRecording(true)
                    CarfuDiag.wake(
                        "WAKE_RECORDER_STARTED warmupMs=${WakeAcceptancePolicy.RECORDER_WARMUP_MS} " +
                            "sharedHub=true",
                    )
                }

                val recorder = ar
                if (recorder == null) {
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    continue
                }

                if (audio.size != wakeDevice.frameSize()) {
                    audio = ShortArray(wakeDevice.frameSize())
                }

                val result = recorder.read(audio, 0, audio.size)
                if (BackgroundWakePolicy.shouldRecreateAfterReadError(result)) {
                    ar = releaseWakeRecorder(ar)
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    interruptibleSleep(retryDelayMs)
                    retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                    continue
                }

                lastSuccessfulReadElapsed.set(SystemClock.elapsedRealtime())
                lastHeard.set(Instant.now())
                retryDelayMs = BackgroundWakePolicy.INITIAL_OPEN_RETRY_MS

                if (result != audio.size) {
                    continue
                }

                val nowMs = SystemClock.elapsedRealtime()
                val (peak, rms) = PcmHealthMonitor.peakAndRms(audio, result)
                val health = healthMonitor.onFrame(
                    nowMs = nowMs,
                    recording = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING,
                    peak = peak,
                    rms = rms,
                )
                if (health == PcmHealthMonitor.Action.RESTART) {
                    CarfuDiag.wake("PCM_RESTART reason=exact_zero peak=0 rms=0")
                    ar = releaseWakeRecorder(ar)
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    continue
                }
                if (health == PcmHealthMonitor.Action.DEAD_KEEP) {
                    CarfuDiag.wake(
                        "PCM_DEAD recordingState=${recorder.recordingState} peak=$peak rms=$rms " +
                            "initialized_is_not_healthy=true",
                    )
                }

                val route = CarfuPcmRouter.route(
                    phase = commandSession.phase,
                    cooldownActive = acceptancePolicy.isCooldownActive(),
                    interactionPaused = interactionPaused.get(),
                )
                if (lastRoute != null && lastRoute != route) {
                    if (CarfuPcmRouter.shouldResetWakeDetectors(lastRoute, route)) {
                        wakeDevice.resetDetectionState()
                        acceptancePolicy.onDetectorAndPcmReset()
                        CarfuDiag.wake("WAKE_DETECTOR_RESET boundary=$lastRoute->$route")
                    }
                }
                lastRoute = route

                when (route) {
                    CarfuPcmRoute.DISCARD -> continue
                    CarfuPcmRoute.COMMAND_RECOGNIZER -> {
                        CarfuPcmHub.feedCommand(audio, result)
                        continue
                    }
                    CarfuPcmRoute.OPEN_WAKE_WORD -> {
                        // scored below
                    }
                }

                if (acceptancePolicy.isWarmupActive()) {
                    continue
                }
                if (warmupResetPending) {
                    wakeDevice.resetDetectionState()
                    acceptancePolicy.onDetectorAndPcmReset()
                    warmupResetPending = false
                    CarfuDiag.wake("WAKE_DETECTOR_RESET after_warmup")
                    continue
                }

                val vad = voiceActivity.observe(peak, rms)
                if (vad.exactZero || !vad.isVoice) {
                    acceptancePolicy.evaluate(
                        scoreAboveThreshold = false,
                        phase = commandSession.phase,
                        voiceActivity = false,
                    )
                    continue
                }

                val scoreHit = try {
                    wakeDevice.processFrame(audio)
                } catch (t: Throwable) {
                    Log.w(TAG, "Wake model process failed; will retry", t)
                    ar = releaseWakeRecorder(ar)
                    recording = false
                    CarfuPcmHub.markRecording(false)
                    publishForegroundNotification()
                    interruptibleSleep(retryDelayMs)
                    retryDelayMs = BackgroundWakePolicy.nextOpenRetryDelayMs(retryDelayMs)
                    continue
                }

                val ttsSpeaking = commandSession.phase == CommandSessionPhase.ACKNOWLEDGING ||
                    commandSession.phase == CommandSessionPhase.RESPONDING
                val verdict = acceptancePolicy.evaluate(
                    scoreAboveThreshold = scoreHit,
                    phase = commandSession.phase,
                    ttsSpeaking = ttsSpeaking,
                    commandCaptureActive = commandSession.phase ==
                        CommandSessionPhase.COMMAND_LISTENING,
                    voiceActivity = vad.isVoice,
                )
                if (verdict != WakeAcceptancePolicy.Verdict.ACCEPT) {
                    if (verdict != WakeAcceptancePolicy.Verdict.BELOW_THRESHOLD &&
                        verdict != WakeAcceptancePolicy.Verdict.ACCUMULATING &&
                        verdict != WakeAcceptancePolicy.Verdict.REJECT_NO_VAD
                    ) {
                        CarfuDiag.wake("WAKE_REJECTED reason=$verdict phase=${commandSession.phase}")
                    }
                    continue
                }

                if (!commandSession.tryBeginWakeSession()) {
                    CarfuDiag.wake("WAKE_REJECTED reason=session_overlap")
                    acceptancePolicy.onDetectorAndPcmReset()
                    continue
                }
                CarfuActivationSource.markAutomaticWake()
                acceptancePolicy.closeGate()
                pauseForInteraction()
                wakeDevice.resetDetectionState()
                acceptancePolicy.onDetectorAndPcmReset()
                CarfuDiag.wake(
                    "WAKE_ACCEPTED session=${commandSession.ui.value.sessionId} " +
                        "recorder_released=false sharedHub=true",
                )
                onWakeWordDetected()
            }
        } finally {
            CarfuPcmHub.markRecording(false)
            CarfuPcmHub.detachCommandConsumer()
            releaseWakeRecorder(ar)
        }
    }

    private fun releaseWakeRecorder(record: AudioRecord?): AudioRecord? {
        if (record == null) return null
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        try {
            record.release()
        } catch (_: Throwable) {
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            6400,
        )
    }

    private fun interruptibleSleep(delayMs: Long) {
        var remaining = delayMs
        while (remaining > 0 && listening.get()) {
            val slice = minOf(80L, remaining)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun onWakeWordDetected() {
        CarfuDiag.wake("WAKE_CALLBACK phase=${commandSession.phase}")
        if (commandSession.phase != CommandSessionPhase.WAKE_DETECTED &&
            commandSession.phase != CommandSessionPhase.IDLE_WAKE
        ) {
            CarfuLog.i(CommandSession.TAG, "COMMAND_SESSION_OVERLAP ignored")
            return
        }
        pauseForInteraction()
        publishForegroundNotification()

        val intent = Intent(this, MainActivity::class.java)
        intent.setAction(ACTION_WAKE_WORD)
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK)

        // Speak the wake-word acknowledgment ("Tôi nghe đây?") then start STT once TTS finishes.
        // Note that this works even if the MainActivity is opened later!
        skillEvaluator.onWakeWordDetected()

        // Unload the STT after a while because it would be using RAM uselessly
        handler.removeCallbacks(releaseSttResourcesRunnable)
        handler.postDelayed(releaseSttResourcesRunnable, RELEASE_STT_RESOURCES_MILLIS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || MainActivity.isInForeground > 0) {
            // start the activity directly on versions prior to Android 10,
            // or if the MainActivity is already running in the foreground
            startActivity(intent)

        } else {
            // Android 10+ does not allow starting activities from the background,
            // so show a full-screen notification instead, which does actually result in starting
            // the activity from the background if the phone is off and Do Not Disturb is not active
            // Maybe we could also use the "Display over other apps" permission?

            val channel = NotificationChannel(
                TRIGGERED_NOTIFICATION_CHANNEL_ID,
                getString(R.string.wake_service_triggered_notification),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = getString(R.string.wake_service_triggered_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(this, TRIGGERED_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(getString(R.string.wake_service_triggered_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    getString(R.string.wake_service_triggered_notification_summary)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pendingIntent, true)
                .build()

            notificationManager.cancel(TRIGGERED_NOTIFICATION_ID)
            notificationManager.notify(TRIGGERED_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /**
         * Starting from Android 11, it is not possible to start a foreground service
         * that accesses the microphone from a BOOT_COMPLETED broadcast. So we show a
         * notification instead, which starts the foreground service when clicked.
         * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
         */
        @RequiresApi(Build.VERSION_CODES.R)
        fun createNotificationToStartLater(context: Context) {
            val notificationManager = getSystemService(context, NotificationManager::class.java)
                ?: return

            val channel = NotificationChannel(
                START_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.wake_service_start_notification),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = context.getString(R.string.wake_service_start_notification_summary)
            notificationManager.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, WakeService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, START_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(context.getString(R.string.wake_service_start_notification))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.wake_service_start_notification_summary)))
                .setOngoing(false)
                .setShowWhen(false)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(START_NOTIFICATION_ID, notification)
        }

        /**
         * Start the service. Call this only from a foreground part of the app (e.g. the main
         * activity), or from BOOT_COMPLETED only before Android 11. For BOOT_COMPLETED on Android
         * 11+ use [createNotificationToStartLater] instead.
         *
         * Start is idempotent: a second call does not open a second AudioRecord.
         */
        fun start(context: Context) {
            Log.d(TAG, "WakeService.start() called from ${Throwable().stackTrace[1]}")
            val intent = Intent(context, WakeService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            disableAndStop(context)
        }

        fun disableAndStop(context: Context) {
            try {
                context.startService(
                    Intent(context, WakeService::class.java)
                        .apply { action = ACTION_DISABLE_BACKGROUND_WAKE }
                )
            } catch (_: IllegalStateException) {
                // Must not have been running. No problem with that.
            }
        }

        fun isInstanceAlive(): Boolean = instanceAlive.get()

        fun isInteractionPaused(): Boolean = interactionPaused.get()

        // Consider the service running if it processed any audio data within the past half second.
        fun isRunning(): Boolean = lastHeard.get()?.isAfter(Instant.now().minusMillis(500)) == true

        /**
         * On Android 10+ cancels any notification telling the user that the Dicio wake word was
         * triggered, which is not needed anymore after the main activity starts.
         */
        fun cancelTriggeredNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getSystemService(context, NotificationManager::class.java)
                    ?.cancel(TRIGGERED_NOTIFICATION_ID)
            }
        }

        private val lastHeard = AtomicReference<Instant>()
        private val interactionPaused = AtomicBoolean(false)
        private val instanceAlive = AtomicBoolean(false)
        private val lastKnownBackgroundWakeEnabled = AtomicBoolean(true)
        private val resumeHandler = Handler(Looper.getMainLooper())
        private val autoResumeRunnable = Runnable { resumeAfterInteraction() }
        internal val acceptancePolicy = WakeAcceptancePolicy { SystemClock.elapsedRealtime() }

        /**
         * Keep the physical AudioRecord open. Scoring is gated; PCM is discarded during TTS.
         */
        fun pauseForInteraction() {
            acceptancePolicy.onPauseForInteraction()
            interactionPaused.set(true)
            resumeHandler.removeCallbacks(autoResumeRunnable)
            resumeHandler.postDelayed(autoResumeRunnable, WAKE_MIC_PAUSE_TIMEOUT_MILLIS)
        }

        /**
         * MODE / Assist: bypass wake scoring, close the detector, keep the shared 16 kHz hub.
         */
        fun pauseForHardwareButton() {
            CarfuDiag.wake("HARDWARE_BUTTON_PAUSE sharedHub=true")
            pauseForInteraction()
            acceptancePolicy.closeGate()
            acceptancePolicy.onDetectorAndPcmReset()
        }

        fun resumeAfterInteraction(automaticFalseWake: Boolean = false) {
            resumeHandler.removeCallbacks(autoResumeRunnable)
            if (automaticFalseWake) {
                acceptancePolicy.markAutomaticFalseWakeCooldown()
            } else {
                acceptancePolicy.markPostAssistantTtsCooldown()
            }
            val minCooldown = if (automaticFalseWake) {
                WakeAcceptancePolicy.AUTOMATIC_FALSE_WAKE_COOLDOWN_MS
            } else {
                WakeAcceptancePolicy.POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS
            }
            val delayMs = acceptancePolicy.remainingCooldownMs().coerceAtLeast(minCooldown)
            CarfuDiag.wake(
                "WAKE_RESUME_SCHEDULED cooldownMs=$delayMs falseWake=$automaticFalseWake " +
                    "recorder_held=true",
            )
            resumeHandler.postDelayed({
                acceptancePolicy.onCooldownElapsed()
                interactionPaused.set(false)
                CarfuDiag.wake("WAKE_ENGINE_RESUMED")
            }, delayMs)
        }

        internal fun rememberBackgroundWakeEnabled(enabled: Boolean) {
            lastKnownBackgroundWakeEnabled.set(enabled)
        }

        private val TAG = WakeService::class.simpleName
        private const val FOREGROUND_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.FOREGROUND"
        private const val START_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.START"
        private const val TRIGGERED_NOTIFICATION_CHANNEL_ID =
            "org.stypox.dicio.io.wake.WakeService.TRIGGERED"
        private const val FOREGROUND_NOTIFICATION_ID = 19803672
        private const val START_NOTIFICATION_ID = 48019274
        private const val TRIGGERED_NOTIFICATION_ID = 601398647
        private const val WAKE_MIC_PAUSE_TIMEOUT_MILLIS = 45_000L
        private const val ACTION_STOP_WAKE_SERVICE =
            "org.stypox.dicio.io.wake.WakeService.ACTION_STOP"
        private const val ACTION_DISABLE_BACKGROUND_WAKE =
            "org.stypox.dicio.io.wake.WakeService.ACTION_DISABLE"
        private const val RELEASE_STT_RESOURCES_MILLIS = 1000L * 60 * 5 // 5 minutes
    }
}
