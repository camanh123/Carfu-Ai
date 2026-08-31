package org.stypox.dicio.ui.driving

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.stypox.dicio.io.session.CarfuDiag

object DrivingQuickActions {
    val MUSIC_PACKAGES = listOf(
        "com.musicloop.car",
        "com.syu.music",
    )

    val TILE_IDS = listOf("nav", "music", "call", "apps", "volume")
    const val TILE_HEIGHT_DP = 96
    const val MIN_TOUCH_DP = 80

    fun navigate(context: Context) {
        CarfuDiag.quick("nav launch geo")
        startSafely(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun resolveMusicPackage(isLaunchable: (String) -> Boolean): String? {
        return MUSIC_PACKAGES.firstOrNull(isLaunchable)
    }

    fun music(context: Context): MusicLaunchResult {
        val pkg = resolveMusicPackage { candidate ->
            context.packageManager.getLaunchIntentForPackage(candidate) != null
        }
        if (pkg != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (startSafely(context, launch)) {
                    CarfuDiag.quick("music launched=$pkg")
                    return MusicLaunchResult.Launched(pkg)
                }
            }
        }
        CarfuDiag.quick("music not_found")
        return MusicLaunchResult.NotFound
    }

    fun call(context: Context) {
        CarfuDiag.quick("call launch dialer")
        startSafely(
            context,
            Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun apps(context: Context) {
        CarfuDiag.quick("apps launch chooser")
        startSafely(
            context,
            Intent.createChooser(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun volumeTileAction(): VolumeTileAction = VolumeTileAction.SHOW_IN_APP_CONTROLLER

    fun allTilesClickable(): List<QuickActionTileSpec> = TILE_IDS.map { id ->
        QuickActionTileSpec(
            id = id,
            containerClickable = true,
            enabled = true,
            minHeightDp = TILE_HEIGHT_DP,
            minTouchDp = MIN_TOUCH_DP,
            overlayConsumesTouches = false,
        )
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

sealed class MusicLaunchResult {
    data class Launched(val packageName: String) : MusicLaunchResult()
    data object NotFound : MusicLaunchResult()
}

enum class VolumeTileAction {
    SHOW_IN_APP_CONTROLLER,
}

data class QuickActionTileSpec(
    val id: String,
    val containerClickable: Boolean,
    val enabled: Boolean,
    val minHeightDp: Int,
    val minTouchDp: Int,
    val overlayConsumesTouches: Boolean,
)
