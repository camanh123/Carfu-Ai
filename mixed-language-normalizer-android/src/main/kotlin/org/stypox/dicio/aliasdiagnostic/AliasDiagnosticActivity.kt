package org.stypox.dicio.aliasdiagnostic

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import org.stypox.dicio.aliasnormalizer.ProviderAliasNormalizer

/**
 * Standalone diagnostic: microphone → SpeechRecognizer vi-VN →
 * [org.stypox.dicio.aliasnormalizer.DefaultProviderAliasNormalizer] → UI.
 *
 * Does not execute commands, launch apps, call production NLU, become
 * the default assistant, or use Accessibility / background wake.
 */
class AliasDiagnosticActivity : Activity() {

    private val normalizer: ProviderAliasNormalizer = DiagnosticDisplay.defaultNormalizer()

    private lateinit var listenButton: Button
    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var rawView: TextView
    private lateinit var normalizedView: TextView
    private lateinit var providerView: TextView
    private lateinit var aliasView: TextView
    private lateinit var changedView: TextView

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastDump: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alias_diagnostic)

        listenButton = findViewById(R.id.btn_listen)
        statusView = findViewById(R.id.field_status)
        partialView = findViewById(R.id.field_partial)
        rawView = findViewById(R.id.field_raw)
        normalizedView = findViewById(R.id.field_normalized)
        providerView = findViewById(R.id.field_provider)
        aliasView = findViewById(R.id.field_alias)
        changedView = findViewById(R.id.field_changed)

        listenButton.setOnClickListener {
            if (listening) stopListening() else startListeningFlow()
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyDump() }

        render(
            DiagnosticSnapshot(
                status = "idle · locale=${AliasDiagnosticPolicy.SPEECH_LOCALE} · " +
                    "app=${AliasDiagnosticPolicy.APPLICATION_ID}",
                partialTranscript = "",
                rawStt = "",
                normalized = "",
                provider = "",
                aliasMatched = "",
                changed = false,
            ),
        )
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecognizer()
        } else {
            listening = false
            renderStatus("RECORD_AUDIO denied — cannot start SpeechRecognizer")
        }
    }

    private fun startListeningFlow() {
        if (!hasRecordAudio()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                renderStatus("requesting RECORD_AUDIO")
                return
            }
            renderStatus("RECORD_AUDIO missing")
            return
        }
        startRecognizer()
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listening = false
            renderStatus("SpeechRecognizer not available on this device")
            return
        }
        stopListening()
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (t: Throwable) {
            renderStatus("createSpeechRecognizer failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (created == null) {
            renderStatus("createSpeechRecognizer returned null")
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        listening = true
        listenButton.setText(R.string.diagnostic_stop)
        render(
            DiagnosticSnapshot(
                status = "listening · ${AliasDiagnosticPolicy.SPEECH_LOCALE}",
                partialTranscript = "",
                rawStt = "",
                normalized = "",
                provider = "",
                aliasMatched = "",
                changed = false,
            ),
        )
        try {
            created.startListening(recognitionIntent())
        } catch (t: Throwable) {
            listening = false
            listenButton.setText(R.string.diagnostic_start)
            renderStatus("startListening failed: ${t.javaClass.simpleName}: ${t.message}")
            destroyRecognizer()
        }
    }

    private fun stopListening() {
        listening = false
        listenButton.setText(R.string.diagnostic_start)
        val current = recognizer
        recognizer = null
        if (current != null) {
            try {
                current.stopListening()
            } catch (_: Throwable) {
            }
            try {
                current.cancel()
            } catch (_: Throwable) {
            }
            try {
                current.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun destroyRecognizer() {
        val current = recognizer
        recognizer = null
        listening = false
        listenButton.setText(R.string.diagnostic_start)
        if (current != null) {
            try {
                current.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, AliasDiagnosticPolicy.SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, AliasDiagnosticPolicy.SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            renderStatus("ready for speech · ${AliasDiagnosticPolicy.SPEECH_LOCALE}")
        }

        override fun onBeginningOfSpeech() {
            renderStatus("beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            renderStatus("end of speech")
        }

        override fun onError(error: Int) {
            listening = false
            listenButton.setText(R.string.diagnostic_start)
            renderStatus("error ${errorName(error)} ($error)")
            destroyRecognizer()
        }

        override fun onResults(results: Bundle?) {
            val raw = firstTranscript(results)
            val snapshot = DiagnosticDisplay.of(
                normalizer = normalizer,
                rawTranscript = raw,
                partialTranscript = partialView.text?.toString().orEmpty(),
                status = "final",
            )
            render(snapshot)
            destroyRecognizer()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstTranscript(partialResults)
            if (partial.isBlank()) return
            val snapshot = DiagnosticDisplay.of(
                normalizer = normalizer,
                rawTranscript = partial,
                partialTranscript = partial,
                status = "partial",
            )
            render(snapshot)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun render(snapshot: DiagnosticSnapshot) {
        statusView.text = snapshot.status
        partialView.text = snapshot.partialTranscript
        rawView.text = snapshot.rawStt
        normalizedView.text = snapshot.normalized
        providerView.text = snapshot.provider
        aliasView.text = snapshot.aliasMatched
        changedView.text = snapshot.changed.toString()
        lastDump = snapshot.format()
    }

    private fun renderStatus(status: String) {
        statusView.text = status
        lastDump = DiagnosticSnapshot(
            status = status,
            partialTranscript = partialView.text?.toString().orEmpty(),
            rawStt = rawView.text?.toString().orEmpty(),
            normalized = normalizedView.text?.toString().orEmpty(),
            provider = providerView.text?.toString().orEmpty(),
            aliasMatched = aliasView.text?.toString().orEmpty(),
            changed = changedView.text?.toString() == "true",
        ).format()
    }

    private fun copyDump() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("alias-diagnostic", lastDump))
        Toast.makeText(this, "Diagnostic copied", Toast.LENGTH_SHORT).show()
    }

    private fun hasRecordAudio(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 511

        fun firstTranscript(bundle: Bundle?): String {
            val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return results?.firstOrNull().orEmpty()
        }

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            else -> "ERROR_UNKNOWN"
        }
    }
}
