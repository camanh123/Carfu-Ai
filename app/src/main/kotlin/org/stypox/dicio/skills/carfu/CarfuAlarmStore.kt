package org.stypox.dicio.skills.carfu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

object CarfuAlarmStore {
    private const val PREFS = "carfu_alarms"
    private const val KEY_TIMER = "timer"
    private const val KEY_REMINDER = "reminder"

    fun save(context: Context, kind: CarfuAlarmKind, alarm: CarfuPersistedAlarm?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = keyOf(kind)
        if (alarm == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, toJson(alarm)).apply()
        }
    }

    fun load(context: Context, kind: CarfuAlarmKind): CarfuPersistedAlarm? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyOf(kind), null) ?: return null
        return fromJson(raw)
    }

    fun restoreAll(context: Context) {
        val now = System.currentTimeMillis()
        listOf(CarfuAlarmKind.TIMER, CarfuAlarmKind.REMINDER).forEach { kind ->
            val alarm = load(context, kind) ?: return@forEach
            if (alarm.fireAtEpochMs <= now) {
                save(context, kind, null)
            } else {
                CarfuAlarmScheduler.schedule(
                    context, alarm.id, alarm.fireAtEpochMs, alarm.kind, alarm.label,
                )
            }
        }
    }

    private fun keyOf(kind: CarfuAlarmKind): String = when (kind) {
        CarfuAlarmKind.TIMER -> KEY_TIMER
        CarfuAlarmKind.REMINDER -> KEY_REMINDER
    }

    private fun toJson(alarm: CarfuPersistedAlarm): String = JSONObject()
        .put("id", alarm.id)
        .put("fireAtEpochMs", alarm.fireAtEpochMs)
        .put("durationMs", alarm.durationMs)
        .put("label", alarm.label)
        .put("kind", alarm.kind.name)
        .toString()

    private fun fromJson(raw: String): CarfuPersistedAlarm? = try {
        val o = JSONObject(raw)
        CarfuPersistedAlarm(
            id = o.getString("id"),
            fireAtEpochMs = o.getLong("fireAtEpochMs"),
            durationMs = o.getLong("durationMs"),
            label = o.optString("label"),
            kind = CarfuAlarmKind.valueOf(o.getString("kind")),
        )
    } catch (_: Exception) {
        null
    }
}

object CarfuAlarmScheduler {
    const val ACTION_TIMER = "org.stypox.dicio.skills.carfu.TIMER_FIRE"
    const val ACTION_REMINDER = "org.stypox.dicio.skills.carfu.REMINDER_FIRE"
    const val EXTRA_ID = "id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_KIND = "kind"

    fun schedule(
        context: Context,
        id: String,
        fireAtEpochMs: Long,
        kind: CarfuAlarmKind,
        label: String,
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setExact(AlarmManager.RTC_WAKEUP, fireAtEpochMs, pending(context, id, kind, label))
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val kind = if (id == CarfuVietnameseSkillExecutor.TIMER_ID) {
            CarfuAlarmKind.TIMER
        } else {
            CarfuAlarmKind.REMINDER
        }
        am.cancel(pending(context, id, kind, ""))
    }

    private fun pending(
        context: Context,
        id: String,
        kind: CarfuAlarmKind,
        label: String,
    ): PendingIntent {
        val action = if (kind == CarfuAlarmKind.TIMER) ACTION_TIMER else ACTION_REMINDER
        val intent = Intent(context, CarfuAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_KIND, kind.name)
        }
        val requestCode = if (kind == CarfuAlarmKind.TIMER) 41001 else 41002
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
