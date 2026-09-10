package org.stypox.dicio.youtubeplayauto

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.stypox.dicio.playauto.core.PlaybackStrategy
import java.util.concurrent.Executors

/**
 * Standalone YouTube PlayAuto harness. Phase 4.9.2 jack:
 * PlayAutoRequest → CARFU resolver HTTP → existing DIRECT_TARGET launcher.
 * Accessibility fallback is off unless the tester checks the box, and the
 * resolver jack never uses it.
 */
class YouTubePlayAutoHarnessActivity : Activity() {

    private lateinit var queryField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var dryRunRadio: RadioButton
    private lateinit var a11yFallback: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var runtime: AndroidYouTubeRuntime
    private lateinit var adapter: YouTubeMediaAdapter
    private lateinit var selector: AndroidYouTubeInAppSelector
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastResolved: ResolvedYouTubeTarget? = null
    private var lastPlayAuto: YouTubePlayAutoResult? = null
    private var lastResolutionMethod: String = "NONE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_playauto_harness)

        queryField = findViewById(R.id.harness_query)
        baseUrlField = findViewById(R.id.harness_base_url)
        dryRunRadio = findViewById(R.id.harness_mode_dry)
        a11yFallback = findViewById(R.id.harness_a11y_fallback)
        logView = findViewById(R.id.harness_log)
        logScroll = findViewById(R.id.harness_log_scroll)

        runtime = AndroidYouTubeRuntime(this)
        adapter = YouTubeMediaAdapter(runtime)
        selector = AndroidYouTubeInAppSelector(this)
        a11yFallback.isChecked = false
        baseUrlField.setText(loadBaseUrl())

        findViewById<Button>(R.id.btn_resolve).setOnClickListener { resolveOnly() }
        findViewById<Button>(R.id.btn_open_exact).setOnClickListener { openExact() }
        findViewById<Button>(R.id.btn_playauto).setOnClickListener { runPlayAuto() }
        findViewById<Button>(R.id.btn_a11y_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_export_diagnostic).setOnClickListener {
            exportDiagnostic()
        }

        logView.text = "Ready. Path: PlayAuto → resolver HTTP → exact watch URL → one ACTION_VIEW.\n" +
            "No API key on Android. Accessibility fallback default OFF. Dry-run is default.\n"
    }

    private fun launchMode(): YouTubeLaunchMode =
        if (dryRunRadio.isChecked) YouTubeLaunchMode.DRY_RUN else YouTubeLaunchMode.DEVICE_TEST

    private fun driver(): YouTubePlayAutoDriver {
        saveBaseUrl()
        return YouTubePlayAutoDriver(
            adapter = adapter,
            selector = selector,
            resolver = ParsedYouTubeContentResolver,
            options = YouTubePlayAutoOptions(accessibilityFallbackEnabled = a11yFallback.isChecked),
            resolverClient = HttpYouTubeResolverClient(baseUrlProvider = { configuredBaseUrl() }),
        )
    }

    private fun playAutoRequest(): PlayAutoRequest = PlayAutoRequest(
        targetApp = "YouTube",
        query = queryField.text?.toString().orEmpty(),
    )

    private fun configuredBaseUrl(): String = YouTubeResolverEndpoint.normalizeBaseUrl(
        baseUrlField.text?.toString().orEmpty(),
    )

    private fun loadBaseUrl(): String {
        val prefs = getSharedPreferences(YouTubeResolverEndpoint.PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(YouTubeResolverEndpoint.PREFS_KEY, null)
        if (!saved.isNullOrBlank()) return saved
        return BuildConfig.CARFU_RESOLVER_BASE_URL
    }

    private fun saveBaseUrl() {
        getSharedPreferences(YouTubeResolverEndpoint.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(YouTubeResolverEndpoint.PREFS_KEY, configuredBaseUrl())
            .apply()
    }

    private fun resolveOnly() {
        val query = playAutoRequest().query
        worker.execute {
            val resolution = driver().resolveOnly(query)
            main.post {
                when (resolution) {
                    is YouTubeContentResolution.Resolved -> {
                        lastResolved = resolution.target
                        lastResolutionMethod = resolution.target.source
                        render(
                            "RESOLVE",
                            extra = formatResolution(resolution) +
                                YouTubePlayAutoResult(
                                    targetApp = "YouTube",
                                    query = query,
                                    searchOpened = false,
                                    resultSelected = false,
                                    playbackRequested = false,
                                    resolvedVideoId = resolution.target.videoId,
                                    resolvedTitle = resolution.target.title,
                                    resolutionMethod = resolution.target.source,
                                    targetUri = resolution.target.canonicalUri,
                                    resolverSuccess = true,
                                    launchAttempted = false,
                                    launchResult = "NOT_LAUNCHED",
                                    path = "RESOLVER_DIRECT_TARGET",
                                    resolverBaseUrlConfigured = YouTubeResolverEndpoint.isConfigured(configuredBaseUrl()),
                                    resolverRequestAttempted = resolution.target.source == "carfu_resolver_service",
                                    resolverStatus = "RESOLVED",
                                ).formatHarness(),
                            launched = false,
                        )
                    }
                    is YouTubeContentResolution.Unresolved -> {
                        lastResolved = null
                        lastResolutionMethod = "unresolved"
                        render(
                            "RESOLVE",
                            extra = "Resolver success: NO\nFailure: ${resolution.reason}\n" +
                                "Launch attempted: NO\nPath: RESOLVER_DIRECT_TARGET\n",
                            launched = false,
                        )
                    }
                }
            }
        }
    }

    private fun openExact() {
        worker.execute {
            val query = playAutoRequest().query
            val drive = driver()
            val target = lastResolved ?: when (val resolution = drive.resolveOnly(query)) {
                is YouTubeContentResolution.Resolved -> resolution.target
                is YouTubeContentResolution.Unresolved -> null
            }
            val result = if (target == null) {
                YouTubePlayAutoResult(
                    targetApp = "YouTube",
                    query = query,
                    searchOpened = false,
                    resultSelected = false,
                    playbackRequested = false,
                    failure = "no_resolved_video_target",
                    launchResult = "NOT_LAUNCHED",
                    path = "RESOLVER_DIRECT_TARGET",
                    resolverBaseUrlConfigured = YouTubeResolverEndpoint.isConfigured(configuredBaseUrl()),
                )
            } else {
                lastResolved = target
                drive.openExact(target, query, launchMode())
            }
            lastPlayAuto = result
            main.post {
                render(
                    "OPEN EXACT VIDEO",
                    extra = result.formatHarness() + "Failure: ${result.failure ?: "none"}\n",
                    launched = launchMode() == YouTubeLaunchMode.DEVICE_TEST && result.launchAttempted,
                )
            }
        }
    }

    private fun runPlayAuto() {
        worker.execute {
            val result = driver().execute(playAutoRequest(), launchMode())
            lastPlayAuto = result
            if (result.resolverSuccess && result.resolvedVideoId != null) {
                lastResolved = ResolvedYouTubeTarget(
                    videoId = result.resolvedVideoId,
                    canonicalUri = result.targetUri
                        ?: YouTubeVideoIdParser.canonicalWatchUri(result.resolvedVideoId),
                    title = result.resolvedTitle,
                    source = result.resolutionMethod ?: "carfu_resolver_service",
                )
            }
            main.post {
                render(
                    "PLAYAUTO YOUTUBE",
                    extra = formatPlayAuto(result),
                    launched = launchMode() == YouTubeLaunchMode.DEVICE_TEST && result.launchAttempted,
                )
            }
        }
    }

    private fun formatResolution(resolution: YouTubeContentResolution.Resolved): String = buildString {
        appendLine("Resolved videoId: ${resolution.target.videoId}")
        appendLine("Resolved title: ${resolution.target.title ?: "NONE"}")
        appendLine("Resolved watchUrl: ${resolution.target.canonicalUri}")
        appendLine("Resolution method: ${resolution.target.source}")
    }

    private fun formatPlayAuto(result: YouTubePlayAutoResult): String = buildString {
        appendLine("PlayAutoRequest.targetApp: ${result.targetApp}")
        appendLine("PlayAutoRequest.query: ${result.query}")
        append(result.formatHarness())
        appendLine("Search opened: ${result.searchOpened}")
        appendLine("Result selected: ${result.resultSelected}")
        appendLine("Select attempt count: ${result.selectAttemptCount}")
        appendLine("YouTube left open: ${result.youtubeLeftOpen}")
        appendLine("Failure: ${result.failure ?: "none"}")
        appendLine("Voice connected: NO")
        if (result.accessibilityFallbackUsed) {
            append(YouTubePlayAutoSelectBus.diagnostics().format())
        }
    }

    private fun render(
        title: String,
        extra: String = "",
        launched: Boolean,
    ) {
        val snap = adapter.detect()
        val req = playAutoRequest()
        val dispatch = adapter.lastDispatch
        val base = configuredBaseUrl()
        val result = lastPlayAuto
        logView.text = buildString {
            appendLine("=== $title ===")
            appendLine("YouTube package: ${snap.packageName ?: "NONE"}")
            appendLine("Installed: ${snap.installed}")
            appendLine("Query: ${req.query}")
            appendLine("Resolver base URL configured: ${if (YouTubeResolverEndpoint.isConfigured(base)) "YES" else "NO"}")
            if (YouTubeResolverEndpoint.isCleartextHttp(base)) {
                appendLine("HTTPS unavailable: cleartext HTTP configured for harness/dev only (not production TLS weakening)")
            }
            appendLine("Resolver request attempted: ${if (result?.resolverRequestAttempted == true) "YES" else "NO"}")
            appendLine("Resolver status: ${result?.resolverStatus ?: "NONE"}")
            appendLine("HTTP status: ${result?.resolverHttpStatus?.toString() ?: "NONE"}")
            appendLine("Resolved videoId: ${result?.resolvedVideoId ?: lastResolved?.videoId ?: "NONE"}")
            appendLine("Resolved title: ${result?.resolvedTitle ?: lastResolved?.title ?: "NONE"}")
            appendLine("Resolved channel: ${result?.resolvedChannelTitle ?: "NONE"}")
            appendLine("Resolved watchUrl: ${result?.targetUri ?: lastResolved?.canonicalUri ?: "NONE"}")
            appendLine("Resolver cache: ${result?.resolverCache ?: "NONE"}")
            appendLine("Resolver latency: ${result?.resolverLatencyMs?.let { "${it}ms" } ?: "NONE"}")
            appendLine("Launch attempted: ${if (result?.launchAttempted == true) "YES" else "NO"}")
            appendLine("Launch result: ${result?.launchResult ?: "NONE"}")
            appendLine("Path: ${result?.path ?: "RESOLVER_DIRECT_TARGET"}")
            appendLine("Resolution method: ${lastResolved?.source ?: lastResolutionMethod}")
            appendLine("Launch mode: ${launchMode()}")
            appendLine("Selected strategy: ${adapter.lastStrategy ?: PlaybackStrategy.DEEP_LINK}")
            appendLine("Launch spec: ${adapter.lastSpec ?: "none"}")
            appendLine("Dispatch result: ${dispatch ?: "none"}")
            appendLine("External launch this press: $launched")
            appendLine("Accessibility fallback used: NO")
            appendLine("Cast APIs used: NO")
            appendLine("Media keys sent: NO")
            appendLine("Accessibility fallback checkbox: ${if (a11yFallback.isChecked) "ON" else "OFF"}")
            if (extra.isNotBlank()) append(extra)
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportDiagnostic() {
        val text = buildString {
            appendLine("=== EXPORT DIAGNOSTIC 4.9.2 ===")
            append(logView.text)
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("carfu-playauto-diagnostic", text))
            Toast.makeText(this, "Diagnostic copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        logView.text = text
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
