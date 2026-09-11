package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.FakeYouTubeRuntime
import org.stypox.dicio.youtubeplayauto.YouTubeCandidatePackages
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchSpec
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta

/**
 * Phase 4.9.3 targeted audit: "Đừng Xa Em Đêm Nay" production voice chain vs
 * the Phase 4.9.2b harness Direct Target path. Does not rebuild PlayAuto.
 */
class YouTubeDungXaEmDemNayAuditTest : StringSpec({
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
    val resolvedTitle = "$song - Hồ Hoàng Yến [Official 4K MV]"

    val failingSpoken = listOf(
        "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        "Mở Đừng Xa Em Đêm Nay trên YouTube",
        "Phát bài Đừng Xa Em Đêm Nay trên YouTube",
        "Phát Đừng Xa Em Đêm Nay trên YouTube",
        "Cho tôi nghe Đừng Xa Em Đêm Nay trên YouTube",
    )
    val passingSpoken = listOf(
        "Phát Nơi Này Có Anh trên YouTube",
        "Cho tôi nghe Lạc Trôi trên YouTube",
        "Mở Hotel California Eagles trên YouTube",
    )

    fun resolvedClient() = FakeYouTubeResolverClient(
        result = YouTubeResolveResult.Resolved(
            videoId = videoId,
            title = resolvedTitle,
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

    fun executor(
        p: Phase3FakePlatform,
        youtube: YouTubePlayAutoPort,
    ) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = youtube,
    )

    data class AuditRow(
        val rawTranscript: String,
        val normalizedTranscript: String,
        val canonicalCommand: String,
        val mediaQuery: String?,
        val provider: String?,
        val playAutoRequestQuery: String?,
        val resolverStatus: String?,
        val resolvedVideoId: String?,
        val resolvedTitle: String?,
        val finalWatchUri: String?,
        val targetPackage: String?,
        val actionCount: Int,
        val intentAction: String?,
        val extraQuery: String?,
        val launchedOpenAppHome: Boolean,
    )

    fun audit(raw: String): AuditRow {
        val understood = VietnameseCommandUnderstanding.understand(raw)
        val p = Phase3FakePlatform()
        val client = resolvedClient()
        val runtime = FakeYouTubeRuntime()
        val port = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        }
        val cmd = understood.command
        val trace = if (cmd != null && understood.completeness == SemanticCompleteness.COMPLETE) {
            executor(p, port).executeTraced(cmd)
        } else {
            null
        }
        val spec = runtime.lastSpec
        val openAppHome = p.activities.any {
            it.action == "LAUNCH" && it.packageName == "com.google.android.youtube"
        } || spec?.action == YouTubeLaunchSpec.ACTION_MAIN
        return AuditRow(
            rawTranscript = understood.rawTranscript,
            normalizedTranscript = understood.normalizedTranscript,
            canonicalCommand = cmd?.toString() ?: "NONE intent=${understood.intent} " +
                "complete=${understood.completeness} reason=${understood.reason}",
            mediaQuery = understood.query ?: (cmd as? CanonicalCommand.PlayMedia)?.query,
            provider = understood.provider ?: (cmd as? CanonicalCommand.PlayMedia)?.provider,
            playAutoRequestQuery = client.lastQuery,
            resolverStatus = if (client.resolveCount == 0) "NOT_CALLED" else "RESOLVED",
            resolvedVideoId = if (client.resolveCount == 0) null else videoId,
            resolvedTitle = if (client.resolveCount == 0) null else resolvedTitle,
            finalWatchUri = spec?.uri ?: trace?.mediaData,
            targetPackage = spec?.packageName ?: trace?.packageName,
            actionCount = runtime.dispatchCount + p.activities.size,
            intentAction = spec?.action,
            extraQuery = spec?.extraQuery,
            launchedOpenAppHome = openAppHome,
        )
    }

    "AUDIT five failing spoken variants keep clean PlayMedia and one watch VIEW" {
        failingSpoken.forEach { raw ->
            val row = audit(raw)
            row.rawTranscript shouldBe raw
            row.normalizedTranscript shouldBe VietnameseTranscript.foldForMatch(raw)
            row.canonicalCommand shouldBe CanonicalCommand.PlayMedia(song, "YouTube").toString()
            row.mediaQuery shouldBe song
            row.provider shouldBe "YouTube"
            row.playAutoRequestQuery shouldBe song
            row.playAutoRequestQuery!!.shouldNotContain("Mở")
            row.playAutoRequestQuery!!.shouldNotContain("Phát")
            row.playAutoRequestQuery!!.shouldNotContain("hát")
            row.playAutoRequestQuery!!.shouldNotContain("YouTube")
            row.playAutoRequestQuery!!.shouldNotContain("trên")
            row.resolverStatus shouldBe "RESOLVED"
            row.resolvedVideoId shouldBe videoId
            row.resolvedTitle shouldBe resolvedTitle
            row.finalWatchUri shouldBe watch
            row.finalWatchUri!!.shouldNotContain("search_query=")
            row.targetPackage shouldBe YouTubeCandidatePackages.OFFICIAL
            row.actionCount shouldBe 1
            row.intentAction shouldBe YouTubeLaunchSpec.ACTION_VIEW
            row.extraQuery shouldBe song
            row.launchedOpenAppHome.shouldBeFalse()
            MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        }
    }

    "AUDIT known passing arbitrary songs keep the same Direct Target path" {
        val expectedQuery = mapOf(
            passingSpoken[0] to "Nơi Này Có Anh",
            passingSpoken[1] to "Lạc Trôi",
            passingSpoken[2] to "Hotel California Eagles",
        )
        passingSpoken.forEach { raw ->
            val row = audit(raw)
            val q = expectedQuery.getValue(raw)
            row.canonicalCommand shouldBe CanonicalCommand.PlayMedia(q, "YouTube").toString()
            row.mediaQuery shouldBe q
            row.playAutoRequestQuery shouldBe q
            row.resolverStatus shouldBe "RESOLVED"
            row.resolvedVideoId shouldBe videoId
            row.finalWatchUri shouldBe watch
            row.targetPackage shouldBe YouTubeCandidatePackages.OFFICIAL
            row.actionCount shouldBe 1
            row.intentAction shouldBe YouTubeLaunchSpec.ACTION_VIEW
            row.launchedOpenAppHome.shouldBeFalse()
        }
    }

    "FIRST DIVERGENCE: media partial then Mở YouTube must not commit OpenApp home" {
        val growing = listOf(
            "Mở",
            "Mở bài",
            "Mở bài Đừng Xa Em Đêm Nay",
            "Mở YouTube",
        )
        StableCompletePartialTracker.bind(8L)
        SessionCommandDecision.bindSession(8L)
        CanonicalActionGate.bind(8L)
        growing.forEachIndexed { index, raw ->
            val result = VietnameseCommandUnderstanding.understand(raw, sessionId = 8L)
            val obs = StableCompletePartialTracker.onPartial(8L, result, index * 10L, 8L)
            if (raw == "Mở YouTube") {
                result.command shouldBe CanonicalCommand.OpenApp("YouTube")
                obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
                obs.reason shouldBe "play_media_in_progress_blocks_open_app_youtube"
            } else {
                obs.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            }
        }
        StableCompletePartialTracker.sawPlayMediaIntentForTests().shouldBeTrue()
        val complete = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
            sessionId = 8L,
        )
        val commit = StableCompletePartialTracker.onPartial(8L, complete, 80L, 8L)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_play_media"
        complete.command shouldBe CanonicalCommand.PlayMedia(song, "YouTube")
        SessionCommandDecision.locked(8L).shouldBeNull()
    }

    "bare Mở YouTube still semantic-commits OpenApp (Phase 4.4 preserved)" {
        val open = VietnameseCommandUnderstanding.understand("Mở YouTube")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(open).shouldBeTrue()
        StableCompletePartialTracker.bind(2L)
        val obs = StableCompletePartialTracker.onPartial(2L, open, 0L, 2L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_open_app"
    }

    "rescue n-best must not convert in-progress PlayMedia into OpenApp YouTube" {
        SessionCommandDecision.bindSession(9L)
        val decision = SessionCommandDecision.decideFinal(
            9L,
            listOf(
                "Mở YouTube" to 0.99f,
                "Mở bài Đừng Xa Em Đêm Nay" to 0.70f,
                "Mở bài Đừng Xa Em Đêm Nay trên YouTube" to 0.60f,
            ),
        )
        decision.shouldNotBeNull()
        decision!!.command shouldBe CanonicalCommand.PlayMedia(song, "YouTube")
        VietnameseCommandUnderstanding.isExactCatalogOpenAppYouTube(decision).shouldBeFalse()
    }

    "incomplete media plus Mở YouTube does not lock YouTube home" {
        SessionCommandDecision.bindSession(10L)
        val decision = SessionCommandDecision.decideFinal(
            10L,
            listOf(
                "Mở YouTube" to 0.99f,
                "Mở bài Đừng Xa Em Đêm Nay" to 0.80f,
            ),
        )
        decision.shouldNotBeNull()
        decision!!.intent shouldBe VoiceIntent.PLAY_MEDIA
        decision.command.shouldBeNull()
        decision.query shouldBe song
        VietnameseCommandUnderstanding.isExactCatalogOpenAppYouTube(decision).shouldBeFalse()
    }

    "Mở hát … still resolves through PlayAuto without opening YouTube home" {
        val understood = VietnameseCommandUnderstanding.understand(
            "Mở hát Đừng Xa Em Đêm Nay trên YouTube",
        )
        understood.command.shouldBeInstanceOf<CanonicalCommand.PlayMedia>()
        val q = (understood.command as CanonicalCommand.PlayMedia).query
        q shouldContain song
        q.shouldNotContain("YouTube")
        val p = Phase3FakePlatform()
        val client = resolvedClient()
        val runtime = FakeYouTubeRuntime()
        val port = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        }
        val trace = executor(p, port).executeTraced(understood.command!!)
        client.lastQuery shouldBe q
        runtime.dispatchCount shouldBe 1
        runtime.lastSpec!!.uri shouldBe watch
        runtime.lastSpec!!.action shouldBe YouTubeLaunchSpec.ACTION_VIEW
        runtime.lastSpec!!.strategy shouldBe PlaybackStrategy.DEEP_LINK
        trace.actionTaken.shouldBeTrue()
        p.activities.shouldBeEmpty()
    }

    "PlayMedia resolver failure never degrades to OpenApp YouTube home" {
        val client = FakeYouTubeResolverClient(result = YouTubeResolveResult.NoResults())
        val runtime = FakeYouTubeRuntime()
        val p = Phase3FakePlatform()
        val port = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        }
        val trace = executor(p, port).executeTraced(CanonicalCommand.PlayMedia(song, "YouTube"))
        trace.actionTaken.shouldBeFalse()
        trace.mediaData.shouldBeNull()
        runtime.dispatchCount shouldBe 0
        p.activities.shouldBeEmpty()
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
    }

    "production Direct Target Intent matches 4.9.2b harness fields" {
        val client = resolvedClient()
        val runtime = FakeYouTubeRuntime()
        val port = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        }
        executor(Phase3FakePlatform(), port).executeTraced(
            CanonicalCommand.PlayMedia(song, "YouTube"),
        )
        val spec = runtime.lastSpec!!
        spec.strategy shouldBe PlaybackStrategy.DEEP_LINK
        spec.action shouldBe YouTubeLaunchSpec.ACTION_VIEW
        spec.uri shouldBe watch
        spec.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        spec.extraQuery shouldBe song
        spec.categories.shouldBeEmpty()
    }
})
