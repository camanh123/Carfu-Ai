package org.stypox.dicio.skills.carfu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.stypox.dicio.R

enum class CarfuSkillUiStatus {
    WORKING,
    NEED_PERMISSION,
    NEED_INTERNET,
    UNSUPPORTED,
    NOT_IMPLEMENTED,
}

data class CarfuSkillUiRow(
    val id: String,
    val nameRes: Int,
    val status: CarfuSkillUiStatus,
)

/**
 * Skills-screen catalog. Availability is based on a real production executor, not a router
 * pattern and not Dicio's sentences-language checkbox.
 */
object CarfuSkillCatalog {
    val hiddenUpstreamIds = setOf("lyrics", "joke", "translation")

    private val carfuIds = listOf(
        "open",
        "navigation",
        "current_time",
        "media",
        "volume",
        "telephone",
        "weather",
        "timer",
        "listening",
        "search",
        "calculator",
        "notify",
        "flashlight",
    )

    fun nameRes(id: String): Int = when (id) {
        "open" -> R.string.skill_name_open
        "navigation" -> R.string.skill_name_navigation
        "current_time" -> R.string.skill_name_current_time
        "media" -> R.string.skill_name_media
        "volume" -> R.string.skill_name_volume
        "telephone" -> R.string.skill_name_telephone
        "weather" -> R.string.skill_name_weather
        "timer" -> R.string.skill_name_timer
        "listening" -> R.string.skill_name_listening
        "search" -> R.string.skill_name_search
        "calculator" -> R.string.skill_name_calculator
        "notify" -> R.string.skill_name_notify
        "flashlight" -> R.string.skill_name_flashlight
        else -> R.string.skill_name_open
    }

    fun statusStringRes(status: CarfuSkillUiStatus): Int = when (status) {
        CarfuSkillUiStatus.WORKING -> R.string.carfu_skill_status_working
        CarfuSkillUiStatus.NEED_PERMISSION -> R.string.carfu_skill_status_need_permission
        CarfuSkillUiStatus.NEED_INTERNET -> R.string.carfu_skill_status_need_internet
        CarfuSkillUiStatus.UNSUPPORTED -> R.string.carfu_skill_status_unsupported
        CarfuSkillUiStatus.NOT_IMPLEMENTED -> R.string.carfu_skill_status_not_implemented
    }

    fun visibleRows(
        context: Context,
        hasContactsPermission: Boolean = hasContactsPermission(context),
        hasTorch: Boolean = hasTorch(context),
    ): List<CarfuSkillUiRow> {
        return carfuIds.mapNotNull { id ->
            val status = statusOf(id, hasContactsPermission, hasTorch)
            if (status == CarfuSkillUiStatus.UNSUPPORTED ||
                status == CarfuSkillUiStatus.NOT_IMPLEMENTED
            ) {
                null
            } else {
                CarfuSkillUiRow(id, nameRes(id), status)
            }
        }
    }

    fun statusOf(
        id: String,
        hasContactsPermission: Boolean,
        hasTorch: Boolean,
    ): CarfuSkillUiStatus = when (id) {
        "lyrics", "joke", "translation" -> CarfuSkillUiStatus.NOT_IMPLEMENTED
        "flashlight" -> if (hasTorch) CarfuSkillUiStatus.WORKING else CarfuSkillUiStatus.UNSUPPORTED
        "telephone" -> if (hasContactsPermission) {
            CarfuSkillUiStatus.WORKING
        } else {
            CarfuSkillUiStatus.NEED_PERMISSION
        }
        "weather" -> CarfuSkillUiStatus.NEED_INTERNET
        "open", "navigation", "current_time", "media", "volume",
        "timer", "listening", "search", "calculator", "notify" -> CarfuSkillUiStatus.WORKING
        else -> CarfuSkillUiStatus.NOT_IMPLEMENTED
    }

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasTorch(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val cm = context.getSystemService(CameraManager::class.java) ?: return false
        return try {
            cm.cameraIdList.any { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            false
        }
    }
}
