package org.stypox.dicio.resolver.youtube

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

data class HttpGetResult(
    val code: Int,
    val body: String,
)

fun interface HttpGetClient {
    fun get(uri: URI): HttpGetResult
}

class JdkHttpGetClient(
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 8_000,
) : HttpGetClient {
    override fun get(uri: URI): HttpGetResult {
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "carfu-resolver-service/1.0")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpGetResult(code, body)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun mapTransportFailure(error: Exception): YouTubeSearchOutcome.Failure {
    return when (error) {
        is SocketTimeoutException -> YouTubeSearchOutcome.Failure(
            org.stypox.dicio.resolver.api.ResolveStatus.TIMEOUT,
            "timeout",
        )
        is IOException -> YouTubeSearchOutcome.Failure(
            org.stypox.dicio.resolver.api.ResolveStatus.RESOLVER_UNAVAILABLE,
            "network_failure",
        )
        else -> YouTubeSearchOutcome.Failure(
            org.stypox.dicio.resolver.api.ResolveStatus.RESOLVER_UNAVAILABLE,
            "provider_failure",
        )
    }
}
