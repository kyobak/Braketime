package com.conditiondrive.vehicle.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 런타임이 시간을 기다리는 방식. 시연은 시계와 입력 포트만 바꾸므로(규칙 6) 판단 경로에 분기를 두지 않고 여기로만 끼어든다.
 */
interface RuntimePacer {
    /** 엔진 시각 차이를 실제로 얼마나 기다릴지 */
    fun realDelayMs(engineDeltaMs: Long): Long
    fun onNextDeadline(deadlineMs: Long?)
    fun onSpeechBusy(busy: Boolean)
}

object RealTimePacer : RuntimePacer {
    override fun realDelayMs(engineDeltaMs: Long): Long = engineDeltaMs
    override fun onNextDeadline(deadlineMs: Long?) = Unit
    override fun onSpeechBusy(busy: Boolean) = Unit
}

class RuntimeHooks(
    val bootCount: () -> Int = { 0 },                   // Settings.Global.BOOT_COUNT. 포트가 없어 함수로 받는다
    val pacer: RuntimePacer = RealTimePacer,
    val demoLabel: StateFlow<String?> = MutableStateFlow(null),
    val isFirstRun: suspend () -> Boolean = { false },  // 온보딩 전이면 true. V30 판단에 쓴다
    val checkinHeld: StateFlow<Boolean> = MutableStateFlow(false),  // 온보딩 중이면 true. V01을 미룬다
    val onError: (Throwable) -> Unit = {},
)
