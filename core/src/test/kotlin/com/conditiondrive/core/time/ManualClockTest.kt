package com.conditiondrive.core.time

import kotlin.test.Test
import kotlin.test.assertEquals

class ManualClockTest {
    @Test
    fun setAndAdvanceMoveTime() {
        val clock = ManualClock(1_000)
        clock.advance(500)
        assertEquals(1_500, clock.nowMs())
        clock.set(10)
        assertEquals(10, clock.nowMs())
    }
}
