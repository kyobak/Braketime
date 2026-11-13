package com.conditiondrive.vehicle.platform

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.conditiondrive.vehicle.port.ListenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 인식 결과 수명을 SDK와 떼어 시험한다. 실측:
 * - onEndedRecognition이 completed=true 결과보다 먼저 온다. 끝 신호만으로 "말 없음"을 내면 답을 버린다.
 * - 마이크 경로의 stop()은 아무 콜백도 주지 않는다. 그래서 끝맺음은 항상 여기서 한다.
 */
internal class ListenSession(
    private val scope: CoroutineScope,
    private val endGraceMs: Long = 1000,
) {
    val result = CompletableDeferred<ListenResult>()
    private val ready = CompletableDeferred<Unit>()

    fun onReady() { ready.complete(Unit) }

    fun onUpdated(text: String, completed: Boolean) {
        // 6.3 V8: completed=false는 쓰지 않는다
        if (!completed) return
        result.complete(if (text.isBlank()) ListenResult.NoSpeech else ListenResult.Heard(text))
    }

    fun onEnded() {
        ready.complete(Unit)
        scope.launch {
            delay(endGraceMs)
            result.complete(ListenResult.NoSpeech)
        }
    }

    fun onError(errorName: String) {
        result.complete(ListenResult.Failed(retriable = classifySpeechError(errorName) == SpeechFailure.RETRY_ONCE))
    }

    fun cancelled() {
        result.complete(ListenResult.Failed(retriable = true))
    }

    /** 8초는 마이크가 열린 뒤부터 잰다. 준비에 1~3초 걸려(실측) request 시점부터 재면 실제 듣는 시간이 줄어든다 */
    suspend fun await(timeoutMs: Long, readyTimeoutMs: Long, onReady: () -> Unit = {}, stopMic: () -> Unit): ListenResult {
        withTimeoutOrNull(readyTimeoutMs) { ready.await() }
        onReady()
        val heard = withTimeoutOrNull(timeoutMs) { result.await() }
        if (heard != null) return heard
        stopMic()
        return withTimeoutOrNull(endGraceMs) { result.await() } ?: ListenResult.NoSpeech
    }
}

internal class PleosSttEngine(private val context: Context, private val scope: CoroutineScope) : SttEngine {
    override val kind = SpeechEngineKind.PLEOS
    private var stt: SpeechToText? = null
    private var init: CompletableDeferred<Boolean>? = null
    @Volatile private var session: ListenSession? = null

    private val results = object : ResultListener {
        override fun onReady() { session?.onReady() }
        override fun onStartedRecognition() = Unit
        override fun onUpdated(stt: String, completed: Boolean) { session?.onUpdated(stt, completed) }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { session?.onEnded() }
        override fun onError(errorType: ErrorType) { session?.onError(errorType.name) }
    }

    /**
     * SDK는 RECORD_AUDIO 없이도 request를 받아들인다(실측). 거부한 권한을 무시하고 듣거나, 듣는 척하면 안 되므로 먼저 확인한다.
     * 초기화 시간 초과는 늦는 것일 수 있어 끊지 않는다. 다음 호출이 같은 초기화를 이어서 기다린다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun prepare(): Boolean {
        val allowed = guarded("stt permission", false) {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        if (!allowed) return false
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

    private fun begin(done: CompletableDeferred<Boolean>) {
        guarded("stt init", Unit) {
            val engine = SpeechToText(context, Mode.ON_DEVICE)
            stt = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // TTS와 달리 먼저 걸어도 되지만 같은 순서로 맞춘다. 문서는 순서를 보장하지 않는다
                    done.complete(guarded("stt listen", false) {
                        engine.addListener(results)
                        engine.setLocale(Locale.KOREA)
                        true
                    })
                }

                override fun onFailure(errorType: ErrorType) {
                    logInfo("stt init failed: ${errorType.name}")
                    done.complete(false)
                }
            })
        }
        if (stt == null) done.complete(false)
    }

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult {
        val engine = stt ?: return ListenResult.Failed(retriable = false)
        val active = ListenSession(scope)
        session = active
        val sent = guarded("stt request", false) {
            engine.request()
            true
        }
        if (!sent) return ListenResult.Failed(retriable = false)
        return try {
            active.await(timeoutMs, READY_TIMEOUT_MS, onReady) { guarded("stt stop", Unit) { engine.stop() } }
        } finally {
            session = null
        }
    }

    override fun stop() {
        session?.cancelled()
        guarded("stt stop", Unit) { stt?.stop() }
    }

    override fun release() {
        val engine = stt
        stt = null
        synchronized(this) { init = null }
        if (engine == null) return
        guarded("stt remove", Unit) { engine.removeListener(results) }
        guarded("stt release", Unit) { engine.release() }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5000L
        const val READY_TIMEOUT_MS = 4000L
    }
}
