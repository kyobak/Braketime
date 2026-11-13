package com.conditiondrive.vehicle.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Vehicle SDK Odometer. 공백 판별과 F8 */
interface OdometerPort {
    suspend fun totalKm(): Double?          // 미지원이면 null
}

/** Vehicle SDK HVAC. F10 */
enum class AirMode { FRESH, RECIRCULATE }
interface VentilationPort {
    suspend fun supported(): Boolean        // capability WRITE 포함, AutoRecirculation 꺼짐
    suspend fun current(): AirMode?
    suspend fun set(mode: AirMode): Boolean
    val changes: Flow<AirMode>              // 운전자 수동 변경 감지
}

/** Vehicle SDK Seat. F11. 정보 없음이면 null */
interface OccupancyPort {
    val passengerPresent: StateFlow<Boolean?>
    fun start(); fun stop()
}
