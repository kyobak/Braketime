package com.conditiondrive.vehicle.service

import com.conditiondrive.core.contract.PairingCodeResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException

internal enum class DeletionKind { UNPAIR, ACCOUNT }

internal sealed interface DeletionState {
    data object Idle : DeletionState
    data class Pending(val kind: DeletionKind) : DeletionState
    data class Done(val kind: DeletionKind) : DeletionState
}

internal interface DeletionStore {
    var pending: DeletionKind?
    val baseUrl: String
    val token: String?
    fun onAccountDeleted()
}

/**
 * S5 연결 해제와 서버 데이터 삭제. 터널이나 주차장에서 눌러도 서버에 닿을 때까지 잊지 않는다.
 * 토큰은 서버가 받은 뒤에만 지운다. 먼저 지우면 다시 지울 수단이 없고 폰은 계속 올린다.
 * 폰 앱의 RemoteDeletion과 같은 구조다.
 */
internal class ServerDeletion(
    private val remote: suspend (kind: DeletionKind, baseUrl: String, token: String) -> Boolean,
    private val store: DeletionStore,
    private val scope: CoroutineScope,
    private val retryDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000),
) {
    private val _state = MutableStateFlow<DeletionState>(store.pending?.let { DeletionState.Pending(it) } ?: DeletionState.Idle)
    val state: StateFlow<DeletionState> = _state

    private var retryJob: Job? = null

    /** 계정 삭제가 연결 해제를 포함한다. 더 넓은 요청이 남는다 */
    fun request(kind: DeletionKind) {
        store.pending = if (store.pending == DeletionKind.ACCOUNT) DeletionKind.ACCOUNT else kind
        retryJob?.cancel()
        retryJob = scope.launch { drainWithRetry() }
    }

    /** 서비스 시작. 재시작 전에 끝내지 못한 삭제를 이어 간다 */
    fun resume() {
        if (store.pending == null || retryJob?.isActive == true) return
        retryJob = scope.launch { drainWithRetry() }
    }

    private suspend fun drainWithRetry() {
        var attempt = 0
        while (!drain()) {
            delay(retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)])
            attempt++
        }
    }

    /** 남은 삭제가 없으면 true. 새 연결 코드를 받기 전에도 부른다 */
    suspend fun drain(): Boolean {
        val kind = store.pending ?: return true
        val token = store.token
        if (token != null && !remote(kind, store.baseUrl, token)) {
            _state.value = DeletionState.Pending(kind)
            return false
        }
        if (kind == DeletionKind.ACCOUNT) store.onAccountDeleted()
        store.pending = null
        _state.value = DeletionState.Done(kind)
        return true
    }
}

internal object PairingRule {
    /**
     * 이번 코드로 연결된 것인지. 해제를 아직 전하지 못한 옛 폰이 남아 있으면 paired=true만으로는
     * 코드를 스캔하지 않았는데도 연결된 것으로 보게 된다. 옛 폰이 있었으면 연결 시각이 코드 발급 뒤인지 본다.
     */
    fun consumed(code: PairingCodeResponse, status: PairingStatusResponse): Boolean {
        if (!status.paired) return false
        if (!code.replacesPhone) return true
        val pairedAt = instant(status.phone?.pairedAt) ?: return false
        val issuedAt = instant(code.serverTime) ?: return false
        return pairedAt >= issuedAt
    }

    private fun instant(text: String?): Instant? = try {
        text?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }
}
