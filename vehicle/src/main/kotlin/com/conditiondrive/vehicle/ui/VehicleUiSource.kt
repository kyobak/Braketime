package com.conditiondrive.vehicle.ui

import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.LocationVia
import com.conditiondrive.vehicle.port.RouteSnapshot
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.UxLimits
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

/**
 * 화면 입력 전부. 화면은 이 묶음만 보고 포트 구현을 모른다.
 * 런타임은 10.1의 StateFlow를 그대로 넘긴다(view, promptPlace, demoLabel은 EngineRuntime, uiMode와 limits는 DrivingGatePort).
 */
class VehicleUiSource(
    val view: StateFlow<EngineView>,
    val uiMode: StateFlow<UiMode>,
    val limits: StateFlow<UxLimits>,
    val promptPlace: StateFlow<PlaceCandidate?>,
    val demoLabel: StateFlow<String?>,
    val route: StateFlow<RouteSnapshot?>,
    val passengerPresent: StateFlow<Boolean?>,
    val summary: StateFlow<SummarySnapshot>,
    /** S5의 폰 연결. 시연 요약(summary)을 따라가지 않는다. 시나리오 값은 폰에서 오지 않았다 */
    val link: StateFlow<LinkState>,
    val tripsToday: Flow<List<TripRecord>>,
    val listening: StateFlow<Boolean>,
    val settings: StateFlow<SettingsFacts>,
    val actions: SettingsActions,
    val nowMs: () -> Long,
    val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    val demoCaption: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow(),
    val linkedSummary: StateFlow<SummarySnapshot> = summary,
    /** S4 "최근 7일" 원본. 창은 RecentSummary가 자른다 */
    val tripsRecent: Flow<List<TripRecord>> = flowOf(emptyList()),
)

/** S5가 문장으로 바꿔 보여 주는 사실. null은 아직 모른다는 뜻이다 */
data class SupportFacts(
    val vehicleName: String? = null,
    val capabilities: VehicleCapabilities? = null,
    val naviAvailable: Boolean? = null,
    val waypointSupported: Boolean? = null,
    val speaker: EngineKind? = null,
    val listener: EngineKind? = null,
    val location: LocationVia? = null,
    val ventilationSupported: Boolean? = null,
    val occupancySupported: Boolean? = null,
)

/** 10.2 대체 경로. 사용자에게 어느 엔진이 도는지 문장으로 알린다 */
enum class EngineKind { VEHICLE, DEVICE, NONE }

data class PairingFacts(val code: String, val qrPayload: String)

/** S5 데이터 화면이 조작 결과를 문장으로 알린다. 두 번 누른 버튼이 원래 문구로 돌아갈 뿐이면 지워졌는지 알 수 없다 */
enum class DataNotice { TRIPS_CLEARED, UNPAIRED, SERVER_DELETED, SERVER_PENDING }

/**
 * S5 보류 풀기 결과(U-30). 엔진이 받았는지까지 확인한 결과다.
 * 그 사이 출발했거나 값이 돌아왔으면 엔진이 거절하므로 화면이 "풀었어요"라고 하면 안 된다
 */
enum class HoldRelease { RELEASED, REFUSED, UNAVAILABLE }

data class DemoFacts(
    val running: Boolean = false,
    val pendingPlace: String? = null,   // 시연이 남긴 장소. 사람이 눌러야 실제 내비로 간다
    val sendOffline: Boolean = false,
)

data class SettingsFacts(
    val pairing: PairingFacts? = null,
    val pairingFailed: Boolean = false,
    val guardianRegistered: Boolean = false,
    val dataNotice: DataNotice? = null,
    val demo: DemoFacts = DemoFacts(),
    val support: SupportFacts = SupportFacts(),
    val serverUrl: String? = null,
    val todayDistanceKm: Double? = null,
    val measurementGap: Boolean = false,      // 10.3 자동 실행 실패 구간
)

/** S5 조작. 화면은 결과를 settings 흐름으로 다시 받는다. 기본 구현이 없는 항목은 아무 일도 하지 않는다 */
interface SettingsActions {
    fun requestPairingCode() {}
    fun unpair() {}
    fun clearTrips() {}
    fun deleteServerData() {}
    fun setServerUrl(url: String) {}
    fun startDemo(scenarioId: String, realSignals: Boolean) {}
    fun stopDemo() {}
    fun restartDemo() {}
    fun sendDemoPlace() {}

    /** U-30, E34. 값을 받은 뒤 끊긴 혈당 보류를 차에서 푼다. 정차 중인지와 실제 낮은 값은 엔진이 다시 거른다 */
    suspend fun releaseGlucoseHold(): HoldRelease = HoldRelease.UNAVAILABLE

    /** O3. 말하고 듣는다. 마이크가 열리면 onListening을 부른다. 들을 수 없으면 묻지도 않는다 */
    suspend fun tryVoice(onListening: () -> Unit = {}): VoiceTrial = VoiceTrial.Unavailable
}

/**
 * Activity와 서비스의 수명이 달라 인터페이스로 잇는다.
 * 런타임이 뜨기 전에는 미연결 원본을 준다. 8.1: 미연결은 DRIVING이므로 S2~S5가 열리지 않는다.
 */
object UiGraph {
    private val current = MutableStateFlow(unconnected())
    val source: StateFlow<VehicleUiSource> = current.asStateFlow()

    fun install(source: VehicleUiSource) {
        current.value = source
    }

    fun unconnected(nowMs: () -> Long = System::currentTimeMillis): VehicleUiSource {
        val engine = Engine()
        val now = nowMs()
        return VehicleUiSource(
            view = MutableStateFlow(engine.view(engine.initial(now), now)).asStateFlow(),
            uiMode = MutableStateFlow(UiMode.DRIVING).asStateFlow(),
            limits = MutableStateFlow(UxLimits(noKeyboard = false, noSetup = false, limitContent = false, limitStringLength = false)).asStateFlow(),
            promptPlace = MutableStateFlow<PlaceCandidate?>(null).asStateFlow(),
            demoLabel = MutableStateFlow<String?>(null).asStateFlow(),
            route = MutableStateFlow<RouteSnapshot?>(null).asStateFlow(),
            passengerPresent = MutableStateFlow<Boolean?>(null).asStateFlow(),
            summary = MutableStateFlow(SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)).asStateFlow(),
            link = MutableStateFlow(LinkState.UNPAIRED).asStateFlow(),
            tripsToday = flowOf(emptyList()),
            listening = MutableStateFlow(false).asStateFlow(),
            settings = MutableStateFlow(SettingsFacts()).asStateFlow(),
            actions = object : SettingsActions {},
            nowMs = nowMs,
        )
    }
}
