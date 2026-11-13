package com.conditiondrive.vehicle.ui

import com.conditiondrive.core.time.Clock
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.SummarySource
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.runtime.EngineRuntime
import com.conditiondrive.vehicle.ui.model.RecentSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 10.1 시그니처를 화면 입력으로 묶는다. 조립하는 쪽(서비스)이 UiGraph.install(vehicleUiSource(...))을 부른다.
 * 시그니처가 어긋나면 여기서 컴파일이 깨지므로 화면과 런타임이 조용히 갈라지지 않는다.
 */
fun vehicleUiSource(
    runtime: EngineRuntime,
    gate: DrivingGatePort,
    navi: NaviPort,
    occupancy: OccupancyPort,
    summary: SummarySource,
    trips: TripLogPort,
    clock: Clock,
    settings: StateFlow<SettingsFacts>,
    actions: SettingsActions,
    demoCaption: StateFlow<String?> = MutableStateFlow(null),
    phoneLink: SummarySource = summary,     // 실제 폰 연결. 시연 중에도 S5는 이것만 말한다
): VehicleUiSource = VehicleUiSource(
    view = runtime.view,
    uiMode = gate.uiMode,
    limits = gate.limits,
    promptPlace = runtime.promptPlace,
    demoLabel = runtime.demoLabel,
    route = navi.route,
    passengerPresent = occupancy.passengerPresent,
    summary = summary.snapshot,
    link = phoneLink.link,
    linkedSummary = phoneLink.snapshot,
    tripsToday = trips.today(),
    tripsRecent = trips.recent(RecentSummary.DAYS),
    listening = runtime.listening,
    settings = settings,
    actions = actions,
    nowMs = clock::nowMs,
    demoCaption = demoCaption,
)
