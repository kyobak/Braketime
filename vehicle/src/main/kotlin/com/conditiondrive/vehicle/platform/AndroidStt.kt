package com.conditiondrive.vehicle.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.conditiondrive.vehicle.port.ListenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 10.2 대체. SpeechRecognizer는 메인 스레드 전용이고 RECORD_AUDIO가 필요하다. 없으면 prepare가 false라 일방 안내로 내려간다 */
internal class AndroidSttEngine(private val context: Context, private val scope: CoroutineScope) : SttEngine {
    override val kind = SpeechEngineKind.ANDROID
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var session: ListenSession? = null

    override suspend fun prepare(): Boolean = guarded("sr check", false) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult = withContext(Dispatchers.Main) {
        val active = ListenSession(scope)
        session = active
        val started = guarded("sr start", false) {
            val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            sr.setRecognitionListener(listener(active))
            sr.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
            )
            true
        }
        if (!started) return@withContext ListenResult.Failed(retriable = false)
        try {
            active.await(timeoutMs, READY_TIMEOUT_MS, onReady) { guarded("sr stop", Unit) { recognizer?.stopListening() } }
        } finally {
            session = null
        }
    }

    private fun listener(active: ListenSession) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = active.onReady()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            active.onUpdated(text, completed = true)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> active.onUpdated("", completed = true)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> active.onError("NETWORK_ERROR")
                else -> active.onError("RECOGNIZER_$error")
            }
        }
    }

    override fun stop() {
        session?.cancelled()
        onMainThread { guarded("sr cancel", Unit) { recognizer?.cancel() } }
    }

    override fun release() {
        onMainThread {
            guarded("sr destroy", Unit) { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun onMainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private companion object {
        const val READY_TIMEOUT_MS = 4000L
    }
}
