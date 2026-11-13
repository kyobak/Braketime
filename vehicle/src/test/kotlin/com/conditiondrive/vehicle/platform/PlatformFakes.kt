package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakeSignals(gear: Gear = Gear.UNKNOWN, speed: Double? = null) : VehicleSignalsPort {
    override val gear = MutableStateFlow(gear)
    override val speedKmh = MutableStateFlow(speed)
    override val ignition = MutableStateFlow(Ignition.ON)
    override val capabilities = MutableStateFlow(VehicleCapabilities(gear = true, speed = true, ignition = false))
    override fun start() = Unit
    override fun stop() = Unit
}

internal class FakeFocus(var grant: Boolean = true) : FocusPort {
    var acquired = 0
    var released = 0
    var lastPurpose: FocusPurpose? = null
    var onLoss: ((FocusLoss) -> Unit)? = null

    override fun acquire(purpose: FocusPurpose, onLoss: (FocusLoss) -> Unit): Boolean {
        acquired++
        lastPurpose = purpose
        this.onLoss = onLoss
        return grant
    }

    override fun release() {
        released++
    }
}
