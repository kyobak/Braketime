package com.conditiondrive.vehicle.port

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import kotlinx.coroutines.flow.StateFlow

/** AAOS CarPropertyManager. PERF_VEHICLE_SPEED는 1Hz, 나머지는 onChange */
interface VehicleSignalsPort {
    val gear: StateFlow<Gear>
    val speedKmh: StateFlow<Double?>        // 권한 없음이면 null. 음수는 절댓값
    val ignition: StateFlow<Ignition>
    val capabilities: StateFlow<VehicleCapabilities>
    fun start(); fun stop()
}
data class VehicleCapabilities(val gear: Boolean, val speed: Boolean, val ignition: Boolean)
