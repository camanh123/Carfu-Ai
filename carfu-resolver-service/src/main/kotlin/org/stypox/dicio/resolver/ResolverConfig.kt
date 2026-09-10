package org.stypox.dicio.resolver

data class ResolverConfig(
    val apiKey: String,
    val port: Int,
    val fetchStats: Boolean,
) {
    val apiKeyConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 8787

        fun fromEnv(env: Map<String, String> = System.getenv()): ResolverConfig {
            val port = env["PORT"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return ResolverConfig(
                apiKey = env["YOUTUBE_API_KEY"].orEmpty().trim(),
                port = port,
                fetchStats = env["YOUTUBE_FETCH_STATS"]?.equals("true", ignoreCase = true) == true,
            )
        }
    }
}
