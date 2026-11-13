package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.time.Clock
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.SummarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class SummaryCredentials(val baseUrl: String, val vehicleToken: String)

/**
 * 연결 정보. 저장 방식은 storage 몫이고 여기서는 알 필요가 없다.
 * credentials가 null이면 1단계 사용자이며 어떤 호출도 하지 않는다(12.1, E7).
 */
interface SummaryLink {
    val credentials: StateFlow<SummaryCredentials?>
    /** 401. 토큰과 요약을 지운다 */
    suspend fun onTokenRevoked()
    /** paired=false. 토큰은 두고 폰 연결만 끊긴 것으로 표시한다. S5에서 다시 연결하면 credentials가 돌아온다 */
    suspend fun onPhoneUnpaired()
    /** 재시작한 뒤 서버에 닿기 전에도 S5가 연결을 "없음"으로 말하지 않게 마지막 이름을 남긴다 */
    val lastPhoneName: String? get() = null
    fun rememberPhoneName(name: String) {}
}

sealed interface TransportResult {
    data class Response(val status: Int, val body: String, val retryAfterSec: Long? = null) : TransportResult
    data object NetworkError : TransportResult
}

fun interface SummaryTransport {
    suspend fun fetchLatest(credentials: SummaryCredentials): TransportResult
}

/** 12.8 차량 폴링. 시각은 응답 안의 값으로만 계산한다(10.1 규칙 5). */
class RemoteSummarySource(
    private val transport: SummaryTransport,
    private val summaryLink: SummaryLink,
    private val ignition: StateFlow<Ignition>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val policy: Policy = Policy.DEFAULT,
) : SummarySource {
    private val initiallyUnpaired = summaryLink.credentials.value == null
    private val _snapshot = MutableStateFlow(SummarySnapshot(null, unpaired = initiallyUnpaired, receivedAtMs = null, phoneName = summaryLink.lastPhoneName.takeUnless { initiallyUnpaired }))
    // 폰 쪽 해제와 401. 곧이어 credentials가 비어도 사용자가 직접 끊은 것으로 보면 안 된다
    private var lostRemotely = false
    override val snapshot: StateFlow<SummarySnapshot> = _snapshot
    private val _link = MutableStateFlow(if (initiallyUnpaired) LinkState.UNPAIRED else LinkState.OFFLINE)
    override val link: StateFlow<LinkState> = _link

    private var pollJob: Job? = null
    private var failures = 0

    private enum class Outcome { OK, FAILED, RATE_LIMITED, STOP }
    private var retryAfterSec = 0L

    override fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            combine(ignition, summaryLink.credentials) { on, credentials -> (on != Ignition.OFF) to credentials }
                .collectLatest { (on, credentials) ->
                    if (credentials == null) {
                        markUnpaired()
                    } else {
                        lostRemotely = false
                    }
                    if (on && credentials != null) poll()
                }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    override suspend fun fetchNow(timeoutMs: Long) {
        fetchOnce(timeoutMs)
    }

    private suspend fun poll() {
        failures = 0
        var outcome = fetchOnce(policy.FIRST_FETCH_TIMEOUT_MS)
        while (outcome != Outcome.STOP) {
            delay(nextDelayMs(outcome))
            outcome = fetchOnce(policy.HTTP_TIMEOUT_MS.call)
        }
    }

    private fun nextDelayMs(outcome: Outcome): Long = when (outcome) {
        Outcome.OK -> {
            failures = 0
            val jitter = random.nextLong(-policy.JITTER, policy.JITTER + 1)
            (policy.POLL_SEC + jitter) * 1000
        }
        Outcome.RATE_LIMITED -> retryAfterSec.coerceIn(1, MAX_RETRY_AFTER_SEC) * 1000
        else -> {
            val backoff = policy.POLL_BACKOFF_SEC
            val sec = backoff.getOrElse(failures) { backoff.last() }
            failures++
            sec * 1000
        }
    }

    private suspend fun fetchOnce(timeoutMs: Long): Outcome {
        val credentials = summaryLink.credentials.value ?: return Outcome.STOP
        val result = try {
            withTimeoutOrNull(timeoutMs) { transport.fetchLatest(credentials) }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
        if (result !is TransportResult.Response) return failed()
        return when (result.status) {
            200 -> onBody(result.body)
            401 -> {
                lostRemotely = true
                summaryLink.onTokenRevoked()
                clearSummary()
                Outcome.STOP
            }
            429 -> {
                retryAfterSec = result.retryAfterSec ?: policy.POLL_SEC
                failed(Outcome.RATE_LIMITED)
            }
            else -> failed()
        }
    }

    private suspend fun onBody(body: String): Outcome =
        when (val decoded = SummaryDecoder.decode(body, clock.nowMs())) {
            is SummaryDecoder.Decoded.Snapshot -> {
                decoded.snapshot.phoneName?.let(summaryLink::rememberPhoneName)
                _snapshot.value = decoded.snapshot
                _link.value = LinkState.FRESH
                Outcome.OK
            }
            SummaryDecoder.Decoded.Unpaired -> {
                lostRemotely = true
                summaryLink.onPhoneUnpaired()
                clearSummary()
                Outcome.STOP
            }
            // 망가진 응답을 값 없음으로 넣으면 혈당 끊김으로 말하게 된다. 끊긴 통신과 같게 보고 자체 만료에 맡긴다
            SummaryDecoder.Decoded.Invalid -> failed()
        }

    /** 13장 통신 끊김: 상태 변화 없음. 혈당과 수면은 엔진이 각자의 만료로 처리한다 */
    private fun failed(outcome: Outcome = Outcome.FAILED): Outcome {
        _link.value = LinkState.OFFLINE
        return outcome
    }

    private fun clearSummary() = markUnpaired()

    private fun markUnpaired() {
        _snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null, linkLost = lostRemotely)
        _link.value = LinkState.UNPAIRED
    }

    private companion object {
        const val MAX_RETRY_AFTER_SEC = 300L    // 12.1 429, 최대 5분
    }
}
