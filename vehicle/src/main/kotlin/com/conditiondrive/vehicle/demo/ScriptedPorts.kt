package com.conditiondrive.vehicle.demo

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.Place
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.RouteSnapshot
import com.conditiondrive.vehicle.port.TripEstimate
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.UxLimits
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내장 시연의 화면 게이트. 실제 차는 P라 실제 게이트만 보면 스크립트 주행 중에도 S2에 머문다.
 * 8.1과 같이 Driving은 즉시, Parked는 3초 유지다. 실제 차가 Driving이면 스크립트와 무관하게 Driving이다(0장 규칙).
 */
class DemoDrivingGate(
    private val signals: VehicleSignalsPort,
    private val real: DrivingGatePort?,
    private val scope: CoroutineScope,
    private val holdMs: Long = 3000,
) : DrivingGatePort {
    private val _mode = MutableStateFlow(real?.uiMode?.value ?: UiMode.PARKED)
    private var job: Job? = null

    override val uiMode: StateFlow<UiMode> = _mode
    override val limits: StateFlow<UxLimits> = real?.limits ?: MutableStateFlow(UxLimits(false, false, false, false))

    override fun start() {
        if (job != null) return
        job = scope.launch {
            combine(signals.gear, signals.speedKmh, real?.uiMode ?: MutableStateFlow(UiMode.PARKED)) { gear, speed, realMode ->
                // 첫 줄이 들어오기 전의 UNKNOWN은 시작 조건(실제 P)을 그대로 잇는다. 여기서 S1이 깜빡이면 안 된다
                realMode == UiMode.PARKED && (gear == Gear.PARK || gear == Gear.UNKNOWN) && (speed == null || speed < 0.5)
            }.collectLatest { parked ->
                if (!parked) {
                    _mode.value = UiMode.DRIVING
                } else if (_mode.value != UiMode.PARKED) {
                    delay(holdMs)
                    _mode.value = UiMode.PARKED
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

/** 내장 시연(15.1). 시작값은 ScenarioRunner와 같다. 첫 줄이 시동 ON을 넣기 전까지 아무 신호도 아니다. */
class ScriptedVehicleSignals : VehicleSignalsPort {
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    private val _speed = MutableStateFlow<Double?>(null)
    private val _ignition = MutableStateFlow(Ignition.OFF)
    override val gear: StateFlow<Gear> = _gear
    override val speedKmh: StateFlow<Double?> = _speed
    override val ignition: StateFlow<Ignition> = _ignition
    override val capabilities: StateFlow<VehicleCapabilities> =
        MutableStateFlow(VehicleCapabilities(gear = true, speed = true, ignition = true))

    override fun start() = Unit
    override fun stop() = Unit

    fun set(gear: Gear?, speedKmh: Double?, ignition: Ignition?) {
        gear?.let { _gear.value = it }
        speedKmh?.let { _speed.value = it }
        ignition?.let { _ignition.value = it }
    }
}

/** 차량 신호 시연(15.1, 15.4). 기어와 차속은 Virtual Console이 넣고, 콘솔에 없는 시동만 시나리오가 넣는다. */
class HybridVehicleSignals(private val real: VehicleSignalsPort) : VehicleSignalsPort {
    private val _ignition = MutableStateFlow(Ignition.OFF)
    override val gear: StateFlow<Gear> = real.gear
    override val speedKmh: StateFlow<Double?> = real.speedKmh
    override val ignition: StateFlow<Ignition> = _ignition
    override val capabilities: StateFlow<VehicleCapabilities> = real.capabilities

    override fun start() = real.start()
    override fun stop() = real.stop()

    fun setIgnition(ignition: Ignition) {
        _ignition.value = ignition
    }
}

class ScriptedOccupancy(present: Boolean? = false) : OccupancyPort {
    private val _present = MutableStateFlow(present)
    override val passengerPresent: StateFlow<Boolean?> = _present
    override fun start() = Unit
    override fun stop() = Unit

    fun set(present: Boolean?) {
        _present.value = present
    }
}

/** 위치 | 시나리오 고정 좌표(15.1). 실제 위치와 섞으면 재현되지 않는다. */
class ScriptedLocation(fix: GeoFix) : LocationPort {
    override val fix: StateFlow<GeoFix?> = MutableStateFlow(fix)
    override fun start() = Unit
    override fun stop() = Unit
}

/** 시연은 주행 거리로 공백을 판정할 일이 없다. 시작할 때마다 저장본을 비운다 */
object ScriptedOdometer : OdometerPort {
    override suspend fun totalKm(): Double? = null
}

/**
 * NaviHelper | 실제, 없으면 음성만(15.1).
 * 경유지 추가만 실제로 보낸다. 잔여 시간과 경로 측정은 시나리오 좌표와 실제 위치가 달라 그대로 쓰면 판단이 어긋난다.
 * 내비는 경로를 받으면 지도를 앱 영역 전면에 올리고, 앱은 백그라운드 시작 제한(실측 BAL_BLOCK) 때문에 돌아오지 못한다.
 * 경로는 앱과 무관하게 남아 실사용 판단에도 쓰인다. 그래서 내장 시연(deferReal)은 실제로 보내지 않고 장소만 넘긴다.
 * 보낼지는 시연이 끝난 뒤 사람이 설정 > 시연 모드에서 정한다(15.3 규칙 5).
 */
class ScriptedNavi(
    private val real: NaviPort?,
    private val deferReal: Boolean = false,
    private val onDeferred: (Place) -> Unit = {},
) : NaviPort {
    override val available: StateFlow<Boolean> = real?.available ?: MutableStateFlow(false)
    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    override val route: StateFlow<RouteSnapshot?> = _route
    override val waypointArrived: SharedFlow<Unit> = real?.waypointArrived ?: MutableSharedFlow()

    fun setRemaining(remainingSec: Int) {
        _route.value = RouteSnapshot(isRouting = true, remainingSec = remainingSec, remainingM = null, waypointCount = 0)
    }

    /** 실제 내비는 시나리오 좌표를 모른다. 시나리오 장소는 정해진 거리이고 그 밖은 재지 못한다 */
    override suspend fun measure(place: Place): TripEstimate? =
        if (place.id == DemoFixtures.PLACE.id) TripEstimate(DemoFixtures.LEAD_M, DemoFixtures.LEAD_ETA_SEC) else null

    override suspend fun guideTo(place: Place): GuideOutcome {
        val navi = real ?: return GuideOutcome.UNSUPPORTED
        if (!deferReal) return navi.guideTo(place)
        onDeferred(place)
        return GuideOutcome.WAYPOINT_ADDED
    }

    override fun openSearch(keyword: String): Boolean = real?.openSearch(keyword) ?: false
}

/**
 * STT | 실제, 실패 시 스크립트(15.1).
 * 답은 시나리오 줄의 시각에 나가야 엔진 전이가 표와 같다. 실제 인식 결과도 컨트롤러가 그 줄까지 시계를 옮긴 뒤에 돌려준다.
 */
class ScriptedListener(
    private val real: ListenerPort? = null,
    private val onScripted: (String) -> Unit = {},
) : ListenerPort {
    /** text는 시나리오의 답, accepts는 실제 인식 결과를 그대로 써도 되는지(분류가 되는 문장인지) */
    class PlannedAnswer(val text: String, val accepts: (String) -> Boolean)

    override val available: StateFlow<Boolean> = MutableStateFlow(true)

    private val _awaiting = MutableStateFlow(false)
    /** 답을 받을 준비가 된 상태. 컨트롤러가 이 값을 보고 줄의 입력을 넘긴다 */
    val awaiting: StateFlow<Boolean> = _awaiting

    private val _usedScript = MutableStateFlow(false)
    /** 표지의 "음성 응답은 시나리오 입력"(15.2)을 결정한다 */
    val usedScript: StateFlow<Boolean> = _usedScript

    var planner: () -> PlannedAnswer? = { null }

    @Volatile private var gate: CompletableDeferred<String>? = null

    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult {
        val plan = planner()
        val usable = real?.takeIf { it.available.value }
        if (usable == null) onReady()
        val heard = usable?.listen(timeoutMs, onReady)
        if (plan == null) return heard ?: ListenResult.NoSpeech

        val delivery = CompletableDeferred<String>()
        gate = delivery
        _awaiting.value = true
        val scripted = try {
            delivery.await()
        } finally {
            _awaiting.value = false
            gate = null
        }
        if (heard is ListenResult.Heard && plan.accepts(heard.text)) return heard
        _usedScript.value = true
        if (scripted.isNotBlank()) onScripted(scripted)
        return if (scripted.isBlank()) ListenResult.NoSpeech else ListenResult.Heard(scripted)
    }

    /** 듣고 있지 않으면 false */
    fun deliver(text: String): Boolean = gate?.complete(text) ?: false

    override fun stopNow() {
        real?.stopNow()
    }
}
