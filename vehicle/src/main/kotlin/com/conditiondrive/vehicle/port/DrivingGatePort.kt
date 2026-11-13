package com.conditiondrive.vehicle.port

import kotlinx.coroutines.flow.StateFlow

/** CarUxRestrictionsManager + VehicleSignalsPort */
enum class UiMode { DRIVING, PARKED }
data class UxLimits(val noKeyboard: Boolean, val noSetup: Boolean, val limitContent: Boolean, val limitStringLength: Boolean)
interface DrivingGatePort {
    val uiMode: StateFlow<UiMode>           // 미연결·미확인은 DRIVING. PARKED는 8.1 조건 3초 유지
    val limits: StateFlow<UxLimits>
    fun start(); fun stop()
}
