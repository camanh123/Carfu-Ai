package org.stypox.dicio.smarttubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta
import org.stypox.dicio.youtubeplayauto.YouTubeVideoIdParser

class SmartTubePlayAutoDriverTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"
    val videoId = "W20zl5N_jbg"
    val watch = YouTubeVideoIdParser.canonicalWatchUri(videoId)
    val title = "$song - Hồ Hoàng Yến [Official 4K MV]"

    fun resolvedClient() = FakeYouTubeResolverClient(
        result = YouTubeResolveResult.Resolved(
            videoId = videoId,
            title = title,
            channelTitle = "MMG Studios",
            watchUrl = watch,
            cache = "MISS",
            meta = YouTubeResolverMeta(
                httpStatus = 200,
                requestAttempted = true,
                baseUrlConfigured = true,
                usedHttps = true,
            ),
        ),
    )

    fun driver(
        client: FakeYouTubeResolverClient = resolvedClient(),
        launcher: FakeSmartTubeLauncher = FakeSmartTubeLauncher(),
        form: SmartTubeLaunchForm? = SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED,
        selectedPackage: String? = null,
    ): Triple<SmartTubePlayAutoDriver, FakeYouTubeResolverClient, FakeSmartTubeLauncher> {
        val playAuto = SmartTubePlayAutoDriver(
            resolverClient = client,
            launcher = launcher,
            options = SmartTubePlayAutoOptions(
                launchForm = form,
                selectedPackage = selectedPackage,
            ),
        )
        return Triple(playAuto, client, launcher)
    }

    fun request(query: String = song, app: String = "SmartTube") = PlayAutoRequest(app, query)

    "1. query and SmartTube provider send the exact query to the existing resolver" {
        val client = resolvedClient()
        val (playAuto, _, _) = driver(client = client)
        playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        client.resolveCount shouldBe 1
        client.lastQuery shouldBe song
        client.lastQuery shouldNotBe "Mở bài Đừng Xa Em Đêm Nay trên SmartTube"
        playAuto.canHandle(request()) shouldBe true
        playAuto.canHandle(PlayAutoRequest("YouTube", song)) shouldBe false
    }

    "2. resolver videoId is delivered to the SmartTube launcher as the exact target" {
        val (playAuto, _, launcher) = driver()
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.resolvedVideoId shouldBe videoId
        result.targetUri shouldBe watch
        launcher.launchCount shouldBe 1
        launcher.lastSpec?.videoId shouldBe videoId
        launcher.lastSpec?.uri shouldBe watch
        launcher.receivedTargets.single().videoId shouldBe videoId
        launcher.lastSpec?.packageName shouldBe SmartTubeCatalog.TEAMSMART
        launcher.lastSpec?.action shouldBe SmartTubeLaunchAudit.ACTION_VIEW
        ForbiddenYouTubePackages.isYouTube(launcher.lastSpec?.packageName) shouldBe false
    }

    "3. resolvedTitle is preserved" {
        val (playAuto, _, launcher) = driver()
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.resolvedTitle shouldBe title
        launcher.lastSpec?.resolvedTitle shouldBe title
        launcher.receivedTargets.single().resolvedTitle shouldBe title
    }

    "4. one request performs at most one external launch" {
        val (playAuto, _, launcher) = driver()
        playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        launcher.launchCount shouldBe 1
        launcher.launchCount shouldBeLessThanOrEqual 1
        playAuto.executeCount shouldBe 1
        playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        playAuto.executeCount shouldBe 2
        launcher.launchCount shouldBe 1
    }

    "5. resolver failure performs zero launches" {
        val client = FakeYouTubeResolverClient(result = YouTubeResolveResult.NoResults())
        val launcher = FakeSmartTubeLauncher()
        val (playAuto, _, _) = driver(client = client, launcher = launcher)
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe false
        result.failure shouldBe "NO_RESULTS"
        result.launchAttempted shouldBe false
        launcher.launchCount shouldBe 0
        client.lastQuery shouldBe song
    }

    "6. SmartTube unavailable performs zero unsafe launches" {
        val launcher = FakeSmartTubeLauncher(catalogInstalled = emptyList())
        val (playAuto, _, _) = driver(launcher = launcher)
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe true
        result.resolvedVideoId shouldBe videoId
        result.failure shouldBe "smarttube_unavailable"
        result.launchAttempted shouldBe false
        launcher.launchCount shouldBe 0
        launcher.youtubePackageLaunchCount shouldBe 0
    }

    "7. failed SmartTube execute does not fall back to YouTube" {
        val launcher = FakeSmartTubeLauncher(launchSucceeds = false)
        val (playAuto, _, _) = driver(launcher = launcher)
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.launchAttempted shouldBe true
        result.failure shouldBe "smarttube_launch_failed"
        result.youtubeFallbackUsed shouldBe false
        launcher.launchCount shouldBe 1
        launcher.youtubePackageLaunchCount shouldBe 0
        ForbiddenYouTubePackages.isYouTube(launcher.lastSpec?.packageName) shouldBe false
        playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        launcher.launchCount shouldBe 1
        launcher.youtubePackageLaunchCount shouldBe 0
    }

    "default PlayAuto does not guess an unproven SmartTube Intent" {
        val launcher = FakeSmartTubeLauncher()
        val (playAuto, client, _) = driver(launcher = launcher, form = null)
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe true
        result.resolvedVideoId shouldBe videoId
        result.resolvedTitle shouldBe title
        result.failure shouldBe SmartTubeLaunchAudit.UNPROVEN
        result.launchAttempted shouldBe false
        result.path shouldBe "RESOLVE_ONLY"
        launcher.launchCount shouldBe 0
        client.lastQuery shouldBe song
        SmartTubeLaunchAudit.SOURCE_PROVEN shouldBe false
    }

    "unpinned Intent that would open YouTube is refused without launching" {
        val launcher = FakeSmartTubeLauncher(
            resolveActivityOverride = SmartTubeResolveActivity(
                component = "com.google.android.youtube/.Watch",
                packageName = "com.google.android.youtube",
            ),
        )
        val (playAuto, _, _) = driver(
            launcher = launcher,
            form = SmartTubeLaunchForm.VIEW_WATCH_URL_UNPINNED,
        )
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.launchAttempted shouldBe false
        result.failure shouldBe "would_launch_youtube_not_smarttube"
        result.youtubeFallbackUsed shouldBe false
        launcher.launchCount shouldBe 0
        launcher.youtubePackageLaunchCount shouldBe 0
    }

    "blank query and non-SmartTube target do not launch" {
        val launcher = FakeSmartTubeLauncher()
        val (playAuto, client, _) = driver(launcher = launcher)
        playAuto.execute(request(query = "  "), YouTubeLaunchMode.DEVICE_TEST)
            .failure shouldBe "blank_query"
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
            .failure shouldBe "unsupported_target_app"
        launcher.launchCount shouldBe 0
        client.resolveCount shouldBe 0
    }

    "accessibility, Cast, and media keys stay unused" {
        val (playAuto, _, _) = driver()
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.accessibilityUsed shouldBe false
        result.castApisUsed shouldBe false
        result.mediaKeysSent shouldBe false
        result.formatHarness() shouldContain "Accessibility used: NO"
        result.formatHarness() shouldContain "Cast APIs used: NO"
        result.exactVideoTargetRequested shouldBe true
    }

    "harness package can never become the selected SmartTube target" {
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(
                SmartTubeInstalledPackage(
                    packageName = SmartTubeHarnessIdentity.PACKAGE,
                    installed = true,
                    applicationLabel = "CARFU SmartTube P1.1",
                    launchActivity = "${SmartTubeHarnessIdentity.PACKAGE}/.SmartTubePlayAutoHarnessActivity",
                    evidence = setOf(SmartTubeEvidence.DEVICE_INSTALLED),
                    source = "harness_fixture",
                ),
            ),
        )
        val (playAuto, _, _) = driver(launcher = launcher)
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.launchAttempted shouldBe false
        result.failure shouldBe "smarttube_unavailable"
        result.targetPackage shouldBe null
        launcher.launchCount shouldBe 0
        launcher.harnessPackageLaunchCount shouldBe 0
        launcher.preferredInstalledPackage() shouldBe null

        val forced = driver(
            launcher = launcher,
            selectedPackage = SmartTubeHarnessIdentity.PACKAGE,
        ).first.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        forced.launchAttempted shouldBe false
        forced.failure shouldBe "harness_package_forbidden"
        launcher.launchCount shouldBe 0
        launcher.harnessPackageLaunchCount shouldBe 0
    }

    "MAIN that would resolve to the harness is refused without launching" {
        val launcher = FakeSmartTubeLauncher(
            resolveActivityOverride = SmartTubeResolveActivity(
                component = "${SmartTubeHarnessIdentity.PACKAGE}/.SmartTubePlayAutoHarnessActivity",
                packageName = SmartTubeHarnessIdentity.PACKAGE,
            ),
        )
        val (playAuto, _, _) = driver(
            launcher = launcher,
            form = SmartTubeLaunchForm.MAIN_LAUNCHER,
        )
        val result = playAuto.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        result.launchAttempted shouldBe false
        result.failure shouldBe "would_launch_harness_not_smarttube"
        result.resolveActivityPackage shouldBe SmartTubeHarnessIdentity.PACKAGE
        launcher.launchCount shouldBe 0
        launcher.harnessPackageLaunchCount shouldBe 0
    }

    "beta and stable both installed require explicit package selection" {
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(
                SmartTubeInstalledPackage(
                    packageName = SmartTubeCatalog.ORG_SMARTTUBE_BETA,
                    installed = true,
                    applicationLabel = "SmartTube Beta",
                    evidence = setOf(
                        SmartTubeEvidence.DEVICE_INSTALLED,
                        SmartTubeEvidence.DEVICE_LAUNCHABLE,
                        SmartTubeEvidence.DEVICE_LABEL_MATCH,
                    ),
                    source = "device_query",
                ),
                SmartTubeInstalledPackage(
                    packageName = SmartTubeCatalog.ORG_SMARTTUBE_STABLE,
                    installed = true,
                    applicationLabel = "SmartTube",
                    evidence = setOf(
                        SmartTubeEvidence.DEVICE_INSTALLED,
                        SmartTubeEvidence.DEVICE_LAUNCHABLE,
                        SmartTubeEvidence.DEVICE_LABEL_MATCH,
                    ),
                    source = "device_query",
                ),
            ),
        )
        val unspecified = driver(launcher = launcher).first
            .execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        unspecified.launchAttempted shouldBe false
        unspecified.failure shouldBe "package_selection_required"
        launcher.launchCount shouldBe 0

        val selected = driver(
            launcher = launcher,
            selectedPackage = SmartTubeCatalog.ORG_SMARTTUBE_BETA,
        ).first.execute(request(), YouTubeLaunchMode.DEVICE_TEST)
        selected.launchAttempted shouldBe true
        selected.targetPackage shouldBe SmartTubeCatalog.ORG_SMARTTUBE_BETA
        selected.failure shouldBe null
        launcher.launchCount shouldBe 1
        launcher.lastSpec?.packageName shouldBe SmartTubeCatalog.ORG_SMARTTUBE_BETA
        launcher.harnessPackageLaunchCount shouldBe 0
        launcher.youtubePackageLaunchCount shouldBe 0
    }
})
