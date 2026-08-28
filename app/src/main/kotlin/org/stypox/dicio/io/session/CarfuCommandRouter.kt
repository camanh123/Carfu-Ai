package org.stypox.dicio.io.session

import org.stypox.dicio.skills.carfu.VietnameseNumbers

/**
 * Single CARFU intent router. Matching happens only after STT produced a meaningful phrase.
 * Isolated noise fragments such as "thổ" / "hà hồ" / "người" never map to an intent.
 *
 * Parameterized routes extract contact, phone number, city, duration, search query, and
 * arithmetic operands. [match] is not an executor — [org.stypox.dicio.skills.carfu.CarfuVietnameseSkillExecutor]
 * is the only production caller for a matched intent.
 */
enum class CarfuIntent {
    OPEN_YOUTUBE,
    OPEN_MAPS,
    OPEN_MUSICLOOP,
    NAVIGATE_AIRPORT,
    NAVIGATE_HOME,
    NAVIGATE_PLACE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_PAUSE,
    MEDIA_PLAY,
    VOLUME_UP,
    VOLUME_DOWN,
    CURRENT_TIME,
    CALL_CONTACT,
    CALL_NUMBER,
    OPEN_PHONE,
    WEATHER,
    TIMER_SET,
    TIMER_CANCEL,
    TIMER_QUERY,
    LISTENING_STOP,
    LISTENING_START,
    SEARCH,
    CALCULATE,
    REMINDER_SET,
    REMINDER_CANCEL,
    FLASHLIGHT_ON,
    FLASHLIGHT_OFF,
}

enum class WeatherWhen { TODAY, TOMORROW }

data class RoutedCommand(
    val intent: CarfuIntent,
    val canonicalVi: String,
    val skillId: String,
    val contactName: String? = null,
    val phoneNumber: String? = null,
    val place: String? = null,
    val city: String? = null,
    val weatherWhen: WeatherWhen = WeatherWhen.TODAY,
    val durationMs: Long? = null,
    val searchQuery: String? = null,
    val calcLeft: Double? = null,
    val calcOp: VietnameseNumbers.ArithmeticOp? = null,
    val calcRight: Double? = null,
    val reminderMessage: String? = null,
    val rainAsk: Boolean = false,
)

object CarfuCommandRouter {
    private val PHONE_NUMBER = Regex("""(?:\+?84|0)\d[\d\s]{7,13}""")
    private val NAV_PREFIX = Regex("""^(?:chi duong|dan duong)(?: den| toi| ve)?\s+""")
    private val SEARCH_PREFIX = Regex("""^(?:tim kiem|tim)\s+""")
    private val CALL_CONTACT_PREFIX = Regex("""^(?:goi cho|goi)\s+""")

    private val EXACT: List<Pair<Set<String>, RoutedCommand>> = listOf(
        setOf("mo youtube", "mo you tube", "mo yt") to RoutedCommand(
            CarfuIntent.OPEN_YOUTUBE, "mở youtube", "open",
        ),
        setOf("mo ban do", "mo maps", "mo google maps", "mo google map") to RoutedCommand(
            CarfuIntent.OPEN_MAPS, "mở bản đồ", "open",
        ),
        setOf("mo musicloop", "mo music loop", "mo nhac loop") to RoutedCommand(
            CarfuIntent.OPEN_MUSICLOOP, "mở musicloop", "open",
        ),
        setOf(
            "chi duong den san bay",
            "chi duong toi san bay",
            "dan duong den san bay",
        ) to RoutedCommand(
            CarfuIntent.NAVIGATE_AIRPORT, "chỉ đường đến sân bay", "navigation",
            place = "sân bay",
        ),
        setOf(
            "chi duong ve nha",
            "chi duong den nha",
            "dan duong ve nha",
        ) to RoutedCommand(
            CarfuIntent.NAVIGATE_HOME, "chỉ đường về nhà", "navigation",
            place = "nhà",
        ),
        setOf("bai tiep theo", "bai tiep", "qua bai") to RoutedCommand(
            CarfuIntent.MEDIA_NEXT, "bài tiếp theo", "media",
        ),
        setOf("bai truoc", "bai truoc do") to RoutedCommand(
            CarfuIntent.MEDIA_PREVIOUS, "bài trước", "media",
        ),
        setOf("tam dung nhac", "dung nhac", "pause nhac") to RoutedCommand(
            CarfuIntent.MEDIA_PAUSE, "tạm dừng nhạc", "media",
        ),
        setOf("phat nhac", "bat nhac", "mo nhac") to RoutedCommand(
            CarfuIntent.MEDIA_PLAY, "phát nhạc", "media",
        ),
        setOf("tang am luong", "to tieng", "to tieng len") to RoutedCommand(
            CarfuIntent.VOLUME_UP, "tăng âm lượng", "volume",
        ),
        setOf("giam am luong", "nho tieng", "nho tieng xuong") to RoutedCommand(
            CarfuIntent.VOLUME_DOWN, "giảm âm lượng", "volume",
        ),
        setOf("may gio roi", "may gio", "bay gio la may gio", "xem gio") to RoutedCommand(
            CarfuIntent.CURRENT_TIME, "mấy giờ rồi", "current_time",
        ),
        setOf("mo dien thoai", "mo dt", "mo phone") to RoutedCommand(
            CarfuIntent.OPEN_PHONE, "mở điện thoại", "telephone",
        ),
        setOf("tat nghe nen", "tat nghe", "dung nghe nen") to RoutedCommand(
            CarfuIntent.LISTENING_STOP, "tắt nghe nền", "listening",
        ),
        setOf("bat nghe nen", "bat nghe", "mo nghe nen") to RoutedCommand(
            CarfuIntent.LISTENING_START, "bật nghe nền", "listening",
        ),
        setOf("huy hen gio", "tat hen gio", "huy timer") to RoutedCommand(
            CarfuIntent.TIMER_CANCEL, "hủy hẹn giờ", "timer",
        ),
        setOf("con bao lau", "kiem tra hen gio", "hen gio con bao lau") to RoutedCommand(
            CarfuIntent.TIMER_QUERY, "còn bao lâu", "timer",
        ),
        setOf("huy nhac nho", "tat nhac nho") to RoutedCommand(
            CarfuIntent.REMINDER_CANCEL, "hủy nhắc nhở", "notify",
        ),
        setOf("bat den pin", "mo den pin") to RoutedCommand(
            CarfuIntent.FLASHLIGHT_ON, "bật đèn pin", "flashlight",
        ),
        setOf("tat den pin") to RoutedCommand(
            CarfuIntent.FLASHLIGHT_OFF, "tắt đèn pin", "flashlight",
        ),
    )

    fun match(raw: String): RoutedCommand? {
        if (VietnameseTranscript.isTooWeakToSubmit(raw)) {
            return null
        }
        val folded = VietnameseTranscript.foldForMatch(raw)
        if (folded.isEmpty()) return null
        for ((phrases, route) in EXACT) {
            if (folded in phrases) return route
        }
        matchParameterized(raw, folded)?.let { return it }
        return null
    }

    private fun matchParameterized(raw: String, folded: String): RoutedCommand? {
        matchTelephone(folded)?.let { return it }
        matchNavigation(raw, folded)?.let { return it }
        matchWeather(raw, folded)?.let { return it }
        matchTimer(folded)?.let { return it }
        matchReminder(raw, folded)?.let { return it }
        matchCalculator(folded)?.let { return it }
        matchSearch(raw, folded)?.let { return it }
        return null
    }

    private fun matchTelephone(folded: String): RoutedCommand? {
        val numberMatch = PHONE_NUMBER.find(folded.replace(" ", ""))
            ?: PHONE_NUMBER.find(folded)
        if (folded.startsWith("goi") && numberMatch != null) {
            val digits = numberMatch.value.replace(" ", "")
            return RoutedCommand(
                intent = CarfuIntent.CALL_NUMBER,
                canonicalVi = "gọi số $digits",
                skillId = "telephone",
                phoneNumber = digits,
            )
        }
        if (folded.startsWith("goi cho ") || folded.startsWith("goi ")) {
            val name = CALL_CONTACT_PREFIX.replace(folded, "").trim()
            if (name.isEmpty() || name == "dien thoai") return null
            if (PHONE_NUMBER.containsMatchIn(name) || PHONE_NUMBER.containsMatchIn(name.replace(" ", ""))) {
                return null
            }
            return RoutedCommand(
                intent = CarfuIntent.CALL_CONTACT,
                canonicalVi = "gọi cho $name",
                skillId = "telephone",
                contactName = name,
            )
        }
        return null
    }

    private fun matchNavigation(raw: String, folded: String): RoutedCommand? {
        val isNav = folded.startsWith("chi duong") || folded.startsWith("dan duong")
        if (!isNav) return null
        val destFolded = NAV_PREFIX.replace(folded, "").trim()
        if (destFolded.isEmpty()) return null
        val destRaw = raw.trim()
        return RoutedCommand(
            intent = CarfuIntent.NAVIGATE_PLACE,
            canonicalVi = "chỉ đường đến $destFolded",
            skillId = "navigation",
            place = destRaw,
        )
    }

    private fun matchWeather(raw: String, folded: String): RoutedCommand? {
        val isTomorrow = folded.contains("ngay mai")
        val isRainAsk = folded.contains("co mua")
        val isWeather = folded.startsWith("thoi tiet") || isRainAsk
        if (!isWeather) return null
        val whenValue = if (isTomorrow) WeatherWhen.TOMORROW else WeatherWhen.TODAY
        var city: String? = null
        val oIndex = folded.indexOf(" o ")
        val taiIndex = folded.indexOf(" tai ")
        val splitAt = when {
            oIndex >= 0 -> oIndex + 3
            taiIndex >= 0 -> taiIndex + 5
            else -> -1
        }
        if (splitAt > 0) {
            city = folded.substring(splitAt)
                .replace("ngay mai", "")
                .replace("hom nay", "")
                .replace("co mua khong", "")
                .replace("co mua", "")
                .trim()
                .ifBlank { null }
        }
        return RoutedCommand(
            intent = CarfuIntent.WEATHER,
            canonicalVi = if (whenValue == WeatherWhen.TOMORROW) {
                "thời tiết ngày mai"
            } else {
                "thời tiết hôm nay"
            },
            skillId = "weather",
            city = city,
            weatherWhen = whenValue,
            rainAsk = isRainAsk,
        )
    }

    private fun matchTimer(folded: String): RoutedCommand? {
        if (!folded.startsWith("hen gio")) return null
        val durationMs = VietnameseNumbers.parseDurationMs(folded) ?: return null
        return RoutedCommand(
            intent = CarfuIntent.TIMER_SET,
            canonicalVi = "hẹn giờ ${VietnameseNumbers.formatDurationVi(durationMs)}",
            skillId = "timer",
            durationMs = durationMs,
        )
    }

    private fun matchReminder(raw: String, folded: String): RoutedCommand? {
        if (!folded.startsWith("nhac toi") && !folded.startsWith("nhac nho")) return null
        val durationMs = VietnameseNumbers.parseDurationMs(folded) ?: return null
        val message = raw.trim()
        return RoutedCommand(
            intent = CarfuIntent.REMINDER_SET,
            canonicalVi = "nhắc nhở ${VietnameseNumbers.formatDurationVi(durationMs)}",
            skillId = "notify",
            durationMs = durationMs,
            reminderMessage = message,
        )
    }

    private fun matchCalculator(folded: String): RoutedCommand? {
        val arithmetic = VietnameseNumbers.parseArithmetic(folded) ?: return null
        if (!folded.startsWith("tinh") &&
            !folded.contains(" cong ") &&
            !folded.contains(" tru ") &&
            !folded.contains(" nhan ") &&
            !folded.contains(" chia ")
        ) {
            return null
        }
        return RoutedCommand(
            intent = CarfuIntent.CALCULATE,
            canonicalVi = "tính ${VietnameseNumbers.formatNumberVi(arithmetic.left)}",
            skillId = "calculator",
            calcLeft = arithmetic.left,
            calcOp = arithmetic.op,
            calcRight = arithmetic.right,
        )
    }

    private fun matchSearch(raw: String, folded: String): RoutedCommand? {
        if (folded.startsWith("chi duong") || folded.startsWith("dan duong")) return null
        if (!folded.startsWith("tim kiem") && !folded.startsWith("tim ")) return null
        val queryFolded = SEARCH_PREFIX.replace(folded, "").trim()
        if (queryFolded.isEmpty()) return null
        val query = raw.trim()
        return RoutedCommand(
            intent = CarfuIntent.SEARCH,
            canonicalVi = "tìm kiếm $queryFolded",
            skillId = "search",
            searchQuery = query,
        )
    }
}
