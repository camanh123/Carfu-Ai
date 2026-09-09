package org.stypox.dicio.youtubeplayauto

enum class YouTubeLaunchMode {
    /** Resolve / detect / select only. Never start an external app. */
    DRY_RUN,

    /** Explicit device test. Caller must press a harness button. */
    DEVICE_TEST,
}
