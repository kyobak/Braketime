package com.conditiondrive.vehicle.platform

import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.ListenerPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface SttEngine {
    val kind: SpeechEngineKind
    suspend fun prepare(): Boolean
    suspend fun listen(timeoutMs: Long, onReady: () -> Unit = {}): ListenResult
    fun stop()
    fun release()
}

/**
 * 10.2 체인: Pleos ON_DEVICE → Android SpeechRecognizer → Failed(런타임이 일방 안내, 체크인 응답 없음).
 * 치명 오류면 같은 질문을 다음 엔진으로 받는다. 재시도 가능 오류는 같은 엔진으로 1회 더 받는다.
 * 실패한 엔진은 잠시 건너뛸 뿐 영구히 버리지 않는다. 권한이 생기면 다음 질문부터 듣는다.
 */
internal class ChainListener(
    private val engines: List<SttEngine>,
    private val focus: FocusPort,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryAfterMs: Long = ENGINE_RETRY_MS,
    private val readyCue: () -> Unit = {},
) : ListenerPort {
    private val mutex = Mutex()
    private val enginesMutex = Mutex()
    private val down = mutableMapOf<SttEngine, Long>()
    private val _available = MutableStateFlow(true)
    private val _active = MutableStateFlow(engines.firstOrNull()?.kind ?: SpeechEngineKind.NONE)
    @Volatile private var current: SttEngine? = null
    @Volatile private var stopped = false

    val activeEngine: StateFlow<SpeechEngineKind> = _active.asStateFlow()
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    init {
        // Pleos 첫 인식은 준비에 1~3초 걸린다. 미리 초기화해 그만큼이라도 줄인다
        scope.launch { pick() }
    }

    override suspend fun ready(): Boolean = pick() != null

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult = mutex.withLock {
        stopped = false
        // 포커스를 못 잡아도 듣기는 시도한다. 응답을 놓치는 것보다 낫고 음악은 이미 사용자 몫이다
        focus.acquire(FocusPurpose.LISTEN) { stopNow() }
        var signalled = false
        val signal = {
            if (!signalled) {
                signalled = true
                guarded("listen cue", Unit) { readyCue() }
                onReady()
            }
        }
        try {
            runChain(timeoutMs, signal)
        } catch (e: CancellationException) {
            // 부른 쪽이 듣기를 취소했다(온보딩 듣기 시험 중 Back 등). 코루틴만 끝나면 엔진 마이크는 계속 열려 있다(실측 97초)
            // stopNow 뒤의 취소(VoiceQueue 끊기)는 이미 멈췄으니 다시 보내지 않는다
            if (!stopped) guarded("stt stop", Unit) { current?.stop() }
            throw e
        } finally {
            current = null
            focus.release()
        }
    }

    private suspend fun runChain(timeoutMs: Long, onReady: () -> Unit): ListenResult {
        while (true) {
            val engine = pick() ?: return ListenResult.Failed(retriable = false)
            current = engine
            var result = guarded("stt listen", ListenResult.Failed(retriable = false) as ListenResult) { engine.listen(timeoutMs, onReady) }
            if (result is ListenResult.Failed && result.retriable && !stopped) {
                result = guarded("stt listen", ListenResult.Failed(retriable = false) as ListenResult) { engine.listen(timeoutMs, onReady) }
            }
            when {
                stopped -> return ListenResult.Failed(retriable = true)
                result !is ListenResult.Failed -> return result
                else -> markDead(engine)
            }
        }
    }

    private suspend fun pick(): SttEngine? = enginesMutex.withLock {
        for (engine in engines) {
            val downAt = down[engine]
            if (downAt != null && nowMs() - downAt < retryAfterMs) continue
            if (guarded("stt prepare", false) { engine.prepare() }) {
                down.remove(engine)
                _active.value = engine.kind
                _available.value = true
                return@withLock engine
            }
            // 권한이 없거나 초기화가 늦는 것이다. 해제하면 곧 끝날 초기화까지 잃는다
            down[engine] = nowMs()
        }
        _active.value = SpeechEngineKind.NONE
        _available.value = false
        null
    }

    private suspend fun markDead(engine: SttEngine) = enginesMutex.withLock { markDeadLocked(engine) }

    private fun markDeadLocked(engine: SttEngine) {
        down[engine] = nowMs()
        logInfo("stt engine down: ${engine.kind}")
        guarded("stt release", Unit) { engine.release() }
    }

    /** 권한이 바뀌었거나 시동을 켰다. 기다리지 말고 지금 다시 해 본다 */
    suspend fun revive() {
        enginesMutex.withLock { down.clear() }
        pick()
    }

    override fun stopNow() {
        stopped = true
        guarded("stt stop", Unit) { current?.stop() }
    }

    fun release() {
        stopNow()
        focus.release()
        engines.forEach { guarded("stt release", Unit) { it.release() } }
    }
}
