package org.stypox.dicio.skills.carfu

import android.content.Intent

data class CarfuContact(
    val name: String,
    val numbers: List<String>,
)

sealed class HttpFetchResult {
    data class Ok(val body: String) : HttpFetchResult()
    data object Offline : HttpFetchResult()
    data object Timeout : HttpFetchResult()
    data class Error(val message: String) : HttpFetchResult()
}

data class StartedActivity(
    val action: String?,
    val packageName: String?,
    val className: String?,
    val data: String?,
)

/**
 * JVM-testable Android/network seams used by [CarfuVietnameseSkillExecutor].
 * Every supported intent has exactly one production executor; this interface is that seam.
 */
interface CarfuSkillPlatform {
    fun hasPermission(permission: String): Boolean
    fun lookupContacts(foldedQuery: String): List<CarfuContact>
    fun resolvePackage(intent: Intent): String?
    fun startActivity(intent: Intent): Boolean
    fun isPackageLaunchable(packageName: String): Boolean
    fun launchPackage(packageName: String): Boolean
    fun dispatchMediaKey(keyCode: Int): Boolean
    fun adjustVolume(raise: Boolean): Boolean
    fun currentTimeSpeech(): String
    fun isOnline(): Boolean
    fun httpGet(url: String, timeoutMs: Int): HttpFetchResult
    fun nowEpochMs(): Long
    fun scheduleAlarm(id: String, fireAtEpochMs: Long, kind: CarfuAlarmKind, label: String)
    fun cancelAlarm(id: String)
    fun saveTimer(timer: CarfuPersistedAlarm?)
    fun loadTimer(): CarfuPersistedAlarm?
    fun saveReminder(reminder: CarfuPersistedAlarm?)
    fun loadReminder(): CarfuPersistedAlarm?
    fun setBackgroundWakeEnabled(enabled: Boolean)
    fun startWakeService()
    fun stopWakeService()
    fun hasTorch(): Boolean
    fun setTorch(on: Boolean): Boolean
    fun startedActivities(): List<StartedActivity> = emptyList()
}

enum class CarfuAlarmKind { TIMER, REMINDER }

data class CarfuPersistedAlarm(
    val id: String,
    val fireAtEpochMs: Long,
    val durationMs: Long,
    val label: String,
    val kind: CarfuAlarmKind,
)
