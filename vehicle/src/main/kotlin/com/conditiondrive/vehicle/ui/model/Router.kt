package com.conditiondrive.vehicle.ui.model

import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.vehicle.port.UiMode

enum class ParkedTab { TODAY, LOG, SETTINGS }
enum class Screen { S1, S2, S3, S4, S5 }

/**
 * 8.1 게이트. DRIVING이면 탭 값과 무관하게 S1이다.
 * 분기를 한곳에 둬 S2~S5로 가는 경로가 게이트를 지나지 않을 수 없게 한다.
 */
fun routeScreen(mode: UiMode, tab: ParkedTab, view: EngineView): Screen = when {
    mode == UiMode.DRIVING -> Screen.S1
    tab == ParkedTab.LOG -> Screen.S4
    tab == ParkedTab.SETTINGS -> Screen.S5
    view.state == DriverState.RECOVER || view.preDepartureHold -> Screen.S3
    else -> Screen.S2
}

/**
 * 앱을 켠 직후는 게이트가 3초를 채우기 전이라 DRIVING이다. 터치 0은 맞지만 P인데 "여유 / 다음 휴식까지"가 먼저 보이면 화면이 틀렸다.
 * 첫 판정 전에는 S1 대신 터치 없는 대기 문장을 보인다. 실제로 달리고 있으면 바로 S1이다.
 */
object MainGate {
    fun showWaiting(settled: Boolean, mode: UiMode, driving: Boolean, onboardingPending: Boolean): Boolean = when {
        mode == UiMode.PARKED -> onboardingPending
        driving -> false
        else -> !settled
    }
}

/** SetupActivity(DO 미선언)의 판단. 플랫폼 차단이 있어도 자체적으로 한 번 더 막는다 */
enum class SetupGateAction { WAIT, SHOW, FINISH }

object SetupGate {
    /** 콜드 스타트는 게이트가 3초(C-39)를 채우기 전까지 DRIVING이라 곧바로 닫으면 adb 시연 진입이 항상 실패한다 */
    const val COLD_START_GRACE_MS = 6000L

    fun decide(mode: UiMode, everParked: Boolean, graceOver: Boolean): SetupGateAction = when {
        mode == UiMode.PARKED -> SetupGateAction.SHOW
        everParked || graceOver -> SetupGateAction.FINISH
        else -> SetupGateAction.WAIT
    }
}

/** 8.3 S5. 제목을 연속으로 눌러야 나타나는 숨김 설정 */
class HiddenTapCounter(private val required: Int = 5, private val windowMs: Long = 3000) {
    private var count = 0
    private var lastAtMs = Long.MIN_VALUE

    fun tap(atMs: Long): Boolean {
        count = if (lastAtMs != Long.MIN_VALUE && atMs - lastAtMs <= windowMs) count + 1 else 1
        lastAtMs = atMs
        if (count < required) return false
        count = 0
        return true
    }
}

/** 8.3 S5, B-09. LAN 평문으로 건강 데이터가 흐르지 않게 https와 개발용 주소만 받는다 */
object ServerUrlRule {
    private val DEV_HOSTS = listOf("http://10.0.2.2:", "http://127.0.0.1:")
    private val HTTPS = Regex("""^https://[^\s/?#]+(/\S*)?$""")
    private val DEV_PORT = Regex("""^\d{1,5}(/\S*)?$""")

    fun accepts(url: String): Boolean {
        val u = url.trim()
        if (HTTPS.matches(u)) return true
        val host = DEV_HOSTS.firstOrNull { u.startsWith(it) } ?: return false
        return DEV_PORT.matches(u.removePrefix(host))
    }
}
