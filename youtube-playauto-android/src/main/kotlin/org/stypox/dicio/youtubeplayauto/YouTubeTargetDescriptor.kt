package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.core.MediaTarget
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * Opaque [MediaTarget.descriptor] codec. PlayAuto Core does not interpret this.
 */
object YouTubeTargetDescriptor {
    private const val QUERY_PREFIX = "youtube:query:"
    private const val VIDEO_PREFIX = "youtube:video:"

    fun queryOnly(query: String): String = QUERY_PREFIX + query

    fun video(target: ResolvedYouTubeTarget): String =
        VIDEO_PREFIX + target.videoId + "|" + target.canonicalUri

    fun parseVideo(descriptor: String): ResolvedYouTubeTarget? {
        if (!descriptor.startsWith(VIDEO_PREFIX)) return null
        val rest = descriptor.removePrefix(VIDEO_PREFIX)
        val id = rest.substringBefore('|')
        val uri = rest.substringAfter('|', missingDelimiterValue = "")
        if (YouTubeVideoIdParser.parse(id) == null) return null
        return ResolvedYouTubeTarget(
            videoId = id,
            canonicalUri = uri.ifBlank { YouTubeVideoIdParser.canonicalWatchUri(id) },
            source = "descriptor",
        )
    }

    fun mediaTarget(
        query: String,
        mediaType: MediaType,
        resolved: ResolvedYouTubeTarget?,
    ): MediaTarget = MediaTarget(
        query = query,
        mediaType = mediaType,
        provider = MediaProvider.YOUTUBE,
        descriptor = if (resolved != null) video(resolved) else queryOnly(query),
    )
}
