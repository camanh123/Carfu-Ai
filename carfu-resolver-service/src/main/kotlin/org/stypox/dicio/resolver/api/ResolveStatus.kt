package org.stypox.dicio.resolver.api

enum class ResolveStatus {
    RESOLVED,
    NO_RESULTS,
    QUOTA_EXCEEDED,
    RESOLVER_UNAVAILABLE,
    INVALID_RESPONSE,
    TIMEOUT,
}
