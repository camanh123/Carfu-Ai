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
 * Standalone YouTube PlayAuto harness. Primary path is DIRECT_TARGET.
 * Accessibility fallback is off unless the tester checks the box.
 */
class YouTubePlayAutoHarnessActivity : Activity() {

    private lateinit var queryField: EditText
    private lateinit var dryRunRadio: RadioButton
    private lateinit var a11yFallback: CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var runtime: AndroidYouTubeRuntime
    private lateinit var adapter: YouTubeMediaAdapter
    private lateinit var selector: AndroidYouTubeInAppSelector
    private lateinit var searchClient: AndroidYouTubeHtmlSearchClient
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastResolved: ResolvedYouTubeTarget? = null
    private var lastResolutionMethod: String = "NONE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_playauto_harness)

        queryField = findViewById(R.id.harness_query)
        dryRunRadio = findViewById(R.id.harness_mode_dry)
        a11yFallback = findViewById(R.id.harness_a11y_fallback)
        logView = findViewById(R.id.harness_log)
        logScroll = findViewById(R.id.harness_log_scroll)

        runtime = AndroidYouTubeRuntime(this)
        adapter = YouTubeMediaAdapter(runtime)
        selector = AndroidYouTubeInAppSelector(this)
        searchClient = AndroidYouTubeHtmlSearchClient()
        a11yFallback.isChecked = false

        findViewById<Button>(R.id.btn_resolve).setOnClickListener { resolveOnly() }
        findViewById<Button>(R.id.btn_open_exact).setOnClickListener { openExact() }
        findViewById<Button>(R.id.btn_playauto).setOnClickListener { runPlayAuto() }
        findViewById<Button>(R.id.btn_a11y_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_export_diagnostic).setOnClickListener {
            exportDiagnostic()
        }

        logView.text = "Ready. Primary path: Resolve → watch URL → one launch. Accessibility fallback default OFF. Dry-run is default.\n"
    }

    private fun launchMode(): YouTubeLaunchMode =
        if (dryRunRadio.isChecked) YouTubeLaunchMode.DRY_RUN else YouTubeLaunchMode.DEVICE_TEST

    private fun driver(): YouTubePlayAutoDriver = YouTubePlayAutoDriver(
        adapter = adapter,
        selector = selector,
        resolver = defaultHarnessContentResolver(searchClient),
        options = YouTubePlayAutoOptions(accessibilityFallbackEnabled = a11yFallback.isChecked),
    )

    private fun playAutoRequest(): PlayAutoRequest = PlayAutoRequest(
        targetApp = "YouTube",
        query = queryField.text?.toString().orEmpty(),
    )

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
                                    path = "DIRECT_TARGET",
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
                                "Local query→videoId: only if the query is already an id or watch URL.\n",
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
                    path = "DIRECT_TARGET",
                )
            } else {
                lastResolved = target
                drive.openExact(target, query, launchMode())
            }
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
            if (result.resolverSuccess && result.resolvedVideoId != null) {
                lastResolved = ResolvedYouTubeTarget(
                    videoId = result.resolvedVideoId,
                    canonicalUri = result.targetUri ?: YouTubeVideoIdParser.canonicalWatchUri(result.resolvedVideoId),
                    title = result.resolvedTitle,
                    source = result.resolutionMethod ?: "playauto",
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
        appendLine("Resolved video id: ${resolution.target.videoId}")
        appendLine("Resolved title: ${resolution.target.title ?: "NONE"}")
        appendLine("Resolution method: ${resolution.target.source}")
        appendLine("Target URI: ${resolution.target.canonicalUri}")
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
        logView.text = buildString {
            appendLine("=== $title ===")
            appendLine("YouTube package: ${snap.packageName ?: "NONE"}")
            appendLine("Installed: ${snap.installed}")
            appendLine("Query: ${req.query}")
            appendLine("Resolved video id: ${lastResolved?.videoId ?: "NONE"}")
            appendLine("Resolved title: ${lastResolved?.title ?: "NONE"}")
            appendLine("Resolution method: ${lastResolved?.source ?: lastResolutionMethod}")
            appendLine("Target URI: ${lastResolved?.canonicalUri ?: "NONE"}")
            appendLine("Launch mode: ${launchMode()}")
            appendLine("Selected strategy: ${adapter.lastStrategy ?: PlaybackStrategy.DEEP_LINK}")
            appendLine("Launch spec: ${adapter.lastSpec ?: "none"}")
            appendLine("Dispatch result: ${dispatch ?: "none"}")
            appendLine("External launch this press: $launched")
            appendLine("Cast APIs used: NO")
            appendLine("Media keys sent: NO")
            appendLine("Accessibility fallback checkbox: ${if (a11yFallback.isChecked) "ON" else "OFF"}")
            if (extra.isNotBlank()) append(extra)
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportDiagnostic() {
        val text = buildString {
            appendLine("=== EXPORT DIAGNOSTIC 4.9 ===")
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
