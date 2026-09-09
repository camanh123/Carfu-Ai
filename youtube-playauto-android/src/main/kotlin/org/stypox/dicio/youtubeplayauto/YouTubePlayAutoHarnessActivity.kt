package org.stypox.dicio.youtubeplayauto

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlayAutoEngine
import org.stypox.dicio.playauto.core.PlaybackResult
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider
import org.stypox.dicio.playauto.provider.ProviderRegistry

/**
 * Standalone Phase 4.7 device harness. Not the CARFU voice flow.
 * No launch on startup; the tester must press a button.
 */
class YouTubePlayAutoHarnessActivity : Activity() {

    private lateinit var queryField: EditText
    private lateinit var videoIdField: EditText
    private lateinit var dryRunRadio: RadioButton
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var runtime: AndroidYouTubeRuntime
    private lateinit var adapter: YouTubeMediaAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_playauto_harness)

        queryField = findViewById(R.id.harness_query)
        videoIdField = findViewById(R.id.harness_video_id)
        dryRunRadio = findViewById(R.id.harness_mode_dry)
        logView = findViewById(R.id.harness_log)
        logScroll = findViewById(R.id.harness_log_scroll)

        runtime = AndroidYouTubeRuntime(this)
        adapter = YouTubeMediaAdapter(runtime)

        findViewById<Button>(R.id.btn_detect).setOnClickListener { detectOnly() }
        findViewById<Button>(R.id.btn_open_app).setOnClickListener {
            runStrategy(PlaybackStrategy.OPEN_APP)
        }
        findViewById<Button>(R.id.btn_search).setOnClickListener {
            runStrategy(PlaybackStrategy.SEARCH)
        }
        findViewById<Button>(R.id.btn_deep_link).setOnClickListener {
            runStrategy(PlaybackStrategy.DEEP_LINK)
        }
        findViewById<Button>(R.id.btn_direct_play).setOnClickListener { reportDirectPlay() }

        logView.text = "Ready. Dry-run is default. Press Detect YouTube. No app is launched until Device test + a strategy button.\n"
    }

    private fun launchMode(): YouTubeLaunchMode =
        if (dryRunRadio.isChecked) YouTubeLaunchMode.DRY_RUN else YouTubeLaunchMode.DEVICE_TEST

    private fun request(): MediaRequest = MediaRequest(
        query = queryField.text?.toString().orEmpty(),
        mediaType = MediaType.AUDIO,
        preferredProvider = MediaProvider.YOUTUBE,
    )

    private fun injectedTarget(): ResolvedYouTubeTarget? {
        val id = YouTubeVideoIdParser.parse(videoIdField.text?.toString().orEmpty()) ?: return null
        return ResolvedYouTubeTarget(
            videoId = id,
            canonicalUri = YouTubeVideoIdParser.canonicalWatchUri(id),
            source = "harness_injected",
        )
    }

    private fun detectOnly() {
        adapter.launchMode = YouTubeLaunchMode.DRY_RUN
        val snap = adapter.detect()
        val enginePreview = previewEngine(snap)
        render("DETECT", snap, enginePreview, launched = false)
    }

    private fun runStrategy(strategy: PlaybackStrategy) {
        adapter.launchMode = launchMode()
        val snap = adapter.detect()
        val req = request()
        val injected = injectedTarget()
        if (strategy == PlaybackStrategy.DEEP_LINK && injected == null) {
            render(
                title = "DEEP_LINK",
                snap = snap,
                extra = "Resolved target: NONE\nSelected strategy: DEEP_LINK\nDispatch result: skipped\nFailure reason: no_resolved_video_target\nHighest provenance possible this phase: INTENT_DISPATCHED (not claimed)\n",
                launched = false,
            )
            return
        }
        val outcome = adapter.executeExplicit(req, strategy, injected)
        render(
            title = strategy.name,
            snap = snap,
            extra = buildString {
                appendLine("PlayAuto execute: $outcome")
                appendLine("Adapter executionCount: ${adapter.executionCount}")
                appendLine("Adapter dispatchCount: ${adapter.dispatchCount}")
            },
            launched = adapter.launchMode == YouTubeLaunchMode.DEVICE_TEST &&
                adapter.lastDispatch is YouTubeDispatchOutcome.Dispatched,
        )
    }

    private fun reportDirectPlay() {
        adapter.detect()
        val injected = injectedTarget()
        val built = YouTubeDirectPlayStrategy.build(
            YouTubeStrategyInput(
                packageName = adapter.lastSnapshot.packageName,
                query = request().query,
                resolved = injected,
            ),
        )
        render(
            title = "DIRECT_PLAY",
            snap = adapter.lastSnapshot,
            extra = buildString {
                appendLine("Selected strategy: DIRECT_PLAY")
                appendLine("Resolved target: ${injected?.canonicalUri ?: "NONE"}")
                appendLine("Direct-play implementation: NOT PROVEN")
                appendLine("Build result: $built")
                appendLine("Dispatch result: skipped (will not launch a watch URL as DIRECT_PLAY)")
                appendLine("Provenance: not claimed")
            },
            launched = false,
        )
    }

    private fun previewEngine(snap: YouTubeCapabilitySnapshot): String {
        if (!snap.installed || snap.engineCapabilities.isEmpty()) {
            return "PlayAutoEngine preview: provider unavailable / no engine capability"
        }
        val dry = YouTubeMediaAdapter(runtime, launchMode = YouTubeLaunchMode.DRY_RUN)
        dry.detect()
        val registry = ProviderRegistry()
        registry.register(dry)
        return when (val result = PlayAutoEngine(registry).execute(request())) {
            is PlaybackResult.Success ->
                "PlayAutoEngine preview: Success strategy=${result.strategy} (dry-run, no extra launch)"
            is PlaybackResult.Failure ->
                "PlayAutoEngine preview: Failure ${result.reason} ${result.detail}"
        }
    }

    private fun render(
        title: String,
        snap: YouTubeCapabilitySnapshot,
        extra: String = "",
        launched: Boolean,
    ) {
        val req = request()
        val injected = injectedTarget()
        val dispatch = adapter.lastDispatch
        val spec = adapter.lastSpec
        val provenance = when (dispatch) {
            is YouTubeDispatchOutcome.DryRun -> dispatch.provenance
            is YouTubeDispatchOutcome.Dispatched -> dispatch.provenance
            is YouTubeDispatchOutcome.Failed -> null
            null -> null
        }
        logView.text = buildString {
            appendLine("=== $title ===")
            appendLine("YouTube package: ${snap.packageName ?: "NONE"}")
            appendLine("Installed: ${snap.installed}")
            appendLine("Resolved activity: ${snap.launchActivity ?: "NONE"}")
            appendLine("Version: ${snap.versionName ?: "NONE"}")
            appendLine("Capabilities (engine): ${snap.engineCapabilities}")
            appendLine("OPEN_APP resolvable: ${snap.openAppResolvable}")
            appendLine("SEARCH resolvable: ${snap.searchResolvable}")
            appendLine("DEEP_LINK activity resolvable: ${snap.deepLinkActivityResolvable}")
            appendLine("DIRECT_PLAY supported: ${snap.directPlaySupported}")
            appendLine("Notes: ${snap.notes}")
            appendLine("Query: ${req.query}")
            appendLine("Resolved target: ${injected?.canonicalUri ?: adapter.lastTarget?.descriptor ?: "NONE"}")
            appendLine("Selected strategy: ${adapter.lastStrategy ?: title}")
            appendLine("Launch mode: ${adapter.launchMode}")
            appendLine("Launch spec: $spec")
            appendLine("Dispatch result: ${dispatch ?: "none"}")
            appendLine("Provenance: ${provenance ?: "none"} (PLAYBACK_CONFIRMED is never claimed)")
            appendLine("External launch this press: $launched")
            appendLine("Failure reason: ${(dispatch as? YouTubeDispatchOutcome.Failed)?.detail ?: "none"}")
            if (extra.isNotBlank()) append(extra)
        }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
