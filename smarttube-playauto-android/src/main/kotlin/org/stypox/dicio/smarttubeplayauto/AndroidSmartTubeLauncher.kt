package org.stypox.dicio.smarttubeplayauto

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode

class AndroidSmartTubeLauncher(
    private val context: Context,
) : SmartTubeLauncher {
    private val pm: PackageManager = context.packageManager

    override fun installedPackages(): List<SmartTubeInstalledPackage> {
        val found = linkedMapOf<String, SmartTubeInstalledPackage>()
        for (pkg in SmartTubeCatalog.CATALOG_PACKAGES) {
            val info = packageOrNull(pkg) ?: continue
            found[pkg] = SmartTubeInstalledPackage(
                packageName = pkg,
                versionName = info.versionName,
                launchActivity = pm.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString(),
                source = "catalog_lookup",
            )
        }
        for (info in installedPackageInfos()) {
            val pkg = info.packageName ?: continue
            if (pkg in found) continue
            if (!SmartTubePackageNames.looksLikeSmartTube(pkg)) continue
            found[pkg] = SmartTubeInstalledPackage(
                packageName = pkg,
                versionName = info.versionName,
                launchActivity = pm.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString(),
                source = "device_scan_name_match",
            )
        }
        return found.values.toList()
    }

    override fun resolveActivity(spec: SmartTubeLaunchSpec): SmartTubeResolveActivity? {
        val intent = toIntent(spec) ?: return null
        val component = try {
            intent.resolveActivity(pm)
        } catch (_: Exception) {
            null
        } ?: return null
        return SmartTubeResolveActivity(
            component = component.flattenToShortString(),
            packageName = component.packageName,
        )
    }

    override fun launch(
        spec: SmartTubeLaunchSpec,
        mode: YouTubeLaunchMode,
    ): SmartTubeLaunchOutcome {
        if (ForbiddenYouTubePackages.isYouTube(spec.packageName)) {
            return SmartTubeLaunchOutcome.Refused(
                detail = "youtube_package_forbidden",
                spec = spec,
            )
        }
        val resolved = resolveActivity(spec)
        if (ForbiddenYouTubePackages.isYouTube(resolved?.packageName)) {
            return SmartTubeLaunchOutcome.Refused(
                detail = "would_launch_youtube_not_smarttube",
                spec = spec,
                resolveActivity = resolved,
            )
        }
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            return SmartTubeLaunchOutcome.DryRun(spec, resolved)
        }
        val intent = toIntent(spec)
            ?: return SmartTubeLaunchOutcome.Failed("intent_unconstructable", spec, resolved)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            SmartTubeLaunchOutcome.Dispatched(spec, resolved)
        } catch (e: ActivityNotFoundException) {
            SmartTubeLaunchOutcome.Failed("activity_not_found:${e.message}", spec, resolved)
        } catch (e: Exception) {
            SmartTubeLaunchOutcome.Failed(
                "dispatch_exception:${e.javaClass.simpleName}",
                spec,
                resolved,
            )
        }
    }

    private fun toIntent(spec: SmartTubeLaunchSpec): Intent? {
        if (spec.action == SmartTubeLaunchAudit.ACTION_MAIN && spec.packageName != null) {
            val launch = pm.getLaunchIntentForPackage(spec.packageName)
            if (launch != null) return launch
        }
        val intent = Intent(spec.action)
        spec.uri?.let { intent.data = Uri.parse(it) }
        spec.packageName?.let { intent.setPackage(it) }
        spec.categories.forEach { intent.addCategory(it) }
        return intent
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

    private fun installedPackageInfos(): List<android.content.pm.PackageInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
