package org.stypox.dicio.playauto.provider

import org.stypox.dicio.playauto.adapter.MediaAdapter

/**
 * Insertion-ordered adapter registry. Future providers register here;
 * [org.stypox.dicio.playauto.core.PlayAutoEngine] does not switch on provider ids.
 */
class ProviderRegistry {
    private val adapters = LinkedHashMap<MediaProvider, MediaAdapter>()
    var defaultProvider: MediaProvider? = null

    fun register(adapter: MediaAdapter) {
        adapters[adapter.provider] = adapter
    }

    fun lookup(provider: MediaProvider): MediaAdapter? = adapters[provider]

    fun isRegistered(provider: MediaProvider): Boolean = adapters.containsKey(provider)

    fun adaptersInOrder(): List<MediaAdapter> = adapters.values.toList()

    fun isEmpty(): Boolean = adapters.isEmpty()

    fun size(): Int = adapters.size
}
