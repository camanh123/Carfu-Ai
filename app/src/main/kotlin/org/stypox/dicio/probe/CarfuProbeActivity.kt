package org.stypox.dicio.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.stypox.dicio.R
import org.stypox.dicio.io.session.CarfuDiag
import org.stypox.dicio.util.checkPermissions

/**
 * Phase 0 capability probe for CARFU / UIS7862 / FYT head units.
 *
 * Landscape-first diagnostic Activity: package scan, steering-wheel key capture,
 * Bluetooth dialer intents, and a foreground [AudioRecord] test.
 */
class CarfuProbeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var audioButton: Button

    private var audioRunning = false
    private var pendingAfterPermission: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.filterValues { it }.keys
        val denied = result.filterValues { !it }.keys
        CarfuProbeLog.append("Permission result granted=$granted denied=$denied")
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (denied.isNotEmpty()) {
            CarfuProbeLog.append("Permission denied — aborting pending action")
            return@registerForActivityResult
        }
        action?.invoke()
    }

    private val logListener: (String) -> Unit = { line ->
        appendLineToUi(line)
    }

    private val probeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logBroadcast(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carfu_probe)

        logView = findViewById(R.id.probe_log)
        logScroll = findViewById(R.id.probe_log_scroll)
        audioButton = findViewById(R.id.btn_test_audio)

        findViewById<Button>(R.id.btn_scan_packages).setOnClickListener { scanPackages() }
        findViewById<Button>(R.id.btn_test_key_event).setOnClickListener { armKeyCapture() }
        findViewById<Button>(R.id.btn_test_bt_call).setOnClickListener { testBluetoothCall() }
        audioButton.setOnClickListener { toggleAudioProbe() }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener { copyDiagLog() }

        restoreLog()
        CarfuProbeLog.addListener(logListener)
        registerProbeReceiver()

        CarfuProbeLog.append("CarfuProbe ready — UIS7862 / FYT / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        CarfuProbeLog.append("Display ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} density=${resources.displayMetrics.density}")
        CarfuProbeLog.append("Press a steering-wheel key or use the buttons. Broadcasts are armed.")
        logIncomingIntent(intent, "onCreate")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        logIncomingIntent(intent, "onNewIntent")
    }

    override fun onResume() {
        super.onResume()
        window.decorView.requestFocus()
        CarfuProbeLog.append("Activity onResume (window focused=${window.decorView.hasWindowFocus()})")
        updateAudioButton()
    }

    override fun onPause() {
        CarfuProbeLog.append("Activity onPause — audio probe keeps running if started")
        super.onPause()
    }

    override fun onDestroy() {
        CarfuProbeLog.removeListener(logListener)
        try {
            unregisterReceiver(probeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKey("dispatchKeyEvent", event)
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logKey("onKeyDown", event)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        logKey("onKeyUp", event)
        return super.onKeyUp(keyCode, event)
    }

    private fun restoreLog() {
        val existing = CarfuProbeLog.snapshot()
        logView.text = if (existing.isEmpty()) "" else existing.joinToString("\n") + "\n"
        scrollLogToBottom()
    }

    private fun appendLineToUi(line: String) {
        logView.append(line)
        logView.append("\n")
        scrollLogToBottom()
    }

    private fun scrollLogToBottom() {
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clearLog() {
        CarfuProbeLog.clear()
        logView.text = ""
        CarfuProbeLog.append("Log cleared")
    }

    private fun copyDiagLog() {
        val text = CarfuDiag.copyableLog()
        CarfuProbeLog.append("=== ${getString(R.string.carfu_probe_copy_log)} ===")
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            CarfuProbeLog.append(line)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CARFU log", text))
        CarfuProbeLog.append(getString(R.string.carfu_probe_copied))
    }

    // --- Feature 1: Package Inspector ----------------------------------------------------------

    @SuppressLint("QueryPermissionsNeeded")
    private fun scanPackages() {
        CarfuProbeLog.append("=== Package Inspector ===")
        val pm = packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }
        } catch (t: Throwable) {
            CarfuProbeLog.append("getInstalledPackages FAILED: ${t.message}")
            return
        }

        val sorted = installed.sortedBy { it.packageName }
        CarfuProbeLog.append("Installed packages: ${sorted.size}")

        FytPackages.allWatched.forEach { (group, packages) ->
            CarfuProbeLog.append("-- $group --")
            packages.forEach { pkg ->
                val info = sorted.find { it.packageName == pkg }
                if (info == null) {
                    CarfuProbeLog.append("  MISSING  $pkg")
                } else {
                    val label = info.applicationInfo?.loadLabel(pm) ?: "?"
                    val enabled = info.applicationInfo?.enabled ?: false
                    val system = info.applicationInfo.isSystemApp()
                    CarfuProbeLog.append(
                        "  FOUND    $pkg  label=\"$label\" enabled=$enabled system=$system"
                    )
                }
            }
        }

        CarfuProbeLog.append("-- Full package list --")
        sorted.forEach { pkg ->
            val mark = if (pkg.packageName in FytPackages.allWatchedPackages) " *" else ""
            val label = pkg.applicationInfo?.loadLabel(pm) ?: "?"
            val system = pkg.applicationInfo.isSystemApp()
            CarfuProbeLog.append(
                "  ${pkg.packageName}$mark  label=\"$label\" system=$system"
            )
        }
        CarfuProbeLog.append("=== End Package Inspector ===")
    }

    private fun ApplicationInfo?.isSystemApp(): Boolean {
        if (this == null) return false
        return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }

    // --- Feature 2: Key Event & Broadcast Detector ---------------------------------------------

    private fun armKeyCapture() {
        window.decorView.requestFocus()
        CarfuProbeLog.append("=== Key capture armed ===")
        CarfuProbeLog.append("Press Voice / SEEK / CALL on the steering wheel now.")
        CarfuProbeLog.append("Synthetic KEYCODE_VOICE_ASSIST follows to verify dispatchKeyEvent.")
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST)
        dispatchKeyEvent(down)
        dispatchKeyEvent(up)
    }

    private fun logKey(source: String, event: KeyEvent) {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> event.action.toString()
        }
        CarfuProbeLog.append(
            "$source keyCode=${event.keyCode} keyName=$name action=$action " +
                "repeat=${event.repeatCount} scanCode=${event.scanCode} " +
                "source=${event.source} deviceId=${event.deviceId} flags=${event.flags}"
        )
    }

    private fun registerProbeReceiver() {
        val filter = IntentFilter()
        BROADCAST_ACTIONS.forEach { filter.addAction(it) }
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        ContextCompat.registerReceiver(
            this,
            probeReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        CarfuProbeLog.append("BroadcastReceiver registered for ${BROADCAST_ACTIONS.size} actions")
    }

    private fun logBroadcast(intent: Intent) {
        val extras = intent.extras
        val extraDump = if (extras == null || extras.isEmpty) {
            "(no extras)"
        } else {
            extras.keySet().joinToString { key ->
                "$key=${extras.get(key)}"
            }
        }
        CarfuProbeLog.append(
            "BROADCAST action=${intent.action} pkg=${intent.`package`} " +
                "component=${intent.component} extras={$extraDump}"
        )
    }

    private fun logIncomingIntent(intent: Intent?, origin: String) {
        if (intent == null) {
            CarfuProbeLog.append("$origin intent=null")
            return
        }
        CarfuProbeLog.append("$origin action=${intent.action} data=${intent.data} extras=${intent.extras}")
    }

    // --- Feature 3: Bluetooth Call Intent Tester -----------------------------------------------

    private fun testBluetoothCall() {
        val uri = Uri.parse(PROBE_TEL_URI)
        CarfuProbeLog.append("=== Bluetooth Call Intent Tester uri=$PROBE_TEL_URI ===")
        launchCallIntent(Intent.ACTION_DIAL, uri)

        if (!checkPermissions(this, Manifest.permission.CALL_PHONE)) {
            CarfuProbeLog.append("CALL_PHONE not granted — requesting before ACTION_CALL")
            pendingAfterPermission = {
                launchCallIntent(Intent.ACTION_CALL, uri)
                CarfuProbeLog.append("=== End Bluetooth Call Intent Tester ===")
            }
            permissionLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE))
            return
        }

        launchCallIntent(Intent.ACTION_CALL, uri)
        CarfuProbeLog.append("=== End Bluetooth Call Intent Tester ===")
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun launchCallIntent(action: String, uri: Uri) {
        val intent = Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = packageManager
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
        } catch (t: Throwable) {
            CarfuProbeLog.append("$action resolveActivity FAILED: ${t.message}")
            null
        }

        val handlers = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (t: Throwable) {
            CarfuProbeLog.append("$action queryIntentActivities FAILED: ${t.message}")
            emptyList()
        }

        CarfuProbeLog.append(
            "$action resolveActivity=${resolved?.activityInfo?.packageName}/" +
                "${resolved?.activityInfo?.name}"
        )
        if (handlers.isEmpty()) {
            CarfuProbeLog.append("$action handlers: (none)")
        } else {
            handlers.forEach { info ->
                val pkg = info.activityInfo.packageName
                val fyt = if (pkg in FytPackages.bluetoothDialer) "  << FYT dialer" else ""
                CarfuProbeLog.append("$action handler: $pkg/${info.activityInfo.name}$fyt")
            }
        }

        try {
            startActivity(intent)
            CarfuProbeLog.append("$action startActivity() launched")
        } catch (t: Throwable) {
            CarfuProbeLog.append("$action startActivity() FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // --- Feature 4: Foreground Audio Test ------------------------------------------------------

    private fun toggleAudioProbe() {
        if (audioRunning) {
            CarfuAudioProbeService.stop(this)
            audioRunning = false
            updateAudioButton()
            CarfuProbeLog.append("AudioProbe stop requested (service may take ~1s to exit)")
            return
        }

        if (!checkPermissions(this, Manifest.permission.RECORD_AUDIO)) {
            CarfuProbeLog.append("RECORD_AUDIO not granted — requesting, then start")
            pendingAfterPermission = { toggleAudioProbe() }
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        CarfuProbeLog.append("=== Foreground Audio Test ===")
        CarfuProbeLog.append("Starting AudioRecord FGS. Background the app or open YouTube/Zing MP3.")
        CarfuAudioProbeService.start(this)
        audioRunning = true
        updateAudioButton()
    }

    private fun updateAudioButton() {
        audioButton.setText(
            if (audioRunning) R.string.carfu_probe_stop_audio
            else R.string.carfu_probe_test_audio
        )
    }

    companion object {
        private const val PROBE_TEL_URI = "tel:123456"

        val BROADCAST_ACTIONS = listOf(
            Intent.ACTION_VOICE_COMMAND,
            Intent.ACTION_ASSIST,
            "android.speech.action.WEB_SEARCH",
            "android.speech.action.RECOGNIZE_SPEECH",
            Intent.ACTION_MEDIA_BUTTON,
            Intent.ACTION_CALL_BUTTON,
            "android.intent.action.VOICE_ASSIST",
            "com.syu.ms.action",
            "com.syu.ms.action.KEY",
            "com.syu.ms.action.VOICE",
            "com.syu.ms.action.KEYCODE",
            "com.syu.intent.action.KEY",
            "com.syu.broadcast",
            "notify.syu",
            "app.ACTION",
        )
    }
}
