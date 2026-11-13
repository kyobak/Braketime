package com.conditiondrive.vehicle.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.VentilationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Vehicle SDK 진입점 하나를 나눠 쓴다. initialize에 완료 콜백이 없어 호출 쪽이 재시도한다 */
internal class VehicleSdk(private val context: Context) {
    private var vehicle: Vehicle? = null

    @Synchronized
    fun get(): Vehicle? {
        vehicle?.let { return it }
        return guarded("vehicle init", null) { Vehicle(context).also { it.initialize(); vehicle = it } }
    }

    @Synchronized
    fun release() {
        guarded("vehicle release", Unit) { vehicle?.release() }
        vehicle = null
    }
}

/**
 * onComplete/onFailed 콜백을 값 하나로 바꾼다. 서비스 연결 전 호출은 실패로 오므로 몇 번 더 묻는다.
 * 콜백이 오지 않는 차종에서도 시작이 멈추지 않게 제한 시간을 둔다.
 */
internal suspend fun <T> vehicleCall(
    what: String,
    attempts: Int = 3,
    block: (onValue: (T?) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): T? {
    repeat(attempts) { attempt ->
        val outcome = withTimeoutOrNull(VEHICLE_CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<T?>> { cont ->
                val sent = guarded(what, false) {
                    block(
                        { v -> if (cont.isActive) cont.resume(Result.success(v)) },
                        { e -> if (cont.isActive) cont.resume(Result.failure(e)) },
                    )
                    true
                }
                if (!sent && cont.isActive) cont.resume(Result.failure(IllegalStateException(what)))
            }
        }
        if (outcome != null && outcome.isSuccess) return outcome.getOrNull()
        if (outcome != null) outcome.exceptionOrNull()?.let { logFailure(what, it) }
        if (attempt < attempts - 1) delay(VEHICLE_RETRY_MS)
    }
    return null
}

private const val VEHICLE_CALL_TIMEOUT_MS = 3000L
private const val VEHICLE_RETRY_MS = 1000L

/** 10.2: 미지원이면 null. 공백은 UNKNOWN으로 다룬다 */
internal class PleosOdometer(private val sdk: VehicleSdk) : OdometerPort {
    override suspend fun totalKm(): Double? {
        val odometer = sdk.get()?.let { v -> guarded("odometer", null) { v.getOdometer() } } ?: return null
        val config = vehicleCall<CapabilityConfig>("odometer capability") { ok, fail ->
            odometer.checkCurrentDistanceDrivenCapabilities(ok, fail)
        } ?: return null
        if (!config.accessType.canRead()) return null
        return vehicleCall<Float>("odometer value") { ok, fail ->
            odometer.getCurrentDistanceDriven(ok, fail)
        }?.toDouble()
    }
}

/**
 * F10. 실측: 재순환은 앞뒤 전 좌석을 묶은 영역 하나로만 받는다(ROW_1_LEFT 단독은 IllegalArgumentException).
 * 그래서 영역은 capability가 돌려준 값을 그대로 쓴다.
 */
internal class PleosVentilation(private val sdk: VehicleSdk) : VentilationPort {
    @Volatile private var area: SeatArea? = null

    private fun hvac() = sdk.get()?.let { v -> guarded("hvac", null) { v.getHVAC() } }

    override suspend fun supported(): Boolean {
        val api = hvac() ?: return false
        val found = vehicleCall<Pair<CapabilityConfig, List<SeatArea>>>("recirc capability") { ok, fail ->
            api.checkHvacRecirculationCapabilities({ c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, fail)
        } ?: return false
        val target = found.second.firstOrNull() ?: return false
        if (found.first.accessType != AccessType.READ_WRITE) return false
        area = target
        // 자동 재순환이 켜져 있으면 차가 곧 되돌린다. 없는 차종은 꺼진 것으로 본다
        val auto = vehicleCall<Boolean>("auto recirc state", attempts = 1) { ok, fail ->
            api.getHvacAutoRecirculationState(target, ok, fail)
        }
        return auto != true
    }

    private suspend fun targetArea(): SeatArea? {
        area?.let { return it }
        supported()
        return area
    }

    override suspend fun current(): AirMode? {
        val api = hvac() ?: return null
        val target = targetArea() ?: return null
        return vehicleCall<Boolean>("recirc state") { ok, fail ->
            api.getHvacRecirculationState(target, ok, fail)
        }?.let(::airMode)
    }

    override suspend fun set(mode: AirMode): Boolean {
        val api = hvac() ?: return false
        val target = targetArea() ?: return false
        val accepted = vehicleCall<Boolean>("recirc set", attempts = 1) { ok, fail ->
            api.setHvacRecirculation(target, mode == AirMode.RECIRCULATE, { _, value -> ok(value ?: (mode == AirMode.RECIRCULATE)) }, fail)
        }
        if (accepted != (mode == AirMode.RECIRCULATE)) return false
        // onComplete는 요청을 받았다는 뜻일 뿐이다. 실측: 차량 쪽이 반영하지 않으면 값이 그대로다. V27 뒤 공조가 안 바뀌면 안 된다
        repeat(SET_VERIFY_ATTEMPTS) {
            if (current() == mode) return true
            delay(SET_VERIFY_DELAY_MS)
        }
        return false
    }

    override val changes: Flow<AirMode> = callbackFlow {
        val api = hvac()
        val target = targetArea()
        if (api == null || target == null) {
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : HvacRecirculationStateListener {
            // SDK가 일반·자동 재순환에 같은 콜백 이름을 쓴다(문서 확인)
            override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) {
                state?.let { trySend(airMode(it)) }
            }

            override fun onFailed(e: Exception) = logFailure("recirc listener", e)
        }
        val registered = guarded("recirc register", false) {
            api.registerHvacRecirculationState(target, listener)
            true
        }
        awaitClose {
            if (registered) guarded("recirc unregister", Unit) { api.unregisterHvacRecirculationState(target, listener) }
        }
    }

    private fun airMode(recirculating: Boolean) = if (recirculating) AirMode.RECIRCULATE else AirMode.FRESH

    private companion object {
        const val SET_VERIFY_ATTEMPTS = 10
        const val SET_VERIFY_DELAY_MS = 200L
    }
}

/** 좌석별 값을 동승 여부로 줄인다. 운전석은 뺀다. 모르는 좌석이 있어도 있다고 답한 좌석이 먼저다 */
internal object OccupancyRule {
    /** 값의 방향을 실차로 확인하면 true로 바꾼다(19장) */
    const val SEAT_VALUE_DIRECTION_CONFIRMED = false

    fun reported(values: Collection<Boolean?>, confirmed: Boolean = SEAT_VALUE_DIRECTION_CONFIRMED): Boolean? =
        if (confirmed) passengerPresent(values) else null

    fun passengerPresent(values: Collection<Boolean?>): Boolean? = when {
        values.any { it == true } -> true
        values.isEmpty() || values.any { it == null } -> null
        else -> false
    }
}

/**
 * F11. 실측: Virtual Console의 Occupied와 SDK 값이 반대다(콘솔 Occupied → false, Empty → true).
 * 어느 쪽 오류인지 공개 자료로 확정할 수 없다. 값이 뒤집혀 있으면 동승자가 탔을 때 혈당 수치가 그대로 나오므로
 * 실차나 공식 답변으로 방향을 확인하기 전까지는 "모름"으로 내보내 항상 수치를 숨긴다. 시연은 ScriptedOccupancy를 쓴다.
 */
internal class PleosOccupancy(private val sdk: VehicleSdk, private val scope: CoroutineScope) : OccupancyPort {
    private val _present = MutableStateFlow<Boolean?>(null)
    override val passengerPresent: StateFlow<Boolean?> = _present.asStateFlow()
    private val _supported = MutableStateFlow<Boolean?>(null)
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    private var job: Job? = null
    private val listeners = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()
    private val seats = mutableMapOf<List<SeatValue>, Boolean?>()

    override fun start() {
        if (job != null) return
        job = scope.launch { subscribe() }
    }

    private suspend fun subscribe() {
        val seat = sdk.get()?.let { v -> guarded("seat", null) { v.getSeat() } }
        if (seat == null) {
            _supported.value = false
            return
        }
        val found = vehicleCall<Pair<CapabilityConfig, List<SeatArea>>>("occupancy capability") { ok, fail ->
            seat.checkSeatOccupancyCapabilities({ c: CapabilityConfig, a: List<SeatArea> -> ok(c to a) }, fail)
        }
        val areas = found?.second?.filterNot { SeatValue.ROW_1_LEFT in it.values }.orEmpty()
        if (found == null || !found.first.accessType.canRead() || areas.isEmpty()) {
            _supported.value = false
            return
        }
        _supported.value = true
        synchronized(seats) { areas.forEach { seats[it.values.toList()] = null } }
        areas.forEach { area ->
            val listener = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) = update(area, occupancy)
                override fun onFailed(e: Exception) {
                    logFailure("occupancy listener", e)
                    update(area, null)
                }
            }
            guarded("occupancy register", Unit) {
                seat.registerSeatOccupancy(area, listener)
                listeners += area to listener
            }
        }
    }

    private fun update(area: SeatArea, value: Boolean?) {
        val snapshot = synchronized(seats) {
            seats[area.values.toList()] = value
            seats.values.toList()
        }
        _present.value = OccupancyRule.reported(snapshot)
    }

    override fun stop() {
        job?.cancel()
        job = null
        val seat = sdk.get()?.let { v -> guarded("seat", null) { v.getSeat() } }
        listeners.forEach { (area, listener) -> guarded("occupancy unregister", Unit) { seat?.unregisterSeatOccupancy(area, listener) } }
        listeners.clear()
        synchronized(seats) { seats.clear() }
        _present.value = null
    }
}

internal fun AccessType.canRead() = this == AccessType.READ || this == AccessType.READ_WRITE
