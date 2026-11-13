package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.port.SpeakerPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 실패한 음성 엔진을 다시 시도하는 간격. 이 값 안에서는 대체 엔진으로 바로 넘어간다 */
internal const val ENGINE_RETRY_MS = 60_000L

internal sealed interface TtsOutcome {
    data object Done : TtsOutcome
    data object Stopped : TtsOutcome
    data class Failed(val failure: SpeechFailure) : TtsOutcome
}

internal enum class SpeechEngineKind { PLEOS, ANDROID, NONE }

/**
 * Pleos 지침서의 TTS 선행 묵음은 150~200ms다. 짧으면 첫 음절이 잘리고 길면 늦게 들린다.
 * 하한에 맞춘다. ③ 재생 지연은 SDK가 실제로 소리를 내기까지의 시간이 더해지므로 위로만 벗어난다.
 */
internal const val LEAD_SILENCE_MS = 150L
internal val LEAD_SILENCE_RANGE = 150L..200L

/** 묵음을 넣는 자리. 지침서 우선순위 ① PCM/WAV 앞 패딩 ② TTS 결과에 묵음 추가 ③ 플레이어 재생 지연 중 엔진이 할 수 있는 가장 높은 것(U-27) */
internal enum class LeadSilence {
    /**
     * ②에 가깝다. 엔진의 재생 큐 안에서 묵음 발화만큼 기다린 뒤 본 발화를 잇는다. 0 샘플을 쓰는 패딩은 아니지만
     * 합성은 그동안 계속돼 첫 소리가 크게 늦지 않고, 앱 스케줄링 편차가 없다
     */
    IN_ENGINE,
    /**
     * ③ 앱이 오디오 데이터를 만질 수 없어 포커스를 잡은 뒤 합성·재생 요청 전체를 늦춘다. 덕킹이 끝난 뒤 시작하게 할 뿐,
     * 첫 음절 잘림이 실제로 줄어드는지는 재지 않았다. 권장되지 않는 마지막 수단이다
     */
    PLAYER_DELAY,
}

/** 엔진 하나. prepare 전에는 바인딩하지 않는다. 대체 엔진까지 미리 띄울 이유가 없다 */
internal interface TtsEngine {
    val kind: SpeechEngineKind
    val ready: StateFlow<Boolean>
    val leadSilence: LeadSilence
    /** 초기화까지 기다린다. 쓸 수 없으면 false */
    suspend fun prepare(): Boolean
    /** [leadSilenceMs]는 IN_ENGINE 엔진만 받는다. PLAYER_DELAY 엔진에는 0이 온다(묵음은 체인이 이미 기다렸다) */
    suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome
    fun stop()
    fun release()
}

/**
 * 10.2 체인: Pleos ON_DEVICE → Android TTS → FAILED(런타임이 헤드업과 화면으로 내린다).
 * 포커스 획득~해제는 여기서 끝낸다. 실패한 엔진은 잠시 건너뛸 뿐 영구히 버리지 않는다.
 * 대체 엔진이 없는 환경에서 오류 한 번으로 시동을 다시 켜도 소리가 나지 않으면 안 된다.
 */
internal class ChainSpeaker(
    private val engines: List<TtsEngine>,
    private val focus: FocusPort,
    scope: CoroutineScope,
    private val leadSilenceMs: Long = LEAD_SILENCE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryAfterMs: Long = ENGINE_RETRY_MS,
) : SpeakerPort {
    private val mutex = Mutex()
    private val enginesMutex = Mutex()
    private val down = mutableMapOf<TtsEngine, Long>()
    private val _available = MutableStateFlow(true)
    private val _active = MutableStateFlow(engines.firstOrNull()?.kind ?: SpeechEngineKind.NONE)

    @Volatile private var active: TtsEngine? = null
    @Volatile private var interrupted: CompletableDeferred<Unit>? = null

    val activeEngine: StateFlow<SpeechEngineKind> = _active.asStateFlow()
    override val available: StateFlow<Boolean> = _available.asStateFlow()

    init {
        require(leadSilenceMs in LEAD_SILENCE_RANGE) { "lead silence $leadSilenceMs ms" }
        // 켜지자마자 V30, V01을 말할 수 있어야 한다. 첫 음성까지 초기화를 미루면 첫 안내가 늦는다
        scope.launch { pickEngine() }
    }

    override suspend fun speak(text: String, priority: VoicePriority): SpeakResult = mutex.withLock {
        val stop = CompletableDeferred<Unit>()
        interrupted = stop
        // 6.3 거부: 재큐잉. 엔진 실패와 구분해야 통화 중에 온 V09가 버려지지 않는다
        val granted = focus.acquire(FocusPurpose.SPEAK) { stop.complete(Unit) }
        if (!granted) {
            interrupted = null
            return@withLock SpeakResult.DENIED
        }
        try {
            runChain(text, stop)
        } finally {
            interrupted = null
            active = null
            focus.release()
        }
    }

    private suspend fun runChain(text: String, stop: CompletableDeferred<Unit>): SpeakResult {
        while (true) {
            val engine = pickEngine() ?: return SpeakResult.FAILED
            when (val outcome = sayOnce(engine, text, stop)) {
                TtsOutcome.Done -> return SpeakResult.DONE
                TtsOutcome.Stopped -> return SpeakResult.INTERRUPTED
                is TtsOutcome.Failed -> {
                    if (outcome.failure == SpeechFailure.RETRY_ONCE) {
                        when (val again = sayOnce(engine, text, stop)) {
                            TtsOutcome.Done -> return SpeakResult.DONE
                            TtsOutcome.Stopped -> return SpeakResult.INTERRUPTED
                            is TtsOutcome.Failed -> markDead(engine)
                        }
                    } else {
                        markDead(engine)
                    }
                }
            }
        }
    }

    /**
     * 시도마다 새 재생이라 묵음을 다시 넣는다. 한 발화는 text 하나로 엔진에 한 번에 넘기므로(나누지 않는다)
     * 문장 사이에는 묵음이 끼지 않는다. 연속 음성에 추가 묵음을 넣지 않는다는 규칙이 여기서 지켜진다.
     */
    private suspend fun sayOnce(engine: TtsEngine, text: String, stop: CompletableDeferred<Unit>): TtsOutcome {
        val inEngine = engine.leadSilence == LeadSilence.IN_ENGINE
        // 재시도면 앞 시도의 엔진이 남아 있다. 비우지 않으면 묵음 대기 중 stopNow가 아직 말하지 않은 엔진에 stop을 보낸다
        active = null
        // ③ Pleos speak(text)는 Speech Service가 직접 재생해 앱이 오디오 앞에 묵음을 붙일 수 없다. 요청을 늦춘다
        if (!inEngine && withTimeoutOrNull(leadSilenceMs) { stop.await() } != null) return TtsOutcome.Stopped
        // 기다린 뒤에 잡는다. 아직 speak하지 않은 엔진에 stop을 보내면 Pleos가 늦은 onStop으로 다음 발화를 끊을 수 있다
        active = engine
        val finished = CompletableDeferred<TtsOutcome>()
        val outcome = try {
            coroutineScope {
                val saying = launch { finished.complete(engine.say(text, if (inEngine) leadSilenceMs else 0L)) }
                val stopping = launch {
                    stop.await()
                    guarded("tts stop", Unit) { engine.stop() }
                    finished.complete(TtsOutcome.Stopped)
                }
                // 완료 콜백이 오지 않아도 음성 큐가 멈추지 않게 한다. 한국어 1자 0.35초면 넉넉하다
                val result = withTimeoutOrNull(text.length * 350L + WATCHDOG_MARGIN_MS) { finished.await() }
                saying.cancel()
                stopping.cancel()
                result
            }
        } catch (e: CancellationException) {
            // 부른 쪽이 말하기를 취소했다. 포커스만 놓으면 Speech Service는 문장을 끝까지 말해 다른 소리와 겹친다.
            // stopNow 뒤의 취소(VoiceQueue 끊기)는 이미 멈췄다. 다시 보내면 Pleos가 쓸데없는 onStop을 또 보낸다
            if (!stop.isCompleted) guarded("tts stop", Unit) { engine.stop() }
            throw e
        }
        return outcome ?: run {
            guarded("tts stop", Unit) { engine.stop() }
            TtsOutcome.Failed(SpeechFailure.FATAL)
        }
    }

    private suspend fun pickEngine(): TtsEngine? = enginesMutex.withLock {
        for (engine in engines) {
            val downAt = down[engine]
            if (downAt != null && nowMs() - downAt < retryAfterMs) continue
            if (guarded("tts prepare", false) { engine.prepare() }) {
                down.remove(engine)
                _active.value = engine.kind
                _available.value = true
                return@withLock engine
            }
            // 초기화가 늦는 것일 수 있다. 해제하면 곧 끝날 초기화까지 잃는다
            down[engine] = nowMs()
        }
        _active.value = SpeechEngineKind.NONE
        _available.value = false
        null
    }

    private suspend fun markDead(engine: TtsEngine) = enginesMutex.withLock { markDeadLocked(engine) }

    private fun markDeadLocked(engine: TtsEngine) {
        down[engine] = nowMs()
        logInfo("tts engine down: ${engine.kind}")
        guarded("tts release", Unit) { engine.release() }
    }

    /** 시동 ON, 서비스 시작. 기다리지 말고 지금 다시 해 본다 */
    suspend fun revive() {
        enginesMutex.withLock { down.clear() }
        pickEngine()
    }

    override fun stopNow() {
        interrupted?.complete(Unit)
        guarded("tts stop", Unit) { active?.stop() }
    }

    fun release() {
        stopNow()
        focus.release()
        engines.forEach { guarded("tts release", Unit) { it.release() } }
    }

    private companion object {
        const val WATCHDOG_MARGIN_MS = 10_000L
    }
}
