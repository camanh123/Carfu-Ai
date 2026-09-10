package org.stypox.dicio.resolver.cache

import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.query.NormalizedQuery

data class CacheKey(
    val foldedQuery: String,
    val variants: String,
    val lang: String,
    val region: String,
) {
    companion object {
        fun of(query: NormalizedQuery, lang: String, region: String): CacheKey =
            CacheKey(
                foldedQuery = query.folded,
                variants = query.variants.cacheToken(),
                lang = lang.lowercase(),
                region = region.uppercase(),
            )
    }
}

data class CacheEntry(
    val status: ResolveStatus,
    val videoId: String? = null,
    val title: String? = null,
    val channelTitle: String? = null,
    val watchUrl: String? = null,
    val storedAtMillis: Long,
    val ttlMillis: Long,
) {
    fun isFresh(nowMillis: Long): Boolean = nowMillis - storedAtMillis < ttlMillis
}

fun interface EpochClock {
    fun nowMillis(): Long
}

interface ResolverCache {
    fun get(key: CacheKey, nowMillis: Long = System.currentTimeMillis()): CacheEntry?
    fun put(key: CacheKey, entry: CacheEntry)
}
