package org.stypox.dicio.resolver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.stypox.dicio.resolver.api.ResolveResponse
import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.service.YouTubeResolveService
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

class ResolverHttpServer(
    private val config: ResolverConfig,
    private val service: YouTubeResolveService,
) {
    private lateinit var server: HttpServer

    val port: Int
        get() = if (this::server.isInitialized) server.address.port else config.port

    fun start(bindPort: Int = config.port): Int {
        server = HttpServer.create(InetSocketAddress("0.0.0.0", bindPort), 0)
        server.createContext("/health", ::health)
        server.createContext("/v1/diagnostics", ::diagnostics)
        server.createContext("/v1/youtube/resolve", ::resolve)
        server.executor = null
        server.start()
        LOGGER.info("carfu-resolver-service listening on ${server.address.port}")
        return server.address.port
    }

    fun stop() {
        if (this::server.isInitialized) {
            server.stop(0)
        }
    }

    private fun health(exchange: HttpExchange) {
        json(exchange, 200, """{"status":"ok"}""")
    }

    private fun diagnostics(exchange: HttpExchange) {
        val body = ResolveResponse.let {
            """{"service":"carfu-resolver-service","youtubeApiKeyConfigured":${config.apiKeyConfigured},"source":"${ResolveResponse.SOURCE_YOUTUBE_DATA_API_V3}","fetchStats":${config.fetchStats}}"""
        }
        json(exchange, 200, body)
    }

    private fun resolve(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            json(
                exchange,
                405,
                ResolveResponse(status = ResolveStatus.INVALID_RESPONSE, error = "METHOD_NOT_ALLOWED").toJson(),
            )
            return
        }
        val params = queryParams(exchange.requestURI.rawQuery)
        val q = params["q"].orEmpty()
        val lang = params["lang"].orEmpty()
        val region = params["region"].orEmpty()
        val response = service.resolve(q, lang, region)
        json(exchange, 200, response.toJson())
    }

    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.isEmpty() || parts[0].isBlank()) return@mapNotNull null
            val key = urlDecode(parts[0])
            val value = if (parts.size > 1) urlDecode(parts[1]) else ""
            key to value
        }.toMap()
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private val LOGGER = Logger.getLogger(ResolverHttpServer::class.java.name)
    }
}
