package com.conditiondrive.vehicle.service

import com.conditiondrive.core.engine.Ignition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 10.3 "IGNITION_STATE 콜백". 서비스가 OFF로 내려가도 프로세스가 살아 있으면 ON을 받아 다시 띄운다.
 * 백그라운드 FGS 시작은 거부될 수 있어 실패를 알려 13장 "자동 실행 실패"로 넘긴다.
 * 차량이 시동 속성을 주지 않으면 값이 바뀌지 않아 아무 일도 하지 않는다.
 */
internal class IgnitionRestarter(
    private val ignition: StateFlow<Ignition>,
    private val scope: CoroutineScope,
    private val isRunning: () -> Boolean,
    private val startService: () -> Boolean,
    private val onStartFailed: () -> Unit,
) {
    private var job: Job? = null

    /** 내려가는 동안 온 ON은 위에서 건너뛴다. 다 내려간 뒤 다시 봐야 이번 운행을 놓치지 않는다 */
    fun onServiceStopped() {
        if (ignition.value != Ignition.OFF && !isRunning() && !startService()) onStartFailed()
    }

    fun watch() {
        if (job != null) return
        job = scope.launch {
            var previous = ignition.value
            ignition.collect { now ->
                if (previous == Ignition.OFF && now == Ignition.ON && !isRunning() && !startService()) onStartFailed()
                previous = now
            }
        }
    }
}
