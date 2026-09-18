package org.stypox.dicio.sherpabenchmark.corpus

/**
 * Spoken test targets are UI/instructions only.
 *
 * THESE STRINGS MUST NOT BE PASSED TO THE ASR ENGINE AS:
 * prompt, hotwords, grammar, correction vocabulary, context, biasing,
 * or expected transcript.
 */
object SpokenTestTargets {
    val items: List<String> = listOf(
        "1. Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        "2. Mở See You Again trên SmartTube",
        "3. Phát Nơi Này Có Anh trên YouTube",
        "4. Chỉ đường đến Mỹ Đình",
    )

    fun asPlainText(): String = buildString {
        appendLine("TEST TARGETS (UI/instructions only)")
        appendLine("NOT passed to the ASR engine as prompt, hotwords, grammar,")
        appendLine("correction vocabulary, context, biasing, or expected transcript.")
        items.forEach { appendLine(it) }
    }

    fun allPhrases(): List<String> = listOf(
        "Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        "Mở See You Again trên SmartTube",
        "Phát Nơi Này Có Anh trên YouTube",
        "Chỉ đường đến Mỹ Đình",
        "Đừng Xa Em Đêm Nay",
        "See You Again",
        "Nơi Này Có Anh",
        "Mỹ Đình",
        "SmartTube",
        "YouTube",
    )
}
