package com.conditiondrive.vehicle.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompressedClockTest {
    private var real = 1_000L
    private val start = 5_000_000L
    private val clock = CompressedClock(start) { real }

    @Test
    fun `배속대로 간다`() {
        clock.setSpeed(240)
        real += 1000
        assertEquals(start + 240_000, clock.nowMs())
        clock.setSpeed(60)
        real += 1000
        assertEquals(start + 300_000, clock.nowMs())
    }

    @Test
    fun `R6 정지 중에는 시각이 그대로이고 풀면 그 시각부터 이어 간다`() {
        clock.setSpeed(240)
        real += 100
        val frozenAt = clock.nowMs()
        clock.freeze()
        real += 8000
        assertEquals(frozenAt, clock.nowMs())
        assertTrue(clock.isFrozen)
        clock.unfreeze()
        assertFalse(clock.isFrozen)
        assertEquals(frozenAt, clock.nowMs())
        real += 100
        assertEquals(frozenAt + 24_000, clock.nowMs())
    }

    @Test
    fun `15_3 마감을 넘어가지 않고 정확히 착지한다`() {
        val deadline = start + 6_300_000
        clock.setSpeed(240)
        clock.landAt(deadline)
        real += 100_000
        assertEquals(deadline, clock.nowMs())
    }

    @Test
    fun `마감이 풀리면 넘친 시간 없이 그 시각부터 이어 간다`() {
        val deadline = start + 1000
        clock.setSpeed(240)
        clock.landAt(deadline)
        real += 100_000
        assertEquals(deadline, clock.nowMs())
        clock.landAt(start + 900_000)
        assertEquals(deadline, clock.nowMs())
        real += 10
        assertEquals(deadline + 2400, clock.nowMs())
    }

    @Test
    fun `지난 마감이 와도 뒤로 가지 않는다`() {
        clock.setSpeed(60)
        real += 1000
        val now = clock.nowMs()
        clock.landAt(start)
        real += 1000
        assertEquals(now, clock.nowMs())
    }

    @Test
    fun `마감과 시나리오 줄 중 먼저 오는 쪽에서 멈춘다`() {
        clock.setSpeed(240)
        clock.landAt(start + 10_000)
        clock.holdAt(start + 5_000)
        real += 1000
        assertEquals(start + 5_000, clock.nowMs())
        clock.holdAt(start + 50_000)
        real += 1000
        assertEquals(start + 10_000, clock.nowMs())
    }

    @Test
    fun `jumpTo는 앞으로만 가고 정지 중에도 옮긴다`() {
        clock.freeze()
        clock.jumpTo(start + 10_000)
        assertEquals(start + 10_000, clock.nowMs())
        clock.jumpTo(start)
        assertEquals(start + 10_000, clock.nowMs())
    }

    @Test
    fun `실제 대기 시간을 배속으로 환산하고 정지 중에는 짧게 살핀다`() {
        clock.setSpeed(240)
        assertEquals(1000, clock.realDelayMs(240_000))
        assertEquals(20, clock.realDelayMs(1))
        clock.freeze()
        assertEquals(250, clock.realDelayMs(240_000))
    }
}
