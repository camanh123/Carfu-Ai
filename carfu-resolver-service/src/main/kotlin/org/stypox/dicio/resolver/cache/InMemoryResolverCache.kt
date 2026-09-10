package org.stypox.dicio.resolver.cache

class InMemoryResolverCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ResolverCache {
    private val entries = object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    override fun get(key: CacheKey, nowMillis: Long): CacheEntry? {
        val entry = entries[key] ?: return null
        if (!entry.isFresh(nowMillis)) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    override fun put(key: CacheKey, entry: CacheEntry) {
        entries[key] = entry
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2048
        const val POSITIVE_TTL_MILLIS = 21L * 24 * 60 * 60 * 1000
        const val NEGATIVE_TTL_MILLIS = 60L * 60 * 1000
    }
}
