package org.stypox.dicio.skills.carfu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CarfuAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val kindName = intent.getStringExtra(CarfuAlarmScheduler.EXTRA_KIND)
        val kind = try {
            CarfuAlarmKind.valueOf(kindName ?: CarfuAlarmKind.TIMER.name)
        } catch (_: Exception) {
            CarfuAlarmKind.TIMER
        }
        val label = intent.getStringExtra(CarfuAlarmScheduler.EXTRA_LABEL).orEmpty()
        CarfuAlarmStore.save(context, kind, null)

        val speech = when (kind) {
            CarfuAlarmKind.TIMER -> "Hết giờ ${label.ifBlank { "" }}".trim()
            CarfuAlarmKind.REMINDER -> "Nhắc nhở: ${label.ifBlank { "đến giờ rồi" }}"
        }
        showNotification(context, kind, speech)
        if (kind == CarfuAlarmKind.TIMER) {
            try {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                    ?.let { RingtoneManager.getRingtone(context, it)?.play() }
            } catch (_: Exception) {
            }
        }
        speakOnce(context.applicationContext, speech) {
            pending.finish()
        }
    }

    private fun showNotification(context: Context, kind: CarfuAlarmKind, text: String) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.carfu_alarm_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (kind == CarfuAlarmKind.TIMER) {
            context.getString(R.string.carfu_timer_fired)
        } else {
            context.getString(R.string.carfu_reminder_fired)
        }
        manager.notify(
            if (kind == CarfuAlarmKind.TIMER) 42001 else 42002,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hearing_white)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun speakOnce(context: Context, text: String, onDone: () -> Unit) {
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                onDone()
            }
        }
        val holder = arrayOfNulls<TextToSpeech>(1)
        holder[0] = TextToSpeech(context) { status ->
            val engine = holder[0]
            if (status == TextToSpeech.SUCCESS && engine != null) {
                engine.language = Locale("vi", "VN")
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        finishOnce()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        finishOnce()
                    }
                })
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "carfu-alarm")
            } else {
                engine?.shutdown()
                finishOnce()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            holder[0]?.shutdown()
            finishOnce()
        }, 8_000)
    }

    companion object {
        private const val CHANNEL_ID = "org.stypox.dicio.skills.carfu.alarms"
    }
}
