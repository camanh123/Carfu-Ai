package org.stypox.dicio.io.session

/**
 * Launchable app metadata for generic OPEN_APP resolution.
 * Queried on demand and cached briefly — not on every STT partial.
 */
data class LaunchableApp(
    val packageName: String,
    val label: String,
)

sealed class AppResolveResult {
    data class Match(
        val packageName: String,
        val displayName: String,
        val score: Float,
        val via: String,
    ) : AppResolveResult()

    data object NotFound : AppResolveResult()
}

/**
 * Resolves [CanonicalCommand.OpenApp].appName to an installed launchable package.
 *
 * Prefer no-match over a weak wrong-app launch.
 */
class InstalledAppResolver(
    private val listLaunchable: () -> List<LaunchableApp>,
    private val isLaunchable: (String) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    private var cachedApps: List<LaunchableApp>? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    fun invalidateCache() {
        cachedApps = null
        cachedAtMs = 0L
    }

    fun resolve(appName: String): AppResolveResult {
        val raw = appName.trim()
        if (raw.isEmpty()) return AppResolveResult.NotFound
        val folded = VietnameseTranscript.foldForMatch(raw)

        // 1) Known alias → preferred packages (exact / strong).
        KnownAppCatalog.aliasPackages(folded)?.let { pref ->
            val pkg = pref.packages.firstOrNull { isLaunchable(it) }
            if (pkg != null) {
                return AppResolveResult.Match(
                    packageName = pkg,
                    displayName = pref.displayName,
                    score = 1.0f,
                    via = "alias",
                )
            }
        }

        val apps = cachedLaunchables()
        if (apps.isEmpty()) {
            // Alias preferred packages may still be launchable even if label scan empty.
            KnownAppCatalog.aliasPackages(folded)?.packages?.firstOrNull { isLaunchable(it) }?.let {
                return AppResolveResult.Match(
                    packageName = it,
                    displayName = KnownAppCatalog.aliasPackages(folded)!!.displayName,
                    score = 1.0f,
                    via = "alias_pkg",
                )
            }
            return AppResolveResult.NotFound
        }

        // 2) Exact normalized label match.
        val exact = apps.firstOrNull {
            VietnameseTranscript.foldForMatch(it.label) == folded
        }
        if (exact != null) {
            return AppResolveResult.Match(
                packageName = exact.packageName,
                displayName = exact.label,
                score = 1.0f,
                via = "exact_label",
            )
        }

        // 3) Strong token / conservative fuzzy against labels (and aliases as soft boost).
        var best: LaunchableApp? = null
        var bestScore = 0f
        for (app in apps) {
            val labelFolded = VietnameseTranscript.foldForMatch(app.label)
            var score = CommandTranscriptNormalizer.tokenSimilarity(folded, labelFolded)
            // Alias soft match against label synonyms
            KnownAppCatalog.aliasesForPackage(app.packageName).forEach { alias ->
                score = maxOf(score, CommandTranscriptNormalizer.tokenSimilarity(folded, alias))
            }
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        // Conservative threshold — prefer no-match over wrong app.
        if (best != null && bestScore >= STRONG_MATCH_MIN) {
            val display = KnownAppCatalog.displayNameForPackage(best.packageName) ?: best.label
            return AppResolveResult.Match(
                packageName = best.packageName,
                displayName = display,
                score = bestScore,
                via = "fuzzy_label",
            )
        }
        return AppResolveResult.NotFound
    }

    private fun cachedLaunchables(): List<LaunchableApp> {
        val now = nowMs()
        val hit = cachedApps
        if (hit != null && now - cachedAtMs < CACHE_TTL_MS) return hit
        val fresh = listLaunchable()
        cachedApps = fresh
        cachedAtMs = now
        return fresh
    }

    companion object {
        const val CACHE_TTL_MS = 60_000L
        /** Below this, refuse to launch (weak fuzzy safety). */
        const val STRONG_MATCH_MIN = 0.86f
    }
}

/**
 * Alias / preference catalog — maps names, not full spoken sentences.
 * Used as preference + known packages; generic label scan covers other apps.
 */
object KnownAppCatalog {
    data class Pref(val displayName: String, val packages: List<String>)

    private val BY_ALIAS: Map<String, Pref> = mapOf(
        "youtube" to Pref("YouTube", listOf(
            "com.google.android.youtube",
            "com.vanced.android.youtube",
            "app.revanced.android.youtube",
        )),
        "you tube" to Pref("YouTube", listOf("com.google.android.youtube")),
        "yt" to Pref("YouTube", listOf("com.google.android.youtube")),
        "musicloop" to Pref("MusicLoop", listOf(
            "com.musicloop.car", "com.musicloop", "com.syu.music",
        )),
        "music loop" to Pref("MusicLoop", listOf("com.musicloop.car", "com.musicloop")),
        "music look" to Pref("MusicLoop", listOf("com.musicloop.car", "com.musicloop")),
        "music lup" to Pref("MusicLoop", listOf("com.musicloop.car", "com.musicloop")),
        "smarttube" to Pref("SmartTube", listOf(
            "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttube.tv",
            "com.liskovsoft.smartyoutubetv2",
        )),
        "smart tube" to Pref("SmartTube", listOf("com.teamsmart.videomanager.tv")),
        "zalo" to Pref("Zalo", listOf("com.zing.zalo")),
        "chrome" to Pref("Chrome", listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
        )),
        "google chrome" to Pref("Chrome", listOf("com.android.chrome")),
        "maps" to Pref("Maps", listOf("com.google.android.apps.maps")),
        "google maps" to Pref("Maps", listOf("com.google.android.apps.maps")),
        "ban do" to Pref("Maps", listOf("com.google.android.apps.maps")),
        "phone" to Pref("Phone", listOf("com.android.dialer", "com.google.android.dialer")),
        "dien thoai" to Pref("Phone", listOf("com.android.dialer")),
    )

    fun aliasPackages(foldedName: String): Pref? = BY_ALIAS[foldedName.trim()]

    fun aliasesForPackage(packageName: String): List<String> =
        BY_ALIAS.filterValues { pref -> packageName in pref.packages }.keys.toList()

    fun displayNameForPackage(packageName: String): String? =
        BY_ALIAS.values.firstOrNull { packageName in it.packages }?.displayName
}
