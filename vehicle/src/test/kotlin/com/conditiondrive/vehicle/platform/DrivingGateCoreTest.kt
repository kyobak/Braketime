package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.vehicle.port.UiMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 8.1 게이트. H 표의 판정을 차량 API 없이 본다 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingGateCoreTest {
    private val parkedUx = UxSnapshot(requiresDo = false, activeRestrictions = 0)
    private val movingUx = UxSnapshot(requiresDo = true, activeRestrictions = 0x50)

    private fun run(block: suspend TestScope.(FakeSignals, DrivingGateCore) -> Unit) = runTest(StandardTestDispatcher()) {
        val signals = FakeSignals(Gear.PARK, 0.0)
        val gate = DrivingGateCore(signals, backgroundScope, holdMs = 3000)
        gate.start()
        block(signals, gate)
    }

    /** 기어를 못 읽는 차종도 설정과 권한 요청에 닿아야 한다. 차속 신호까지 없으면 여전히 Driving이다 */
    @Test fun missingGearFallsBackToSpeed() {
        assertTrue(DrivingGateCore.parkedCondition(parkedUx, Gear.UNKNOWN, 0.0, gearSupported = false))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.UNKNOWN, 40.0, gearSupported = false))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.UNKNOWN, null, gearSupported = false))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.UNKNOWN, 0.0, gearSupported = true))
        assertFalse(DrivingGateCore.parkedCondition(movingUx, Gear.UNKNOWN, 0.0, gearSupported = false))
        assertFalse(DrivingGateCore.parkedCondition(null, Gear.UNKNOWN, 0.0, gearSupported = false))
    }

    @Test fun unconnectedIsDriving() = run { _, gate ->
        advanceTimeBy(10_000); runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
        assertTrue(gate.limits.value.noSetup)
    }

    @Test fun parkedOnlyAfterHold() = run { _, gate ->
        gate.updateUx(parkedUx)
        advanceTimeBy(2999); runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
        advanceTimeBy(1); runCurrent()
        assertEquals(UiMode.PARKED, gate.uiMode.value)
    }

    @Test fun drivingIsImmediate() = run { signals, gate ->
        gate.updateUx(parkedUx)
        advanceTimeBy(3000); runCurrent()
        assertEquals(UiMode.PARKED, gate.uiMode.value)
        signals.gear.value = Gear.DRIVE
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
    }

    @Test fun holdRestartsWhenConditionBreaks() = run { signals, gate ->
        gate.updateUx(parkedUx)
        advanceTimeBy(2000); runCurrent()
        signals.gear.value = Gear.DRIVE
        runCurrent()
        signals.gear.value = Gear.PARK
        advanceTimeBy(2000); runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
        advanceTimeBy(1000); runCurrent()
        assertEquals(UiMode.PARKED, gate.uiMode.value)
    }

    @Test fun uxRestrictionKeepsDrivingEvenInPark() = run { _, gate ->
        gate.updateUx(movingUx)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
    }

    @Test fun losingUxServiceDropsToDriving() = run { _, gate ->
        gate.updateUx(parkedUx)
        advanceTimeBy(3000); runCurrent()
        gate.updateUx(null)
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
    }

    @Test fun conditionTable() {
        assertTrue(DrivingGateCore.parkedCondition(parkedUx, Gear.PARK, 0.0))
        assertTrue(DrivingGateCore.parkedCondition(parkedUx, Gear.PARK, null))
        assertTrue(DrivingGateCore.parkedCondition(parkedUx, Gear.PARK, 0.49))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.PARK, 0.5))
        // D, 0km/h(신호 대기)는 Driving이다
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.DRIVE, 0.0))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.UNKNOWN, 0.0))
        assertFalse(DrivingGateCore.parkedCondition(parkedUx, Gear.NEUTRAL, 0.0))
        assertFalse(DrivingGateCore.parkedCondition(movingUx, Gear.PARK, 0.0))
        assertFalse(DrivingGateCore.parkedCondition(null, Gear.PARK, 0.0))
    }

    @Test fun limitsReadRestrictionBits() {
        val limits = UxFlags.limits(UxSnapshot(true, 0x50))
        assertTrue(limits.noSetup)
        assertFalse(limits.noKeyboard)
        assertFalse(limits.limitContent)
        assertFalse(limits.limitStringLength)
        assertTrue(UxFlags.limits(UxSnapshot(true, 0x2C)).run { noKeyboard && limitContent && limitStringLength })
    }
}
