package com.conditiondrive.core.poi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun distanceMatchesKnownValues() {
        assertEquals(0, Geo.distanceM(36.0, 128.0, 36.0, 128.0))
        // 위도 1도는 약 111.2km
        assertEquals(111_195.0, Geo.distanceM(36.0, 128.0, 37.0, 128.0).toDouble(), 5.0)
        // 서울시청-부산시청 대권거리는 약 325km
        assertEquals(325_000.0, Geo.distanceM(37.5665, 126.9780, 35.1798, 129.0750).toDouble(), 3_000.0)
    }

    @Test
    fun bearingIsNormalizedClockwiseFromNorth() {
        assertEquals(0.0, Geo.bearingDeg(36.0, 128.0, 37.0, 128.0), 1e-9)
        assertEquals(180.0, Geo.bearingDeg(36.0, 128.0, 35.0, 128.0), 1e-9)
        assertEquals(90.0, Geo.bearingDeg(36.0, 128.0, 36.0, 128.001), 1e-3)
        assertEquals(270.0, Geo.bearingDeg(36.0, 128.0, 36.0, 127.999), 1e-3)
        for (lon in listOf(127.0, 127.5, 128.5, 129.0)) {
            val bearing = Geo.bearingDeg(36.0, 128.0, 35.5, lon)
            assertTrue(bearing >= 0.0 && bearing < 360.0, "$bearing")
        }
    }
}
