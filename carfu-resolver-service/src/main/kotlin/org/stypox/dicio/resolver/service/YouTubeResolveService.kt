package org.stypox.dicio.resolver.service

import org.stypox.dicio.resolver.api.ResolveResponse
import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.cache.CacheEntry
import org.stypox.dicio.resolver.cache.CacheKey
import org.stypox.dicio.resolver.cache.EpochClock
import org.stypox.dicio.resolver.cache.InMemoryResolverCache
import org.stypox.dicio.resolver.cache.ResolverCache
import org.stypox.dicio.resolver.query.QueryNormalizer
import org.stypox.dicio.resolver.ranking.YouTubeResultRanker
import org.stypox.dicio.resolver.youtube.ResolveStatusDetail
import org.stypox.dicio.resolver.youtube.WatchUrl
import org.stypox.dicio.resolver.youtube.YouTubeSearchOutcome
import org.stypox.dicio.resolver.youtube.YouTubeSearchProvider
import org.stypox.dicio.resolver.youtube.YouTubeSearchRequest

class YouTubeResolveService(
    private val provider: YouTubeSearchProvider,
    private val cache: ResolverCache = InMemoryResolverCache(),
    private val clock: EpochClock = EpochClock { System.currentTimeMillis() },
    private val apiKeyConfigured: Boolean = true,
) {
    fun resolve(rawQuery: String, lang: String = "", region: String = ""): ResolveResponse {
        val query = QueryNormalizer.normalize(rawQuery)
        if (query.original.isEmpty() || query.coreTokens.isEmpty()) {
            return ResolveResponse(
                status = ResolveStatus.INVALID_RESPONSE,
                query = query.original.ifEmpty { rawQuery },
                error = "EMPTY_QUERY",
            )
        }
        val cacheKey = CacheKey.of(query, lang, region)
        val now = clock.nowMillis()
        val cached = cache.get(cacheKey, now)
        if (cached != null) {
            return cached.toResponse(query.original, ResolveResponse.CACHE_HIT)
        }
        if (!apiKeyConfigured) {
            return ResolveResponse(
                status = ResolveStatus.RESOLVER_UNAVAILABLE,
                query = query.original,
                cache = ResolveResponse.CACHE_MISS,
                error = ResolveResponse.ERROR_MISSING_API_KEY,
            )
        }
        val outcome = provider.search(
            YouTubeSearchRequest(
                query = query.searchQuery,
                lang = lang,
                region = region,
            ),
        )
        val miss = when (outcome) {
            is YouTubeSearchOutcome.Failure -> {
                val error = if (outcome.detail == ResolveStatusDetail.MISSING_API_KEY) {
                    ResolveResponse.ERROR_MISSING_API_KEY
                } else {
                    null
                }
                ResolveResponse(
                    status = outcome.status,
                    query = query.original,
                    cache = ResolveResponse.CACHE_MISS,
                    error = error,
                )
            }
            is YouTubeSearchOutcome.Success -> {
                if (outcome.candidates.isEmpty()) {
                    cache.put(cacheKey, noResultsEntry(now))
                    ResolveResponse(
                        status = ResolveStatus.NO_RESULTS,
                        query = query.original,
                        cache = ResolveResponse.CACHE_MISS,
                    )
                } else {
                    val pick = YouTubeResultRanker.pick(query, outcome.candidates)
                    if (pick == null) {
                        cache.put(cacheKey, noResultsEntry(now))
                        ResolveResponse(
                            status = ResolveStatus.NO_RESULTS,
                            query = query.original,
                            cache = ResolveResponse.CACHE_MISS,
                        )
                    } else {
                        val videoId = pick.candidate.videoId
                        if (!WatchUrl.isValidVideoId(videoId)) {
                            ResolveResponse(
                                status = ResolveStatus.INVALID_RESPONSE,
                                query = query.original,
                                cache = ResolveResponse.CACHE_MISS,
                            )
                        } else {
                            val watchUrl = WatchUrl.fromVideoId(videoId)
                            cache.put(
                                cacheKey,
                                CacheEntry(
                                    status = ResolveStatus.RESOLVED,
                                    videoId = videoId,
                                    title = pick.candidate.title,
                                    channelTitle = pick.candidate.channelTitle,
                                    watchUrl = watchUrl,
                                    storedAtMillis = now,
                                    ttlMillis = InMemoryResolverCache.POSITIVE_TTL_MILLIS,
                                ),
                            )
                            ResolveResponse(
                                status = ResolveStatus.RESOLVED,
                                query = query.original,
                                videoId = videoId,
                                title = pick.candidate.title,
                                channelTitle = pick.candidate.channelTitle,
                                watchUrl = watchUrl,
                                source = ResolveResponse.SOURCE_YOUTUBE_DATA_API_V3,
                                cache = ResolveResponse.CACHE_MISS,
                            )
                        }
                    }
                }
            }
        }
        return miss
    }

    private fun noResultsEntry(now: Long): CacheEntry =
        CacheEntry(
            status = ResolveStatus.NO_RESULTS,
            storedAtMillis = now,
            ttlMillis = InMemoryResolverCache.NEGATIVE_TTL_MILLIS,
        )

    private fun CacheEntry.toResponse(originalQuery: String, cacheFlag: String): ResolveResponse =
        ResolveResponse(
            status = status,
            query = originalQuery,
            videoId = videoId,
            title = title,
            channelTitle = channelTitle,
            watchUrl = watchUrl,
            source = if (status == ResolveStatus.RESOLVED) {
                ResolveResponse.SOURCE_YOUTUBE_DATA_API_V3
            } else {
                null
            },
            cache = cacheFlag,
        )
}
