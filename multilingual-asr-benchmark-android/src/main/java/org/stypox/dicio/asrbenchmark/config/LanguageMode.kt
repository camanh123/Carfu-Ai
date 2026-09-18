package org.stypox.dicio.asrbenchmark.config

enum class LanguageMode(
    val displayName: String,
    val whisperLanguage: String,
) {
    AUTO("AUTO / multilingual", "auto"),
    VI("VI", "vi");

    companion object {
        fun fromDisplay(label: String): LanguageMode =
            entries.firstOrNull { it.displayName == label || it.name == label } ?: AUTO
    }
}
