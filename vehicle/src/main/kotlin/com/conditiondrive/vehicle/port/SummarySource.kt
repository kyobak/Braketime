package com.conditiondrive.vehicle.port

import com.conditiondrive.core.engine.ConditionInput
import kotlinx.coroutines.flow.StateFlow

/**
 * unpaired는 S5에서 사용자가 직접 끊었거나 처음부터 연결이 없는 경우다(E33).
 * linkLost는 폰 쪽 해제나 401처럼 차량 밖에서 끊긴 경우다. 값을 모르게 된 것이지 회복이 아니라서 값 없음 경로로 간다.
 */
data class SummarySnapshot(
    val input: ConditionInput?,
    val unpaired: Boolean,
    val receivedAtMs: Long?,
    val phoneName: String?,
    val linkLost: Boolean = false,
)
enum class LinkState { UNPAIRED, FRESH, OFFLINE }
interface SummarySource {
    val snapshot: StateFlow<SummarySnapshot>
    val link: StateFlow<LinkState>
    fun start(); fun stop()
    suspend fun fetchNow(timeoutMs: Long = 3000)
}
