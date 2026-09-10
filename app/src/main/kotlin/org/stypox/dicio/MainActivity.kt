package org.stypox.dicio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shreyaspatil.permissionFlow.PermissionFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.datastore.core.DataStore
import org.stypox.dicio.di.SpeechOutputDeviceWrapper
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.assist.CarfuAssistIntents
import org.stypox.dicio.io.session.CarfuLatencyLog
import org.stypox.dicio.io.session.CarfuSessionGate
import org.stypox.dicio.io.session.RecordAudioPermission
import org.stypox.dicio.io.session.VoiceTriggerManager
import org.stypox.dicio.io.wake.BackgroundWakePolicy
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.ambient.AmbientVoiceOverlayController
import org.stypox.dicio.ui.home.wakeWordPermissions
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity() {

    @Inject
    lateinit var skillEvaluator: SkillEvaluator
    @Inject
    lateinit var sttInputDevice: SttInputDeviceWrapper
    @Inject
    lateinit var wakeDevice: WakeDeviceWrapper
    @Inject
    lateinit var userSettings: DataStore<UserSettings>
    @Inject
    lateinit var speechOutputDevice: SpeechOutputDeviceWrapper
    @Inject
    lateinit var ambientVoiceOverlayController: AmbientVoiceOverlayController

    private var sttPermissionJob: Job? = null
    private var wakeServiceJob: Job? = null

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        RecordAudioPermission.markRequested(this)
        if (granted) {
            when (RecordAudioPermission.consumePending()) {
                RecordAudioPermission.Pending.HARDWARE_MODE ->
                    skillEvaluator.onHardwareButtonDetected()
                RecordAudioPermission.Pending.UI_MIC ->
                    skillEvaluator.onUiModeDetected()
                RecordAudioPermission.Pending.NONE -> Unit
            }
        } else {
            RecordAudioPermission.logForVoiceTrigger(this, "permission_denied", this)
            try {
                speechOutputDevice.stopSpeaking()
                speechOutputDevice.speak(getString(R.string.carfu_need_microphone_permission))
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestRecordAudioForVoice(pending: RecordAudioPermission.Pending) {
        RecordAudioPermission.pendingAfterGrant = pending
        RecordAudioPermission.markRequested(this)
        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * FYT MODE / system Assist. Converges at [SkillEvaluator.onHardwareButtonDetected] →
     * [VoiceTriggerManager] HARDWARE_MODE. Duplicate VIS + ASSIST fan-out is rejected
     * by the V2 trigger + [CarfuSessionGate] debounce — not by Activity-only backoff.
     */
    private fun onAssistIntentReceived(intent: Intent?, stale: Boolean = false) {
        CarfuAssistIntents.logIncoming("MainActivity", intent)
        CarfuSessionGate.noteIncomingIntent(
            action = intent?.action,
            component = intent?.component?.flattenToShortString(),
        )
        if (stale) {
            VoiceTriggerManager.request(
                origin = VoiceTriggerManager.Origin.STALE_ASSIST,
                staleAssist = true,
                reason = "activity_recreate_or_old_intent",
            )
            Log.d(TAG, "Ignored stale assist intent action=${intent?.action}")
            return
        }
        CarfuLatencyLog.nowMs = { SystemClock.elapsedRealtime() }
        CarfuLatencyLog.onModeIntent()
        Log.d(TAG, "Received assist intent action=${intent?.action}")
        val snap = RecordAudioPermission.logForVoiceTrigger(this, "hardware_mode", this)
        if (!snap.mayStartSpeechRecognizer()) {
            requestRecordAudioForVoice(RecordAudioPermission.Pending.HARDWARE_MODE)
            return
        }
        skillEvaluator.onHardwareButtonDetected()
    }

    private fun handleWakeWordTurnOnScreen(intent: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            intent?.action == ACTION_WAKE_WORD
        ) {
            // Dicio was started anew based on a wake word,
            // turn on the screen to let the user see what is happening
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // the wake word triggered notification is not needed anymore
        WakeService.cancelTriggeredNotification(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        handleWakeWordTurnOnScreen(intent)
        if (isAssistIntent(intent)) {
            onAssistIntentReceived(intent)
        } else if (intent.getBooleanExtra(RecordAudioPermission.EXTRA_REQUEST_RECORD_AUDIO, false)) {
            val pending = try {
                RecordAudioPermission.Pending.valueOf(
                    intent.getStringExtra(RecordAudioPermission.EXTRA_PENDING_VOICE)
                        ?: RecordAudioPermission.Pending.UI_MIC.name,
                )
            } catch (_: Throwable) {
                RecordAudioPermission.Pending.UI_MIC
            }
            requestRecordAudioForVoice(pending)
        }
    }

    override fun onStart() {
        isInForeground += 1
        super.onStart()
    }

    override fun onStop() {
        // Home / another app in the foreground must not stop WakeService or the wake AudioRecord.
        super.onStop()
        isInForeground -= 1

        // once the activity is swiped away from the lock screen (or put in the background in any
        // other way), we don't want to show it on the lock screen anymore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCreated += 1
        RecordAudioPermission.requester = RecordAudioPermission.Requester {
            requestRecordAudioForVoice(
                if (RecordAudioPermission.pendingAfterGrant != RecordAudioPermission.Pending.NONE) {
                    RecordAudioPermission.pendingAfterGrant
                } else {
                    RecordAudioPermission.Pending.UI_MIC
                },
            )
        }

        // Observe CommandSession for Ambient HUD after Activity exists (not Application.onCreate).
        try {
            ambientVoiceOverlayController.ensureStarted()
        } catch (_: Throwable) {
        }

        handleWakeWordTurnOnScreen(intent)
        if (!isAssistIntent(intent)) {
            speechOutputDevice.prewarm()
        }
        if (intent.action != ACTION_WAKE_WORD) {
            if (sttInputDevice.usesAndroidOnlineEngine()) {
                Log.i(
                    TAG,
                    "STT_ENGINE ANDROID_ONLINE ready=${sttInputDevice.isRecognizerReady()} " +
                        "vosk=false",
                )
            } else {
                sttInputDevice.ensureModelPipeline()
                sttInputDevice.tryLoad(null)
            }
        }
        if (isAssistIntent(intent)) {
            // Recreation redelivers the old Assist Intent — not a new MODE press.
            onAssistIntentReceived(intent, stale = savedInstanceState != null)
        } else if (intent.getBooleanExtra(RecordAudioPermission.EXTRA_REQUEST_RECORD_AUDIO, false)) {
            val pending = try {
                RecordAudioPermission.Pending.valueOf(
                    intent.getStringExtra(RecordAudioPermission.EXTRA_PENDING_VOICE)
                        ?: RecordAudioPermission.Pending.UI_MIC.name,
                )
            } catch (_: Throwable) {
                RecordAudioPermission.Pending.UI_MIC
            }
            requestRecordAudioForVoice(pending)
        }

        // The Activity may start the foreground wake service, but does not own its lifetime
        // or the wake AudioRecord. onStop/onDestroy must not stop listening.
        wakeServiceJob?.cancel()
        wakeServiceJob = lifecycleScope.launch {
            combine(
                wakeDevice.state,
                userSettings.data,
                PermissionFlow.getInstance().getMultiplePermissionState(*wakeWordPermissions),
            ) { state, settings, perm ->
                val enabled = BackgroundWakePolicy.isBackgroundWakeEnabled(settings)
                CarfuSessionGate.setBackgroundWakeEnabled(enabled)
                BackgroundWakePolicy.shouldStartWakeService(
                    backgroundWakeEnabled = enabled,
                    recordAudioGranted = perm.allGranted,
                    wakeDeviceEnabled = state != null,
                    wakeModelReadyOrPending = BackgroundWakePolicy.isWakeModelReadyOrPending(state),
                )
            }
                .distinctUntilChanged()
                .filter { it }
                .collect { WakeService.start(this@MainActivity) }
        }

        sttPermissionJob?.cancel()
        sttPermissionJob = lifecycleScope.launch {
            // if the STT failed to load because of the missing permission, this will try again
            PermissionFlow.getInstance().getPermissionState(Manifest.permission.RECORD_AUDIO)
                .drop(1)
                .filter { it.isGranted }
                .collect { sttInputDevice.tryLoad(null) }
        }

        composeSetContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.safeDrawingPadding()
                ) {
                    Navigation()
                }
            }
        }
    }

    override fun onDestroy() {
        RecordAudioPermission.requester = null
        // STT can be unloaded when the Activity is gone; wake AudioRecord stays with WakeService.
        sttInputDevice.reinitializeToReleaseResources()
        isCreated -= 1
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.simpleName
        const val ACTION_WAKE_WORD = "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD"

        var isInForeground: Int = 0
            private set
        var isCreated: Int = 0
            private set

        private fun isAssistIntent(intent: Intent?): Boolean {
            return CarfuAssistIntents.isHardwareAssistAction(intent?.action)
        }
    }
}
