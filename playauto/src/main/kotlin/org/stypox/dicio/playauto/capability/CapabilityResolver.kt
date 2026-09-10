package org.stypox.dicio.playauto.capability

import org.stypox.dicio.playauto.adapter.MediaAdapter
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.PlaybackCapability

class CapabilityResolver {
    fun resolve(adapter: MediaAdapter, request: MediaRequest): Set<PlaybackCapability> {
        if (!adapter.canHandle(request)) return emptySet()
        return adapter.capabilities
    }
}
