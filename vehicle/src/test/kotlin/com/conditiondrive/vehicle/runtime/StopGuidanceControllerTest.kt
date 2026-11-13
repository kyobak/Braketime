package com.conditiondrive.vehicle.runtime

import kotlin.test.assertFalse
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StopGuidanceControllerTest {
    private class Rig(
        scope: TestScope,
        providers: List<PlaceProvider>,
        fix: GeoFix? = TEST_FIX,
        speed: Double? = 80.0,
    ) {
        val location = FakeLocation(fix)
        val navi = FakeNavi()
        val signals = FakeSignals(speed = speed)
        val controller = StopGuidanceController(providers, location, navi, signals, scope.virtualClock(), scope.backgroundScope)
    }

    @Test
    fun `P1 전방 쉼터를 고른다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        assertEquals("a", r.controller.resolve()?.place?.id)
        assertEquals("a", r.controller.current.value?.place?.id)
    }

    @Test
    fun `P2 후방은 제외`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeNorth("back", 1.0), placeSouth("front", 3.2)))))
        assertEquals("front", r.controller.resolve()?.place?.id)
    }

    @Test
    fun `P5 80km 전방 1km는 진입 불가로 제외`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("near", 1.0)))))
        assertNull(r.controller.resolve())
    }

    @Test
    fun `P6 차속 null 전방 400m는 하한으로 제외`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("near", 0.4)))), speed = null)
        assertNull(r.controller.resolve())
    }

    @Test
    fun `P7 5km에 없으면 반경을 넓혀 10km에서 찾는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("far", 8.0)))))
        val found = r.controller.resolve()
        assertEquals("far", found?.place?.id)
    }

    @Test
    fun `P8 진행 방향 null이면 방향 필터를 건너뛴다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeNorth("north", 3.0)))), fix = TEST_FIX.copy(headingDeg = null))
        assertEquals("north", r.controller.resolve()?.place?.id)
    }

    @Test
    fun `P9 경로가 직선의 2배를 넘으면 다음 후보`() = runTest {
        val first = placeSouth("first", 2.0)
        val second = placeSouth("second", 3.0)
        val r = Rig(this, listOf(FakeProvider(listOf(first, second))))
        r.navi.routeM["first"] = 4200
        r.navi.routeM["second"] = 3100
        assertEquals("second", r.controller.resolve()?.place?.id)
    }

    @Test
    fun `P10 측정 실패면 직선 거리로 진행`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))))
        val found = r.controller.resolve()
        assertEquals("a", found?.place?.id)
    }

    @Test
    fun `측정을 건너뛰면 경로가 길어도 첫 후보다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))))
        r.navi.routeM["a"] = 100_000
        assertEquals("a", r.controller.resolve(measureRoute = false)?.place?.id)
    }

    @Test
    fun `P11 전부 없으면 30초 뒤 재검색`() = runTest {
        val provider = object : PlaceProvider {
            override val id = "later"
            var places = emptyList<com.conditiondrive.core.poi.Place>()
            override suspend fun nearby(query: PlaceQuery) = places
        }
        val r = Rig(this, listOf(provider))
        val found = mutableListOf<PlaceCandidate>()
        assertNull(r.controller.resolve())
        r.controller.startRetry { found += it }
        provider.places = listOf(placeSouth("a", 3.2))
        advanceTimeBy(29_000)
        runCurrent()
        assertTrue(found.isEmpty())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("a", found.single().place.id)
    }

    @Test
    fun `P12 위치가 돌아오면 즉시 재시도`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))), fix = null)
        val found = mutableListOf<PlaceCandidate>()
        assertNull(r.controller.resolve())
        r.controller.startRetry { found += it }
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(found.isEmpty())
        r.location.fix.value = TEST_FIX
        runCurrent()
        assertEquals("a", found.single().place.id)
    }

    @Test
    fun `재검색을 취소하면 더 찾지 않는다`() = runTest {
        val provider = FakeProvider(emptyList())
        val r = Rig(this, listOf(provider))
        r.controller.startRetry { error("불리면 안 된다") }
        r.controller.cancelRetry()
        val before = provider.queries
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(before, provider.queries)
    }

    @Test
    fun `P13 경유지 2개 참이면 결과를 그대로 돌려준다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        r.navi.outcome = GuideOutcome.SLOTS_FULL
        r.controller.resolve()
        assertEquals(GuideOutcome.SLOTS_FULL, r.controller.startGuidance(auto = false))
    }

    @Test
    fun `P14 P15 안내 결과 ROUTE_STARTED 뒤 같은 장소는 재추가하지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        r.navi.outcome = GuideOutcome.ROUTE_STARTED
        r.controller.resolve()
        assertEquals(GuideOutcome.ROUTE_STARTED, r.controller.startGuidance(auto = false))
        assertEquals(GuideOutcome.ALREADY_WAYPOINT, r.controller.startGuidance(auto = true))
        assertEquals(1, r.navi.guided.size)
    }

    @Test
    fun `P16 30초 무응답이면 FAILED`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        r.navi.guideDelayMs = 31_000
        r.controller.resolve()
        assertEquals(GuideOutcome.FAILED, r.controller.startGuidance(auto = false))
    }

    @Test
    fun `P16 실측 범위 25초 응답은 실패로 보지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        r.navi.guideDelayMs = 25_000
        r.controller.resolve()
        assertTrue(r.controller.startGuidance(auto = false) != GuideOutcome.FAILED)
    }

    @Test
    fun `내비가 없으면 UNSUPPORTED이고 호출하지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        r.navi.available.value = false
        r.controller.resolve()
        assertEquals(GuideOutcome.UNSUPPORTED, r.controller.startGuidance(auto = false))
        assertTrue(r.navi.guided.isEmpty())
    }

    @Test
    fun `E1 F3-6 외부 공급자가 실패해도 다음 공급자 결과를 쓴다`() = runTest {
        val broken = FakeProvider(listOf(placeSouth("x", 3.0))).also { it.failing = true }
        val bundled = FakeProvider(listOf(placeSouth("a", 3.2)))
        val r = Rig(this, listOf(broken, bundled))
        assertEquals("a", r.controller.resolve()?.place?.id)
    }

    @Test
    fun `공급자 순서는 같은 반경 안에서만 우선한다`() = runTest {
        val scripted = FakeProvider(listOf(placeSouth("far", 8.0)))
        val bundled = FakeProvider(listOf(placeSouth("near", 3.0)))
        val r = Rig(this, listOf(scripted, bundled))
        assertEquals("near", r.controller.resolve()?.place?.id)
    }

    @Test
    fun `표시 거리를 차 위치로 갱신한다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.2)))))
        val found = assertNotNull(r.controller.resolve())
        r.location.fix.value = TEST_FIX.copy(latitude = TEST_FIX.latitude - 1.2 / 111.195)
        r.controller.refreshDistance()
        val updated = assertNotNull(r.controller.current.value)
        assertTrue(updated.straightDistanceM < found.straightDistanceM - 1000)
    }

    /** 묻지 않고 넣는 경유지다. 측정이 우회라고 하면 맞은편 시설로 보고 넣지 않는다 */
    @Test
    fun `P19 혈당 자동 안내는 우회 후보를 버리고 다음 후보로 간다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("near", 2.0), placeSouth("far", 3.0)))))
        r.navi.routeM["near"] = 30_000
        r.navi.routeM["far"] = 3_100
        r.controller.resolveForGlucose()
        assertEquals("near", r.controller.current.value?.place?.id)
        assertEquals(GuideOutcome.WAYPOINT_ADDED, r.controller.startGuidance(auto = true))
        assertEquals(listOf("far"), r.navi.guided.map { it.id })
        assertEquals("far", r.controller.current.value?.place?.id)
        assertEquals(3_100, r.controller.current.value?.routeDistanceM)
    }

    @Test
    fun `P20 측정 실패에 방향 표기도 없으면 경유지를 넣지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))))
        r.controller.resolveForGlucose()
        assertEquals(GuideOutcome.NOT_VERIFIED, r.controller.startGuidance(auto = true))
        assertTrue(r.navi.guided.isEmpty())
        assertEquals("a", r.controller.current.value?.place?.id, "방향을 알면 후보를 남겨 다음에 다시 시도한다")
    }

    @Test
    fun `P20 측정 실패여도 방향 표기가 맞으면 넣는다`() = runTest {
        val place = placeSouth("a", 3.0).copy(name = "칠곡휴게소(부산방향)", direction = "부산방향")
        val r = Rig(this, listOf(FakeProvider(listOf(place))))
        r.controller.resolveForGlucose()
        assertEquals(GuideOutcome.WAYPOINT_ADDED, r.controller.startGuidance(auto = true))
    }

    /** P23(P-15). 화물차휴게소가 더 가까워도 묻지 않고 넣는 혈당 경유지는 다음 일반 장소다 */
    @Test
    fun `P23 혈당 자동 안내는 화물차휴게소를 넣지 않는다`() = runTest {
        val truck = placeSouth("truck", 2.0).copy(kind = PlaceKind.REST_AREA, facility = "화물차휴게소")
        val general = placeSouth("general", 3.0).copy(name = "칠곡휴게소(부산방향)", direction = "부산방향", kind = PlaceKind.REST_AREA, facility = "일반휴게소")
        val r = Rig(this, listOf(FakeProvider(listOf(truck, general))))
        assertEquals("general", r.controller.resolveForGlucose()?.place?.id)
        assertEquals(GuideOutcome.WAYPOINT_ADDED, r.controller.startGuidance(auto = true))
        assertEquals(listOf("general"), r.navi.guided.map { it.id })
    }

    /** 피로 제안("{장소}에서 쉬어 갈까요?")도 앱이 고른 장소라 같은 규칙이다. 화물차휴게소뿐이면 장소 없음이다 */
    @Test
    fun `P23 피로 제안도 화물차휴게소를 고르지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("truck", 3.0).copy(kind = PlaceKind.REST_AREA, facility = "화물차휴게소")))))
        assertNull(r.controller.resolve())
        assertEquals(GuideOutcome.FAILED, r.controller.startGuidance(auto = false))
        assertTrue(r.navi.guided.isEmpty())
    }

    /** 사람이 "응"한 안내는 그대로 간다. 확인은 묻지 않는 혈당 경로에만 한다 */
    @Test
    fun `P20 수락한 안내는 방향을 확인하지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))))
        r.controller.resolve()
        assertEquals(GuideOutcome.WAYPOINT_ADDED, r.controller.startGuidance(auto = false))
    }

    @Test
    fun `P8 진행 방향을 모르면 혈당 음성은 장소를 말하지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeNorth("north", 3.0)))), fix = TEST_FIX.copy(headingDeg = null))
        assertNull(r.controller.resolveForGlucose())
        assertNull(r.controller.current.value)
    }

    @Test
    fun `P8 방향을 몰라도 측정이 확인해 주면 경유지를 넣는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))), fix = TEST_FIX.copy(headingDeg = null))
        r.navi.routeM["a"] = 3_200
        assertEquals(GuideOutcome.WAYPOINT_ADDED, r.controller.startGuidance(auto = true))
    }

    @Test
    fun `P8 방향도 모르고 측정도 안 되면 넣지 않고 장소를 비운다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))), fix = TEST_FIX.copy(headingDeg = null))
        assertEquals(GuideOutcome.NOT_VERIFIED, r.controller.startGuidance(auto = true))
        assertTrue(r.navi.guided.isEmpty())
        assertNull(r.controller.current.value)
    }

    @Test
    fun `위치가 없으면 혈당 자동 안내는 경유지를 넣지 않는다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 3.0)))))
        r.controller.resolveForGlucose()
        r.location.fix.value = null
        assertEquals(GuideOutcome.NOT_VERIFIED, r.controller.startGuidance(auto = true))
    }

    /** 진입로를 놓치면 뒤쪽 장소를 계속 "안내 중"이라고 말하게 된다 */
    @Test
    fun `P21 지나친 장소는 지우고 앞쪽을 다시 찾는다`() = runTest {
        val a = placeSouth("a", 3.0)
        val b = placeSouth("b", 8.0)
        val r = Rig(this, listOf(FakeProvider(listOf(a, b))))
        r.navi.routeM["a"] = 3_100
        r.controller.resolveForGlucose()
        r.controller.startGuidance(auto = true)
        assertTrue(r.controller.isGuided(r.controller.current.value))
        // 5km 더 내려왔다. a는 2km 뒤에 있다
        r.location.fix.value = TEST_FIX.copy(latitude = TEST_FIX.latitude - 5.0 / 111.195)
        val now = r.controller.reconfirm()
        assertEquals("b", now?.place?.id)
        assertFalse(r.controller.isGuided(now))
    }

    @Test
    fun `P21 앞에 남아 있으면 그대로 두고 거리만 줄인다`() = runTest {
        val r = Rig(this, listOf(FakeProvider(listOf(placeSouth("a", 8.0)))))
        r.navi.routeM["a"] = 8_400
        r.controller.resolveForGlucose()
        r.controller.startGuidance(auto = true)
        r.location.fix.value = TEST_FIX.copy(latitude = TEST_FIX.latitude - 3.0 / 111.195)
        val now = r.controller.reconfirm()
        assertEquals("a", now?.place?.id)
        assertTrue(r.controller.isGuided(now))
        assertTrue(now!!.straightDistanceM in 4_900..5_100, "${now.straightDistanceM}")
        assertEquals(5_400, now.routeDistanceM!!.div(100) * 100)
    }

    @Test
    fun `P21 멀어지고 있으면 다시 찾는다`() = runTest {
        val a = placeSouth("a", 3.0)
        val b = placeSouth("b", 9.0)
        val r = Rig(this, listOf(FakeProvider(listOf(a, b))))
        r.navi.routeM["a"] = 3_100
        r.controller.resolveForGlucose()
        assertEquals("a", r.controller.current.value?.place?.id)
        r.controller.startGuidance(auto = true)
        // 분기로 빠져 북쪽으로 2km 물러났다. 진행 방위는 아직 남쪽을 가리켜도 거리가 는다
        r.location.fix.value = TEST_FIX.copy(latitude = TEST_FIX.latitude + 2.0 / 111.195)
        r.controller.reconfirm()
        assertFalse(r.controller.isGuided(r.controller.current.value))
    }
}
