package org.stypox.dicio.io.session

/**
 * Exact/near-exact routing of the CARFU Vietnamese command set.
 *
 * Matching happens only after STT produced a meaningful phrase. Isolated noise fragments
 * such as "thổ" / "hà hồ" / "người" never map to an intent.
 */
enum class CarfuIntent {
    OPEN_YOUTUBE,
    OPEN_MAPS,
    OPEN_MUSICLOOP,
    NAVIGATE_AIRPORT,
    NAVIGATE_HOME,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_PAUSE,
    MEDIA_PLAY,
    VOLUME_UP,
    VOLUME_DOWN,
    CURRENT_TIME,
}

data class RoutedCommand(
    val intent: CarfuIntent,
    val canonicalVi: String,
    val skillId: String,
)

object CarfuCommandRouter {
    private val ROUTES: List<Pair<Set<String>, RoutedCommand>> = listOf(
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
        ),
        setOf(
            "chi duong ve nha",
            "chi duong den nha",
            "dan duong ve nha",
        ) to RoutedCommand(
            CarfuIntent.NAVIGATE_HOME, "chỉ đường về nhà", "navigation",
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
    )

    fun match(raw: String): RoutedCommand? {
        if (VietnameseTranscript.isTooWeakToSubmit(raw)) {
            return null
        }
        val folded = VietnameseTranscript.foldForMatch(raw)
        if (folded.isEmpty()) return null
        for ((phrases, route) in ROUTES) {
            if (folded in phrases) return route
        }
        return null
    }
}
