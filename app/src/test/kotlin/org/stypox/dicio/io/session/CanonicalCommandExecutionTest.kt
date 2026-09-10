package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.skills.carfu.CarfuAlarmKind
import org.stypox.dicio.skills.carfu.CarfuContact
import org.stypox.dicio.skills.carfu.CarfuLaunchSpec
import org.stypox.dicio.skills.carfu.CarfuPersistedAlarm
import org.stypox.dicio.skills.carfu.CarfuSkillPlatform
import org.stypox.dicio.skills.carfu.HttpFetchResult
import org.stypox.dicio.skills.carfu.StartedActivity

/**
 * Phase 3 — CanonicalCommand execution (Navigate / OpenApp / PlayMedia) + gates.
 */
class CanonicalCommandExecutionTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
    }

    fun platform(): Phase3FakePlatform = Phase3FakePlatform()

    fun recordingYouTube(
        queries: MutableList<String> = mutableListOf(),
        launched: Boolean = true,
        failure: String? = null,
        watchUrl: String? = "https://www.youtube.com/watch?v=W20zl5N_jbg",
        videoId: String? = "W20zl5N_jbg",
    ): YouTubePlayAutoPort = YouTubePlayAutoPort { q ->
        queries += q
        YouTubeProductionJackResult(
            query = q,
            playAutoRequestCount = 1,
            launched = launched && failure == null,
            launchCount = if (launched && failure == null) 1 else 0,
            watchUrl = if (launched && failure == null) watchUrl else null,
            videoId = if (launched && failure == null) videoId else null,
            failure = failure,
            path = if (failure == null) "RESOLVER_DIRECT_TARGET" else "RESOLVER_DIRECT_TARGET",
            searchOpened = false,
            accessibilityFallbackUsed = false,
            castApisUsed = false,
            mediaKeysSent = false,
            resolverStatus = failure ?: "RESOLVED",
        )
    }

    fun executor(
        p: Phase3FakePlatform = platform(),
        youtube: YouTubePlayAutoPort = recordingYouTube(),
    ) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = youtube,
    )

    // --- NAVIGATE ---

    "1. Navigate Mỹ Đình TTS + Maps payload from same command" {
        val p = platform()
        val cmd = CanonicalCommand.Navigate("Mỹ Đình")
        val speech = VietnameseCommandUnderstanding.confirmationSpeechVi(cmd)!!
        speech shouldContain "Mỹ Đình"
        speech shouldContain "chỉ đường"
        val trace = executor(p).executeTraced(cmd)
        trace.speechVi shouldBe speech
        trace.actionTaken.shouldBeTrue()
        trace.geoUri.shouldNotBeNull()
        NavigatePayload.decodeQuery(trace.geoUri!!) shouldBe "Mỹ Đình"
        p.activities.single().data shouldContain "geo:0,0?q="
        // Destination not re-parsed from a transcript string inside executor.
        trace.reason shouldBe "navigate_ok"
    }

    "2. Navigate Hồ Gươm same object for TTS and action" {
        val cmd = CanonicalCommand.Navigate("Hồ Gươm")
        val p = platform()
        val trace = executor(p).executeTraced(cmd)
        trace.speechVi shouldContain "Hồ Gươm"
        NavigatePayload.decodeQuery(trace.geoUri!!) shouldBe "Hồ Gươm"
    }

    "3. Navigate sân bay Nội Bài preserves full entity" {
        val cmd = CanonicalCommand.Navigate("sân bay Nội Bài")
        val trace = executor().executeTraced(cmd)
        NavigatePayload.decodeQuery(trace.geoUri!!) shouldBe "sân bay Nội Bài"
        trace.speechVi shouldContain "sân bay Nội Bài"
    }

    "4. Navigate payload builder does not read raw transcript" {
        val uri = NavigatePayload.geoUri("Mỹ Đình")
        NavigatePayload.decodeQuery(uri) shouldBe "Mỹ Đình"
        // Incomplete understanding never becomes Navigate executable.
        val incomplete = VietnameseCommandUnderstanding.understand("Chỉ đường đến")
        incomplete.executable.shouldBeFalse()
        incomplete.command.shouldBeNull()
    }

    "5. Incomplete navigation cannot reach executor action" {
        val u = VietnameseCommandUnderstanding.understand("Chỉ đường đến")
        u.completeness shouldBe SemanticCompleteness.INCOMPLETE
        // Gate path: no command → no executeTraced call in production.
        u.command.shouldBeNull()
    }

    // --- OPEN_APP ---

    "OpenApp YouTube resolves via alias catalog" {
        val p = platform()
        val trace = executor(p).executeTraced(CanonicalCommand.OpenApp("YouTube"))
        trace.actionTaken.shouldBeTrue()
        trace.packageName shouldBe "com.google.android.youtube"
        trace.speechVi shouldContain "YouTube"
    }

    "OpenApp MusicLoop resolves" {
        val p = platform()
        val trace = executor(p).executeTraced(CanonicalCommand.OpenApp("MusicLoop"))
        trace.actionTaken.shouldBeTrue()
        trace.packageName shouldBe "com.musicloop.car"
    }

    "OpenApp Chrome when installed" {
        val p = platform()
        val trace = executor(p).executeTraced(CanonicalCommand.OpenApp("Chrome"))
        trace.actionTaken.shouldBeTrue()
        trace.packageName shouldBe "com.android.chrome"
    }

    "music look understanding + resolver → MusicLoop" {
        val understood = VietnameseCommandUnderstanding.understand("mở music look")
        understood.command shouldBe CanonicalCommand.OpenApp("MusicLoop")
        val p = platform()
        val trace = executor(p).executeTraced(understood.command!!)
        trace.packageName shouldBe "com.musicloop.car"
        // Raw UI transcript remains the STT text, not rewritten.
        understood.rawTranscript shouldBe "mở music look"
    }

    "Unknown app → safe no-match" {
        val p = platform()
        val trace = executor(p).executeTraced(CanonicalCommand.OpenApp("Something Unknown"))
        trace.actionTaken.shouldBeFalse()
        p.activities.shouldBe(emptyList())
        trace.speechVi shouldContain "Không tìm thấy"
    }

    "Weak fuzzy must not launch unrelated app" {
        val p = platform()
        // "Maps" must not match a weak query like "ma"
        val resolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        )
        resolver.resolve("ma") shouldBe AppResolveResult.NotFound
        resolver.resolve("youtubeeeeeee") shouldBe AppResolveResult.NotFound
    }

    // --- PLAY_MEDIA ---

    "PlayMedia Đừng xa em đêm nay on YouTube" {
        val cmd = CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        val speech = VietnameseCommandUnderstanding.confirmationSpeechVi(cmd)!!
        speech shouldContain "Đừng xa em đêm nay"
        speech shouldContain "YouTube"
        val p = platform()
        val queries = mutableListOf<String>()
        val trace = executor(p, recordingYouTube(queries)).executeTraced(cmd)
        trace.speechVi shouldBe speech
        trace.mediaQuery shouldBe "Đừng xa em đêm nay"
        trace.mediaProvider shouldBe "YouTube"
        trace.mediaData.shouldNotBeNull()
        trace.mediaData!! shouldContain "watch?v="
        trace.mediaData!!.shouldNotContain("search_query=")
        trace.actionTaken.shouldBeTrue()
        queries shouldBe listOf("Đừng xa em đêm nay")
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        p.activities.shouldBe(emptyList())
    }

    "PlayMedia Nơi này có anh without title special-case" {
        val cmd = CanonicalCommand.PlayMedia("Nơi này có anh", "YouTube")
        val trace = executor().executeTraced(cmd)
        trace.mediaQuery shouldBe "Nơi này có anh"
        trace.speechVi shouldContain "Nơi này có anh"
    }

    "Unknown media provider → unsupported" {
        val trace = executor().executeTraced(
            CanonicalCommand.PlayMedia("any song", "SpotifyX"),
        )
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldContain "unknown_provider"
    }

    // --- EXACTLY ONCE / CANCEL ---

    "duplicate final claim → one action only" {
        CanonicalActionGate.bind(100L)
        CanonicalActionGate.tryClaim(100L).shouldBeTrue()
        CanonicalActionGate.tryClaim(100L).shouldBeFalse()
    }

    "cancel before final → zero action" {
        CanonicalActionGate.bind(200L)
        SessionCommandDecision.bindSession(200L)
        SessionCommandDecision.markCancelled(200L)
        CanonicalActionGate.markCancelled(200L)
        CanonicalActionGate.tryClaim(200L).shouldBeFalse()
        SessionCommandDecision.decideFinal(
            200L,
            listOf("Chỉ đường đến Mỹ Đình" to 1f),
        ).shouldBeNull()
    }

    "stale previous-session command → zero action" {
        CanonicalActionGate.bind(300L)
        CanonicalActionGate.tryClaim(999L).shouldBeFalse()
        CanonicalActionGate.clear(300L)
        CanonicalActionGate.tryClaim(300L).shouldBeFalse()
    }

    "late callback after terminal → zero second action" {
        CanonicalActionGate.bind(400L)
        CanonicalActionGate.tryClaim(400L).shouldBeTrue()
        CanonicalActionGate.markCompleted(400L)
        CanonicalActionGate.tryClaim(400L).shouldBeFalse()
        CanonicalActionGate.mayAct(400L).shouldBeFalse()
    }

    "CommandSessionOutcome duplicate EXECUTED rejected" {
        CommandSessionOutcome.reset()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
    }

    "geo URI encoding round-trip for Vietnamese destinations" {
        listOf("Mỹ Đình", "Hồ Gươm", "sân bay Nội Bài", "Big C Thăng Long").forEach { d ->
            NavigatePayload.decodeQuery(NavigatePayload.geoUri(d)) shouldBe d
        }
    }
})

class Phase3FakePlatform : CarfuSkillPlatform {
    var launchable = mutableSetOf(
        "com.google.android.youtube",
        "com.google.android.apps.maps",
        "com.musicloop.car",
        "com.android.chrome",
    )
    var launchableLabels = mutableMapOf(
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.maps" to "Maps",
        "com.musicloop.car" to "MusicLoop",
        "com.android.chrome" to "Chrome",
    )
    val activities = mutableListOf<StartedActivity>()

    override fun hasPermission(permission: String): Boolean = true
    override fun lookupContacts(foldedQuery: String): List<CarfuContact> = emptyList()

    override fun resolveLaunch(spec: CarfuLaunchSpec): String? {
        if (!spec.packageName.isNullOrBlank() && spec.packageName in launchable) {
            return spec.packageName
        }
        return when {
            spec.data?.startsWith("geo:") == true -> "com.google.android.apps.maps"
            spec.data?.contains("youtube.com") == true -> "com.google.android.youtube"
            else -> spec.packageName
        }
    }

    override fun startLaunch(spec: CarfuLaunchSpec): Boolean {
        val pkg = resolveLaunch(spec) ?: return false
        activities += StartedActivity(spec.action, pkg, spec.className, spec.data)
        return true
    }

    override fun isPackageLaunchable(packageName: String): Boolean = packageName in launchable

    override fun launchPackage(packageName: String): Boolean {
        if (packageName !in launchable) return false
        activities += StartedActivity("LAUNCH", packageName, null, null)
        return true
    }

    override fun listLaunchableApps(): List<LaunchableApp> =
        launchable.map { LaunchableApp(it, launchableLabels[it] ?: it) }

    override fun dispatchMediaKey(keyCode: Int): Boolean = true
    override fun adjustVolume(raise: Boolean): Boolean = true
    override fun currentTimeSpeech(): String = "09:30"
    override fun isOnline(): Boolean = true
    override fun httpGet(url: String, timeoutMs: Int): HttpFetchResult =
        HttpFetchResult.Error("n/a")
    override fun nowEpochMs(): Long = 0L
    override fun scheduleAlarm(
        id: String,
        fireAtEpochMs: Long,
        kind: org.stypox.dicio.skills.carfu.CarfuAlarmKind,
        label: String,
    ) {}
    override fun cancelAlarm(id: String) {}
    override fun saveTimer(timer: CarfuPersistedAlarm?) {}
    override fun loadTimer(): CarfuPersistedAlarm? = null
    override fun saveReminder(reminder: CarfuPersistedAlarm?) {}
    override fun loadReminder(): CarfuPersistedAlarm? = null
    override fun setBackgroundWakeEnabled(enabled: Boolean) {}
    override fun startWakeService() {}
    override fun stopWakeService() {}
    override fun hasTorch(): Boolean = false
    override fun setTorch(on: Boolean): Boolean = false
}
