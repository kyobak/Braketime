package com.conditiondrive.vehicle.ui.model

import androidx.compose.runtime.Immutable
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.LocationVia
import com.conditiondrive.vehicle.ui.DataNotice
import com.conditiondrive.vehicle.ui.EngineKind
import com.conditiondrive.vehicle.ui.SettingsFacts
import com.conditiondrive.vehicle.ui.SupportFacts

enum class S5Page { DEVICES, GUARDIAN, SEARCH, SUPPORT, SOURCES, DATA, DEMO, SERVER }

/** 거부한 권한 표시용. 온보딩이 거부해도 앞으로 넘어가므로 S5에 사실로 남긴다 */
enum class DeniedPermission(val label: Int) {
    SPEED(R.string.perm_speed),
    LOCATION(R.string.perm_location),
    MIC(R.string.perm_mic),
    NOTIFY(R.string.perm_notify),
    ;

    companion object {
        /**
         * 온보딩과 S5가 요청하는 실행 시간 권한. 다른 Pleos 권한은 설치 때 부여된다(spike-results).
         * FUSED_LOCATION도 dangerous라 빠지면 -g 없는 설치에서 FusedLocation SDK를 영영 부르지 못한다.
         */
        val REQUESTED: Map<String, DeniedPermission> = mapOf(
            "android.car.permission.CAR_SPEED" to SPEED,
            "pleos.car.permission.FUSED_LOCATION" to LOCATION,
            "android.permission.ACCESS_FINE_LOCATION" to LOCATION,
            "android.permission.ACCESS_COARSE_LOCATION" to LOCATION,
            "android.permission.RECORD_AUDIO" to MIC,
            "android.permission.POST_NOTIFICATIONS" to NOTIFY,
        )
    }
}

@Immutable
data class SupportRow(val label: UiText, val value: UiText)

@Immutable
data class S5Rows(val devices: UiText, val guardian: UiText, val search: UiText)

object S5Presenter {
    fun rows(link: LinkState, phoneName: String?, facts: SettingsFacts, glucoseHold: Boolean = false): S5Rows = S5Rows(
        devices = when {
            // 창 하나에서는 목록만 보인다. 폰 쪽 해제 뒤에는 "없음"만 보여 보류 풀기 버튼을 찾을 단서가 없다(U-30)
            glucoseHold -> UiText.res(R.string.glucose_unavailable_hidden)
            link == LinkState.UNPAIRED -> UiText.res(R.string.value_none)
            // 서버에 닿지 않아도 연결은 살아 있다. "없음"이면 없는 연결을 다시 맺으려 한다
            link == LinkState.OFFLINE || phoneName == null -> UiText.res(R.string.value_not_linked)
            else -> UiText.Plain(phoneName)
        },
        guardian = UiText.res(if (facts.guardianRegistered) R.string.value_registered else R.string.value_none),
        search = UiText.res(R.string.value_off),
    )

    fun dataNotice(notice: DataNotice): Int = when (notice) {
        DataNotice.TRIPS_CLEARED -> R.string.data_trips_cleared
        DataNotice.UNPAIRED -> R.string.data_unpaired
        DataNotice.SERVER_DELETED -> R.string.data_server_deleted
        DataNotice.SERVER_PENDING -> R.string.data_server_pending
    }

    /**
     * 지원 현황은 (항목, 상태) 행이다. 지원 안 됨도 같은 색의 문장으로 쓴다(design-tokens 2.3 비활성 색 금지).
     * 화면 정보는 기록용이라 행이 아니라 맨 아래 출처 줄로 둔다(screenLine).
     */
    fun supportRows(facts: SupportFacts, denied: Set<DeniedPermission>): List<SupportRow> = buildList {
        fun line(label: Int, value: UiText) = add(SupportRow(UiText.res(label), value))
        fun known(known: Boolean?, on: Int, off: Int) = UiText.res(
            when (known) {
                null -> R.string.support_checking
                true -> on
                false -> off
            },
        )

        facts.vehicleName?.let { line(R.string.support_vehicle, UiText.Plain(it)) }
        val caps = facts.capabilities
        if (caps != null && !caps.gear && !caps.speed) {
            line(R.string.support_drive_signal, UiText.res(R.string.support_no_drive_signal))
        } else {
            line(R.string.support_gear, known(caps?.gear, R.string.support_supported, R.string.support_gear_off))
            line(R.string.support_speed, known(caps?.speed, R.string.support_supported, R.string.support_speed_off))
        }
        line(R.string.support_ignition, known(caps?.ignition, R.string.support_supported, R.string.support_ignition_off))
        line(R.string.support_route, known(facts.naviAvailable, R.string.support_route_on, R.string.support_route_off))
        line(R.string.support_waypoint, known(facts.waypointSupported, R.string.support_supported, R.string.support_unsupported))
        line(R.string.support_voice, engine(facts.speaker, R.string.support_voice_vehicle, R.string.support_voice_device, R.string.support_voice_off))
        line(R.string.support_listen, engine(facts.listener, R.string.support_listen_vehicle, R.string.support_listen_device, R.string.support_listen_off))
        line(R.string.support_location, location(facts.location))
        line(R.string.support_vent, known(facts.ventilationSupported, R.string.support_supported, R.string.support_unsupported))
        line(R.string.support_seat, known(facts.occupancySupported, R.string.support_seat_unverified, R.string.support_seat_off))
        denied.forEach { add(SupportRow(UiText.res(R.string.support_perm_label, UiText.res(it.label)), UiText.res(R.string.support_perm_denied))) }
    }

    fun screenLine(density: Float, ydpi: Float): UiText =
        UiText.res(R.string.support_line, UiText.res(R.string.support_screen), UiText.res(R.string.support_screen_value, density.toString(), ydpi.toString()))

    private fun location(via: LocationVia?) = UiText.res(
        when (via) {
            null -> R.string.support_checking
            LocationVia.FUSED -> R.string.support_location_on
            LocationVia.NAVI -> R.string.support_location_navi
            LocationVia.DEVICE -> R.string.support_location_device
            LocationVia.NONE -> R.string.support_location_off
        },
    )

    private fun engine(kind: EngineKind?, vehicle: Int, device: Int, none: Int) = UiText.res(
        when (kind) {
            null -> R.string.support_checking
            EngineKind.VEHICLE -> vehicle
            EngineKind.DEVICE -> device
            EngineKind.NONE -> none
        },
    )

    fun sourceLines(): List<UiText> {
        val p = Policy.DEFAULT
        return listOf(
            UiText.res(R.string.src_rest),
            // U-28. 주의 시점은 규정이 아니라 정차 권고를 미리 알리려는 설계값이다
            UiText.res(
                R.string.src_caution_time,
                Formats.driveTime(p.FATIGUE_CAUTION_SEC),
                ((p.FATIGUE_STOP_SEC - p.FATIGUE_CAUTION_SEC) / 60).toInt(),
            ),
            UiText.res(
                R.string.src_glucose,
                UiFormat.mmol(p.GLUCOSE_STOP_BELOW),
                UiFormat.mmol(p.GLUCOSE_RECOVER_AT),
                (p.GLUCOSE_WAIT_SEC / 60).toInt(),
            ),
            UiText.res(R.string.src_sleep, p.SLEEP_SHORT_MINUTES / 60, (p.ADVANCE_SEC / 60).toInt()),
            UiText.res(
                R.string.src_caution,
                UiFormat.mmol(p.CAUTION_FALLING_FAST_BELOW),
                UiFormat.mmol(p.CAUTION_FALLING_BELOW),
                UiFormat.mmol(p.CAUTION_FLAT_BELOW),
            ),
            UiText.res(R.string.src_poi),
            UiText.res(R.string.legal_not_official),
        )
    }
}

/** 15.2 시연 시나리오. 파일 이름은 시나리오 JSON과 같다(R1) */
enum class DemoScenario(val id: String, val label: Int) {
    SC1("sc1", R.string.demo_scenario_1),
    SC2("sc2", R.string.demo_scenario_2),
    SC3("sc3", R.string.demo_scenario_3),
}
