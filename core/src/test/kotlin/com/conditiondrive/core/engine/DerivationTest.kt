package com.conditiondrive.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** test-scenarios.md G1~G3. 공식을 그대로 다시 쓰지 않고 표의 문장으로 단언한다. */
class DerivationTest {
    private val engine = Engine()

    private fun cell(f: Level, c: Level, g: Level, latched: Boolean, parked: Boolean): EngineState = EngineState(
        lastEventMs = 0,
        ignition = Ignition.ON,
        gear = if (parked) Gear.PARK else Gear.DRIVE,
        speedKmh = if (parked) 0.0 else 80.0,
        fatigue = f,
        checkin = c,
        glucose = g,
        glucoseLatched = latched,
    )

    @Test
    fun g1Exhaustive108Cells() {
        var cells = 0
        for (f in Level.entries) for (c in Level.entries) for (g in Level.entries)
            for (latched in listOf(false, true)) for (parked in listOf(false, true)) {
                cells++
                val view = engine.view(cell(f, c, g, latched, parked), 0)
                val anyStop = Level.STOP in listOf(f, c) || (g == Level.STOP && !latched)
                val anyCaution = Level.CAUTION in listOf(f, c) || (g == Level.CAUTION && !latched) || latched
                val expected = when {
                    anyStop -> if (parked) DriverState.RECOVER else DriverState.STOP
                    anyCaution -> DriverState.CAUTION
                    else -> DriverState.NORMAL
                }
                assertEquals(expected, view.state, "F=$f C=$c G=$g L=$latched parked=$parked")
            }
        assertEquals(108, cells)
    }

    @Test
    fun g2StopBeatsCaution() {
        for (a in Level.entries) for (b in Level.entries) {
            val view = engine.view(cell(Level.STOP, a, b, false, false), 0)
            assertEquals(DriverState.STOP, view.state)
            assertNotEquals(DriverState.CAUTION, view.state)
        }
    }

    @Test
    fun g3RecoveryNeedsEveryReason() {
        for (f in Level.entries) for (c in Level.entries) for (g in Level.entries) {
            if (f == Level.NONE && c == Level.NONE && g == Level.NONE) continue
            for (parked in listOf(false, true)) {
                val view = engine.view(cell(f, c, g, false, parked), 0)
                assertNotEquals(DriverState.NORMAL, view.state, "F=$f C=$c G=$g")
                assertTrue(view.reasons.isNotEmpty())
            }
        }
    }

    @Test
    fun departPendingCountsAsParked() {
        val state = cell(Level.STOP, Level.NONE, Level.NONE, false, false).copy(departPendingSinceMs = 0, speedKmh = 5.0)
        val view = engine.view(state, 1000)
        assertEquals(DriverState.RECOVER, view.state)
        assertEquals(false, view.driving)
    }

    @Test
    fun holdIsAFlagNotAState() {
        val state = cell(Level.NONE, Level.NONE, Level.STOP, false, true)
        assertEquals(DriverState.RECOVER, engine.view(state, 0).state)
        assertEquals(true, engine.view(state, 0).preDepartureHold)
        assertEquals(false, engine.view(state.copy(departedThisIgnition = true), 0).preDepartureHold)
    }

    @Test
    fun fallbackParkedDerivesRecover() {
        val state = EngineState(lastEventMs = 0, ignition = Ignition.ON, gear = Gear.UNKNOWN, speedKmh = 0.0, fatigue = Level.STOP)
        assertEquals(DriverState.RECOVER, engine.view(state, 0).state)
    }
}
