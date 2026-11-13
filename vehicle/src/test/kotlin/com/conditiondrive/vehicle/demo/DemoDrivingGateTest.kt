package com.conditiondrive.vehicle.demo

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.UxLimits
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoDrivingGateTest {
    private class RealGate(mode: UiMode) : DrivingGatePort {
        val mode = MutableStateFlow(mode)
        override val uiMode: StateFlow<UiMode> = this.mode
        override val limits: StateFlow<UxLimits> = MutableStateFlow(UxLimits(false, false, false, false))
        override fun start() = Unit
        override fun stop() = Unit
    }

    private fun TestScope.rig(realMode: UiMode = UiMode.PARKED): Triple<ScriptedVehicleSignals, RealGate, DemoDrivingGate> {
        val signals = ScriptedVehicleSignals()
        val real = RealGate(realMode)
        val gate = DemoDrivingGate(signals, real, backgroundScope)
        gate.start()
        runCurrent()
        return Triple(signals, real, gate)
    }

    @Test
    fun `첫 줄 전에는 S1으로 깜빡이지 않는다`() = runTest {
        val (_, _, gate) = rig()
        assertEquals(UiMode.PARKED, gate.uiMode.value)
    }

    @Test
    fun `스크립트 주행은 즉시 Driving이고 P는 3초 유지 뒤 Parked다`() = runTest {
        val (signals, _, gate) = rig()
        signals.set(Gear.DRIVE, 80.0, null)
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)

        signals.set(Gear.PARK, 0.0, null)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(UiMode.PARKED, gate.uiMode.value)
    }

    @Test
    fun `D 0km는 Driving이다`() = runTest {
        val (signals, _, gate) = rig()
        signals.set(Gear.DRIVE, 0.0, null)
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
    }

    @Test
    fun `실제 차가 Driving이면 스크립트가 P여도 Driving이다`() = runTest {
        val (signals, real, gate) = rig()
        signals.set(Gear.PARK, 0.0, null)
        runCurrent()
        real.mode.value = UiMode.DRIVING
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(UiMode.DRIVING, gate.uiMode.value)
    }
}
