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
            obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
            obs.reason shouldBe "navigate_waiting_stable"
            obs.eosConfirmed.shouldBeFalse()
            StableCompletePartialTracker.onTimer(
                1L,
                StableCompletePartialPolicy.NAV_STABILIZATION_MS,
            ).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
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
            p.activities.single().flags shouldBe NavigatePayload.NAVIGATION_INTENT_FLAGS
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
        p.activities.single().flags shouldBe 0
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
        p.activities.single().flags shouldBe NavigatePayload.NAVIGATION_INTENT_FLAGS
    }

    "consecutive MODE sessions dispatch four distinct current destinations" {
        val spokenSessions = listOf(
            "Chỉ đường tới Mỹ Đình" to "Mỹ Đình",
            "Dẫn đường đến Hồ Gươm" to "Hồ Gươm",
            "Đi đến sân bay Nội Bài" to "sân bay Nội Bài",
            "Chỉ đường đến bia bà" to "bia bà",
        )
        val p = platform()
        val exec = executor(p)
        spokenSessions.forEachIndexed { index, (raw, destination) ->
            val sid = (index + 1).toLong()
            SessionCommandDecision.bindSession(sid)
            CanonicalActionGate.bind(sid)
            CommandSessionOutcome.reset()
            StableCompletePartialTracker.bind(sid)
            StableCompletePartialTracker.committedForTests().shouldBeFalse()
            StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
            CanonicalActionGate.tryClaim(sid).shouldBeTrue()
            val understood = VietnameseCommandUnderstanding.understand(raw, sessionId = sid)
            understood.command shouldBe CanonicalCommand.Navigate(destination)
            understood.destination shouldBe destination
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(understood).shouldBeTrue()
            val obs = StableCompletePartialTracker.onPartial(sid, understood, 0L, sid)
            obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
            obs.fingerprint shouldBe "NAVIGATE|$destination"
            StableCompletePartialTracker.onTimer(
                sid,
                StableCompletePartialPolicy.NAV_STABILIZATION_MS,
            ).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
            val trace = exec.executeTraced(understood.command!!)
            trace.actionTaken.shouldBeTrue()
            NavigatePayload.decodeQuery(trace.geoUri!!) shouldBe destination
            trace.geoUri shouldBe NavigatePayload.navigationUri(destination)
        }
        p.activities.size shouldBe spokenSessions.size
        val decoded = p.activities.map { NavigatePayload.decodeQuery(it.data!!) }
        decoded shouldBe spokenSessions.map { it.second }
        p.activities.map { it.data }.toSet().size shouldBe spokenSessions.size
        p.activities.forEach { launched ->
            launched.action shouldBe CarfuDialer.ACTION_VIEW
            launched.packageName shouldBe NavigatePayload.GOOGLE_MAPS_PACKAGE
            launched.flags shouldBe NavigatePayload.NAVIGATION_INTENT_FLAGS
            NavigatePayload.isGeoSearchUri(launched.data).shouldBeFalse()
        }
    }

    "growing partials do not truncate bia bà or sân bay Nội Bài" {
        StableCompletePartialTracker.bind(1L)
        val incomplete = VietnameseCommandUnderstanding.understand("Chỉ đường đến", 1L)
        incomplete.completeness shouldBe SemanticCompleteness.INCOMPLETE
        StableCompletePartialTracker.onPartial(1L, incomplete, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        val bia = VietnameseCommandUnderstanding.understand("Chỉ đường đến bia", 1L)
        bia.command shouldBe CanonicalCommand.Navigate("bia")
        StableCompletePartialTracker.onPartial(1L, bia, 5L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val biaBa = VietnameseCommandUnderstanding.understand("Chỉ đường đến bia bà", 1L)
        biaBa.command shouldBe CanonicalCommand.Navigate("bia bà")
        val biaCommit = StableCompletePartialTracker.onPartial(1L, biaBa, 10L, 1L)
        biaCommit.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        biaCommit.fingerprint shouldBe "NAVIGATE|bia bà"
        StableCompletePartialTracker.onTimer(
            1L,
            10L + StableCompletePartialPolicy.NAV_STABILIZATION_MS,
        ).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        NavigatePayload.navigationUri("bia bà") shouldBe "google.navigation:q=bia%20b%C3%A0"

        StableCompletePartialTracker.bind(2L)
        listOf("Đi đến sân bay", "Đi đến sân bay Nội").forEachIndexed { index, raw ->
            val mid = VietnameseCommandUnderstanding.understand(raw, 2L)
            mid.command.shouldBeInstanceOf<CanonicalCommand.Navigate>()
            StableCompletePartialTracker.onPartial(2L, mid, index * 10L, 2L).decision shouldBe
                StableCompletePartialTracker.Decision.WAIT
        }
        val fullAirport = VietnameseCommandUnderstanding.understand("Đi đến sân bay Nội Bài", 2L)
        fullAirport.command shouldBe CanonicalCommand.Navigate("sân bay Nội Bài")
        val airportWait = StableCompletePartialTracker.onPartial(2L, fullAirport, 20L, 2L)
        airportWait.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        airportWait.fingerprint shouldBe "NAVIGATE|sân bay Nội Bài"
        val airportCommit = StableCompletePartialTracker.onTimer(
            2L,
            20L + StableCompletePartialPolicy.NAV_STABILIZATION_MS,
        )
        airportCommit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        airportCommit.fingerprint shouldBe "NAVIGATE|sân bay Nội Bài"
    }

    "Navigate dispatch diagnostics name current destination" {
        CarfuDiag.clear()
        executor().executeTraced(CanonicalCommand.Navigate("Hồ Gươm"))
        val lines = CarfuDiag.recent(CarfuDiag.TAG_VOICE)
        val nav = lines.last { it.contains("NAVIGATE_DISPATCH") }
        nav shouldContain "CANONICAL_DESTINATION=Hồ Gươm"
        nav shouldContain "FINAL_NAV_URI=google.navigation:q=H%E1%BB%93%20G%C6%B0%C6%A1m"
        nav shouldContain "START_ACTIVITY_CALLED=true"
        nav shouldContain "RESULT=navigate_ok"
        nav shouldContain "ACTION_CLAIMED=true"
    }
})
