package org.stypox.dicio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.assist.CarfuAssistIntents
import org.stypox.dicio.io.wake.BackgroundWakePolicy
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.home.wakeWordPermissions
import org.stypox.dicio.ui.nav.Navigation
import org.stypox.dicio.util.BaseActivity
import java.time.Instant
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

    private var sttPermissionJob: Job? = null
    private var wakeServiceJob: Job? = null

    private var nextAssistAllowed = Instant.MIN

    /**
     * FYT MODE / system Assist. Starts the existing CommandSession with origin
     * HARDWARE_BUTTON. Does not open a second AudioRecord or browser search.
     */
    private fun onAssistIntentReceived(intent: Intent?) {
        CarfuAssistIntents.logIncoming("MainActivity", intent)
        val now = Instant.now()
        if (nextAssistAllowed < now) {
            nextAssistAllowed = now.plusMillis(INTENT_BACKOFF_MILLIS)
            Log.d(TAG, "Received assist intent action=${intent?.action}")
            skillEvaluator.onHardwareButtonDetected()
        } else {
            Log.w(TAG, "Ignoring duplicate assist intent")
        }
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

        handleWakeWordTurnOnScreen(intent)
        // Preload Vosk (Vietnamese) without listening so COMMAND_LISTENING can attach immediately.
        if (intent.action != ACTION_WAKE_WORD) {
            sttInputDevice.tryLoad(null)
        }
        if (isAssistIntent(intent)) {
            onAssistIntentReceived(intent)
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
                BackgroundWakePolicy.shouldStartWakeService(
                    backgroundWakeEnabled = BackgroundWakePolicy.isBackgroundWakeEnabled(settings),
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
        // STT can be unloaded when the Activity is gone; wake AudioRecord stays with WakeService.
        sttInputDevice.reinitializeToReleaseResources()
        isCreated -= 1
        super.onDestroy()
    }

    companion object {
        private const val INTENT_BACKOFF_MILLIS = 100L
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
