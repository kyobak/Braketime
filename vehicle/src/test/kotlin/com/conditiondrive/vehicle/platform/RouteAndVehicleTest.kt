package com.conditiondrive.vehicle.platform

import com.conditiondrive.vehicle.port.RouteSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RouteAndVehicleTest {
    @Test fun trackerTreatsZeroRemainingAsUnknown() {
        var last: RouteSnapshot? = null
        val tracker = RouteTracker { last = it }
        assertNull(last)
        tracker.guiding(true)
        tracker.remaining(1620, 26000)
        assertEquals(RouteSnapshot(true, 1620, 26000, 0), last)
        tracker.remaining(0, 0)
        assertEquals(RouteSnapshot(true, null, null, 0), last)
    }

    @Test fun trackerClearsWhenGuidanceEnds() {
        var last: RouteSnapshot? = null
        val tracker = RouteTracker { last = it }
        tracker.guiding(true)
        tracker.remaining(100, 200)
        tracker.waypoints(2)
        assertEquals(2, last?.waypointCount)
        tracker.guiding(false)
        assertEquals(RouteSnapshot(false, null, null, 0), last)
    }

    @Test fun occupancyRule() {
        assertEquals(true, OccupancyRule.passengerPresent(listOf(false, true, null)))
        assertEquals(false, OccupancyRule.passengerPresent(listOf(false, false)))
        // F11: 모르면 있음으로 간주하는 쪽(null)으로 넘긴다
        assertNull(OccupancyRule.passengerPresent(listOf(false, null)))
        // 값의 방향을 확인하기 전에는 무엇이 와도 모름이다. 뒤집혀 있으면 동승자 앞에서 수치가 나온다
        assertEquals(false, OccupancyRule.SEAT_VALUE_DIRECTION_CONFIRMED)
        assertNull(OccupancyRule.reported(listOf(false, false)))
        assertEquals(false, OccupancyRule.reported(listOf(false, false), confirmed = true))
        assertNull(OccupancyRule.passengerPresent(emptyList()))
    }

    @Test fun vehicleCallReturnsValue() = runTest {
        assertEquals(1.5f, vehicleCall<Float>("t") { ok, _ -> ok(1.5f) })
    }

    @Test fun vehicleCallRetriesThenGivesUp() = runTest {
        var calls = 0
        val result = vehicleCall<Float>("t", attempts = 3) { _, fail ->
            calls++
            fail(IllegalStateException("not connected"))
        }
        assertNull(result)
        assertEquals(3, calls)
    }

    @Test fun vehicleCallSucceedsAfterFirstFailure() = runTest {
        var calls = 0
        val result = vehicleCall<Int>("t") { ok, fail ->
            calls++
            if (calls == 1) fail(IllegalStateException("connecting")) else ok(7)
        }
        assertEquals(7, result)
    }

    @Test fun vehicleCallSurvivesThrowAndSilence() = runTest {
        assertNull(vehicleCall<Int>("throw", attempts = 1) { _, _ -> throw SecurityException("denied") })
        // 콜백이 오지 않는 차종에서도 시작이 멈추지 않는다
        assertNull(vehicleCall<Int>("silent", attempts = 1) { _, _ -> })
    }

    @Test fun guardedSwallowsLinkageErrors() {
        assertEquals("대체", guarded("t", "대체") { throw NoClassDefFoundError("android/car/Car") })
    }
}
