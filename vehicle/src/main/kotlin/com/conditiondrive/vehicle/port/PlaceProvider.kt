package com.conditiondrive.vehicle.port

import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.Place

data class PlaceQuery(val fix: GeoFix, val radiusKm: Int, val highwayLikely: Boolean)
interface PlaceProvider {
    val id: String
    suspend fun nearby(query: PlaceQuery): List<Place>
}
