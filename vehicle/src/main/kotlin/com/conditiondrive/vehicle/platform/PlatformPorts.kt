package com.conditiondrive.vehicle.platform

import android.content.Context
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.LocationVia
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import com.conditiondrive.vehicle.port.VentilationPort
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** S5 지원 현황의 재료. null은 아직 모른다는 뜻이다 */
data class PlatformSupport(
    val capabilities: VehicleCapabilities = VehicleCapabilities(gear = false, speed = false, ignition = false),
    val speedPermission: Boolean = false,
    val naviAvailable: Boolean? = null,
    val waypointSupported: Boolean? = null,
    val speaker: SpeechEngineChoice = SpeechEngineChoice.VEHICLE,
    val listener: SpeechEngineChoice = SpeechEngineChoice.VEHICLE,
    val location: LocationVia = LocationVia.NONE,
    val ventilationSupported: Boolean? = null,
    val occupancySupported: Boolean? = null,
)

enum class SpeechEngineChoice { VEHICLE, DEVICE, NONE }

/**
 * 차량 API와 SDK 어댑터 전부를 한 번에 만들고 시작한다. 시연의 Scripted 포트는 이것과 같은 포트 인터페이스로 들어간다.
 * 생성과 시작 모두 예외를 내지 않는다. 안 되는 기능은 available, capabilities, null로 드러난다.
 */
class PlatformPorts internal constructor(private val context: Context) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> logFailure("platform", t) },
    )
    private val connection = CarConnection(context)
    private val vehicleSdk = VehicleSdk(context)
    private val focus = AndroidAudioFocus(context)
    // 말하기와 듣기는 요청이 다르다. 한 슬롯을 덮어쓰면 끝난 쪽의 손실 콜백이 다른 쪽을 끊는다
    private val listenFocus = AndroidAudioFocus(context)
    private val cue = ListenCue()
    private val pleosNavi = PleosNavi(context, scope)

    private val carSignals = CarVehicleSignals(connection, scope)
    val signals: VehicleSignalsPort get() = carSignals
    val gate: DrivingGatePort = CarDrivingGate(connection, carSignals, scope)
    val navi: NaviPort get() = pleosNavi
    val odometer: OdometerPort = PleosOdometer(vehicleSdk)
    private val pleosVentilation = PleosVentilation(vehicleSdk)
    val ventilation: VentilationPort get() = pleosVentilation
    private val pleosOccupancy = PleosOccupancy(vehicleSdk, scope)
    val occupancy: OccupancyPort get() = pleosOccupancy

    private var chainSpeaker: ChainSpeaker? = null
    private var chainListener: ChainListener? = null
    private var chainLocation: ChainLocation? = null

    val speaker: SpeakerPort
        get() = chainSpeaker ?: ChainSpeaker(listOf(PleosTtsEngine(context), AndroidTtsEngine(context)), focus, scope).also { chainSpeaker = it }
    val listener: ListenerPort
        get() = chainListener ?: ChainListener(
            listOf(PleosSttEngine(context, scope), AndroidSttEngine(context, scope)), listenFocus, scope, readyCue = cue::play,
        ).also { chainListener = it }
    private val fusedChain: ChainLocation
        get() = chainLocation ?: buildLocation().also { chainLocation = it }
    val location: LocationPort get() = fusedChain

    private val _support = MutableStateFlow(PlatformSupport())
    val support: StateFlow<PlatformSupport> = _support.asStateFlow()

    private var started = false
    private val supportJobs = mutableListOf<kotlinx.coroutines.Job>()

    private fun buildLocation(): ChainLocation {
        val now = { System.currentTimeMillis() }
        var chain: ChainLocation? = null
        val updated = { chain?.onSourceUpdated(); Unit }
        pleosNavi.onLocation = updated
        chain = ChainLocation(
            listOf(FusedLocationSource(context, now, updated), NaviLocationSource(pleosNavi, now), AndroidLocationSource(context, now, updated)),
            scope, now,
        )
        return chain
    }

    /** 공용 어댑터를 켠다. 게이트와 신호는 서비스 없이도 Activity가 볼 수 있어야 해서 프로세스 단위로 유지한다 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        guarded("platform start", Unit) {
            carSignals.start()
            gate.start()
            pleosNavi.start()
            speaker
            listener
            location.start()
            pleosOccupancy.start()
            watchSupport()
        }
        reviveSpeech()
    }

    /** 10.2 시동 ON, 서비스 시작, 권한 변경. 이전 운행에서 실패한 음성 엔진을 다시 시도한다 */
    private fun reviveSpeech() {
        scope.launch {
            chainSpeaker?.revive()
            chainListener?.revive()
        }
    }

    /** 온보딩에서 권한이 바뀌면 부른다. 권한이 늘어도 이미 실패한 구독은 저절로 살아나지 않는다 */
    fun refreshPermissions() {
        guarded("platform refresh", Unit) {
            carSignals.refresh()
            _support.value = _support.value.copy(speedPermission = CarVehicleSignals.hasSpeedPermission(context))
        }
        reviveSpeech()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        guarded("platform stop", Unit) {
            chainSpeaker?.stopNow()
            chainListener?.stopNow()
            location.stop()
            pleosOccupancy.stop()
            pleosNavi.stop()
            gate.stop()
            carSignals.stop()
            focus.release()
            listenFocus.release()
        }
        supportJobs.forEach { it.cancel() }
        supportJobs.clear()
    }

    /** 10.3: 시동 OFF. 신호만 남겨 다음 ON을 받는다. 음성, 마이크, 위치, 내비는 내려놓는다 */
    @Synchronized
    fun stopForIgnitionOff() {
        if (!started) return
        started = false
        guarded("platform off", Unit) {
            chainSpeaker?.stopNow()
            chainListener?.stopNow()
            location.stop()
            pleosOccupancy.stop()
            pleosNavi.stop()
            gate.stop()
            focus.release()
            listenFocus.release()
        }
        supportJobs.forEach { it.cancel() }
        supportJobs.clear()
    }

    private fun watchSupport() {
        _support.value = _support.value.copy(speedPermission = CarVehicleSignals.hasSpeedPermission(context))
        supportJobs += scope.launch {
            carSignals.capabilities.collect { c -> _support.value = _support.value.copy(capabilities = c, speedPermission = CarVehicleSignals.hasSpeedPermission(context)) }
        }
        supportJobs += scope.launch {
            combine(pleosNavi.available, pleosNavi.waypointSupported) { a, w -> a to w }.collect { (a, w) ->
                _support.value = _support.value.copy(naviAvailable = a, waypointSupported = w)
            }
        }
        supportJobs += scope.launch {
            (speaker as ChainSpeaker).activeEngine.collect { _support.value = _support.value.copy(speaker = it.toChoice()) }
        }
        supportJobs += scope.launch {
            (listener as ChainListener).activeEngine.collect { _support.value = _support.value.copy(listener = it.toChoice()) }
        }
        supportJobs += scope.launch {
            fusedChain.via.collect { via -> _support.value = _support.value.copy(location = via) }
        }
        supportJobs += scope.launch {
            pleosOccupancy.supported.collect { _support.value = _support.value.copy(occupancySupported = it) }
        }
        supportJobs += scope.launch {
            val ok = guarded("ventilation check", false) { pleosVentilation.supported() }
            _support.value = _support.value.copy(ventilationSupported = ok)
        }
    }

    private fun SpeechEngineKind.toChoice() = when (this) {
        SpeechEngineKind.PLEOS -> SpeechEngineChoice.VEHICLE
        SpeechEngineKind.ANDROID -> SpeechEngineChoice.DEVICE
        SpeechEngineKind.NONE -> SpeechEngineChoice.NONE
    }

    companion object {
        @Volatile private var instance: PlatformPorts? = null

        /** Activity와 서비스가 같은 연결을 쓴다. 어댑터는 하나만 있어야 SDK 초기화와 포커스가 겹치지 않는다 */
        fun get(context: Context): PlatformPorts = instance ?: synchronized(this) {
            instance ?: PlatformPorts(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * 런타임은 stop에서 신호를 내리지만 신호는 프로세스 공용이다(게이트, 시동 감시, 시연 진입 판정).
 * 소유자만 켜고 끄게 start, stop을 막는다.
 */
class SharedVehicleSignals(private val delegate: VehicleSignalsPort) : VehicleSignalsPort by delegate {
    override fun start() = Unit
    override fun stop() = Unit
}
