package com.conditiondrive.vehicle.port

import kotlinx.coroutines.flow.StateFlow

sealed interface ListenResult {
    data class Heard(val text: String) : ListenResult
    data object NoSpeech : ListenResult     // 8초 침묵
    data class Failed(val retriable: Boolean) : ListenResult
}
interface ListenerPort {
    val available: StateFlow<Boolean>
    /** 지금 물어도 되는지. 실패했던 엔진을 다시 살펴볼 기회이기도 하다 */
    suspend fun ready(): Boolean = available.value
    /** onReady는 마이크가 열린 순간 한 번 불린다. 그 전의 말은 들리지 않는다(6.3) */
    suspend fun listen(timeoutMs: Long = 8000, onReady: () -> Unit = {}): ListenResult
    fun stopNow()
}
