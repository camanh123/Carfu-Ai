package org.stypox.dicio.smarttubeplayauto

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode

class AndroidSmartTubeLauncher(
    private val context: Context,
) : SmartTubeLauncher {
    private val pm: PackageManager = context.packageManager

    override fun installedPackages(): List<SmartTubeInstalledPackage> {
        val found = linkedMapOf<String, SmartTubeInstalledPackage>()

        for (pkg in SmartTubeCatalog.QUERY_PACKAGES) {
            if (SmartTubeHarnessIdentity.isHarness(pkg)) continue
            found[pkg] = inspectKnownPackage(pkg)
        }

        for (info in installedPackageInfos()) {
            val pkg = info.packageName ?: continue
            if (SmartTubeHarnessIdentity.isHarness(pkg)) continue
            if (pkg in found && found[pkg]!!.installed) continue
            val label = applicationLabel(info)
            val nameMatch = SmartTubePackageNames.looksLikeSmartTube(pkg)
            val labelMatch = SmartTubePackageNames.labelMatchesSmartTube(label)
            if (!nameMatch && !labelMatch) continue
            found[pkg] = inspectPackageInfo(
                pkg = pkg,
                info = info,
                extraEvidence = buildSet {
                    if (labelMatch) add(SmartTubeEvidence.DEVICE_LABEL_MATCH)
                },
                source = if (labelMatch) "device_label_match" else "device_scan_name_match",
            )
        }

        for (resolve in launcherActivities()) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            if (SmartTubeHarnessIdentity.isHarness(pkg)) continue
            val label = resolve.loadLabel(pm)?.toString()
            val labelMatch = SmartTubePackageNames.labelMatchesSmartTube(label)
            val nameMatch = SmartTubePackageNames.looksLikeSmartTube(pkg)
            if (!labelMatch && !nameMatch) continue
            val existing = found[pkg]
            if (existing?.installed == true) {
                val evidence = existing.evidence.toMutableSet()
                if (labelMatch) evidence += SmartTubeEvidence.DEVICE_LABEL_MATCH
                evidence += SmartTubeEvidence.DEVICE_LAUNCHABLE
                found[pkg] = existing.copy(
                    applicationLabel = existing.applicationLabel ?: label,
                    evidence = evidence,
                    launchActivity = existing.launchActivity
                        ?: resolve.activityInfo?.name?.let { "$pkg/$it" },
                )
                continue
            }
            val info = packageOrNull(pkg)
            if (info != null) {
                found[pkg] = inspectPackageInfo(
                    pkg = pkg,
                    info = info,
                    extraEvidence = buildSet {
                        if (labelMatch) add(SmartTubeEvidence.DEVICE_LABEL_MATCH)
                    },
                    source = "launcher_query",
                )
            }
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
        if (SmartTubeHarnessIdentity.isHarness(spec.packageName)) {
            return SmartTubeLaunchOutcome.Refused(
                detail = "harness_package_forbidden",
                spec = spec,
            )
        }
        if (ForbiddenYouTubePackages.isYouTube(spec.packageName)) {
            return SmartTubeLaunchOutcome.Refused(
                detail = "youtube_package_forbidden",
                spec = spec,
            )
        }
        val resolved = resolveActivity(spec)
        if (SmartTubeHarnessIdentity.isHarness(resolved?.packageName)) {
            return SmartTubeLaunchOutcome.Refused(
                detail = "would_launch_harness_not_smarttube",
                spec = spec,
                resolveActivity = resolved,
            )
        }
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

    private fun inspectKnownPackage(pkg: String): SmartTubeInstalledPackage {
        val info = packageOrNull(pkg)
        val evidence = mutableSetOf<String>()
        if (pkg in SmartTubeCatalog.CATALOG_PACKAGES) {
            evidence += SmartTubeEvidence.CATALOG_CANDIDATE
        }
        if (info == null) {
            return SmartTubeInstalledPackage(
                packageName = pkg,
                installed = false,
                evidence = evidence,
                source = if (pkg in SmartTubeCatalog.CATALOG_PACKAGES) {
                    "catalog_lookup"
                } else {
                    "device_query"
                },
            )
        }
        return inspectPackageInfo(
            pkg = pkg,
            info = info,
            extraEvidence = evidence,
            source = if (pkg in SmartTubeCatalog.CATALOG_PACKAGES) "catalog_lookup" else "device_query",
        )
    }

    private fun inspectPackageInfo(
        pkg: String,
        info: android.content.pm.PackageInfo,
        extraEvidence: Set<String>,
        source: String,
    ): SmartTubeInstalledPackage {
        val app = info.applicationInfo
        val label = applicationLabel(info)
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: leanbackLaunchIntent(pkg)
        val launchComponent = launch?.component?.flattenToShortString()
        val mainSpec = SmartTubeLaunchSpec(
            action = SmartTubeLaunchAudit.ACTION_MAIN,
            uri = null,
            packageName = pkg,
            categories = listOf(SmartTubeLaunchAudit.CATEGORY_LAUNCHER),
            form = SmartTubeLaunchForm.MAIN_LAUNCHER,
            exactVideoTargetRequested = false,
        )
        val resolved = resolveActivity(mainSpec)
        val evidence = extraEvidence.toMutableSet()
        evidence += SmartTubeEvidence.DEVICE_INSTALLED
        if (launch != null) evidence += SmartTubeEvidence.DEVICE_LAUNCHABLE
        if (SmartTubePackageNames.labelMatchesSmartTube(label)) {
            evidence += SmartTubeEvidence.DEVICE_LABEL_MATCH
        }
        return SmartTubeInstalledPackage(
            packageName = pkg,
            installed = true,
            applicationLabel = label,
            versionName = info.versionName,
            versionCode = versionCodeOf(info),
            enabled = app?.enabled,
            launchIntent = launch?.let { describeIntent(it) },
            launchActivity = launchComponent,
            resolveActivity = resolved?.component,
            evidence = evidence,
            source = source,
        )
    }

    private fun toIntent(spec: SmartTubeLaunchSpec): Intent? {
        if (SmartTubeHarnessIdentity.isHarness(spec.packageName)) return null
        if (ForbiddenYouTubePackages.isYouTube(spec.packageName)) return null
        if (spec.action == SmartTubeLaunchAudit.ACTION_MAIN && spec.packageName != null) {
            val launch = pm.getLaunchIntentForPackage(spec.packageName)
                ?: leanbackLaunchIntent(spec.packageName)
            if (launch != null) {
                launch.setPackage(spec.packageName)
                return launch
            }
        }
        val intent = Intent(spec.action)
        spec.uri?.let { intent.data = Uri.parse(it) }
        spec.packageName?.let { intent.setPackage(it) }
        spec.categories.forEach { intent.addCategory(it) }
        return intent
    }

    private fun leanbackLaunchIntent(pkg: String): Intent? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(CATEGORY_LEANBACK_LAUNCHER)
            .setPackage(pkg)
        val resolved = try {
            intent.resolveActivity(pm)
        } catch (_: Exception) {
            null
        } ?: return null
        intent.component = resolved
        return intent
    }

    private fun launcherActivities(): List<ResolveInfo> {
        val found = mutableListOf<ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, CATEGORY_LEANBACK_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            found += try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return found
    }

    private fun applicationLabel(info: android.content.pm.PackageInfo): String? {
        val app = info.applicationInfo ?: return null
        return try {
            pm.getApplicationLabel(app)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun describeIntent(intent: Intent): String = buildString {
        append(intent.action ?: "NONE")
        intent.component?.let { append(" component=").append(it.flattenToShortString()) }
        intent.data?.let { append(" data=").append(it) }
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
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

    companion object {
        private const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    }
}
