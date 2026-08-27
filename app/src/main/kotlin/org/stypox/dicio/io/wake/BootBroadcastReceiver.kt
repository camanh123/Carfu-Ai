package org.stypox.dicio.io.wake

import android.Manifest.permission.RECORD_AUDIO
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.stypox.dicio.di.WakeDeviceWrapper
import org.stypox.dicio.settings.datastore.UserSettings
import javax.inject.Inject

@AndroidEntryPoint
class BootBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var wakeDevice: WakeDeviceWrapper
    @Inject lateinit var userSettings: DataStore<UserSettings>

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Got intent ${intent.action}")
        if (!BackgroundWakePolicy.isBootAction(intent.action)) {
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ContextCompat.checkSelfPermission(context, RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "Audio permission not granted")
                    return@launch
                }

                val settings = userSettings.data.first()
                val enabled = BackgroundWakePolicy.isBackgroundWakeEnabled(settings)
                val state = wakeDevice.state.value
                if (!BackgroundWakePolicy.shouldStartOnBoot(
                        backgroundWakeEnabled = enabled,
                        recordAudioGranted = true,
                        wakeDeviceEnabled = state != null,
                        wakeModelReadyOrPending =
                            BackgroundWakePolicy.isWakeModelReadyOrPending(state),
                    )
                ) {
                    Log.d(
                        TAG,
                        "Skipping boot start enabled=$enabled state=$state",
                    )
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Starting from Android 11, it is not possible to start a foreground service
                    // that accesses the microphone from a BOOT_COMPLETED broadcast. So we show a
                    // notification instead, which starts the foreground service when clicked.
                    // https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
                    Log.d(TAG, "Creating notification")
                    WakeService.createNotificationToStartLater(context)
                } else {
                    Log.d(TAG, "Starting service")
                    WakeService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        val TAG = BootBroadcastReceiver::class.simpleName
    }
}
