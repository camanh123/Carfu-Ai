package org.stypox.dicio.asrbenchmark.corpus

/**
 * Spoken test targets ONLY.
 * Never used as expected transcripts, never used to repair Whisper output.
 */
object SpokenTestTargets {
    val items: List<String> = listOf(
        "1. Mở bài Đừng Xa Em Đêm Nay",
        "2. Phát Nơi Này Có Anh",
        "3. Mở Vì Đó Là Em Quang Dũng",
        "4. Mở Cơn Mưa Tháng Năm",
        "5. Mở See You Again",
        "6. Mở See You Again trên SmartTube",
        "7. Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        "8. Phát Nơi Này Có Anh trên SmartTube",
        "9. Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        "10. Chỉ đường đến Mỹ Đình bằng Google Maps",
        "11. SmartTube",
        "12. YouTube",
        "13. MusicLoop",
        "14. Tôi đang dùng smartphone",
        "15. Mở YouTube trên smartphone",
    )

    fun asPlainText(): String = buildString {
        appendLine("SPOKEN TEST TARGETS (not used for correction / not expected transcripts)")
        items.forEach { appendLine(it) }
    }
}
