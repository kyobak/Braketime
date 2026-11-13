package com.conditiondrive.vehicle.port

import com.conditiondrive.core.poi.GeoFix
import kotlinx.coroutines.flow.StateFlow

/** 지금 위치 값을 주는 공급자(10.2 체인). 대체 경로를 FusedLocation처럼 표시하지 않으려고 나눈다 */
enum class LocationVia { FUSED, NAVI, DEVICE, NONE }

interface LocationPort {
    val fix: StateFlow<GeoFix?>             // 위치 불가면 null
    fun start(); fun stop()
}
