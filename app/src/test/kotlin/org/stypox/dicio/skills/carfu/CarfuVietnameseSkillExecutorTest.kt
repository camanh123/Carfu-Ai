package org.stypox.dicio.skills.carfu

import android.content.Intent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.io.session.CarfuCommandRouter
import org.stypox.dicio.io.session.CarfuIntent
import org.stypox.dicio.io.session.RoutedCommand
import org.stypox.dicio.io.session.WeatherWhen

private class FakeCarfuPlatform : CarfuSkillPlatform {
    var contactsPermission = true
    var contacts: List<CarfuContact> = listOf(CarfuContact("Mẹ", listOf("0911111111")))
    var fytPresent = true
    var dialerPresent = true
    var zaloOnly = false
    var launchable = mutableSetOf(
        "com.google.android.youtube",
        "com.google.android.apps.maps",
        "com.musicloop.car",
    )
    var online = true
    var http: (String) -> HttpFetchResult = { HttpFetchResult.Error("unset") }
    var now = 1_000_000L
    var timer: CarfuPersistedAlarm? = null
    var reminder: CarfuPersistedAlarm? = null
    var wakePolicyEnabled = true
    var wakeStarted = 0
    var wakeStopped = 0
    var torchAvailable = false
    var torchOn: Boolean? = null
    var mediaOk = true
    var volumeOk = true
    val activities = mutableListOf<StartedActivity>()
    val scheduled = mutableListOf<CarfuPersistedAlarm>()
    val cancelled = mutableListOf<String>()
    val httpUrls = mutableListOf<String>()

    override fun hasPermission(permission: String): Boolean =
        permission != android.Manifest.permission.READ_CONTACTS || contactsPermission

    override fun lookupContacts(foldedQuery: String): List<CarfuContact> = contacts

    override fun resolvePackage(intent: Intent): String? {
        if (zaloOnly) return CarfuDialer.ZALO_PACKAGE
        val component = intent.component?.className
        if (component == CarfuDialer.FYT_PHONE_ACTIVITY) {
            return if (fytPresent) CarfuDialer.FYT_BT_PACKAGE else null
        }
        if (intent.`package` == CarfuDialer.FYT_BT_PACKAGE) {
            return if (fytPresent) CarfuDialer.FYT_BT_PACKAGE else null
        }
        return when (intent.action) {
            Intent.ACTION_DIAL -> if (dialerPresent) "com.android.dialer" else null
            Intent.ACTION_VIEW -> when {
                intent.dataString?.startsWith("geo:") == true -> "com.google.android.apps.maps"
                intent.dataString?.startsWith("http") == true -> "com.android.browser"
                intent.dataString?.startsWith("tel:") == true && fytPresent -> CarfuDialer.FYT_BT_PACKAGE
                intent.dataString?.startsWith("tel:") == true && dialerPresent -> "com.android.dialer"
                else -> null
            }
            Intent.ACTION_WEB_SEARCH -> "com.android.browser"
            else -> intent.component?.packageName ?: intent.`package`
        }
    }

    override fun startActivity(intent: Intent): Boolean {
        val pkg = resolvePackage(intent) ?: return false
        if (CarfuDialer.isBlockedPackage(pkg)) return false
        activities += StartedActivity(
            action = intent.action,
            packageName = pkg,
            className = intent.component?.className,
            data = intent.dataString,
        )
        return true
    }

    override fun isPackageLaunchable(packageName: String): Boolean = packageName in launchable

    override fun launchPackage(packageName: String): Boolean {
        if (packageName !in launchable) return false
        activities += StartedActivity("LAUNCH", packageName, null, null)
        return true
    }

    override fun dispatchMediaKey(keyCode: Int): Boolean = mediaOk

    override fun adjustVolume(raise: Boolean): Boolean = volumeOk

    override fun currentTimeSpeech(): String = "Bây giờ là 09:30."

    override fun isOnline(): Boolean = online

    override fun httpGet(url: String, timeoutMs: Int): HttpFetchResult {
        httpUrls += url
        return http(url)
    }

    override fun nowEpochMs(): Long = now

    override fun scheduleAlarm(id: String, fireAtEpochMs: Long, kind: CarfuAlarmKind, label: String) {
        scheduled += CarfuPersistedAlarm(id, fireAtEpochMs, fireAtEpochMs - now, label, kind)
    }

    override fun cancelAlarm(id: String) {
        cancelled += id
    }

    override fun saveTimer(timer: CarfuPersistedAlarm?) {
        this.timer = timer
    }

    override fun loadTimer(): CarfuPersistedAlarm? = timer

    override fun saveReminder(reminder: CarfuPersistedAlarm?) {
        this.reminder = reminder
    }

    override fun loadReminder(): CarfuPersistedAlarm? = reminder

    override fun setBackgroundWakeEnabled(enabled: Boolean) {
        wakePolicyEnabled = enabled
    }

    override fun startWakeService() {
        wakeStarted += 1
    }

    override fun stopWakeService() {
        wakeStopped += 1
    }

    override fun hasTorch(): Boolean = torchAvailable

    override fun setTorch(on: Boolean): Boolean {
        if (!torchAvailable) return false
        torchOn = on
        return true
    }

    override fun startedActivities(): List<StartedActivity> = activities.toList()
}

private fun production(raw: String, platform: FakeCarfuPlatform): Pair<RoutedCommand, SkillExecutionResult> {
    val routed = CarfuCommandRouter.match(raw)
    routed.shouldNotBeNull()
    val result = CarfuVietnameseSkillExecutor(platform).execute(routed)
    return routed to result
}

class CarfuVietnameseSkillExecutorTest : StringSpec({
    "existing 12 commands still route with and without diacritics" {
        CarfuCommandRouter.match("Mở YouTube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        CarfuCommandRouter.match("mo youtube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        CarfuCommandRouter.match("Chỉ đường đến sân bay")!!.intent shouldBe CarfuIntent.NAVIGATE_AIRPORT
        CarfuCommandRouter.match("chi duong den san bay")!!.intent shouldBe CarfuIntent.NAVIGATE_AIRPORT
        val platform = FakeCarfuPlatform()
        val opened = production("Mở YouTube", platform).second
        opened.actionTaken.shouldBeTrue()
        platform.activities.shouldHaveSize(1)
        platform.activities[0].packageName shouldBe "com.google.android.youtube"
    }

    "telephone contact with diacritics reaches dial executor" {
        val platform = FakeCarfuPlatform()
        val (routed, result) = production("Gọi cho mẹ", platform)
        routed.intent shouldBe CarfuIntent.CALL_CONTACT
        routed.contactName shouldBe "me"
        result.actionTaken.shouldBeTrue()
        result.speechVi shouldContain "Đang gọi"
        platform.activities.shouldHaveSize(1)
        platform.activities[0].packageName shouldBe CarfuDialer.FYT_BT_PACKAGE
        platform.activities[0].data shouldContain "0911111111"
        platform.activities[0].packageName shouldBe CarfuDialer.FYT_BT_PACKAGE
        CarfuDialer.isBlockedPackage(platform.activities[0].packageName).shouldBeFalse()
    }

    "telephone contact without diacritics reaches the same executor" {
        val platform = FakeCarfuPlatform()
        val (_, result) = production("goi cho me", platform)
        result.actionTaken.shouldBeTrue()
        platform.activities.shouldHaveSize(1)
        platform.activities[0].data shouldContain "0911111111"
    }

    "telephone number uses ACTION_DIAL fallback when FYT is missing" {
        val platform = FakeCarfuPlatform().apply { fytPresent = false }
        val (routed, result) = production("Gọi số 0912345678", platform)
        routed.intent shouldBe CarfuIntent.CALL_NUMBER
        routed.phoneNumber shouldBe "0912345678"
        result.actionTaken.shouldBeTrue()
        result.speechVi shouldContain "0912345678"
        platform.activities.shouldHaveSize(1)
        platform.activities[0].action shouldBe Intent.ACTION_DIAL
        platform.activities[0].data shouldContain "0912345678"
        platform.activities[0].packageName shouldBe "com.android.dialer"
    }

    "open phone prefers FYT PhoneActivity" {
        val platform = FakeCarfuPlatform()
        val (_, result) = production("Mở điện thoại", platform)
        result.actionTaken.shouldBeTrue()
        result.speechVi shouldContain "điện thoại"
        platform.activities.shouldHaveSize(1)
        platform.activities[0].className shouldBe CarfuDialer.FYT_PHONE_ACTIVITY
    }

    "telephone permission denied does not dial" {
        val platform = FakeCarfuPlatform().apply { contactsPermission = false }
        val (_, result) = production("Gọi cho mẹ", platform)
        result.actionTaken.shouldBeFalse()
        result.speechVi shouldContain "quyền danh bạ"
        platform.activities.shouldHaveSize(0)
    }

    "missing contact and ambiguous contact speak Vietnamese and do not dial" {
        val none = FakeCarfuPlatform().apply { contacts = emptyList() }
        val missing = production("Gọi cho bố", none).second
        missing.actionTaken.shouldBeFalse()
        missing.speechVi shouldContain "Không tìm thấy liên hệ"
        none.activities.shouldHaveSize(0)

        val many = FakeCarfuPlatform().apply {
            contacts = listOf(
                CarfuContact("Mẹ", listOf("1")),
                CarfuContact("Me", listOf("2")),
            )
        }
        val ambiguous = production("Gọi cho mẹ", many).second
        ambiguous.actionTaken.shouldBeFalse()
        ambiguous.speechVi shouldContain "Có nhiều liên hệ"
        many.activities.shouldHaveSize(0)
    }

    "no dialer and Zalo-only resolver do not place a call" {
        val missing = FakeCarfuPlatform().apply {
            fytPresent = false
            dialerPresent = false
        }
        val noDialer = production("Gọi số 0912345678", missing).second
        noDialer.actionTaken.shouldBeFalse()
        noDialer.speechVi shouldContain "Không tìm thấy ứng dụng điện thoại"
        missing.activities.shouldHaveSize(0)

        val zalo = FakeCarfuPlatform().apply { zaloOnly = true }
        val blocked = production("Gọi số 0912345678", zalo).second
        blocked.actionTaken.shouldBeFalse()
        blocked.speechVi shouldContain "Zalo"
        zalo.activities.shouldHaveSize(0)
    }

    "weather today and city extract then fetch Open-Meteo" {
        val platform = FakeCarfuPlatform().apply {
            http = { url ->
                if (url.contains("geocoding-api")) {
                    HttpFetchResult.Ok("""{"results":[{"name":"Hà Nội","latitude":21.02,"longitude":105.84}]}""")
                } else {
                    HttpFetchResult.Ok(
                        """{"current":{"temperature_2m":32.1,"weather_code":1,"precipitation":0},
                            "daily":{"weather_code":[1,61],"temperature_2m_max":[33,30],
                            "precipitation_probability_max":[10,80]}}"""
                    )
                }
            }
        }
        val (routed, result) = production("Thời tiết ở Hà Nội", platform)
        routed.intent shouldBe CarfuIntent.WEATHER
        routed.city shouldBe "ha noi"
        result.actionTaken.shouldBeTrue()
        result.speechVi shouldContain "Hà Nội"
        platform.httpUrls.shouldHaveSize(2)
        platform.httpUrls[0] shouldContain "geocoding-api.open-meteo.com"
        platform.httpUrls[1] shouldContain "api.open-meteo.com"
    }

    "weather without diacritics and tomorrow rain question" {
        val platform = FakeCarfuPlatform().apply {
            http = { url ->
                if (url.contains("geocoding")) {
                    HttpFetchResult.Ok("""{"results":[{"name":"Hà Nội","latitude":21.0,"longitude":105.8}]}""")
                } else {
                    HttpFetchResult.Ok(
                        """{"current":{"temperature_2m":30,"weather_code":1,"precipitation":0},
                            "daily":{"weather_code":[1,61],"temperature_2m_max":[33,29],
                            "precipitation_probability_max":[10,80]}}"""
                    )
                }
            }
        }
        val (routed, result) = production("ngay mai co mua khong", platform)
        routed.weatherWhen shouldBe WeatherWhen.TOMORROW
        routed.rainAsk.shouldBeTrue()
        result.speechVi shouldContain "mưa"
        result.actionTaken.shouldBeTrue()
    }

    "weather offline and timeout do not claim success" {
        val offline = FakeCarfuPlatform().apply { online = false }
        val off = production("Thời tiết hôm nay", offline).second
        off.actionTaken.shouldBeFalse()
        off.speechVi shouldContain "Không có mạng"
        offline.httpUrls.shouldHaveSize(0)

        val timeout = FakeCarfuPlatform().apply { http = { HttpFetchResult.Timeout } }
        val timed = production("Thời tiết hôm nay", timeout).second
        timed.actionTaken.shouldBeFalse()
        timed.speechVi shouldContain "thử lại"
    }

    "timer set cancel and query persist independently of Activity" {
        val platform = FakeCarfuPlatform()
        val set = production("Hẹn giờ 5 phút", platform)
        set.first.durationMs shouldBe 300_000L
        set.second.actionTaken.shouldBeTrue()
        set.second.speechVi shouldContain "5 phút"
        platform.timer.shouldNotBeNull()
        platform.scheduled.shouldHaveSize(1)

        val words = production("Hẹn giờ mười phút", FakeCarfuPlatform())
        words.first.durationMs shouldBe 600_000L

        val query = production("Còn bao lâu", platform).second
        query.speechVi shouldContain "Còn"

        val cancel = production("Hủy hẹn giờ", platform).second
        cancel.actionTaken.shouldBeTrue()
        platform.timer.shouldBeNull()
        platform.cancelled shouldBe listOf(CarfuVietnameseSkillExecutor.TIMER_ID)
    }

    "listening stop speaks first then stops wake; start persists policy" {
        val platform = FakeCarfuPlatform()
        val stop = production("Tắt nghe nền", platform).second
        stop.speechVi shouldContain "Đã tắt nghe nền"
        stop.resumeWakeAfter.shouldBeFalse()
        platform.wakePolicyEnabled.shouldBeFalse()
        platform.wakeStopped shouldBe 0
        stop.afterTts.shouldNotBeNull()
        stop.afterTts!!.invoke()
        platform.wakeStopped shouldBe 1

        val start = production("Bật nghe nền", platform).second
        start.actionTaken.shouldBeTrue()
        platform.wakePolicyEnabled.shouldBeTrue()
        platform.wakeStarted shouldBe 1
    }

    "search opens a browser intent and does not steal navigation" {
        val platform = FakeCarfuPlatform()
        val search = production("Tìm kiếm quán ăn gần đây", platform)
        search.first.intent shouldBe CarfuIntent.SEARCH
        search.second.actionTaken.shouldBeTrue()
        platform.activities.shouldHaveSize(1)
        platform.activities[0].action shouldBe Intent.ACTION_WEB_SEARCH

        CarfuCommandRouter.match("Chỉ đường đến sân bay")!!.intent shouldBe CarfuIntent.NAVIGATE_AIRPORT
        CarfuCommandRouter.match("chi duong den cho ben thanh")!!.intent shouldBe CarfuIntent.NAVIGATE_PLACE
    }

    "calculator does safe arithmetic and rejects division by zero" {
        val ok = production("Tính 5 cộng 3", FakeCarfuPlatform()).second
        ok.actionTaken.shouldBeTrue()
        ok.speechVi shouldContain "bằng 8"

        val words = production("mười nhân hai", FakeCarfuPlatform()).second
        words.speechVi shouldContain "bằng 20"

        val zero = production("100 chia 0", FakeCarfuPlatform()).second
        zero.actionTaken.shouldBeFalse()
        zero.speechVi shouldContain "Không chia được cho không"
    }

    "reminder is separate from timer and states reboot restore" {
        val platform = FakeCarfuPlatform()
        val set = production("Nhắc tôi sau 10 phút", platform)
        set.first.intent shouldBe CarfuIntent.REMINDER_SET
        set.first.durationMs shouldBe 600_000L
        set.second.speechVi shouldContain "khởi động lại"
        platform.reminder.shouldNotBeNull()
        platform.timer.shouldBeNull()

        val cancel = production("Hủy nhắc nhở", platform).second
        cancel.actionTaken.shouldBeTrue()
        platform.reminder.shouldBeNull()
    }

    "flashlight without hardware does not claim success" {
        val platform = FakeCarfuPlatform().apply { torchAvailable = false }
        val result = production("Bật đèn pin", platform).second
        result.actionTaken.shouldBeFalse()
        result.speechVi shouldBe "Thiết bị này không có đèn pin."
        platform.torchOn.shouldBeNull()
    }

    "missing music package does not fake success" {
        val platform = FakeCarfuPlatform().apply { launchable.clear() }
        val result = production("Mở MusicLoop", platform).second
        result.actionTaken.shouldBeFalse()
        result.speechVi shouldContain "Không tìm thấy"
        platform.activities.shouldHaveSize(0)
    }

    "unknown commands are not routed so no executor action runs" {
        CarfuCommandRouter.match("kể chuyện cười").shouldBeNull()
        CarfuCommandRouter.match("dịch sang tiếng anh").shouldBeNull()
        CarfuCommandRouter.match("lời bài hát we will rock you").shouldBeNull()
        CarfuCommandRouter.match("thổ").shouldBeNull()
    }

    "routed command does not double-fire an activity" {
        val platform = FakeCarfuPlatform()
        production("Gọi số 0912345678", platform)
        platform.activities.shouldHaveSize(1)
    }

    "skills catalog does not enable joke lyrics translation" {
        CarfuSkillCatalog.statusOf("joke", true, true) shouldBe CarfuSkillUiStatus.NOT_IMPLEMENTED
        CarfuSkillCatalog.statusOf("lyrics", true, true) shouldBe CarfuSkillUiStatus.NOT_IMPLEMENTED
        CarfuSkillCatalog.statusOf("translation", true, true) shouldBe CarfuSkillUiStatus.NOT_IMPLEMENTED
        CarfuSkillCatalog.statusOf("telephone", false, true) shouldBe CarfuSkillUiStatus.NEED_PERMISSION
        CarfuSkillCatalog.statusOf("weather", true, true) shouldBe CarfuSkillUiStatus.NEED_INTERNET
        CarfuSkillCatalog.statusOf("flashlight", true, false) shouldBe CarfuSkillUiStatus.UNSUPPORTED
        CarfuSkillCatalog.statusOf("timer", true, true) shouldBe CarfuSkillUiStatus.WORKING
        CarfuSkillCatalog.hiddenUpstreamIds shouldBe setOf("lyrics", "joke", "translation")
    }

    "weather parser reads Open-Meteo geocode and forecast" {
        val geo = CarfuWeatherClient.parseGeocode(
            """{"results":[{"name":"Hà Nội","latitude":21.02,"longitude":105.84}]}"""
        )!!
        geo.first shouldBe "Hà Nội"
        val snap = CarfuWeatherClient.parseForecast(
            geo.first,
            """{"current":{"temperature_2m":32.4,"weather_code":61,"precipitation":1.2},
                "daily":{"weather_code":[61,80],"temperature_2m_max":[33,30],
                "precipitation_probability_max":[70,90]}}""",
        )!!
        CarfuWeatherClient.speak(snap, WeatherWhen.TODAY, rainAsk = false) shouldContain "mưa"
    }
})
