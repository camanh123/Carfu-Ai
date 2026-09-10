package org.stypox.dicio.youtubeplayauto

/**
 * Public / official Android YouTube mechanisms (Phase 4.9 audit).
 *
 * SOURCE-PROVEN
 * - Open a known video id via https://www.youtube.com/watch?v=<id>
 *   (public watch URL; this harness already uses it as DEEP_LINK).
 * - Intent.ACTION_VIEW on that URL, optionally setPackage(installed YouTube).
 * - vnd.youtube:<id> is a historically documented YouTube app URI scheme
 *   (YouTube Android Player API YouTubeIntents; that library is deprecated,
 *   the scheme is still queried in this harness manifest).
 * - YouTube Data API v3 search.list can map a title to a video id, but it
 *   requires an API key. Not used (no paid/secret keys without approval).
 * - IFrame Player `autoplay=1` is documented for **web embeds only**, not as
 *   an Android YouTube-app intent extra.
 *
 * NOT AVAILABLE (official, no API key, on this device)
 * - An Android-only API that maps a song title to a video id.
 * - A search intent that **returns** a resolved video id to the caller.
 * - A documented YouTube-app intent extra that guarantees autoplay.
 *
 * DEVICE-PROVEN
 * - Nothing in this phase. Whether the YouTube app starts local playback
 *   after ACTION_VIEW(watch URL), and whether it restores a previous Cast
 *   session, is observed on CARFU — not claimed here.
 *
 * Launch used by this phase: ACTION_VIEW + https://www.youtube.com/watch?v=<id>
 * pinned to the installed YouTube package, exactly once. Playback start is
 * requested by opening that target; PLAYBACK_CONFIRMED is not claimed.
 * If YouTube restores Cast, that is YouTube-side behavior.
 */
object YouTubePublicLaunchAudit {
    const val WATCH_HOST_PATH = "https://www.youtube.com/watch?v="
    const val VND_YOUTUBE_SCHEME = "vnd.youtube"
    const val ACTION_VIEW = YouTubeLaunchSpec.ACTION_VIEW
    const val NO_OFFICIAL_AUTOPLAY_EXTRA =
        "no_public_youtube_app_intent_extra_for_autoplay"
    const val NO_OFFICIAL_SEARCH_RETURNS_VIDEO_ID =
        "youtube_search_intent_does_not_return_a_video_id_to_the_caller"
    const val TITLE_TO_VIDEO_ID_LOCAL = "not_available_without_search_or_data_api_key"
}
