package org.stypox.dicio.youtubeplayauto

/**
 * Android boundary for the YouTube adapter. Implementations may use PackageManager;
 * the rest of PlayAuto YouTube logic must not.
 */
interface YouTubeRuntime {
    fun installedPackage(): YouTubePackageInfo?

    fun canResolve(spec: YouTubeLaunchSpec): Boolean

    fun dispatch(spec: YouTubeLaunchSpec, mode: YouTubeLaunchMode): YouTubeDispatchOutcome
}
