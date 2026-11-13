package com.conditiondrive.vehicle.poi

import android.content.Context
import com.conditiondrive.core.poi.Geo
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceCatalog
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery

/** 항상 쓰는 내장 POI(F3-6). 통신이 없어도 돌아야 하므로 네트워크를 타지 않는다. */
class BundledPlaceProvider(loadText: () -> String) : PlaceProvider {
    override val id: String = "bundled"

    // 시동 때마다 읽을 필요가 없고, 정차 권고가 나기 전에는 읽을 일도 없다
    private val places: List<Place> by lazy { PlaceCatalog.parse(loadText()) }

    override suspend fun nearby(query: PlaceQuery): List<Place> {
        val limitM = query.radiusKm * 1000
        val fix = query.fix
        return places.filter { Geo.distanceM(fix.latitude, fix.longitude, it.latitude, it.longitude) <= limitM }
    }

    companion object {
        private const val ASSET = "poi.json"

        fun fromAssets(context: Context): BundledPlaceProvider {
            val assets = context.applicationContext.assets
            return BundledPlaceProvider { assets.open(ASSET).bufferedReader().use { it.readText() } }
        }
    }
}
