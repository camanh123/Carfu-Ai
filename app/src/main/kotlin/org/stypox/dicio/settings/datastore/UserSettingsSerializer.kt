package org.stypox.dicio.settings.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object UserSettingsSerializer : Serializer<UserSettings> {
    override val defaultValue: UserSettings = UserSettings.getDefaultInstance()
        .toBuilder()
        .setAutoFinishSttPopup(true)
        .setLanguage(Language.LANGUAGE_VI)
        .setTheme(Theme.THEME_BLACK)
        .setSttPlaySound(SttPlaySound.STT_PLAY_SOUND_NONE)
        // CARFU head-unit default: listen for “CARFU ơi” in a microphone
        // foreground service even when the Activity is not visible.
        .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_ENABLED)
        .build()

    override suspend fun readFrom(input: InputStream): UserSettings {
        try {
            return UserSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        t.writeTo(output)
    }
}
