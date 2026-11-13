package com.conditiondrive.companion.upload

import com.conditiondrive.companion.storage.CompanionStore
import com.conditiondrive.companion.storage.TokenVault
import com.conditiondrive.core.contract.SummaryValidator
import com.conditiondrive.core.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** 화면이 문장으로 바꾸는 전송 상태. 서버 code와 메시지는 여기서 끝난다 */
sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Retrying : UploadStatus           // 통신 실패, 서버 오류, 호출 제한. 다음 시도를 기다린다
    data object Delivered : UploadStatus
    data object Rejected : UploadStatus           // 재시도 금지 응답. 다음 측정에서 다시 만든다
    data object ClockOff : UploadStatus           // 보정 후에도 시계 차이로 거절됨
}

sealed interface UploadStep {
    data object Nothing : UploadStep
    data object Done : UploadStep
    data class Wait(val delayMs: Long) : UploadStep
}

/**
 * 11.3 전송 규칙. 슬롯은 최신 1건뿐이고, 값이 아니라 입력을 담아 보내기 직전에 다시 만든다.
 * 그래서 재시도 때 valid_until이 지났으면 혈당이 자연히 null이 된다.
 */
class UploadCoordinator(
    private val api: CompanionApi,
    private val store: CompanionStore,
    private val tokens: TokenVault,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val onUnauthorized: suspend () -> Unit,
    private val onConsentRequired: suspend () -> Unit,
) {
    private val slot = MutableStateFlow<SummaryInput?>(null)
    private val _status = MutableStateFlow<UploadStatus>(UploadStatus.Idle)
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    private val sending = Mutex()
    private var failures = 0

    fun offer(input: SummaryInput) { slot.value = input }

    fun clear() {
        slot.value = null
        failures = 0
        _status.value = UploadStatus.Idle
    }

    /** 슬롯에 값이 들어올 때만 네트워크를 쓴다. 미연결 사용자는 슬롯이 비어 있어 호출이 0회다(12.1) */
    suspend fun run() {
        while (true) {
            slot.first { it != null }
            when (val step = attempt()) {
                is UploadStep.Wait -> delay(step.delayMs)
                // 조작이 몰려도 서버 제한(기기당 20건/분)에 닿지 않게 간격을 둔다
                else -> delay(MIN_INTERVAL_MS)
            }
        }
    }

    /** 진행 중인 전송이 끝난 뒤에 돌아온다. 서버의 요약을 지우기 전에 불러야 지운 값이 뒤늦은 전송으로 되살아나지 않는다 */
    suspend fun clearAndWait() = sending.withLock { clear() }

    suspend fun attempt(): UploadStep = sending.withLock {
        val input = slot.value ?: return UploadStep.Nothing
        val state = store.read()
        val token = tokens.current()
        if (state.uploadPaused || token == null) {
            slot.compareAndSet(input, null)
            return UploadStep.Nothing
        }
        send(input, token, state.clockOffsetMs, skewRetryLeft = true)
    }

    private suspend fun send(input: SummaryInput, token: String, offsetMs: Long, skewRetryLeft: Boolean): UploadStep {
        val now = clock.nowMs() + offsetMs
        val summary = SummaryBuilder.build(input, now, zone())
        // 시뮬레이터 범위라 걸릴 일이 없어야 한다. 걸리면 보내지 않는다. 서버도 같은 검사로 거절한다
        if (SummaryValidator.validate(summary, now, allowPartner = false).isNotEmpty()) {
            return finish(input, UploadStatus.Rejected)
        }
        return when (val result = api.postSummary(token, summary)) {
            is ApiResult.Ok -> {
                store.update { it.copy(lastUploadAtMs = now, vehicleLastSeenAtMs = result.value.vehicleLastSeenAt?.let(::parseRfc3339)) }
                finish(input, UploadStatus.Delivered)
            }
            ApiResult.NetworkError -> backoff()
            is ApiResult.Failed -> onFailed(result, input, token, offsetMs, skewRetryLeft)
        }
    }

    private suspend fun onFailed(
        failed: ApiResult.Failed,
        input: SummaryInput,
        token: String,
        offsetMs: Long,
        skewRetryLeft: Boolean,
    ): UploadStep = when {
        failed.status == 401 -> {
            clear()
            onUnauthorized()
            UploadStep.Done
        }
        failed.status == 403 && failed.code == CONSENT_REQUIRED -> {
            clear()
            onConsentRequired()
            UploadStep.Done
        }
        failed.status == 429 -> {
            _status.value = UploadStatus.Retrying
            UploadStep.Wait(minOf((failed.retryAfterSec ?: DEFAULT_RETRY_AFTER_SEC) * 1000, MAX_RETRY_AFTER_MS))
        }
        failed.status >= 500 -> backoff()
        failed.code == CLOCK_SKEW -> onClockSkew(failed, input, token, offsetMs, skewRetryLeft)
        else -> finish(input, UploadStatus.Rejected)
    }

    /** 12.1 "clock_skew만 보정 후 1회". 서버 시각이 없으면 보정할 수 없어 재시도하지 않는다 */
    private suspend fun onClockSkew(
        failed: ApiResult.Failed,
        input: SummaryInput,
        token: String,
        offsetMs: Long,
        skewRetryLeft: Boolean,
    ): UploadStep {
        val serverMs = failed.serverTimeMs
        if (!skewRetryLeft || serverMs == null) return finish(input, UploadStatus.ClockOff)
        val newOffset = serverMs - clock.nowMs()
        store.update { it.copy(clockOffsetMs = newOffset) }
        val shifted = input.shifted(newOffset - offsetMs)
        // 재시도 중 더 새 값이 왔으면 그 값은 이미 새 보정이 아니라 옛 보정 기준이다. 같이 옮겨 둔다
        val queued = slot.value
        if (queued != null && queued != input) slot.compareAndSet(queued, queued.shifted(newOffset - offsetMs))
        else slot.compareAndSet(input, shifted)
        return send(shifted, token, newOffset, skewRetryLeft = false)
    }

    private fun finish(input: SummaryInput, status: UploadStatus): UploadStep {
        failures = 0
        // 보내는 동안 더 새 값이 왔으면 지우지 않는다. 다음 루프가 그 값을 보낸다
        slot.compareAndSet(input, null)
        _status.value = status
        return UploadStep.Done
    }

    private fun backoff(): UploadStep {
        val wait = BACKOFF_SEC[minOf(failures, BACKOFF_SEC.lastIndex)]
        failures++
        _status.value = UploadStatus.Retrying
        return UploadStep.Wait(wait * 1000)
    }

    companion object {
        const val MIN_INTERVAL_MS = 3_000L
        const val MAX_RETRY_AFTER_MS = 300_000L
        private const val DEFAULT_RETRY_AFTER_SEC = 60L
        private const val CLOCK_SKEW = "clock_skew"
        private const val CONSENT_REQUIRED = "consent_required"
        val BACKOFF_SEC = longArrayOf(15, 30, 60)
    }
}

internal fun parseRfc3339(text: String): Long? = try {
    java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
} catch (_: java.time.format.DateTimeParseException) {
    null
}
