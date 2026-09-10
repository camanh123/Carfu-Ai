package org.stypox.dicio.youtubeplayauto

/**
 * Structured resolver outcomes for the Android jack.
 * PlayAuto never sees raw HTTP or provider exceptions.
 */
sealed class YouTubeResolveResult {
    data class Resolved(
        val videoId: String,
        val title: String?,
        val channelTitle: String?,
        val watchUrl: String,
        val cache: String?,
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class NoResults(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class NetworkUnavailable(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class ResolverUnavailable(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class QuotaExceeded(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class InvalidResponse(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    data class Timeout(
        val meta: YouTubeResolverMeta = YouTubeResolverMeta(),
    ) : YouTubeResolveResult()

    fun statusName(): String = when (this) {
        is Resolved -> "RESOLVED"
        is NoResults -> "NO_RESULTS"
        is NetworkUnavailable -> "NETWORK_UNAVAILABLE"
        is ResolverUnavailable -> "RESOLVER_UNAVAILABLE"
        is QuotaExceeded -> "QUOTA_EXCEEDED"
        is InvalidResponse -> "INVALID_RESPONSE"
        is Timeout -> "TIMEOUT"
    }

    fun meta(): YouTubeResolverMeta = when (this) {
        is Resolved -> meta
        is NoResults -> meta
        is NetworkUnavailable -> meta
        is ResolverUnavailable -> meta
        is QuotaExceeded -> meta
        is InvalidResponse -> meta
        is Timeout -> meta
    }
}

data class YouTubeResolverMeta(
    val httpStatus: Int? = null,
    val latencyMs: Long = 0,
    val requestAttempted: Boolean = false,
    val baseUrlConfigured: Boolean = false,
    val usedHttps: Boolean = false,
    val usedCleartextHttp: Boolean = false,
    val httpsUnavailableNote: String? = null,
)
