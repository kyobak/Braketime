package com.conditiondrive.vehicle.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/** 엔진 큐에 넣는 두 호출만 떼어 낸 것. 시험이 실제 엔진 없이 순서와 큐 모드를 본다 */
internal interface TtsQueue {
    fun silence(durationMs: Long, queueMode: Int, utteranceId: String): Int
    fun speak(text: String, queueMode: Int, utteranceId: String): Int
}

/**
 * 묵음 발화와 본 발화를 한 요청으로 묶는다(지침서 ②에 가까움, U-27). 묵음이 앞 요청을 비우고(FLUSH) 본 발화가 그 뒤에 붙는다(ADD).
 * 프레임워크는 묵음 발화를 재생 큐에서 그 시간만큼 기다리는 것으로 처리한다(0 샘플을 쓰지는 않는다).
 * 묵음 발화의 onDone은 요청의 끝이 아니다. 완료는 본 발화로만 판단한다.
 */
internal class LeadSilenceUtterance(private val id: String) {
    private val silenceId = "$id-silence"

    /**
     * 묵음 → 본 발화 순서. 묵음을 못 넣었으면 ③으로 [wait]만큼 기다린 뒤 앞 요청을 비우며 말한다.
     * 묵음 없이 말하면 첫 음절이 잘리고, 아예 말하지 않으면 안내를 잃는다. 기다리는 중 취소되면 말하지 않는다.
     * 본 발화를 큐에 넣었으면 true
     */
    suspend fun enqueue(queue: TtsQueue, text: String, silenceMs: Long, wait: suspend (Long) -> Unit = { delay(it) }): Boolean {
        val silenced = silenceMs > 0 && enqueueSilence(queue, silenceMs)
        if (silenceMs > 0 && !silenced) wait(silenceMs)
        return enqueueSpeech(queue, text, afterSilence = silenced)
    }

    /** 묵음을 넣지 못한 엔진이면 false다. 호출자가 ③으로 기다린 뒤 바로 말한다 */
    fun enqueueSilence(queue: TtsQueue, silenceMs: Long): Boolean =
        queue.silence(silenceMs, TextToSpeech.QUEUE_FLUSH, silenceId) == TextToSpeech.SUCCESS

    fun enqueueSpeech(queue: TtsQueue, text: String, afterSilence: Boolean): Boolean =
        queue.speak(text, if (afterSilence) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, id) == TextToSpeech.SUCCESS

    private fun mine(utteranceId: String?) = utteranceId == id || utteranceId == silenceId

    fun onDone(utteranceId: String?): TtsOutcome? = if (utteranceId == id) TtsOutcome.Done else null
    fun onStop(utteranceId: String?): TtsOutcome? = if (mine(utteranceId)) TtsOutcome.Stopped else null
    fun onError(utteranceId: String?): TtsOutcome? = if (mine(utteranceId)) TtsOutcome.Failed(SpeechFailure.FATAL) else null
}

/** 10.2 대체. 실측: Pleos 에뮬레이터 v2.1.0에는 엔진이 없어 init이 ERROR다. 이때 prepare가 false라 체인이 FAILED로 끝난다 */
internal class AndroidTtsEngine(private val context: Context) : TtsEngine {
    override val kind = SpeechEngineKind.ANDROID
    // ②에 가깝다. playSilentUtterance로 엔진 재생 큐 안에서 기다린다(0 샘플 패딩은 아니다). ① synthesizeToFile로 PCM 앞에 붙이면 문장 전체를 합성한 뒤에야
    // 재생이 시작돼 첫 소리가 200ms보다 늦어지고 재생기를 앱이 따로 만들어야 한다(U-27)
    override val leadSilence = LeadSilence.IN_ENGINE
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var tts: TextToSpeech? = null
    private var init: CompletableDeferred<Boolean>? = null
    @Volatile private var current: Pending? = null

    private class Pending(val utterance: LeadSilenceUtterance, val outcome: CompletableDeferred<TtsOutcome>) {
        fun complete(result: TtsOutcome?) {
            if (result != null) outcome.complete(result)
        }
    }

    override suspend fun prepare(): Boolean {
        val waiting = synchronized(this) {
            init ?: CompletableDeferred<Boolean>().also { d ->
                init = d
                begin(d)
            }
        }
        return withTimeoutOrNull(INIT_TIMEOUT_MS) { waiting.await() } == true
    }

    private fun begin(result: CompletableDeferred<Boolean>) {
        guarded("android tts init", Unit) {
            tts = TextToSpeech(context) { status ->
                val ok = status == TextToSpeech.SUCCESS && guarded("android tts locale", false) {
                    val engine = tts
                    engine != null && engine.setLanguage(Locale.KOREA) >= TextToSpeech.LANG_AVAILABLE
                }
                if (ok) {
                    tts?.setOnUtteranceProgressListener(progress)
                    _ready.value = true
                }
                result.complete(ok)
            }
        }
        if (tts == null) result.complete(false)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { current?.let { it.complete(it.utterance.onDone(utteranceId)) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { current?.let { it.complete(it.utterance.onStop(utteranceId)) } }
        @Deprecated("API 21에서 대체됐지만 추상 메서드라 구현해야 한다")
        override fun onError(utteranceId: String?) { current?.let { it.complete(it.utterance.onError(utteranceId)) } }
        override fun onError(utteranceId: String?, errorCode: Int) { current?.let { it.complete(it.utterance.onError(utteranceId)) } }
    }

    override suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome {
        val engine = tts ?: return TtsOutcome.Failed(SpeechFailure.FATAL)
        val queue = object : TtsQueue {
            override fun silence(durationMs: Long, queueMode: Int, utteranceId: String) =
                guarded("android tts silence", TextToSpeech.ERROR) { engine.playSilentUtterance(durationMs, queueMode, utteranceId) }
            override fun speak(text: String, queueMode: Int, utteranceId: String) =
                guarded("android tts speak", TextToSpeech.ERROR) { engine.speak(text, queueMode, null, utteranceId) }
        }
        val utterance = LeadSilenceUtterance(UUID.randomUUID().toString())
        val pending = Pending(utterance, CompletableDeferred())
        current = pending
        return try {
            if (!utterance.enqueue(queue, text, leadSilenceMs)) pending.complete(TtsOutcome.Failed(SpeechFailure.FATAL))
            pending.outcome.await()
        } finally {
            current = null
        }
    }

    override fun stop() {
        guarded("android tts stop", Unit) { tts?.stop() }
    }

    override fun release() {
        val engine = tts
        tts = null
        _ready.value = false
        synchronized(this) { init = null }
        guarded("android tts shutdown", Unit) { engine?.shutdown() }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5000L
    }
}
