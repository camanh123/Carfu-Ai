package org.stypox.dicio.skills.carfu

import android.Manifest
import android.view.KeyEvent
import org.stypox.dicio.io.session.CarfuIntent
import org.stypox.dicio.io.session.RoutedCommand
import org.stypox.dicio.io.session.WeatherWhen
import org.stypox.dicio.io.session.VietnameseTranscript
import java.net.URLEncoder

data class SkillExecutionResult(
    val speechVi: String,
    val actionTaken: Boolean,
    val afterTts: (() -> Unit)? = null,
    val resumeWakeAfter: Boolean = true,
)

/**
 * The only production executor for [CarfuCommandRouter] matches.
 * SkillRanker is not called for a routed intent.
 */
class CarfuVietnameseSkillExecutor(
    private val platform: CarfuSkillPlatform,
) {
    fun execute(routed: RoutedCommand): SkillExecutionResult {
        return when (routed.intent) {
            CarfuIntent.OPEN_YOUTUBE -> openKnown(
                listOf(
                    "com.google.android.youtube",
                    "com.vanced.android.youtube",
                    "app.revanced.android.youtube",
                ),
                "YouTube",
            )
            CarfuIntent.OPEN_MAPS -> openKnown(
                listOf("com.google.android.apps.maps"),
                "bản đồ",
            )
            CarfuIntent.OPEN_MUSICLOOP -> openKnown(
                listOf("com.musicloop.car", "com.musicloop", "com.syu.music"),
                "nhạc",
            )
            CarfuIntent.NAVIGATE_AIRPORT -> navigate(routed.place ?: "sân bay")
            CarfuIntent.NAVIGATE_HOME -> navigate(routed.place ?: "nhà")
            CarfuIntent.NAVIGATE_PLACE -> navigate(routed.place ?: "")
            CarfuIntent.MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT, "Bài tiếp.")
            CarfuIntent.MEDIA_PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Bài trước.")
            CarfuIntent.MEDIA_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Đã tạm dừng nhạc.")
            CarfuIntent.MEDIA_PLAY -> media(KeyEvent.KEYCODE_MEDIA_PLAY, "Đang phát nhạc.")
            CarfuIntent.VOLUME_UP -> volume(raise = true, "Đã tăng âm lượng.")
            CarfuIntent.VOLUME_DOWN -> volume(raise = false, "Đã giảm âm lượng.")
            CarfuIntent.CURRENT_TIME -> SkillExecutionResult(
                speechVi = platform.currentTimeSpeech(),
                actionTaken = true,
            )
            CarfuIntent.CALL_CONTACT -> callContact(routed)
            CarfuIntent.CALL_NUMBER -> callNumber(routed.phoneNumber.orEmpty())
            CarfuIntent.OPEN_PHONE -> openPhone()
            CarfuIntent.WEATHER -> weather(routed)
            CarfuIntent.TIMER_SET -> timerSet(routed)
            CarfuIntent.TIMER_CANCEL -> timerCancel()
            CarfuIntent.TIMER_QUERY -> timerQuery()
            CarfuIntent.LISTENING_STOP -> listeningStop()
            CarfuIntent.LISTENING_START -> listeningStart()
            CarfuIntent.SEARCH -> search(routed)
            CarfuIntent.CALCULATE -> calculate(routed)
            CarfuIntent.REMINDER_SET -> reminderSet(routed)
            CarfuIntent.REMINDER_CANCEL -> reminderCancel()
            CarfuIntent.FLASHLIGHT_ON -> flashlight(true)
            CarfuIntent.FLASHLIGHT_OFF -> flashlight(false)
        }
    }

    private fun openKnown(packages: List<String>, label: String): SkillExecutionResult {
        val pkg = packages.firstOrNull { platform.isPackageLaunchable(it) }
        if (pkg == null) {
            return SkillExecutionResult("Không tìm thấy ứng dụng $label.", actionTaken = false)
        }
        val ok = platform.launchPackage(pkg)
        return if (ok) {
            SkillExecutionResult("Đang mở $label.", actionTaken = true)
        } else {
            SkillExecutionResult("Không mở được $label.", actionTaken = false)
        }
    }

    private fun navigate(place: String): SkillExecutionResult {
        if (place.isBlank()) {
            return SkillExecutionResult("Hãy nói nơi bạn muốn đến.", actionTaken = false)
        }
        val spec = CarfuLaunchSpec(
            action = CarfuDialer.ACTION_VIEW,
            data = "geo:0,0?q=${URLEncoder.encode(place, "UTF-8")}",
        )
        if (CarfuDialer.isBlockedPackage(platform.resolveLaunch(spec))) {
            return SkillExecutionResult("Không tìm thấy ứng dụng bản đồ.", actionTaken = false)
        }
        val ok = platform.startLaunch(spec)
        return if (ok) {
            SkillExecutionResult("Đang dẫn đường đến $place.", actionTaken = true)
        } else {
            SkillExecutionResult("Không tìm thấy ứng dụng bản đồ.", actionTaken = false)
        }
    }

    private fun media(key: Int, success: String): SkillExecutionResult {
        val ok = platform.dispatchMediaKey(key)
        return if (ok) {
            SkillExecutionResult(success, actionTaken = true)
        } else {
            SkillExecutionResult("Không có phiên phát nhạc nào đang chạy.", actionTaken = false)
        }
    }

    private fun volume(raise: Boolean, success: String): SkillExecutionResult {
        val ok = platform.adjustVolume(raise)
        return if (ok) {
            SkillExecutionResult(success, actionTaken = true)
        } else {
            SkillExecutionResult("Không điều chỉnh được âm lượng.", actionTaken = false)
        }
    }

    private fun callContact(routed: RoutedCommand): SkillExecutionResult {
        val query = routed.contactName.orEmpty()
        if (query.isBlank()) {
            return SkillExecutionResult("Hãy nói tên liên hệ cần gọi.", actionTaken = false)
        }
        if (!platform.hasPermission(Manifest.permission.READ_CONTACTS)) {
            return SkillExecutionResult(
                "Cần quyền danh bạ để gọi theo tên. Hãy cấp quyền trong Cài đặt.",
                actionTaken = false,
            )
        }
        val resolved = CarfuTelephoneLookup.resolve(query, platform.lookupContacts(query))
        when {
            resolved.none || resolved.unique == null && resolved.ambiguous.isEmpty() -> {
                return SkillExecutionResult(
                    "Không tìm thấy liên hệ ${displayContact(query)}.",
                    actionTaken = false,
                )
            }
            resolved.ambiguous.isNotEmpty() -> {
                val names = resolved.ambiguous.joinToString(", ") { it.name }
                return SkillExecutionResult(
                    "Có nhiều liên hệ: $names. Hãy nói rõ tên.",
                    actionTaken = false,
                )
            }
            else -> {
                val contact = resolved.unique!!
                val number = contact.numbers.firstOrNull().orEmpty()
                if (number.isBlank()) {
                    return SkillExecutionResult(
                        "Liên hệ ${contact.name} không có số điện thoại.",
                        actionTaken = false,
                    )
                }
                return launchDial(number, "Đang gọi ${contact.name}.")
            }
        }
    }

    private fun callNumber(number: String): SkillExecutionResult {
        if (number.isBlank()) {
            return SkillExecutionResult("Hãy nói số điện thoại cần gọi.", actionTaken = false)
        }
        return launchDial(number, "Đang gọi số $number.")
    }

    private fun openPhone(): SkillExecutionResult {
        return launchFirstUnblocked(
            CarfuDialer.openPhoneCandidates(),
            success = "Đang mở điện thoại.",
            missing = "Không tìm thấy ứng dụng điện thoại.",
            zalo = "Không gửi cuộc gọi sang Zalo. Không tìm thấy ứng dụng điện thoại.",
        )
    }

    private fun launchDial(number: String, success: String): SkillExecutionResult {
        return launchFirstUnblocked(
            CarfuDialer.dialCandidates(number),
            success = success,
            missing = "Không tìm thấy ứng dụng điện thoại.",
            zalo = "Không gửi cuộc gọi sang Zalo. Không tìm thấy ứng dụng điện thoại.",
        )
    }

    private fun launchFirstUnblocked(
        candidates: List<CarfuLaunchSpec>,
        success: String,
        missing: String,
        zalo: String,
    ): SkillExecutionResult {
        var sawZaloOnly = false
        var attempted = 0
        for (spec in candidates) {
            val pkg = platform.resolveLaunch(spec)
            if (pkg == null) continue
            if (CarfuDialer.isBlockedPackage(pkg)) {
                sawZaloOnly = true
                continue
            }
            attempted += 1
            if (platform.startLaunch(spec)) {
                return SkillExecutionResult(success, actionTaken = true)
            }
        }
        if (attempted == 0 && sawZaloOnly) {
            return SkillExecutionResult(zalo, actionTaken = false)
        }
        return SkillExecutionResult(missing, actionTaken = false)
    }

    private fun weather(routed: RoutedCommand): SkillExecutionResult {
        if (!platform.isOnline()) {
            return SkillExecutionResult(
                "Không có mạng, không lấy được thời tiết.",
                actionTaken = false,
            )
        }
        val cityQuery = routed.city?.takeIf { it.isNotBlank() } ?: CarfuWeatherClient.DEFAULT_CITY
        val geo = platform.httpGet(CarfuWeatherClient.geocodeUrl(cityQuery), CarfuWeatherClient.TIMEOUT_MS)
        val geoBody = when (geo) {
            is HttpFetchResult.Ok -> geo.body
            HttpFetchResult.Offline -> return SkillExecutionResult(
                "Không có mạng, không lấy được thời tiết.", actionTaken = false,
            )
            HttpFetchResult.Timeout -> return SkillExecutionResult(
                "Không lấy được thời tiết, hãy thử lại.", actionTaken = false,
            )
            is HttpFetchResult.Error -> return SkillExecutionResult(
                "Không lấy được thời tiết, hãy thử lại.", actionTaken = false,
            )
        }
        val parsedGeo = CarfuWeatherClient.parseGeocode(geoBody)
            ?: return SkillExecutionResult(
                "Không tìm thấy thành phố ${routed.city ?: cityQuery}.",
                actionTaken = false,
            )
        val forecast = platform.httpGet(
            CarfuWeatherClient.forecastUrl(parsedGeo.second, parsedGeo.third),
            CarfuWeatherClient.TIMEOUT_MS,
        )
        val forecastBody = when (forecast) {
            is HttpFetchResult.Ok -> forecast.body
            HttpFetchResult.Offline -> return SkillExecutionResult(
                "Không có mạng, không lấy được thời tiết.", actionTaken = false,
            )
            HttpFetchResult.Timeout, is HttpFetchResult.Error -> return SkillExecutionResult(
                "Không lấy được thời tiết, hãy thử lại.", actionTaken = false,
            )
        }
        val snapshot = CarfuWeatherClient.parseForecast(parsedGeo.first, forecastBody)
            ?: return SkillExecutionResult(
                "Không lấy được thời tiết, hãy thử lại.",
                actionTaken = false,
            )
        return SkillExecutionResult(
            speechVi = CarfuWeatherClient.speak(snapshot, routed.weatherWhen, routed.rainAsk),
            actionTaken = true,
        )
    }

    private fun timerSet(routed: RoutedCommand): SkillExecutionResult {
        val duration = routed.durationMs ?: return SkillExecutionResult(
            "Không hiểu thời gian hẹn giờ.",
            actionTaken = false,
        )
        val fireAt = platform.nowEpochMs() + duration
        val label = VietnameseNumbers.formatDurationVi(duration)
        val alarm = CarfuPersistedAlarm(
            id = TIMER_ID,
            fireAtEpochMs = fireAt,
            durationMs = duration,
            label = label,
            kind = CarfuAlarmKind.TIMER,
        )
        platform.saveTimer(alarm)
        platform.scheduleAlarm(TIMER_ID, fireAt, CarfuAlarmKind.TIMER, label)
        return SkillExecutionResult("Đã hẹn giờ $label.", actionTaken = true)
    }

    private fun timerCancel(): SkillExecutionResult {
        val existing = platform.loadTimer()
        platform.cancelAlarm(TIMER_ID)
        platform.saveTimer(null)
        return if (existing == null) {
            SkillExecutionResult("Không có hẹn giờ nào.", actionTaken = false)
        } else {
            SkillExecutionResult("Đã hủy hẹn giờ.", actionTaken = true)
        }
    }

    private fun timerQuery(): SkillExecutionResult {
        val existing = platform.loadTimer()
            ?: return SkillExecutionResult("Không có hẹn giờ nào.", actionTaken = false)
        val remaining = (existing.fireAtEpochMs - platform.nowEpochMs()).coerceAtLeast(0L)
        return SkillExecutionResult(
            "Còn ${VietnameseNumbers.formatDurationVi(remaining)}.",
            actionTaken = true,
        )
    }

    private fun listeningStop(): SkillExecutionResult {
        platform.setBackgroundWakeEnabled(false)
        return SkillExecutionResult(
            speechVi = "Đã tắt nghe nền. Bật lại trong Cài đặt hoặc thông báo.",
            actionTaken = true,
            afterTts = { platform.stopWakeService() },
            resumeWakeAfter = false,
        )
    }

    private fun listeningStart(): SkillExecutionResult {
        platform.setBackgroundWakeEnabled(true)
        platform.startWakeService()
        return SkillExecutionResult("Đã bật nghe nền.", actionTaken = true)
    }

    private fun search(routed: RoutedCommand): SkillExecutionResult {
        val query = extractSearchQuery(routed.searchQuery.orEmpty())
        if (query.isBlank()) {
            return SkillExecutionResult("Hãy nói nội dung cần tìm.", actionTaken = false)
        }
        val webSearch = CarfuLaunchSpec(
            action = CarfuDialer.ACTION_WEB_SEARCH,
            extraQuery = query,
        )
        val pkg = platform.resolveLaunch(webSearch)
        if (pkg != null && !CarfuDialer.isBlockedPackage(pkg) && platform.startLaunch(webSearch)) {
            return SkillExecutionResult("Đang tìm $query.", actionTaken = true)
        }
        val browser = CarfuLaunchSpec(
            action = CarfuDialer.ACTION_VIEW,
            data = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}",
        )
        if (platform.resolveLaunch(browser) != null && platform.startLaunch(browser)) {
            return SkillExecutionResult("Đang tìm $query.", actionTaken = true)
        }
        return SkillExecutionResult("Không tìm thấy trình duyệt.", actionTaken = false)
    }

    private fun calculate(routed: RoutedCommand): SkillExecutionResult {
        val left = routed.calcLeft
        val right = routed.calcRight
        val op = routed.calcOp
        if (left == null || right == null || op == null) {
            return SkillExecutionResult("Không hiểu phép tính.", actionTaken = false)
        }
        if (op == VietnameseNumbers.ArithmeticOp.DIV && right == 0.0) {
            return SkillExecutionResult("Không chia được cho không.", actionTaken = false)
        }
        val result = when (op) {
            VietnameseNumbers.ArithmeticOp.ADD -> left + right
            VietnameseNumbers.ArithmeticOp.SUB -> left - right
            VietnameseNumbers.ArithmeticOp.MUL -> left * right
            VietnameseNumbers.ArithmeticOp.DIV -> left / right
        }
        val opWord = when (op) {
            VietnameseNumbers.ArithmeticOp.ADD -> "cộng"
            VietnameseNumbers.ArithmeticOp.SUB -> "trừ"
            VietnameseNumbers.ArithmeticOp.MUL -> "nhân"
            VietnameseNumbers.ArithmeticOp.DIV -> "chia"
        }
        return SkillExecutionResult(
            "${VietnameseNumbers.formatNumberVi(left)} $opWord ${VietnameseNumbers.formatNumberVi(right)} bằng ${VietnameseNumbers.formatNumberVi(result)}.",
            actionTaken = true,
        )
    }

    private fun reminderSet(routed: RoutedCommand): SkillExecutionResult {
        val duration = routed.durationMs ?: return SkillExecutionResult(
            "Không hiểu thời gian nhắc nhở.",
            actionTaken = false,
        )
        val fireAt = platform.nowEpochMs() + duration
        val label = routed.reminderMessage?.ifBlank { null }
            ?: VietnameseNumbers.formatDurationVi(duration)
        val alarm = CarfuPersistedAlarm(
            id = REMINDER_ID,
            fireAtEpochMs = fireAt,
            durationMs = duration,
            label = label,
            kind = CarfuAlarmKind.REMINDER,
        )
        platform.saveReminder(alarm)
        platform.scheduleAlarm(REMINDER_ID, fireAt, CarfuAlarmKind.REMINDER, label)
        return SkillExecutionResult(
            "Đã đặt nhắc nhở sau ${VietnameseNumbers.formatDurationVi(duration)}. Nhắc nhở được khôi phục sau khi khởi động lại.",
            actionTaken = true,
        )
    }

    private fun reminderCancel(): SkillExecutionResult {
        val existing = platform.loadReminder()
        platform.cancelAlarm(REMINDER_ID)
        platform.saveReminder(null)
        return if (existing == null) {
            SkillExecutionResult("Không có nhắc nhở nào.", actionTaken = false)
        } else {
            SkillExecutionResult("Đã hủy nhắc nhở.", actionTaken = true)
        }
    }

    private fun flashlight(on: Boolean): SkillExecutionResult {
        if (!platform.hasTorch()) {
            return SkillExecutionResult("Thiết bị này không có đèn pin.", actionTaken = false)
        }
        val ok = platform.setTorch(on)
        return if (!ok) {
            SkillExecutionResult("Không bật được đèn pin.", actionTaken = false)
        } else if (on) {
            SkillExecutionResult("Đã bật đèn pin.", actionTaken = true)
        } else {
            SkillExecutionResult("Đã tắt đèn pin.", actionTaken = true)
        }
    }

    private fun extractSearchQuery(raw: String): String {
        val folded = VietnameseTranscript.foldForMatch(raw)
        val prefixes = listOf("tim kiem ", "tim ")
        var rest = folded
        for (p in prefixes) {
            if (rest.startsWith(p)) {
                rest = rest.removePrefix(p)
                break
            }
        }
        return rest.trim()
    }

    private fun displayContact(folded: String): String = folded

    companion object {
        const val TIMER_ID = "carfu_timer"
        const val REMINDER_ID = "carfu_reminder"
    }
}
