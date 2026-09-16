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
 * One START = one speech session. RAW STT / NORMALIZED are never
 * concatenated across sessions. Does not execute commands.
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
    private lateinit var historyView: TextView

    private var recognizer: SpeechRecognizer? = null
    private var session = DiagnosticSessionState()
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
        historyView = findViewById(R.id.field_history)

        listenButton.setOnClickListener {
            if (session.listening) stopListening() else startListeningFlow()
        }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyDump() }

        renderIdle("idle · locale=${AliasDiagnosticPolicy.SPEECH_LOCALE} · " +
            "app=${AliasDiagnosticPolicy.APPLICATION_ID}")
    }

    override fun onDestroy() {
        discardRecognizer()
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
            session = DiagnosticSession.onStop(session, session.sessionId)
            renderIdle("RECORD_AUDIO denied — cannot start SpeechRecognizer")
        }
    }

    private fun startListeningFlow() {
        if (!hasRecordAudio()) {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                renderIdle("requesting RECORD_AUDIO")
                return
            }
            renderIdle("RECORD_AUDIO missing")
            return
        }
        startRecognizer()
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            renderIdle("SpeechRecognizer not available on this device")
            return
        }
        // Invalidate any in-flight callbacks, then drop the previous recognizer.
        session = DiagnosticSession.begin(session)
        val mySession = session.sessionId
        discardRecognizer()
        val created = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (t: Throwable) {
            session = DiagnosticSession.onStop(session, mySession)
            renderIdle("createSpeechRecognizer failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (created == null) {
            session = DiagnosticSession.onStop(session, mySession)
            renderIdle("createSpeechRecognizer returned null")
            return
        }
        recognizer = created
        created.setRecognitionListener(newListener(mySession))
        listenButton.setText(R.string.diagnostic_stop)
        renderCurrent(
            status = "listening · ${AliasDiagnosticPolicy.SPEECH_LOCALE} · session $mySession",
            raw = "",
            partial = "",
        )
        try {
            created.startListening(recognitionIntent())
        } catch (t: Throwable) {
            session = DiagnosticSession.onStop(session, mySession)
            listenButton.setText(R.string.diagnostic_start)
            renderIdle("startListening failed: ${t.javaClass.simpleName}: ${t.message}")
            discardRecognizer()
        }
    }

    private fun stopListening() {
        val id = session.sessionId
        session = DiagnosticSession.onStop(session, id)
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

    private fun discardRecognizer() {
        val current = recognizer
        recognizer = null
        if (current != null) {
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

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, AliasDiagnosticPolicy.SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, AliasDiagnosticPolicy.SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun newListener(sessionId: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionId != session.sessionId) return
            statusView.text = "ready for speech · ${AliasDiagnosticPolicy.SPEECH_LOCALE}"
        }

        override fun onBeginningOfSpeech() {
            if (sessionId != session.sessionId) return
            statusView.text = "beginning of speech"
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionId != session.sessionId) return
            statusView.text = "end of speech"
        }

        override fun onError(error: Int) {
            if (sessionId != session.sessionId) return
            session = DiagnosticSession.onStop(session, sessionId)
            listenButton.setText(R.string.diagnostic_start)
            renderIdle("error ${errorName(error)} ($error)")
            discardRecognizer()
        }

        override fun onResults(results: Bundle?) {
            if (sessionId != session.sessionId) return
            val raw = firstTranscript(results)
            session = DiagnosticSession.onFinal(session, sessionId, raw)
            val snapshot = DiagnosticDisplay.of(
                normalizer = normalizer,
                rawTranscript = session.rawStt,
                partialTranscript = session.partial,
                status = "final · session $sessionId",
            )
            renderSnapshot(snapshot)
            listenButton.setText(R.string.diagnostic_start)
            discardRecognizer()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionId != session.sessionId) return
            val partial = firstTranscript(partialResults)
            if (partial.isBlank()) return
            session = DiagnosticSession.onPartial(session, sessionId, partial)
            val snapshot = DiagnosticDisplay.of(
                normalizer = normalizer,
                rawTranscript = session.rawStt,
                partialTranscript = session.partial,
                status = "partial · session $sessionId",
            )
            renderSnapshot(snapshot)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun renderCurrent(status: String, raw: String, partial: String) {
        val snapshot = DiagnosticDisplay.of(
            normalizer = normalizer,
            rawTranscript = raw,
            partialTranscript = partial,
            status = status,
        )
        renderSnapshot(snapshot)
    }

    private fun renderIdle(status: String) {
        listenButton.setText(R.string.diagnostic_start)
        val snapshot = DiagnosticSnapshot(
            status = status,
            partialTranscript = session.partial,
            rawStt = session.rawStt,
            normalized = if (session.rawStt.isEmpty()) {
                ""
            } else {
                normalizer.normalize(session.rawStt).normalizedTranscript
            },
            provider = "",
            aliasMatched = "",
            changed = false,
        )
        renderSnapshot(snapshot)
    }

    private fun renderSnapshot(snapshot: DiagnosticSnapshot) {
        statusView.text = snapshot.status
        partialView.text = snapshot.partialTranscript
        rawView.text = snapshot.rawStt
        normalizedView.text = snapshot.normalized
        providerView.text = snapshot.provider
        aliasView.text = snapshot.aliasMatched
        changedView.text = snapshot.changed.toString()
        historyView.text = if (session.history.isEmpty()) {
            ""
        } else {
            session.history.mapIndexed { i, line -> "${i + 1}. $line" }.joinToString("\n")
        }
        lastDump = buildString {
            append(snapshot.format())
            if (session.history.isNotEmpty()) {
                appendLine("HISTORY:")
                session.history.forEach { appendLine("- $it") }
            }
        }
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
        private const val REQUEST_RECORD_AUDIO = 512

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
