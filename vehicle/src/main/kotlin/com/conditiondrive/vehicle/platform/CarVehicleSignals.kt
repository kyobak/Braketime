package com.conditiondrive.vehicle.platform

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 10.2의 차량 신호. 실측 결과:
 * - CAR_SPEED는 dangerous이고 권한이 없으면 getCarPropertyConfig가 null이다. 미지원과 구분되지 않아 refresh가 필요하다.
 * - Pleos 에뮬레이터 v2.1.0에는 앱에 보이는 IGNITION_STATE가 없다. 10.2대로 서비스 시작을 ON으로 간주한다.
 */
class CarVehicleSignals(
    private val connection: CarConnection,
    private val scope: CoroutineScope,
) : VehicleSignalsPort {
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    private val _speed = MutableStateFlow<Double?>(null)
    // 10.2: 시동 속성이 없거나 차량 서비스에 닿지 못하면 서비스가 시작된 것을 ON으로 본다
    private val _ignition = MutableStateFlow(Ignition.ON)
    private val _capabilities = MutableStateFlow(VehicleCapabilities(gear = false, speed = false, ignition = false))

    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    override val speedKmh: StateFlow<Double?> = _speed.asStateFlow()
    override val ignition: StateFlow<Ignition> = _ignition.asStateFlow()
    override val capabilities: StateFlow<VehicleCapabilities> = _capabilities.asStateFlow()

    private var watch: Job? = null
    private var manager: CarPropertyManager? = null
    private val registered = mutableMapOf<Int, CarPropertyManager.CarPropertyEventCallback>()
    private val throttle = SpeedThrottle()

    override fun start() {
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
    }

    /** 온보딩에서 권한을 받은 뒤 부른다. 권한이 생겨도 이미 건 구독에는 영향이 없다 */
    fun refresh() {
        connection.car.value?.let { attach(it) }
    }

    @Synchronized
    private fun attach(car: Car) {
        val pm = guarded("property manager", null) { car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager } ?: run {
            detach()
            return
        }
        manager = pm
        val hasGear = subscribe(pm, VehiclePropertyIds.GEAR_SELECTION, 0f) { value ->
            _gear.value = SignalMapping.gear(value as? Int)
        }
        val hasSpeed = subscribe(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f) { value ->
            val raw = (value as? Float) ?: return@subscribe
            val kmh = SignalMapping.speedKmh(raw)
            if (throttle.accept(kmh, SystemClock.elapsedRealtime())) _speed.value = kmh
        }
        val hasIgnition = subscribe(pm, VehiclePropertyIds.IGNITION_STATE, 0f) { value ->
            _ignition.value = SignalMapping.ignition(value as? Int)
        }
        if (!hasIgnition) _ignition.value = Ignition.ON
        if (!hasSpeed) _speed.value = null
        if (!hasGear) _gear.value = Gear.UNKNOWN
        _capabilities.value = VehicleCapabilities(gear = hasGear, speed = hasSpeed, ignition = hasIgnition)
    }

    private fun subscribe(
        pm: CarPropertyManager,
        propertyId: Int,
        rate: Float,
        onValue: (Any?) -> Unit,
    ): Boolean {
        if (propertyId in registered) return true
        val config = guarded("property config", null) { pm.getCarPropertyConfig(propertyId) } ?: return false
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                // 값이 없는 이벤트를 0으로 읽으면 정차로 오판한다
                if (value.status == CarPropertyValue.STATUS_AVAILABLE) onValue(value.value)
            }

            override fun onErrorEvent(propId: Int, zone: Int) = Unit
        }
        val ok = guarded("property subscribe", false) {
            // minSampleRate보다 낮게 요청하면 플랫폼이 올려 준다. 줄이는 일은 SpeedThrottle이 맡는다
            pm.registerCallback(callback, propertyId, rate.coerceAtLeast(config.minSampleRate))
        }
        if (ok) registered[propertyId] = callback
        return ok
    }

    @Synchronized
    private fun detach() {
        val pm = manager
        if (pm != null) {
            registered.values.forEach { cb -> guarded("property unsubscribe", Unit) { pm.unregisterCallback(cb) } }
        }
        registered.clear()
        manager = null
        throttle.reset()
        // 값을 믿을 수 없으므로 게이트가 DRIVING으로 떨어지게 비운다
        _gear.value = Gear.UNKNOWN
        _speed.value = null
        _capabilities.value = VehicleCapabilities(gear = false, speed = false, ignition = false)
    }

    companion object {
        const val SPEED_PERMISSION: String = "android.car.permission.CAR_SPEED"

        internal fun hasSpeedPermission(context: Context): Boolean =
            context.checkSelfPermission(SPEED_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
