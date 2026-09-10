package org.stypox.dicio.resolver

import org.stypox.dicio.resolver.cache.InMemoryResolverCache
import org.stypox.dicio.resolver.service.YouTubeResolveService
import org.stypox.dicio.resolver.youtube.YouTubeDataApiProvider
import java.util.logging.Logger

fun main() {
    val config = ResolverConfig.fromEnv()
    val provider = YouTubeDataApiProvider(config.apiKey)
    val service = YouTubeResolveService(
        provider = provider,
        cache = InMemoryResolverCache(),
        apiKeyConfigured = config.apiKeyConfigured,
    )
    val server = ResolverHttpServer(config, service)
    val port = server.start()
    LOGGER.info(
        "started port=$port apiKeyConfigured=${config.apiKeyConfigured} fetchStats=${config.fetchStats}",
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

private val LOGGER = Logger.getLogger("carfu-resolver-service")
