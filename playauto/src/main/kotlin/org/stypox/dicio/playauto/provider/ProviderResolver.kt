package org.stypox.dicio.playauto.provider

import org.stypox.dicio.playauto.adapter.MediaAdapter
import org.stypox.dicio.playauto.core.MediaRequest

/**
 * Builds a deterministic candidate list. Does not execute adapters.
 *
 * Order:
 * 1. preferred provider, if registered
 * 2. else configured default, if registered
 * 3. remaining registered adapters in registration order
 */
class ProviderResolver(
    private val registry: ProviderRegistry,
) {
    fun candidates(request: MediaRequest): List<MediaAdapter> {
        if (registry.isEmpty()) return emptyList()
        val ordered = LinkedHashSet<MediaAdapter>()
        val preferred = request.preferredProvider?.let { registry.lookup(it) }
        if (preferred != null) {
            ordered.add(preferred)
        } else {
            registry.defaultProvider?.let { id ->
                registry.lookup(id)?.let { ordered.add(it) }
            }
        }
        ordered.addAll(registry.adaptersInOrder())
        return ordered.toList()
    }
}
