package com.conditiondrive.vehicle.ui.model

import com.conditiondrive.vehicle.port.UiMode

/**
 * Pleos CCP Back 숨김 정책(U-27). 하위 화면만 Back을 받아 닫고, 받지 않은 Back은
 * Activity의 마지막 콜백이 moveTaskToBack(true)로 숨긴다. 첫 화면에서 끝내면 심사 반려다.
 */
object BackPolicy {
    /**
     * 기록 탭만 하위 화면이다. 탭 바의 시작 화면은 오늘이고(8.1 복귀 규칙도 오늘로 돌아간다),
     * 주행 화면(S1)과 대기 문장은 돌아갈 곳이 없다. 설정 탭은 SetupActivity가 따로 받는다.
     */
    fun mainHandlesBack(mode: UiMode, tab: ParkedTab, waiting: Boolean): Boolean =
        !waiting && mode == UiMode.PARKED && tab == ParkedTab.LOG

    /**
     * 설정은 탭이라 Back이면 오늘 탭으로 간다. 온보딩은 앱의 첫 화면이라 첫 단계(또는 아직 보이지 않는 대기)에서는
     * 받지 않고 숨긴다. 끝내면 MainActivity가 온보딩을 다시 띄워 Back이 제자리걸음이 된다.
     * FINISH는 화면이 이미 스스로 닫히는 중이라 Back을 받아 버린다. 보이지 않는 단계를 바꾸거나 onLeave를 두 번 부르지 않고,
     * 닫히는 도중 숨기면 MainActivity 시작이 태스크를 다시 앞으로 끌어올 수 있다
     */
    fun setupBack(gate: SetupGateAction, onboarding: Boolean, firstStep: Boolean): SetupBack = when {
        gate == SetupGateAction.FINISH -> SetupBack.SWALLOW
        !onboarding -> SetupBack.LEAVE
        gate == SetupGateAction.WAIT -> SetupBack.HIDE
        firstStep -> SetupBack.HIDE
        else -> SetupBack.PREVIOUS_STEP
    }
}

/** SetupActivity의 Back 처리. HIDE만 화면이 받지 않아 Activity가 숨긴다 */
enum class SetupBack { HIDE, PREVIOUS_STEP, LEAVE, SWALLOW }
