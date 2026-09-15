package org.stypox.dicio.smarttubeplayauto

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.stypox.dicio.youtubeplayauto.HttpYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolverEndpoint
import java.util.concurrent.Executors

/**
 * Standalone SmartTube diagnostic. Not production Voice.
 *
 * PlayAuto button: resolver only (Intent form unproven in source).
 * Probe buttons: one candidate Intent, exactly one launch attempt, no fallback.
 */
class SmartTubePlayAutoHarnessActivity : Activity() {

    private lateinit var queryField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var dryRunRadio: RadioButton
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var launcher: AndroidSmartTubeLauncher
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastResult: SmartTubePlayAutoResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smarttube_playauto_harness)

        queryField = findViewById(R.id.harness_query)
        baseUrlField = findViewById(R.id.harness_base_url)
        dryRunRadio = findViewById(R.id.harness_mode_dry)
        logView = findViewById(R.id.harness_log)
        logScroll = findViewById(R.id.harness_log_scroll)

        launcher = AndroidSmartTubeLauncher(this)
        baseUrlField.setText(loadBaseUrl())

        findViewById<Button>(R.id.btn_resolve).setOnClickListener { runPlayAuto() }
        findViewById<Button>(R.id.btn_scan).setOnClickListener { scanPackages() }
        findViewById<Button>(R.id.btn_playauto).setOnClickListener { runPlayAuto() }
        findViewById<Button>(R.id.btn_probe_watch_pinned).setOnClickListener {
            probe(SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED)
        }
        findViewById<Button>(R.id.btn_probe_watch_unpinned).setOnClickListener {
            probe(SmartTubeLaunchForm.VIEW_WATCH_URL_UNPINNED)
        }
        findViewById<Button>(R.id.btn_probe_youtu_be).setOnClickListener {
            probe(SmartTubeLaunchForm.VIEW_YOUTU_BE_PINNED)
        }
        findViewById<Button>(R.id.btn_probe_vnd).setOnClickListener {
            probe(SmartTubeLaunchForm.VIEW_VND_YOUTUBE_PINNED)
        }
        findViewById<Button>(R.id.btn_probe_main).setOnClickListener {
            probe(SmartTubeLaunchForm.MAIN_LAUNCHER)
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportDiagnostic() }

        logView.text = "Ready. Standalone SmartTube diagnostic.\n" +
            "PlayAuto = existing resolver only (Intent unproven; no guessed launch).\n" +
            "Each probe tap = one candidate Intent. No fallback. Never YouTube.\n" +
            "Not production Voice. DEVICE PASS not claimed.\n"
    }

    private fun launchMode(): YouTubeLaunchMode =
        if (dryRunRadio.isChecked) YouTubeLaunchMode.DRY_RUN else YouTubeLaunchMode.DEVICE_TEST

    private fun playAutoRequest(): PlayAutoRequest = PlayAutoRequest(
        targetApp = "SmartTube",
        query = queryField.text?.toString().orEmpty(),
    )

    private fun configuredBaseUrl(): String = YouTubeResolverEndpoint.normalizeBaseUrl(
        baseUrlField.text?.toString().orEmpty(),
    )

    private fun driver(form: SmartTubeLaunchForm? = null): SmartTubePlayAutoDriver {
        saveBaseUrl()
        return SmartTubePlayAutoDriver(
            resolverClient = HttpYouTubeResolverClient(baseUrlProvider = { configuredBaseUrl() }),
            launcher = launcher,
            options = SmartTubePlayAutoOptions(launchForm = form),
        )
    }

    private fun loadBaseUrl(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(YouTubeResolverEndpoint.PREFS_KEY, null)
        if (!saved.isNullOrBlank()) return saved
        return BuildConfig.CARFU_RESOLVER_BASE_URL.ifBlank {
            YouTubeResolverEndpoint.DEFAULT_PUBLIC_HTTPS_BASE_URL
        }
    }

    private fun saveBaseUrl() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(YouTubeResolverEndpoint.PREFS_KEY, configuredBaseUrl())
            .apply()
    }

    private fun runPlayAuto() {
        val request = playAutoRequest()
        worker.execute {
            val result = driver(form = null).execute(request, launchMode())
            main.post { render("PLAYAUTO_RESOLVE_ONLY", result) }
        }
    }

    private fun probe(form: SmartTubeLaunchForm) {
        val request = playAutoRequest()
        worker.execute {
            val result = driver(form = form).execute(request, launchMode())
            main.post { render("PROBE_${form.name}", result) }
        }
    }

    private fun scanPackages() {
        worker.execute {
            val installed = launcher.installedPackages()
            val text = buildString {
                appendLine("SCAN")
                appendLine("Catalog candidates (not device-proven): ${SmartTubeCatalog.CATALOG_PACKAGES.joinToString()}")
                appendLine("Package evidence: ${SmartTubeCatalog.PACKAGE_EVIDENCE}")
                if (installed.isEmpty()) {
                    appendLine("Installed SmartTube package(s): NONE")
                } else {
                    installed.forEach { pkg ->
                        appendLine(
                            "installed: ${pkg.packageName} version=${pkg.versionName ?: "NONE"} " +
                                "launch=${pkg.launchActivity ?: "NONE"} source=${pkg.source}",
                        )
                    }
                }
                appendLine("Intent evidence: ${SmartTubeLaunchAudit.EVIDENCE}")
                appendLine("SOURCE_PROVEN launch form: ${SmartTubeLaunchAudit.SOURCE_PROVEN}")
            }
            main.post {
                logView.text = text
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun render(action: String, result: SmartTubePlayAutoResult) {
        lastResult = result
        logView.text = "$action\n${result.formatHarness()}"
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportDiagnostic() {
        val text = lastResult?.formatHarness() ?: logView.text?.toString().orEmpty()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("smarttube-playauto", text))
        Toast.makeText(this, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS_NAME = "carfu_smarttube_playauto_496"
    }
}
