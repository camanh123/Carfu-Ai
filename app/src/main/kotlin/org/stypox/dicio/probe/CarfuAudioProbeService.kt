package org.stypox.dicio.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Foreground microphone probe. Keeps [AudioRecord] running while the UI is
 * backgrounded (or while YouTube / Zing MP3 is playing) so we can see whether
 * the CARFU ROM steals / mutes the mic.
 */
class CarfuAudioProbeService : Service() {

    private val recording = AtomicBoolean(false)
    private var recordThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarfuProbeLog.append("AudioProbe: stop requested")
            stopRecordingAndSelf()
            return START_NOT_STICKY
        }

        try {
            startForegroundNotification()
        } catch (t: Throwable) {
            CarfuProbeLog.append("AudioProbe: cannot start foreground: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CarfuProbeLog.append("AudioProbe: RECORD_AUDIO not granted, aborting")
            stopRecordingAndSelf()
            return START_NOT_STICKY
        }

        if (recording.getAndSet(true)) {
            CarfuProbeLog.append("AudioProbe: already recording")
            return START_STICKY
        }

        recordThread = Thread(::recordLoop, "CarfuAudioProbe").also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        recording.set(false)
        recordThread?.interrupt()
        recordThread = null
        super.onDestroy()
    }

    private fun stopRecordingAndSelf() {
        recording.set(false)
        recordThread?.interrupt()
        recordThread = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.carfu_probe_audio_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.carfu_probe_audio_channel_desc)
            notificationManager.createNotificationChannel(channel)
        }

        val openProbe = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CarfuProbeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CarfuAudioProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hearing_white)
            .setContentTitle(getString(R.string.carfu_probe_audio_notification))
            .setContentText(getString(R.string.carfu_probe_audio_notification_text))
            .setContentIntent(openProbe)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_stop_circle_white,
                getString(R.string.stop),
                stop,
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
        CarfuProbeLog.append("AudioProbe: foreground notification shown")
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            CarfuProbeLog.append("AudioProbe: getMinBufferSize failed ($minBuf)")
            stopRecordingAndSelf()
            return
        }

        val bufferSize = minBuf.coerceAtLeast(SAMPLE_RATE / 5 * 2) // ~200ms
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            CarfuProbeLog.append("AudioProbe: AudioRecord create failed: ${t.message}")
            stopRecordingAndSelf()
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            CarfuProbeLog.append("AudioProbe: AudioRecord not initialized (state=${recorder.state})")
            recorder.release()
            stopRecordingAndSelf()
            return
        }

        val buf = ShortArray(bufferSize / 2)
        var frames = 0
        var lastLogMs = 0L
        try {
            recorder.startRecording()
            CarfuProbeLog.append(
                "AudioProbe: recording started source=VOICE_RECOGNITION " +
                    "rate=$SAMPLE_RATE mono pcm16 buf=$bufferSize session=${recorder.audioSessionId}"
            )
            while (recording.get()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read < 0) {
                    CarfuProbeLog.append("AudioProbe: read error $read — mic may have been stolen")
                    break
                }
                if (read == 0) {
                    continue
                }
                frames += 1
                val now = System.currentTimeMillis()
                if (now - lastLogMs >= LOG_INTERVAL_MS) {
                    lastLogMs = now
                    val (peak, rms) = measure(buf, read)
                    CarfuProbeLog.append(
                        "AudioProbe: peak=$peak rms=${"%.1f".format(rms)} " +
                            "frames=$frames recording=${recorder.recordingState}"
                    )
                }
            }
        } catch (t: Throwable) {
            CarfuProbeLog.append("AudioProbe: loop error: ${t.message}")
        } finally {
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            recorder.release()
            recording.set(false)
            CarfuProbeLog.append("AudioProbe: recording stopped")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun measure(buf: ShortArray, length: Int): Pair<Int, Double> {
        var peak = 0
        var sumSq = 0.0
        for (i in 0 until length) {
            val v = buf[i].toInt()
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            sumSq += v.toDouble() * v.toDouble()
        }
        val rms = sqrt(sumSq / length.coerceAtLeast(1))
        return peak to rms
    }

    companion object {
        const val ACTION_STOP = "org.stypox.dicio.probe.CarfuAudioProbeService.STOP"
        private const val CHANNEL_ID = "org.stypox.dicio.probe.audio"
        private const val NOTIFICATION_ID = 26042501
        private const val SAMPLE_RATE = 16000
        private const val LOG_INTERVAL_MS = 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarfuAudioProbeService::class.java),
            )
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, CarfuAudioProbeService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: IllegalStateException) {
                // Service was not running.
            }
        }
    }
}
