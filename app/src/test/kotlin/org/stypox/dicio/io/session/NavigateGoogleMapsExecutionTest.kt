package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.skills.carfu.CarfuDialer
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.FakeYouTubeRuntime
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta

/**
 * Device-proven Navigate latency + Google Maps search/place UI.
 * Complete Navigate semantic-commits; production launch is google.navigation.
 */
class NavigateGoogleMapsExecutionTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
        StableCompletePartialTracker.resetForTests()
    }

    val spoken = listOf(
        "Chỉ đường tới Mỹ Đình" to "Mỹ Đình",
        "Chỉ đường đến Mỹ Đình" to "Mỹ Đình",
        "Dẫn đường đến Hồ Gươm" to "Hồ Gươm",
        "Đi đến sân bay Nội Bài" to "sân bay Nội Bài",
    )

    fun platform(): Phase3FakePlatform = Phase3FakePlatform()

    fun executor(p: Phase3FakePlatform = platform()) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(
                runtime = FakeYouTubeRuntime(),
                resolverClient = FakeYouTubeResolverClient(
                    result = YouTubeResolveResult.Resolved(
                        videoId = "W20zl5N_jbg",
                        title = "fixture",
                        channelTitle = "MMG",
                        watchUrl = "https://www.youtube.com/watch?v=W20zl5N_jbg",
                        cache = "MISS",
                        meta = YouTubeResolverMeta(
                            httpStatus = 200,
                            requestAttempted = true,
                            baseUrlConfigured = true,
                            usedHttps = true,
                        ),
                    ),
                ),
            )
        },
    )

    "complete spoken Navigate variants early-commit one google.navigation VIEW" {
        spoken.forEach { (raw, destination) ->
            SessionCommandDecision.resetForTests()
            CanonicalActionGate.resetForTests()
            CommandSessionOutcome.resetForTests()
            StableCompletePartialTracker.resetForTests()
            val understood = VietnameseCommandUnderstanding.understand(raw, sessionId = 1L)
            understood.rawTranscript shouldBe raw
            understood.normalizedTranscript shouldBe VietnameseTranscript.foldForMatch(raw)
            understood.command shouldBe CanonicalCommand.Navigate(destination)
            understood.destination shouldBe destination
            understood.intent shouldBe VoiceIntent.NAVIGATE
            understood.completeness shouldBe SemanticCompleteness.COMPLETE
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(understood).shouldBeTrue()
            StableCompletePartialTracker.bind(1L)
            val obs = StableCompletePartialTracker.onPartial(1L, understood, 0L, 1L)
            obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
            obs.reason shouldBe "semantic_navigate"
            obs.eosConfirmed.shouldBeFalse()
            val p = platform()
            val trace = executor(p).executeTraced(understood.command!!)
            trace.actionTaken.shouldBeTrue()
            trace.reason shouldBe "navigate_ok"
            NavigatePayload.isNavigationUri(trace.geoUri).shouldBeTrue()
            NavigatePayload.isGeoSearchUri(trace.geoUri).shouldBeFalse()
            NavigatePayload.decodeQuery(trace.geoUri!!) shouldBe destination
            trace.geoUri!!.shouldNotContain("geo:0,0")
            trace.packageName shouldBe NavigatePayload.GOOGLE_MAPS_PACKAGE
            p.activities.size shouldBe 1
            p.activities.single().action shouldBe CarfuDialer.ACTION_VIEW
            p.activities.single().packageName shouldBe NavigatePayload.GOOGLE_MAPS_PACKAGE
            p.activities.single().data shouldBe trace.geoUri
        }
    }

    "incomplete Navigate does not execute" {
        listOf(
            "Chỉ đường tới",
            "Chỉ đường đến",
            "Dẫn đường đến",
            "Đi đến",
        ).forEach { raw ->
            val result = VietnameseCommandUnderstanding.understand(raw)
            result.completeness shouldBe SemanticCompleteness.INCOMPLETE
            result.executable.shouldBeFalse()
            result.command.shouldBeNull()
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeFalse()
            StableCompletePartialTracker.resetForTests()
            StableCompletePartialTracker.bind(1L)
            StableCompletePartialTracker.onPartial(1L, result, 0L, 1L).decision shouldBe
                StableCompletePartialTracker.Decision.IGNORE
        }
    }

    "URL encoding preserves Vietnamese destinations" {
        val uri = NavigatePayload.navigationUri("Mỹ Đình")!!
        uri shouldBe "google.navigation:q=M%E1%BB%B9%20%C4%90%C3%ACnh"
        uri.shouldNotContain("+")
        NavigatePayload.decodeQuery(uri) shouldBe "Mỹ Đình"
        NavigatePayload.navigationUri("Hồ Gươm")!! shouldContain "%20"
        NavigatePayload.decodeQuery(NavigatePayload.navigationUri("sân bay Nội Bài")!!) shouldBe
            "sân bay Nội Bài"
    }

    "blank destination → zero launch" {
        val p = platform()
        val trace = executor(p).executeTraced(CanonicalCommand.Navigate("   "))
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "empty_destination"
        trace.geoUri.shouldBeNull()
        p.activities.shouldBeEmpty()
    }

    "Maps unavailable → structured failure, zero launches" {
        val p = platform()
        p.launchable.remove(NavigatePayload.GOOGLE_MAPS_PACKAGE)
        val trace = executor(p).executeTraced(CanonicalCommand.Navigate("Mỹ Đình"))
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "maps_missing"
        p.activities.shouldBeEmpty()
        p.launchPackage(NavigatePayload.GOOGLE_MAPS_PACKAGE).shouldBeFalse()
        p.activities.shouldBeEmpty()
    }

    "OpenApp Google Maps is not Navigate and does not use navigation URI" {
        val open = VietnameseCommandUnderstanding.understand("Mở Google Maps")
        open.command.shouldBeInstanceOf<CanonicalCommand.OpenApp>()
        (open.command as CanonicalCommand.OpenApp).appName shouldBe "Maps"
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(open).shouldBeTrue()
        val p = platform()
        val trace = executor(p).executeTraced(open.command!!)
        trace.actionTaken.shouldBeTrue()
        trace.packageName shouldBe NavigatePayload.GOOGLE_MAPS_PACKAGE
        trace.geoUri.shouldBeNull()
        p.activities.single().action shouldBe "LAUNCH"
        p.activities.single().data.shouldBeNull()
        NavigatePayload.isNavigationUri(p.activities.single().data).shouldBeFalse()
    }

    "YouTube PlayMedia is unchanged by Navigate URI change" {
        val media = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        )
        media.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "YouTube")
        val p = platform()
        val runtime = FakeYouTubeRuntime()
        val client = FakeYouTubeResolverClient(
            result = YouTubeResolveResult.Resolved(
                videoId = "W20zl5N_jbg",
                title = "fixture",
                channelTitle = "MMG",
                watchUrl = "https://www.youtube.com/watch?v=W20zl5N_jbg",
                cache = "MISS",
                meta = YouTubeResolverMeta(
                    httpStatus = 200,
                    requestAttempted = true,
                    baseUrlConfigured = true,
                    usedHttps = true,
                ),
            ),
        )
        val exec = CanonicalCommandExecutor(
            platform = p,
            appResolver = InstalledAppResolver(
                listLaunchable = { p.listLaunchableApps() },
                isLaunchable = { p.isPackageLaunchable(it) },
            ),
            youtubePlayAuto = DriverBackedYouTubePlayAutoPort {
                YouTubeProductionJack.driver(runtime, client)
            },
        )
        val trace = exec.executeTraced(media.command!!)
        trace.actionTaken.shouldBeTrue()
        runtime.dispatchCount shouldBe 1
        runtime.lastSpec!!.uri shouldBe "https://www.youtube.com/watch?v=W20zl5N_jbg"
        p.activities.shouldBeEmpty()
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
    }

    "exactly one Maps action per Navigate; no geo search then navigation" {
        val p = platform()
        executor(p).executeTraced(CanonicalCommand.Navigate("Mỹ Đình"))
        p.activities.size shouldBe 1
        p.activities.single().data!!.shouldNotContain("geo:")
        p.activities.none { it.action == "LAUNCH" }.shouldBeTrue()
    }
})
