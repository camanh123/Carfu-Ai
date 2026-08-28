package org.stypox.dicio.skills.carfu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import kotlinx.coroutines.runBlocking
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.skills.telephone.Contact
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AndroidCarfuSkillPlatform(
    private val context: Context,
    private val userSettings: DataStore<UserSettings>?,
) : CarfuSkillPlatform {

    private val started = mutableListOf<StartedActivity>()

    override fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun lookupContacts(foldedQuery: String): List<CarfuContact> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
        val raw = Contact.getFilteredSortedContacts(context.contentResolver, foldedQuery)
        return raw.mapNotNull { contact ->
            val numbers = contact.getNumbers(context.contentResolver)
            if (numbers.isEmpty()) null else CarfuContact(contact.name, numbers)
        }
    }

    override fun resolvePackage(intent: Intent): String? {
        return intent.resolveActivity(context.packageManager)?.packageName
    }

    override fun startActivity(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            started += StartedActivity(
                action = intent.action,
                packageName = intent.component?.packageName ?: intent.`package`,
                className = intent.component?.className,
                data = intent.dataString,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun isPackageLaunchable(packageName: String): Boolean {
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    override fun launchPackage(packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.action = Intent.ACTION_MAIN
        launch.addCategory(Intent.CATEGORY_LAUNCHER)
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return startActivity(launch)
    }

    override fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }

    override fun adjustVolume(raise: Boolean): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        return true
    }

    override fun currentTimeSpeech(): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale("vi", "VN"))
        return "Bây giờ là ${LocalTime.now().format(formatter)}."
    }

    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun httpGet(url: String, timeoutMs: Int): HttpFetchResult {
        if (!isOnline()) return HttpFetchResult.Offline
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                HttpFetchResult.Error("http $code")
            } else {
                HttpFetchResult.Ok(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (_: SocketTimeoutException) {
            HttpFetchResult.Timeout
        } catch (_: UnknownHostException) {
            HttpFetchResult.Offline
        } catch (t: Exception) {
            HttpFetchResult.Error(t.message ?: "io")
        } finally {
            connection?.disconnect()
        }
    }

    override fun nowEpochMs(): Long = System.currentTimeMillis()

    override fun scheduleAlarm(id: String, fireAtEpochMs: Long, kind: CarfuAlarmKind, label: String) {
        CarfuAlarmScheduler.schedule(context, id, fireAtEpochMs, kind, label)
    }

    override fun cancelAlarm(id: String) {
        CarfuAlarmScheduler.cancel(context, id)
    }

    override fun saveTimer(timer: CarfuPersistedAlarm?) {
        CarfuAlarmStore.save(context, CarfuAlarmKind.TIMER, timer)
    }

    override fun loadTimer(): CarfuPersistedAlarm? {
        return CarfuAlarmStore.load(context, CarfuAlarmKind.TIMER)
    }

    override fun saveReminder(reminder: CarfuPersistedAlarm?) {
        CarfuAlarmStore.save(context, CarfuAlarmKind.REMINDER, reminder)
    }

    override fun loadReminder(): CarfuPersistedAlarm? {
        return CarfuAlarmStore.load(context, CarfuAlarmKind.REMINDER)
    }

    override fun setBackgroundWakeEnabled(enabled: Boolean) {
        val store = userSettings ?: return
        runBlocking {
            store.updateData {
                it.toBuilder()
                    .setBackgroundWake(
                        if (enabled) BackgroundWake.BACKGROUND_WAKE_ENABLED
                        else BackgroundWake.BACKGROUND_WAKE_DISABLED
                    )
                    .build()
            }
        }
    }

    override fun startWakeService() {
        WakeService.start(context)
    }

    override fun stopWakeService() {
        WakeService.disableAndStop(context)
    }

    override fun hasTorch(): Boolean = torchCameraId() != null

    override fun setTorch(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cameraId = torchCameraId() ?: return false
        val cm = context.getSystemService(CameraManager::class.java) ?: return false
        return try {
            cm.setTorchMode(cameraId, on)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun startedActivities(): List<StartedActivity> = started.toList()

    private fun torchCameraId(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        return try {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }
}
