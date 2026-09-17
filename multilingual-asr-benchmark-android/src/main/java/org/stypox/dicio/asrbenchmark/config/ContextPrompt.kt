package org.stypox.dicio.asrbenchmark.config

/**
 * Short vocabulary/context hint only. Never include expected full test sentences.
 */
object ContextPrompt {
    const val VOCABULARY_HINT: String = "SmartTube YouTube MusicLoop Google Maps Vietmap"

    fun display(enabled: Boolean): String = if (enabled) "ON" else "OFF"

    fun forbiddenCorpusSentences(): List<String> = listOf(
        "Đừng Xa Em Đêm Nay",
        "Nơi Này Có Anh",
        "Vì Đó Là Em",
        "Cơn Mưa Tháng Năm",
        "See You Again",
        "Tôi đang dùng smartphone",
        "Chỉ đường đến Mỹ Đình",
    )
}
