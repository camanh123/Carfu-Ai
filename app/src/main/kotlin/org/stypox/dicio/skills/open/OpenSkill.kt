package org.stypox.dicio.skills.open

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.io.session.VietnameseTranscript
import org.stypox.dicio.sentences.Sentences.Open
import org.stypox.dicio.util.StringUtils

class OpenSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Open>)
    : StandardRecognizerSkill<Open>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Open): SkillOutput {
        val userAppName = when (inputData) {
            is Open.Query -> inputData.what?.trim { it <= ' ' }
        }
        val packageManager: PackageManager = ctx.android.packageManager
        val applicationInfo = userAppName?.let { getMostSimilarApp(packageManager, it) }

        if (applicationInfo != null) {
            val launchIntent: Intent =
                packageManager.getLaunchIntentForPackage(applicationInfo.packageName)!!
            launchIntent.action = Intent.ACTION_MAIN
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.android.startActivity(launchIntent)
        }

        return OpenOutput(
            appName = applicationInfo?.loadLabel(packageManager)?.toString() ?: userAppName,
            packageName = applicationInfo?.packageName,
        )
    }

    companion object {
        private val KNOWN_PACKAGES: List<Pair<Set<String>, List<String>>> = listOf(
            setOf("youtube", "you tube", "yt") to listOf(
                "com.google.android.youtube",
                "com.vanced.android.youtube",
                "app.revanced.android.youtube",
            ),
            setOf("ban do", "maps", "google maps", "google map", "map") to listOf(
                "com.google.android.apps.maps",
            ),
            setOf("musicloop", "music loop") to listOf(
                "com.musicloop",
                "com.carfu.musicloop",
            ),
        )

        private fun getMostSimilarApp(
            packageManager: PackageManager,
            appName: String
        ): ApplicationInfo? {
            val folded = VietnameseTranscript.foldForMatch(appName)
            resolveKnownPackage(packageManager, folded)?.let { return it }

            val resolveInfosIntent = Intent(Intent.ACTION_MAIN, null)
            resolveInfosIntent.addCategory(Intent.CATEGORY_LAUNCHER)

            @SuppressLint("QueryPermissionsNeeded") // we need to query all apps
            val resolveInfos: List<ResolveInfo> =
                packageManager.queryIntentActivities(resolveInfosIntent, 0)
            var bestDistance = Int.MAX_VALUE
            var bestApplicationInfo: ApplicationInfo? = null

            for (resolveInfo in resolveInfos) {
                try {
                    val currentApplicationInfo: ApplicationInfo = packageManager.getApplicationInfo(
                        resolveInfo.activityInfo.packageName, PackageManager.GET_META_DATA
                    )
                    val label = packageManager.getApplicationLabel(currentApplicationInfo).toString()
                    val foldedLabel = VietnameseTranscript.foldForMatch(label)
                    if (folded.isNotEmpty() &&
                        (foldedLabel == folded || foldedLabel.replace(" ", "") == folded.replace(" ", ""))
                    ) {
                        return currentApplicationInfo
                    }
                    val currentDistance = StringUtils.customStringDistance(appName, label)
                    if (currentDistance < bestDistance) {
                        bestDistance = currentDistance
                        bestApplicationInfo = currentApplicationInfo
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
            return if (bestDistance > 5) null else bestApplicationInfo
        }

        private fun resolveKnownPackage(
            packageManager: PackageManager,
            foldedName: String,
        ): ApplicationInfo? {
            for ((aliases, packages) in KNOWN_PACKAGES) {
                if (foldedName !in aliases) continue
                for (pkg in packages) {
                    try {
                        if (packageManager.getLaunchIntentForPackage(pkg) != null) {
                            return packageManager.getApplicationInfo(pkg, 0)
                        }
                    } catch (_: PackageManager.NameNotFoundException) {
                    }
                }
            }
            return null
        }
    }
}
