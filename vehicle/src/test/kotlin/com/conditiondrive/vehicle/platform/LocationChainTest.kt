package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.vehicle.port.LocationVia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationChainTest {
    private val seoul = GeoFix(37.56, 126.97, 90.0, 1_000)
    private val mountainView = GeoFix(37.42, -122.08, null, 1_000)

    private class FakeSource(override var latest: GeoFix? = null, override val via: LocationVia = LocationVia.FUSED) : LocationSource {
        var active = false
        var polls = 0
        override fun activate() { active = true }
        override fun deactivate() { active = false }
        override fun poll() { polls++ }
    }

    @Test fun rulesRejectForeignAndStale() {
        assertTrue(FixRules.usable(seoul, 1_000))
        assertFalse(FixRules.usable(mountainView, 1_000))
        assertFalse(FixRules.usable(null, 1_000))
        assertFalse(FixRules.usable(seoul, 1_000 + FixRules.MAX_AGE_MS + 1))
    }

    @Test fun zeroHeadingMeansUnknown() {
        assertNull(FixRules.heading(0.0))
        assertNull(FixRules.heading(null))
        assertNull(FixRules.heading(Double.NaN))
        assertEquals(180.0, FixRules.heading(180.0))
    }

    @Test fun firstUsableSourceWinsAndLowerOnesStayOff() = runTest {
        val fused = FakeSource(seoul)
        val navi = FakeSource()
        val lm = FakeSource()
        val job = Job()
        val chain = ChainLocation(listOf(fused, navi, lm), CoroutineScope(coroutineContext + job), { 1_000L }, tickMs = 15_000)
        chain.start()
        runCurrent()
        assertEquals(seoul, chain.fix.value)
        assertTrue(fused.active)
        assertFalse(navi.active)
        assertFalse(lm.active)
        job.cancel()
    }

    @Test fun foreignFusedFallsToNavi() = runTest {
        val fused = FakeSource(mountainView)
        val navi = FakeSource(seoul)
        val job = Job()
        val chain = ChainLocation(listOf(fused, navi), CoroutineScope(coroutineContext + job), { 1_000L })
        chain.start()
        runCurrent()
        assertEquals(seoul, chain.fix.value)
        assertTrue(fused.active)   // 값이 돌아오면 다시 윗 순위로 간다
        job.cancel()
    }

    @Test fun noFixAnywhereIsNullAndAllTrying() = runTest {
        val a = FakeSource()
        val b = FakeSource()
        val job = Job()
        val chain = ChainLocation(listOf(a, b), CoroutineScope(coroutineContext + job), { 1_000L })
        chain.start()
        runCurrent()
        assertNull(chain.fix.value)
        assertTrue(a.active && b.active)
        job.cancel()
    }

    /** 회귀: 응답 콜백이 poll을 부르면 조회 → 응답 → 조회가 무한히 돌아 내비 서비스를 때린다(에뮬레이터에서 잡음) */
    @Test fun sourceUpdateDoesNotPoll() = runTest {
        val fused = FakeSource()
        val navi = FakeSource()
        val job = Job()
        val chain = ChainLocation(listOf(fused, navi), CoroutineScope(coroutineContext + job), { 1_000L }, tickMs = 15_000)
        chain.start()
        runCurrent()
        assertEquals(1, navi.polls)
        repeat(5) { chain.onSourceUpdated() }
        assertEquals(1, navi.polls)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, navi.polls)
        job.cancel()
    }

    @Test fun newFixArrivesWithoutWaitingForTick() = runTest {
        val fused = FakeSource()
        val job = Job()
        val chain = ChainLocation(listOf(fused), CoroutineScope(coroutineContext + job), { 1_000L })
        chain.start()
        runCurrent()
        fused.latest = seoul
        chain.onSourceUpdated()
        assertEquals(seoul, chain.fix.value)
        job.cancel()
    }

    @Test fun stopClearsFixAndDeactivates() = runTest {
        val fused = FakeSource(seoul)
        val job = Job()
        val chain = ChainLocation(listOf(fused), CoroutineScope(coroutineContext + job), { 1_000L })
        chain.start()
        runCurrent()
        chain.stop()
        assertNull(chain.fix.value)
        assertFalse(fused.active)
        job.cancel()
    }

    /** 재검증: 대체 경로로 돌 때도 S5가 FusedLocation을 쓰는 것처럼 보였다 */
    @Test fun viaNamesTheSourceThatGaveTheFix() = runTest {
        val fused = FakeSource(mountainView, LocationVia.FUSED)
        val navi = FakeSource(seoul, LocationVia.NAVI)
        val lm = FakeSource(null, LocationVia.DEVICE)
        val job = Job()
        val chain = ChainLocation(listOf(fused, navi, lm), CoroutineScope(coroutineContext + job), { 1_000L })
        chain.start()
        runCurrent()
        assertEquals(LocationVia.NAVI, chain.via.value)
        fused.latest = seoul
        chain.onSourceUpdated()
        assertEquals(LocationVia.FUSED, chain.via.value)
        chain.stop()
        assertEquals(LocationVia.NONE, chain.via.value)
        job.cancel()
    }
}
