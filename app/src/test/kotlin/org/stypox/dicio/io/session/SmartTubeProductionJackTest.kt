package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.smarttubeplayauto.FakeSmartTubeLauncher
import org.stypox.dicio.smarttubeplayauto.SmartTubeCatalog
import org.stypox.dicio.smarttubeplayauto.SmartTubeEvidence
import org.stypox.dicio.smarttubeplayauto.SmartTubeHarnessIdentity
import org.stypox.dicio.smarttubeplayauto.SmartTubeInstalledPackage
import org.stypox.dicio.smarttubeplayauto.SmartTubeLaunchAudit
import org.stypox.dicio.smarttubeplayauto.SmartTubeProductionPolicy
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta

/**
 * Phase 4.9.4 — production PlayMedia(SmartTube) jack onto the DEVICE-PROVEN Probe A path.
 * Does not re-parse Vietnamese. Does not use YouTube, beta, or the diagnostic harness.
 */
class SmartTubeProductionJackTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
        StableCompletePartialTracker.resetForTests()
    }

    val song = "Đừng Xa Em Đêm Nay"
    val watch = "https://www.youtube.com/watch?v=W20zl5N_jbg"
    val videoId = "W20zl5N_jbg"
    val title = "$song - Hồ Hoàng Yến [Official 4K MV]"
    val stable = SmartTubeProductionPolicy.PACKAGE

    fun platform(): Phase3FakePlatform = Phase3FakePlatform()

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

    fun pkg(
        name: String,
        label: String = "SmartTube",
    ) = SmartTubeInstalledPackage(
        packageName = name,
        installed = true,
        applicationLabel = label,
        evidence = setOf(
            SmartTubeEvidence.DEVICE_INSTALLED,
            SmartTubeEvidence.DEVICE_LAUNCHABLE,
            SmartTubeEvidence.DEVICE_LABEL_MATCH,
        ),
        source = "test",
    )

    fun stableLauncher(
        extra: List<SmartTubeInstalledPackage> = emptyList(),
        launchSucceeds: Boolean = true,
    ) = FakeSmartTubeLauncher(
        catalogInstalled = emptyList(),
        extraInstalled = listOf(pkg(stable)) + extra,
        launchSucceeds = launchSucceeds,
    )

    fun executor(
        p: Phase3FakePlatform = platform(),
        smartTube: SmartTubePlayAutoPort,
        youtube: YouTubePlayAutoPort? = null,
    ) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = youtube,
        smartTubePlayAuto = smartTube,
    )

    fun driverPort(
        client: FakeYouTubeResolverClient = resolvedClient(),
        launcher: FakeSmartTubeLauncher = stableLauncher(),
    ): Triple<SmartTubePlayAutoPort, FakeYouTubeResolverClient, FakeSmartTubeLauncher> {
        val port = DriverBackedSmartTubePlayAutoPort {
            SmartTubeProductionJack.driver(launcher = launcher, resolverClient = client)
        }
        return Triple(port, client, launcher)
    }

    "1. PlayMedia SmartTube selects the SmartTube production adapter" {
        MediaProviderExecutor.build(song, "SmartTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
            .query shouldBe song
        MediaProviderExecutor.build(song, "YouTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.YouTubePlayAuto>()
        MediaProviderExecutor.build(song, "SpotifyX")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.Unsupported>()
    }

    "2. resolver receives the exact canonical query" {
        val (port, client, _) = driverPort()
        executor(smartTube = port).executeTraced(CanonicalCommand.PlayMedia(song, "SmartTube"))
        client.resolveCount shouldBe 1
        client.lastQuery shouldBe song
        client.lastQuery shouldNotBe "Mở bài Đừng Xa Em Đêm Nay trên SmartTube"
        client.queries shouldBe listOf(song)
    }

    "3. resolved watch URL launches once pinned to org.smarttube.stable" {
        val (port, _, launcher) = driverPort()
        val p = platform()
        val trace = executor(p, port).executeTraced(CanonicalCommand.PlayMedia(song, "SmartTube"))
        launcher.launchCount shouldBe 1
        launcher.launchCount shouldBeLessThanOrEqual 1
        launcher.lastSpec?.action shouldBe SmartTubeLaunchAudit.ACTION_VIEW
        launcher.lastSpec?.packageName shouldBe stable
        launcher.lastSpec?.uri shouldBe watch
        launcher.lastSpec?.uri!!.shouldNotContain("search_query=")
        trace.actionTaken.shouldBeTrue()
        trace.mediaQuery shouldBe song
        trace.mediaProvider shouldBe "SmartTube"
        trace.mediaData shouldBe watch
        trace.packageName shouldBe stable
        trace.reason shouldBe "smarttube_playauto_direct_target"
        p.activities.shouldBeEmpty()
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
    }

    "4. resolvedTitle is preserved" {
        val (port, _, launcher) = driverPort()
        val result = port.play(song)
        result.resolvedTitle shouldBe title
        launcher.lastSpec?.resolvedTitle shouldBe title
        result.launched.shouldBeTrue()
    }

    "5. org.smarttube.stable unavailable → zero launch" {
        val launcher = FakeSmartTubeLauncher(catalogInstalled = emptyList())
        val (port, _, _) = driverPort(launcher = launcher)
        val trace = executor(smartTube = port).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "smarttube_unavailable"
        launcher.launchCount shouldBe 0
        launcher.youtubePackageLaunchCount shouldBe 0
        launcher.harnessPackageLaunchCount shouldBe 0
    }

    "6. resolver failure → zero launch" {
        val client = FakeYouTubeResolverClient(result = YouTubeResolveResult.NoResults())
        val launcher = stableLauncher()
        val (port, _, _) = driverPort(client, launcher)
        val trace = executor(smartTube = port).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "NO_RESULTS"
        launcher.launchCount shouldBe 0
    }

    "7. external launch failure does not fall back" {
        val launcher = stableLauncher(launchSucceeds = false)
        val youtubeQueries = mutableListOf<String>()
        val youtube = YouTubePlayAutoPort { q ->
            youtubeQueries += q
            error("YouTube must not be called")
        }
        val (port, _, _) = driverPort(launcher = launcher)
        val trace = executor(smartTube = port, youtube = youtube).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        trace.actionTaken.shouldBeFalse()
        launcher.launchCount shouldBe 1
        launcher.youtubePackageLaunchCount shouldBe 0
        youtubeQueries.shouldBeEmpty()
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
    }

    "8. one command → max one resolver execution and one launch" {
        CanonicalActionGate.bind(9101L)
        CanonicalActionGate.tryClaim(9101L).shouldBeTrue()
        val client = resolvedClient()
        val launcher = stableLauncher()
        val driver = SmartTubeProductionJack.driver(launcher, client)
        val port = DriverBackedSmartTubePlayAutoPort { driver }
        val p = platform()
        val trace = executor(p, port).executeTraced(CanonicalCommand.PlayMedia(song, "SmartTube"))
        trace.actionTaken.shouldBeTrue()
        client.resolveCount shouldBe 1
        launcher.launchCount shouldBe 1
        driver.executeCount shouldBe 1
        CanonicalActionGate.tryClaim(9101L).shouldBeFalse()
        val second = driver.execute(
            PlayAutoRequest("SmartTube", song),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        launcher.launchCount shouldBe 1
        client.resolveCount shouldBe 1
        (second.failure == "duplicate_request" || second.failure == null).shouldBeTrue()
    }

    "9. SmartTube never targets the diagnostic harness" {
        SmartTubeProductionPolicy.isForbiddenTarget(SmartTubeHarnessIdentity.PACKAGE).shouldBeTrue()
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(
                pkg(SmartTubeHarnessIdentity.PACKAGE, "CARFU SmartTube P1.1"),
            ),
        )
        val (port, _, _) = driverPort(launcher = launcher)
        val trace = executor(smartTube = port).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        trace.actionTaken.shouldBeFalse()
        launcher.launchCount shouldBe 0
        launcher.harnessPackageLaunchCount shouldBe 0
        launcher.lastSpec?.packageName shouldNotBe SmartTubeHarnessIdentity.PACKAGE
    }

    "10. SmartTube never targets org.smarttube.beta" {
        SmartTubeProductionPolicy.isForbiddenTarget(SmartTubeCatalog.ORG_SMARTTUBE_BETA).shouldBeTrue()
        val onlyBeta = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(pkg(SmartTubeCatalog.ORG_SMARTTUBE_BETA, "SmartTube Beta")),
        )
        val (port, _, _) = driverPort(launcher = onlyBeta)
        val trace = executor(smartTube = port).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "smarttube_unavailable"
        onlyBeta.launchCount shouldBe 0
        onlyBeta.lastSpec?.packageName shouldNotBe SmartTubeCatalog.ORG_SMARTTUBE_BETA

        val both = stableLauncher(
            extra = listOf(pkg(SmartTubeCatalog.ORG_SMARTTUBE_BETA, "SmartTube Beta")),
        )
        val (portBoth, _, _) = driverPort(launcher = both)
        val ok = executor(smartTube = portBoth).executeTraced(
            CanonicalCommand.PlayMedia(song, "SmartTube"),
        )
        ok.actionTaken.shouldBeTrue()
        both.lastSpec?.packageName shouldBe stable
        both.lastSpec?.packageName shouldNotBe SmartTubeCatalog.ORG_SMARTTUBE_BETA
    }

    "NLU PlayMedia SmartTube stays the existing grammar" {
        val understood = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        )
        understood.command shouldBe CanonicalCommand.PlayMedia(song, "SmartTube")
        understood.query shouldBe song
        understood.provider shouldBe "SmartTube"
        VietnameseCommandUnderstanding.confirmationSpeechVi(understood.command!!) shouldBe
            "Đang mở $song trên SmartTube"
        val (port, client, _) = driverPort()
        val trace = executor(smartTube = port).executeTraced(understood.command!!)
        trace.speechVi shouldBe "Đang mở $song trên SmartTube"
        client.lastQuery shouldBe song
        client.lastQuery!!.shouldNotContain("Mở bài")
    }

    "cast / a11y / media-key stay off on the production jack" {
        val (port, _, _) = driverPort()
        val result = port.play(song)
        result.accessibilityUsed.shouldBeFalse()
        result.castApisUsed.shouldBeFalse()
        result.mediaKeysSent.shouldBeFalse()
        result.youtubeFallbackUsed.shouldBeFalse()
        result.intentAction shouldBe SmartTubeLaunchAudit.ACTION_VIEW
        result.targetPackage shouldBe stable
    }
})
