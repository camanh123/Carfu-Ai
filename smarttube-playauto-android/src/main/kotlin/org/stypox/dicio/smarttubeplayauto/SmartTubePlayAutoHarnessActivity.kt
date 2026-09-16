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
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import org.stypox.dicio.youtubeplayauto.HttpYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolverEndpoint
import java.util.concurrent.Executors

/**
 * Standalone SmartTube diagnostic. Not production Voice.
 *
 * PlayAuto button: resolver only (Intent form unproven in source).
 * Probe buttons: one candidate Intent pinned to an explicitly selected
 * DEVICE_INSTALLED SmartTube package. Never the harness. No fallback.
 */
class SmartTubePlayAutoHarnessActivity : Activity() {

    private lateinit var queryField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var dryRunRadio: RadioButton
    private lateinit var packageGroup: RadioGroup
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var launcher: AndroidSmartTubeLauncher
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastResult: SmartTubePlayAutoResult? = null
    private var lastScan: List<SmartTubeInstalledPackage> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smarttube_playauto_harness)

        queryField = findViewById(R.id.harness_query)
        baseUrlField = findViewById(R.id.harness_base_url)
        dryRunRadio = findViewById(R.id.harness_mode_dry)
        packageGroup = findViewById(R.id.harness_package_group)
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

        logView.text = "Ready. Standalone SmartTube P1.1 diagnostic.\n" +
            "Harness ${SmartTubeHarnessIdentity.PACKAGE} is excluded from SmartTube targets.\n" +
            "Scan first. If both beta and stable are installed, select one package.\n" +
            "Each probe tap = one pinned Intent. No fallback. Never YouTube.\n" +
            "Not production Voice. DEVICE PASS not claimed.\n"
        scanPackages(showLog = false)
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

    private fun selectedPackage(): String? {
        val checkedId = packageGroup.checkedRadioButtonId
        if (checkedId == -1) return null
        val button = packageGroup.findViewById<RadioButton>(checkedId) ?: return null
        return button.tag as? String
    }

    private fun driver(form: SmartTubeLaunchForm? = null): SmartTubePlayAutoDriver {
        saveBaseUrl()
        return SmartTubePlayAutoDriver(
            resolverClient = HttpYouTubeResolverClient(baseUrlProvider = { configuredBaseUrl() }),
            launcher = launcher,
            options = SmartTubePlayAutoOptions(
                launchForm = form,
                selectedPackage = selectedPackage(),
            ),
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

    private fun scanPackages(showLog: Boolean = true) {
        worker.execute {
            val scanned = launcher.installedPackages()
            val selectable = SmartTubeTargetSelection.selectable(scanned)
            main.post {
                lastScan = scanned
                refreshPackageRadios(selectable)
                if (showLog) {
                    logView.text = formatScan(scanned, selectable)
                    logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun refreshPackageRadios(selectable: List<SmartTubeInstalledPackage>) {
        val previous = selectedPackage()
        packageGroup.removeAllViews()
        if (selectable.isEmpty()) {
            val empty = RadioButton(this)
            empty.isEnabled = false
            empty.text = "NONE — no DEVICE_INSTALLED SmartTube"
            empty.setTextColor(0xFF9E9E9E.toInt())
            packageGroup.addView(empty)
            return
        }
        selectable.forEach { pkg ->
            val radio = RadioButton(this)
            radio.id = View.generateViewId()
            radio.tag = pkg.packageName
            val label = pkg.applicationLabel?.takeIf { it.isNotBlank() } ?: pkg.packageName
            radio.text = "$label\n${pkg.packageName}"
            radio.setTextColor(0xFFEEEEEE.toInt())
            packageGroup.addView(radio)
        }
        val keep = selectable.find { it.packageName == previous }?.packageName
        val auto = if (selectable.size == 1) selectable[0].packageName else keep
        if (auto != null) {
            for (i in 0 until packageGroup.childCount) {
                val radio = packageGroup.getChildAt(i) as? RadioButton ?: continue
                if (radio.tag == auto) {
                    radio.isChecked = true
                    break
                }
            }
        }
    }

    private fun formatScan(
        scanned: List<SmartTubeInstalledPackage>,
        selectable: List<SmartTubeInstalledPackage>,
    ): String = buildString {
        appendLine("SCAN")
        appendLine("HARNESS_PACKAGE=${SmartTubeHarnessIdentity.PACKAGE}")
        appendLine("HARNESS_EXCLUDED=true")
        appendLine("Catalog candidates (not device-proven): ${SmartTubeCatalog.CATALOG_PACKAGES.joinToString()}")
        appendLine("Device query packages: ${SmartTubeCatalog.DEVICE_QUERY_PACKAGES.joinToString()}")
        appendLine("DEVICE_INSTALLED selectable: ${
            selectable.map { it.packageName }.ifEmpty { listOf("NONE") }.joinToString()
        }")
        if (selectable.size > 1) {
            appendLine("PACKAGE_SELECTION=required (do not silently choose)")
        }
        appendLine()
        scanned.forEach { pkg ->
            append(pkg.formatBlock())
            appendLine()
        }
        appendLine("Intent evidence: ${SmartTubeLaunchAudit.EVIDENCE}")
        appendLine("SOURCE_PROVEN launch form: ${SmartTubeLaunchAudit.SOURCE_PROVEN}")
        appendLine("DISPATCHED is not OPENED_SMARTTUBE / OPENED_EXACT_VIDEO / AUTOPLAY_STARTED")
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
        private const val PREFS_NAME = "carfu_smarttube_playauto_497"
    }
}
