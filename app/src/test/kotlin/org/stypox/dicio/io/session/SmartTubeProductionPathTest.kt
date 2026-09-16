package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.smarttubeplayauto.FakeSmartTubeLauncher
import org.stypox.dicio.smarttubeplayauto.SmartTubeEvidence
import org.stypox.dicio.smarttubeplayauto.SmartTubeInstalledPackage
import org.stypox.dicio.smarttubeplayauto.SmartTubeLaunchAudit
import org.stypox.dicio.smarttubeplayauto.SmartTubeProductionPolicy
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta

/**
 * P2.1 — Voice production path: Canonical PlayMedia → CanonicalCommandExecutor
 * (the same public executor SkillEvaluator.executeCanonicalCommand uses).
 *
 * Does not call SmartTubeProductionJack.play() from the test body.
 */
class SmartTubeProductionPathTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
        StableCompletePartialTracker.resetForTests()
        MediaPlayRouting.resetForTests()
    }

    val song = "Đừng Xa Em Đêm Nay"
    val otherSong = "Nơi Này Có Anh"
    val watch = "https://www.youtube.com/watch?v=W20zl5N_jbg"
    val videoId = "W20zl5N_jbg"
    val title = "$song - Hồ Hoàng Yến [Official 4K MV]"
    val stable = SmartTubeProductionPolicy.PACKAGE
    val unsupportedSpeech = "Chưa hỗ trợ phát nhạc trên SmartTube."

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

    fun stablePkg() = SmartTubeInstalledPackage(
        packageName = stable,
        installed = true,
        applicationLabel = "SmartTube",
        evidence = setOf(
            SmartTubeEvidence.DEVICE_INSTALLED,
            SmartTubeEvidence.DEVICE_LAUNCHABLE,
            SmartTubeEvidence.DEVICE_LABEL_MATCH,
        ),
        source = "test",
    )

    fun countingYouTube(): Pair<YouTubePlayAutoPort, MutableList<String>> {
        val queries = mutableListOf<String>()
        val port = YouTubePlayAutoPort { q ->
            queries += q
            YouTubeProductionJackResult(
                query = q,
                playAutoRequestCount = 1,
                launched = true,
                launchCount = 1,
                watchUrl = watch,
                videoId = videoId,
                failure = null,
                path = "SHOULD_NOT_RUN",
                searchOpened = false,
                accessibilityFallbackUsed = false,
                castApisUsed = false,
                mediaKeysSent = false,
                resolverStatus = "RESOLVED",
            )
        }
        return port to queries
    }

    /**
     * Same constructor shape SkillEvaluator uses: explicit appResolver + both jacks.
     */
    fun productionExecutor(
        p: Phase3FakePlatform,
        smartTube: SmartTubePlayAutoPort,
        youtube: YouTubePlayAutoPort,
    ) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = youtube,
        smartTubePlayAuto = smartTube,
    )

    fun wiredJack(
        client: FakeYouTubeResolverClient,
        launcher: FakeSmartTubeLauncher,
    ): Pair<SmartTubePlayAutoPort, MutableList<String>> {
        val plays = mutableListOf<String>()
        val inner = DriverBackedSmartTubePlayAutoPort {
            SmartTubeProductionJack.driver(launcher = launcher, resolverClient = client)
        }
        val port = SmartTubePlayAutoPort { q ->
            plays += q
            inner.play(q)
        }
        return port to plays
    }

    fun runCanonical(
        command: CanonicalCommand,
        client: FakeYouTubeResolverClient = resolvedClient(),
        launcher: FakeSmartTubeLauncher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(stablePkg()),
        ),
    ): CanonicalCommandExecutor.ExecutionTrace {
        val p = platform()
        val (youtube, youtubeQueries) = countingYouTube()
        val (smartTube, _) = wiredJack(client, launcher)
        val exec = productionExecutor(p, smartTube, youtube)
        // Voice: SkillEvaluator.executeCanonicalCommand → executeTraced(command)
        val trace = exec.executeTraced(command)
        youtubeQueries.size shouldBeExactly 0
        return trace
    }

    "production path PlayMedia SmartTube selects the jack once, never Unsupported TTS" {
        val client = resolvedClient()
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(stablePkg()),
        )
        val p = platform()
        val (youtube, youtubeQueries) = countingYouTube()
        val (smartTube, jackPlays) = wiredJack(client, launcher)
        val exec = productionExecutor(p, smartTube, youtube)

        val command = CanonicalCommand.PlayMedia(
            query = song,
            provider = "SmartTube",
        )
        command.shouldBeInstanceOf<CanonicalCommand.PlayMedia>()
        val trace = exec.executeTraced(command)

        MediaPlayRouting.lastExecutorSelected shouldBe "SmartTubeProductionJack"
        MediaPlayRouting.smartTubeJackEntered shouldBeExactly 1
        MediaPlayRouting.legacyUnsupportedEntered shouldBeExactly 0
        jackPlays shouldBe listOf(song)
        client.queries shouldBe listOf(song)
        client.lastQuery shouldBe song
        launcher.launchCount shouldBeExactly 1
        launcher.launches.size shouldBeExactly 1
        launcher.lastSpec.shouldNotBeNull()
        launcher.lastSpec!!.action shouldBe SmartTubeLaunchAudit.ACTION_VIEW
        launcher.lastSpec!!.packageName shouldBe stable
        launcher.lastSpec!!.uri shouldBe watch
        youtubeQueries.shouldBe(emptyList())
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBeExactly 0
        p.activities.shouldBeEmpty()
        trace.actionTaken.shouldBeTrue()
        trace.packageName shouldBe stable
        trace.mediaData shouldBe watch
        trace.speechVi shouldBe "Đang mở $song trên SmartTube"
        trace.speechVi shouldNotContain MediaPlayRouting.UNSUPPORTED_SMARTTUBE_SPEECH_PREFIX
        trace.speechVi shouldNotBe unsupportedSpeech
    }

    "Voice NLU utterance uses the same production executor, not Unsupported TTS" {
        val understood = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
            sessionId = 7L,
        )
        understood.command shouldBe CanonicalCommand.PlayMedia(song, "SmartTube")
        val client = resolvedClient()
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(stablePkg()),
        )
        val trace = runCanonical(understood.command!!, client, launcher)
        MediaPlayRouting.smartTubeJackEntered shouldBeExactly 1
        MediaPlayRouting.legacyUnsupportedEntered shouldBeExactly 0
        client.lastQuery shouldBe song
        launcher.launchCount shouldBeExactly 1
        launcher.lastSpec!!.packageName shouldBe stable
        launcher.lastSpec!!.uri shouldBe watch
        trace.speechVi shouldNotContain MediaPlayRouting.UNSUPPORTED_SMARTTUBE_SPEECH_PREFIX
        trace.speechVi shouldBe "Đang mở $song trên SmartTube"
    }

    "arbitrary title is not hard-coded; still one jack and zero Unsupported" {
        val client = FakeYouTubeResolverClient(
            result = YouTubeResolveResult.Resolved(
                videoId = "dQw4w9WgXcQ",
                title = otherSong,
                channelTitle = "test",
                watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                cache = "MISS",
                meta = YouTubeResolverMeta(
                    httpStatus = 200,
                    requestAttempted = true,
                    baseUrlConfigured = true,
                    usedHttps = true,
                ),
            ),
        )
        val understood = VietnameseCommandUnderstanding.understand(
            "Mở bài Nơi Này Có Anh trên SmartTube",
            sessionId = 8L,
        )
        understood.command shouldBe CanonicalCommand.PlayMedia(otherSong, "SmartTube")
        val launcher = FakeSmartTubeLauncher(
            catalogInstalled = emptyList(),
            extraInstalled = listOf(stablePkg()),
        )
        val trace = runCanonical(understood.command!!, client, launcher)
        client.lastQuery shouldBe otherSong
        client.lastQuery shouldNotBe song
        launcher.launchCount shouldBeExactly 1
        launcher.lastSpec!!.packageName shouldBe stable
        launcher.lastSpec!!.uri shouldBe "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        MediaPlayRouting.smartTubeJackEntered shouldBeExactly 1
        MediaPlayRouting.legacyUnsupportedEntered shouldBeExactly 0
        trace.speechVi shouldContain otherSong
        trace.speechVi shouldNotContain MediaPlayRouting.UNSUPPORTED_SMARTTUBE_SPEECH_PREFIX
    }

    "one command cannot take both jack and legacy Unsupported" {
        val command = CanonicalCommand.PlayMedia(song, "SmartTube")
        runCanonical(command)
        MediaPlayRouting.smartTubeJackEntered shouldBeExactly 1
        MediaPlayRouting.legacyUnsupportedEntered shouldBeExactly 0
        MediaPlayRouting.lastExecutorSelected shouldBe "SmartTubeProductionJack"
    }
})
