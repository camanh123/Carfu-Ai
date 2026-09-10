package org.stypox.dicio.youtubeplayauto

/**
 * Android-side jack onto the frozen CARFU resolver REST contract.
 * No YouTube Data API key. No Google API calls from Android.
 */
interface YouTubeResolverClient {
    suspend fun resolve(
        query: String,
        lang: String = "vi",
        region: String = "VN",
    ): YouTubeResolveResult
}
