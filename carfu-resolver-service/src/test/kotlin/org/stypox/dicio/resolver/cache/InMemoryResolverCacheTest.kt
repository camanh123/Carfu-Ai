package org.stypox.dicio.resolver.cache

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.query.QueryNormalizer

class InMemoryResolverCacheTest : StringSpec({
    "expired negative entries are treated as a miss" {
        val cache = InMemoryResolverCache()
        val key = CacheKey.of(QueryNormalizer.normalize("abc"), "vi", "VN")
        cache.put(
            key,
            CacheEntry(
                status = ResolveStatus.NO_RESULTS,
                storedAtMillis = 1_000,
                ttlMillis = InMemoryResolverCache.NEGATIVE_TTL_MILLIS,
            ),
        )
        cache.get(key, nowMillis = 1_000 + InMemoryResolverCache.NEGATIVE_TTL_MILLIS - 1)
            .shouldNotBeNull()
        cache.get(key, nowMillis = 1_000 + InMemoryResolverCache.NEGATIVE_TTL_MILLIS + 1)
            .shouldBeNull()
    }
})
