package com.conditiondrive.vehicle.platform

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 건강 문구는 ON_DEVICE 고정(14장). 실측:
 * - initialize 전 addEventListener는 lateinit 예외를 던진다. 리스너는 onSuccess에서 건다.
 * - 재생할 것이 없을 때 stop해도 onStop이 온다. 그래서 진행 중인 요청에만 결과를 붙인다.
 * - 콜백은 별도 스레드다.
 */
internal class PleosTtsEngine(private val context: Context) : TtsEngine {
    override val kind = SpeechEngineKind.PLEOS
    // 공개 문서의 speak(text)는 Speech Service가 합성과 재생을 함께 해 PCM도 결과 파일도 앱에 오지 않는다.
    // 그래서 ①②를 쓸 수 없고 ③ 재생 지연만 남는다(U-27, DESIGN 10.2)
    override val leadSilence = LeadSilence.PLAYER_DELAY
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var tts: TextToSpeech? = null
    private var init: CompletableDeferred<Boolean>? = null
    @Volatile private var current: CompletableDeferred<TtsOutcome>? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { current?.complete(TtsOutcome.Done) }
        override fun onStop() { current?.complete(TtsOutcome.Stopped) }
        override fun onError(errorType: ErrorType) {
            current?.complete(TtsOutcome.Failed(classifySpeechError(errorType.name)))
        }
    }

    /** 시간 초과는 늦는 것일 수 있어 끊지 않는다. 다음 호출이 같은 초기화를 이어서 기다린다. 실패로 끝났으면 새로 한다 */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun prepare(): Boolean {
        val waiting = synchronized(this) {
            val current = init
            if (current != null && current.isCompleted && !current.getCompleted()) init = null
            init ?: CompletableDeferred<Boolean>().also { d ->
                init = d
                begin(d)
            }
        }
        return withTimeoutOrNull(INIT_TIMEOUT_MS) { waiting.await() } == true
    }

    private fun begin(result: CompletableDeferred<Boolean>) {
        guarded("tts init", Unit) {
            val engine = TextToSpeech(context, Mode.ON_DEVICE)
            tts = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    val ok = guarded("tts listen", false) {
                        engine.addEventListener(events)
                        engine.setLocale(Locale.KOREA)
                        true
                    }
                    _ready.value = ok
                    result.complete(ok)
                }

                override fun onFailure(errorType: ErrorType) {
                    logInfo("tts init failed: ${errorType.name}")
                    result.complete(false)
                }
            })
        }
        if (tts == null) result.complete(false)
    }

    override suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome {
        val engine = tts ?: return TtsOutcome.Failed(SpeechFailure.FATAL)
        val outcome = CompletableDeferred<TtsOutcome>()
        current = outcome
        val sent = guarded("tts speak", false) {
            engine.speak(text)
            true
        }
        if (!sent) outcome.complete(TtsOutcome.Failed(SpeechFailure.FATAL))
        return try {
            outcome.await()
        } finally {
            current = null
        }
    }

    override fun stop() {
        guarded("tts stop", Unit) { tts?.stop() }
    }

    override fun release() {
        val engine = tts
        tts = null
        _ready.value = false
        synchronized(this) { init = null }
        if (engine == null) return
        guarded("tts stop", Unit) { engine.stop() }
        guarded("tts remove", Unit) { engine.removeEventListener(events) }
        guarded("tts release", Unit) { engine.release() }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5000L
    }
}
