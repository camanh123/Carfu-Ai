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
        // Acceptance build: background wake OFF; MODE + Android STT is the
        // stable path. Existing installs are migrated the first time this
        // field is UNSET.
        .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_DISABLED)
        .setCommandRecognitionEngine(
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE,
        )
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
