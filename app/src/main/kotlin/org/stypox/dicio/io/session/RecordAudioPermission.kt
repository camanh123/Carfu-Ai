package org.stypox.dicio.io.session

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import org.stypox.dicio.BuildConfig
import org.stypox.dicio.MainActivity

/**
 * Android RECORD_AUDIO snapshot + MIC/MODE request handoff.
 *
 * Does not grant the permission itself and does not bypass
 * [android.content.pm.PackageManager.PERMISSION_GRANTED] checks.
 */
object RecordAudioPermission {
    private const val PREFS = "carfu_record_audio_permission"
    private const val KEY_REQUESTED = "runtime_requested"

    const val EXTRA_REQUEST_RECORD_AUDIO = "org.stypox.dicio.REQUEST_RECORD_AUDIO"
    const val EXTRA_PENDING_VOICE = "org.stypox.dicio.PENDING_VOICE_AFTER_RECORD_AUDIO"

    enum class Pending {
        NONE,
        UI_MIC,
        HARDWARE_MODE,
    }

    fun interface Requester {
        fun requestRecordAudio()
    }

    @Volatile
    var requester: Requester? = null

    @Volatile
    var pendingAfterGrant: Pending = Pending.NONE

    fun consumePending(): Pending {
        val p = pendingAfterGrant
        pendingAfterGrant = Pending.NONE
        return p
    }

    fun isGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isManifestDeclared(context: Context): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
            info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun previouslyRequested(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED, false)
    }

    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    fun snapshot(context: Context, activity: Activity? = null): RecordAudioPermissionPolicy.Snapshot {
        val granted = isGranted(context)
        val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        return RecordAudioPermissionPolicy.snapshot(
            manifestDeclared = isManifestDeclared(context),
            granted = granted,
            shouldShowRationale = rationale,
            previouslyRequested = previouslyRequested(context),
        )
    }

    fun logForVoiceTrigger(
        context: Context,
        origin: String,
        activity: Activity? = null,
    ): RecordAudioPermissionPolicy.Snapshot {
        val snap = snapshot(context, activity)
        CarfuLog.i(
            CommandSession.TAG,
            "RECORD_AUDIO_STATE origin=$origin " +
                "manifest=${snap.manifestLabel()} runtime=${snap.runtimeLabel()} " +
                "packageName=${context.packageName} " +
                "applicationId=${BuildConfig.APPLICATION_ID} " +
                "uid=${Process.myUid()} " +
                "checkSelfPermission=${snap.checkSelfPermissionGranted} " +
                "shouldShowRationale=${snap.shouldShowRationale} " +
                "previouslyRequested=${snap.previouslyRequested}",
        )
        CarfuVoiceTrace.permissionOrAvailability(snap.runtimeLabel())
        return snap
    }

    /**
     * Ask the live Activity to show the system dialog, or foreground MainActivity
     * so it can. Never starts a COMMAND_LISTENING session.
     */
    fun requestOrForeground(context: Context, pending: Pending) {
        pendingAfterGrant = pending
        val live = requester
        if (live != null && MainActivity.isCreated > 0) {
            live.requestRecordAudio()
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra(EXTRA_REQUEST_RECORD_AUDIO, true)
            putExtra(EXTRA_PENDING_VOICE, pending.name)
        }
        context.startActivity(intent)
    }

    fun pendingFromActivation(kind: CarfuActivationSource.Kind?): Pending = when (kind) {
        CarfuActivationSource.Kind.HARDWARE_BUTTON -> Pending.HARDWARE_MODE
        CarfuActivationSource.Kind.MANUAL_MIC -> Pending.UI_MIC
        else -> Pending.UI_MIC
    }
}
