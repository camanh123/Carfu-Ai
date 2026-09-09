package org.stypox.dicio.youtubeplayauto

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class AndroidYouTubeRuntime(
    private val context: Context,
) : YouTubeRuntime {
    private val pm: PackageManager = context.packageManager

    override fun installedPackage(): YouTubePackageInfo? {
        for (pkg in YouTubeCandidatePackages.discoveryOrder) {
            val info = packageOrNull(pkg) ?: continue
            val launch = pm.getLaunchIntentForPackage(pkg)
            return YouTubePackageInfo(
                packageName = pkg,
                launchActivity = launch?.component?.flattenToShortString(),
                versionName = info.versionName,
            )
        }
        return null
    }

    override fun canResolve(spec: YouTubeLaunchSpec): Boolean {
        val intent = toIntent(spec) ?: return false
        return resolveActivity(intent) != null
    }

    override fun dispatch(
        spec: YouTubeLaunchSpec,
        mode: YouTubeLaunchMode,
    ): YouTubeDispatchOutcome {
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            return YouTubeDispatchOutcome.DryRun(spec)
        }
        val intent = toIntent(spec)
            ?: return YouTubeDispatchOutcome.Failed("intent_unconstructable")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            YouTubeDispatchOutcome.Dispatched(
                spec = spec,
                provenance = YouTubeProvenance.INTENT_DISPATCHED,
            )
        } catch (e: ActivityNotFoundException) {
            YouTubeDispatchOutcome.Failed("activity_not_found:${e.message}")
        } catch (e: Exception) {
            YouTubeDispatchOutcome.Failed("dispatch_exception:${e.javaClass.simpleName}")
        }
    }

    private fun toIntent(spec: YouTubeLaunchSpec): Intent? {
        val intent = Intent(spec.action)
        spec.uri?.let { intent.data = Uri.parse(it) }
        spec.packageName?.let { intent.setPackage(it) }
        spec.categories.forEach { intent.addCategory(it) }
        spec.extraQuery?.let { intent.putExtra("query", it) }
        if (spec.action == YouTubeLaunchSpec.ACTION_MAIN && spec.packageName != null) {
            val launch = pm.getLaunchIntentForPackage(spec.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return launch
            }
        }
        return intent
    }

    private fun resolveActivity(intent: Intent): android.content.ComponentName? {
        return try {
            intent.resolveActivity(pm)
        } catch (_: Exception) {
            null
        }
    }

    private fun packageOrNull(pkg: String): android.content.pm.PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
