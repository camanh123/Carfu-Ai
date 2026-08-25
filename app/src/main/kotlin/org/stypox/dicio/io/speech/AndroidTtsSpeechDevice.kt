package org.stypox.dicio.io.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.R
import org.stypox.dicio.util.LocaleUtils
import java.util.Locale

class AndroidTtsSpeechDevice(private var context: Context, locale: Locale) : SpeechOutputDevice {
    private var textToSpeech: TextToSpeech? = null
    private var initializedCorrectly = false
    private val runnablesWhenFinished: MutableList<Runnable> = ArrayList()
    private var lastUtteranceId = 0
    // True from speak() until onDone/onError of that utterance. Needed because TTS.speak() is
    // asynchronous: isSpeaking can still be false if runWhenFinishedSpeaking() is called immediately
    // after speak(), which would otherwise start STT before the wake-word acknowledgment is spoken.
    private var pendingUtterance = false

    init {
        textToSpeech = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.run {
                    val errorCode = applyLanguage(this, locale)
                    if (errorCode >= 0) { // errors are -1 or -2
                        initializedCorrectly = true
                        setOnUtteranceProgressListener(object :
                            UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) {}
                            override fun onDone(utteranceId: String) {
                                finishIfLastUtterance(utteranceId)
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            @Deprecated("")
                            override fun onError(utteranceId: String) {
                                finishIfLastUtterance(utteranceId)
                            }

                            override fun onError(utteranceId: String, errorCode: Int) {
                                finishIfLastUtterance(utteranceId)
                            }

                            override fun onStop(utteranceId: String, interrupted: Boolean) {
                                finishIfLastUtterance(utteranceId)
                            }
                        })
                    } else {
                        Log.e(TAG, "Unsupported language: $errorCode")
                        handleInitializationError(R.string.android_tts_unsupported_language)
                    }
                }
            } else {
                Log.e(TAG, "TTS error: $status")
                handleInitializationError(R.string.android_tts_error)
            }
        }
    }

    override fun speak(speechOutput: String) {
        if (initializedCorrectly) {
            lastUtteranceId += 1
            pendingUtterance = true
            val utteranceId = "dicio_$lastUtteranceId"
            val result = textToSpeech?.speak(
                speechOutput, TextToSpeech.QUEUE_ADD, null,
                utteranceId
            )
            if (result != TextToSpeech.SUCCESS) {
                pendingUtterance = false
                runQueuedWhenFinished()
            }
        } else {
            Toast.makeText(context, speechOutput, Toast.LENGTH_LONG).show()
        }
    }

    override fun stopSpeaking() {
        pendingUtterance = false
        runnablesWhenFinished.clear()
        textToSpeech?.stop()
    }

    override val isSpeaking: Boolean
        get() = pendingUtterance || textToSpeech?.isSpeaking == true

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        if (isSpeaking) {
            runnablesWhenFinished.add(runnable)
        } else {
            runnable.run()
        }
    }

    override fun cleanup() {
        pendingUtterance = false
        textToSpeech?.apply {
            shutdown()
            textToSpeech = null
        }
    }

    private fun finishIfLastUtterance(utteranceId: String) {
        if ("dicio_$lastUtteranceId" == utteranceId) {
            pendingUtterance = false
            runQueuedWhenFinished()
        }
    }

    private fun runQueuedWhenFinished() {
        for (runnable in runnablesWhenFinished) {
            runnable.run()
        }
        runnablesWhenFinished.clear()
    }

    private fun handleInitializationError(@StringRes errorString: Int) {
        Toast.makeText(context, errorString, Toast.LENGTH_SHORT).show()
        cleanup()
    }

    companion object {
        val TAG: String = AndroidTtsSpeechDevice::class.simpleName!!

        internal fun applyLanguage(textToSpeech: TextToSpeech, locale: Locale): Int {
            val preferred = LocaleUtils.ttsLocaleFor(locale)
            val errorCode = textToSpeech.setLanguage(preferred)
            if (errorCode >= 0) {
                return errorCode
            }
            if (preferred.country.isNotEmpty()) {
                return textToSpeech.setLanguage(Locale(preferred.language))
            }
            return errorCode
        }
    }
}
