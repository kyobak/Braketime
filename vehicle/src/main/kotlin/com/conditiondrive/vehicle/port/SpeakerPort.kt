package com.conditiondrive.vehicle.port

import com.conditiondrive.core.voice.VoicePriority
import kotlinx.coroutines.flow.StateFlow

/** DENIED는 오디오 포커스를 받지 못한 것이다. 엔진은 멀쩡하므로 큐가 다시 시도한다(6.3) */
enum class SpeakResult { DONE, INTERRUPTED, DENIED, FAILED }
interface SpeakerPort {
    val available: StateFlow<Boolean>
    suspend fun speak(text: String, priority: VoicePriority): SpeakResult   // 포커스 획득~해제 포함
    fun stopNow()
}
