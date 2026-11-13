package com.conditiondrive.vehicle.poi

import com.conditiondrive.core.poi.Geo
import com.conditiondrive.core.poi.Place
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery

/** 시연 중 첫 공급자(F3). 시나리오 고정 좌표에서 항상 같은 장소가 나와야 녹화가 재현된다. */
class ScriptedPlaceProvider(private val places: List<Place>) : PlaceProvider {
    override val id: String = "scripted"

    override suspend fun nearby(query: PlaceQuery): List<Place> {
        val limitM = query.radiusKm * 1000
        val fix = query.fix
        return places.filter { Geo.distanceM(fix.latitude, fix.longitude, it.latitude, it.longitude) <= limitM }
    }
}
