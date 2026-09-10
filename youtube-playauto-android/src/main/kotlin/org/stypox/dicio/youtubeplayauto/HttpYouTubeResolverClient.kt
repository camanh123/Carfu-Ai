package org.stypox.dicio.youtubeplayauto

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException

fun interface ResolverHttpTransport {
    fun get(uri: URI): ResolverHttpResponse
}

data class ResolverHttpResponse(
    val code: Int,
    val body: String,
)

/**
 * HTTPS-capable HttpURLConnection client for the frozen resolver REST contract.
 * Android never sends a Google API key and never calls Google APIs.
 */
class HttpYouTubeResolverClient(
    private val baseUrlProvider: () -> String,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 8_000,
    private val transport: ResolverHttpTransport = ResolverHttpTransport { uri ->
        defaultGet(uri, connectTimeoutMs, readTimeoutMs)
    },
) : YouTubeResolverClient {

    override suspend fun resolve(
        query: String,
        lang: String,
        region: String,
    ): YouTubeResolveResult {
        val started = System.nanoTime()
        fun meta(
            attempted: Boolean,
            configured: Boolean,
            https: Boolean,
            cleartext: Boolean,
            httpStatus: Int? = null,
            note: String? = null,
        ) = YouTubeResolverMeta(
            httpStatus = httpStatus,
            latencyMs = (System.nanoTime() - started) / 1_000_000,
            requestAttempted = attempted,
            baseUrlConfigured = configured,
            usedHttps = https,
            usedCleartextHttp = cleartext,
            httpsUnavailableNote = note,
        )

        val raw = baseUrlProvider()
        val configured = YouTubeResolverEndpoint.isConfigured(raw)
        if (!configured) {
            return YouTubeResolveResult.ResolverUnavailable(
                meta(
                    attempted = false,
                    configured = false,
                    https = false,
                    cleartext = false,
                    note = "CARFU_RESOLVER_BASE_URL is empty or not http(s)",
                ),
            )
        }
        val https = YouTubeResolverEndpoint.isHttps(raw)
        val cleartext = YouTubeResolverEndpoint.isCleartextHttp(raw)
        val httpsNote = if (cleartext) {
            "HTTPS unavailable for this configured endpoint; cleartext HTTP is harness-only and is not a production TLS weakening"
        } else {
            null
        }
        val uri = try {
            YouTubeResolverEndpoint.resolveUri(raw, query, lang, region)
        } catch (_: Exception) {
            return YouTubeResolveResult.InvalidResponse(
                meta(false, true, https, cleartext, note = httpsNote),
            )
        }
        if (uri.host.isNullOrBlank() || uri.path != YouTubeResolverEndpoint.RESOLVE_PATH) {
            return YouTubeResolveResult.InvalidResponse(
                meta(false, true, https, cleartext, note = httpsNote),
            )
        }
        val rawQuery = uri.rawQuery.orEmpty()
        if (rawQuery.split('&').any { it.startsWith("key=") || it.startsWith("key%3D") }) {
            return YouTubeResolveResult.InvalidResponse(
                meta(false, true, https, cleartext, note = "refusing to send a URI that looks like it contains an API key"),
            )
        }
        return try {
            val response = transport.get(uri)
            mapHttp(response, meta(true, true, https, cleartext, response.code, httpsNote))
        } catch (error: SocketTimeoutException) {
            YouTubeResolveResult.Timeout(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: UnknownHostException) {
            YouTubeResolveResult.NetworkUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: ConnectException) {
            YouTubeResolveResult.NetworkUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: NoRouteToHostException) {
            YouTubeResolveResult.NetworkUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: SSLException) {
            YouTubeResolveResult.ResolverUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: SocketException) {
            YouTubeResolveResult.NetworkUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: IOException) {
            YouTubeResolveResult.NetworkUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        } catch (error: Exception) {
            YouTubeResolveResult.ResolverUnavailable(meta(true, true, https, cleartext, note = httpsNote))
        }
    }

    private fun mapHttp(
        response: ResolverHttpResponse,
        meta: YouTubeResolverMeta,
    ): YouTubeResolveResult {
        val code = response.code
        if (code == 408 || code == 504) {
            return YouTubeResolveResult.Timeout(meta.copy(httpStatus = code))
        }
        if (code in 500..599) {
            return YouTubeResolveResult.ResolverUnavailable(meta.copy(httpStatus = code))
        }
        if (code !in 200..299) {
            val parsed = YouTubeResolverJson.parse(code, response.body, meta)
            return if (parsed is YouTubeResolveResult.InvalidResponse && code == 403) {
                val reason = YouTubeResolverJson.parseObject(response.body)?.get("error")
                if (reason == "quotaExceeded" || reason == "dailyLimitExceeded") {
                    YouTubeResolveResult.QuotaExceeded(meta.copy(httpStatus = code))
                } else {
                    YouTubeResolveResult.ResolverUnavailable(meta.copy(httpStatus = code))
                }
            } else if (parsed.statusName() != "INVALID_RESPONSE") {
                parsed
            } else {
                YouTubeResolveResult.ResolverUnavailable(meta.copy(httpStatus = code))
            }
        }
        return YouTubeResolverJson.parse(code, response.body, meta)
    }

    companion object {
        internal fun defaultGet(
            uri: URI,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): ResolverHttpResponse {
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Charset", "utf-8")
                setRequestProperty("User-Agent", "carfu-youtube-playauto-harness/4.9.2")
            }
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                ResolverHttpResponse(code, body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
