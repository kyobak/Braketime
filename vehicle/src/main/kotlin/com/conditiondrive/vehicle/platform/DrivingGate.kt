package com.conditiondrive.vehicle.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.UxLimits
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 8.1 판정만. null은 UX 서비스 미연결이다 */
internal data class UxSnapshot(val requiresDo: Boolean, val activeRestrictions: Int)

internal object UxFlags {
    // CarUxRestrictions 공개 상수. android.car 없이 시험하려고 값만 옮겼다
    const val LIMIT_STRING_LENGTH = 0x4
    const val NO_KEYBOARD = 0x8
    const val LIMIT_CONTENT = 0x20
    const val NO_SETUP = 0x40

    fun limits(ux: UxSnapshot?): UxLimits {
        // 미연결은 DRIVING으로 다루므로 제한도 전부 건 것으로 본다
        if (ux == null) return UxLimits(noKeyboard = true, noSetup = true, limitContent = true, limitStringLength = true)
        val a = ux.activeRestrictions
        return UxLimits(
            noKeyboard = a and NO_KEYBOARD != 0,
            noSetup = a and NO_SETUP != 0,
            limitContent = a and LIMIT_CONTENT != 0,
            limitStringLength = a and LIMIT_STRING_LENGTH != 0,
        )
    }
}

/**
 * 8.1 게이트. Driving은 즉시, Parked는 조건이 hold 동안 유지되어야 한다.
 * 신호 시연에서도 같은 코드가 돌도록 차량 API와 나눴다.
 */
internal class DrivingGateCore(
    private val signals: VehicleSignalsPort,
    private val scope: CoroutineScope,
    private val holdMs: Long = 3000,
) : DrivingGatePort {
    private val ux = MutableStateFlow<UxSnapshot?>(null)
    private val _mode = MutableStateFlow(UiMode.DRIVING)
    private val _limits = MutableStateFlow(UxFlags.limits(null))
    private var job: Job? = null

    override val uiMode: StateFlow<UiMode> = _mode.asStateFlow()
    override val limits: StateFlow<UxLimits> = _limits.asStateFlow()

    fun updateUx(snapshot: UxSnapshot?) {
        ux.value = snapshot
        _limits.value = UxFlags.limits(snapshot)
    }

    override fun start() {
        if (job != null) return
        job = scope.launch {
            combine(ux, signals.gear, signals.speedKmh, signals.capabilities) { u, g, s, caps -> parkedCondition(u, g, s, caps.gear) }
                .collectLatest { parked ->
                    if (!parked) {
                        _mode.value = UiMode.DRIVING
                    } else if (_mode.value != UiMode.PARKED) {
                        // collectLatest가 조건이 깨지는 순간 이 대기를 취소한다
                        delay(holdMs)
                        _mode.value = UiMode.PARKED
                    }
                }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _mode.value = UiMode.DRIVING
    }

    companion object {
        /** 기어를 못 읽는 차종은 차속 0을 정차로 본다(13장 기어 미지원). 그래야 설정과 권한 요청에 닿는다. 신호가 전혀 없으면 Driving이다 */
        fun parkedCondition(ux: UxSnapshot?, gear: Gear, speedKmh: Double?, gearSupported: Boolean = true): Boolean {
            if (ux == null || ux.requiresDo) return false
            if (gear == Gear.PARK) return speedKmh == null || speedKmh < 0.5
            return !gearSupported && gear == Gear.UNKNOWN && speedKmh != null && speedKmh < 0.5
        }
    }
}

/** CarUxRestrictionsManager를 [DrivingGateCore]에 잇는다. 실측: D 진입 즉시 DO=true, UxR 0x50. P에서 DO=false */
class CarDrivingGate private constructor(
    private val connection: CarConnection,
    private val scope: CoroutineScope,
    private val core: DrivingGateCore,
) : DrivingGatePort by core {
    constructor(connection: CarConnection, signals: VehicleSignalsPort, scope: CoroutineScope, holdMs: Long = 3000) :
        this(connection, scope, DrivingGateCore(signals, scope, holdMs))

    private var watch: Job? = null
    private var manager: CarUxRestrictionsManager? = null

    override fun start() {
        core.start()
        if (watch != null) return
        connection.acquire()
        watch = scope.launch {
            connection.car.collect { car -> if (car != null) attach(car) else detach() }
        }
    }

    override fun stop() {
        watch?.cancel()
        watch = null
        detach()
        connection.release()
        core.stop()
    }

    private fun attach(car: Car) {
        val m = guarded("ux manager", null) { car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager } ?: run {
            core.updateUx(null)
            return
        }
        manager = m
        guarded("ux register", Unit) {
            m.registerListener { restrictions -> core.updateUx(restrictions.toSnapshot()) }
            core.updateUx(m.currentCarUxRestrictions?.toSnapshot())
        }
    }

    private fun detach() {
        guarded("ux unregister", Unit) { manager?.unregisterListener() }
        manager = null
        core.updateUx(null)
    }

    private fun CarUxRestrictions.toSnapshot() = UxSnapshot(isRequiresDistractionOptimization, activeRestrictions)
}
