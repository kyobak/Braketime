package com.conditiondrive.vehicle.ui.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.conditiondrive.core.engine.AdvanceReason
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.TripSegment
import com.conditiondrive.vehicle.ui.PairingFacts
import com.conditiondrive.vehicle.ui.SettingsActions
import com.conditiondrive.vehicle.ui.SettingsFacts
import com.conditiondrive.vehicle.ui.model.GlucosePoint
import com.conditiondrive.vehicle.ui.model.S1Presenter
import com.conditiondrive.vehicle.ui.model.S2Presenter
import com.conditiondrive.vehicle.ui.model.S3Presenter
import com.conditiondrive.vehicle.ui.model.S4Presenter
import com.conditiondrive.vehicle.ui.screens.OnboardingScreen
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.screens.S1Screen
import com.conditiondrive.vehicle.ui.screens.S2Screen
import com.conditiondrive.vehicle.ui.screens.S3Screen
import com.conditiondrive.vehicle.ui.screens.S4Screen
import com.conditiondrive.vehicle.ui.screens.S5Screen
import com.conditiondrive.vehicle.ui.screens.S5State
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import com.conditiondrive.vehicle.ui.theme.CdTheme
import java.time.ZoneId

/** 프리뷰와 화면 테스트가 같은 값을 쓴다. 표에 없는 값을 화면 검증에 끼우지 않으려는 것이다 */
object UiSamples {
    const val NOW_MS = 1_793_000_000_000L
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    const val MINUTE = 60_000L

    fun view(
        state: DriverState = DriverState.NORMAL,
        reasons: Set<Reason> = emptySet(),
        cdsSec: Long = 48 * 60,
        cautionSec: Long = 6300,
        stopSec: Long = 7200,
        advanced: AdvanceReason? = null,
        remainingToRest: Long? = (stopSec - cdsSec).takeIf { it > 0 },
        restElapsed: Long? = null,
        restRemaining: Long? = null,
        recoveryRemaining: Long? = null,
        hold: Boolean = false,
        mmol: Double? = null,
        trend: Trend? = null,
        measuredAt: Long? = null,
        simulated: Boolean = false,
        unavailable: Boolean = false,
        sleep: Int? = null,
        checkin: CheckinAnswer? = null,
        sinceStart: Boolean = false,
        driving: Boolean = true,
    ) = EngineView(
        state = state,
        reasons = reasons,
        continuousDriveSec = cdsSec,
        cautionThresholdSec = cautionSec,
        stopThresholdSec = stopSec,
        advanced = advanced,
        remainingToRestSec = remainingToRest,
        restElapsedSec = restElapsed,
        restRemainingSec = restRemaining,
        glucoseRecoveryRemainingSec = recoveryRemaining,
        preDepartureHold = hold,
        ignoredCount = 0,
        glucoseMmol = mmol,
        glucoseTrend = trend,
        glucoseMeasuredAtMs = measuredAt,
        glucoseSimulated = simulated,
        glucoseUnavailable = unavailable,
        sleepMinutes = sleep,
        lastCheckin = checkin,
        measuredSinceAppStart = sinceStart,
        driving = driving,
    )

    val place = PlaceCandidate(
        Place(id = "sample", name = "칠곡졸음쉼터(부산방향)", latitude = 35.9, longitude = 128.4, kind = PlaceKind.DROWSY_SHELTER),
        straightDistanceM = 3200,
    )

    val normal = view()
    val cautionSoon = view(DriverState.CAUTION, setOf(Reason.FATIGUE), cdsSec = 6480)
    val cautionCheckin = view(DriverState.CAUTION, setOf(Reason.CHECKIN), cdsSec = 1200)
    val stopFatigue = view(DriverState.STOP, setOf(Reason.FATIGUE), cdsSec = 7680)
    val stopGlucose = view(
        DriverState.STOP, setOf(Reason.GLUCOSE), cdsSec = 2100,
        mmol = 3.9, trend = Trend.FALLING, measuredAt = NOW_MS - 2 * MINUTE, simulated = true,
    )
    val departGrace = view(DriverState.RECOVER, setOf(Reason.FATIGUE), cdsSec = 7680, restElapsed = 180, restRemaining = 720, driving = false)

    val todayFull = view(
        cdsSec = 0, sleep = 290, advanced = AdvanceReason.SLEEP_SHORT, cautionSec = 4500, stopSec = 5400,
        mmol = 5.4, trend = Trend.FALLING, measuredAt = NOW_MS - 3 * MINUTE, simulated = true,
        checkin = CheckinAnswer.NEUTRAL, driving = false,
    )
    val todayBasic = view(cdsSec = 0, checkin = CheckinAnswer.GOOD, driving = false)
    val todayNoGlucose = view(cdsSec = 0, unavailable = true, checkin = CheckinAnswer.GOOD, driving = false)

    /** U-29. 수면 요약을 지운 뒤에도 이번 운행의 앞당김이 남은 S2(실기 companion-33 상태) */
    val todayAdvanceKept = view(
        cdsSec = 0, advanced = AdvanceReason.SLEEP_SHORT, cautionSec = 4500, stopSec = 5400,
        checkin = CheckinAnswer.GOOD, driving = false,
    )

    /** 운전 40분 뒤 정차 중에 요약을 지운 경우. 15분 쉬면 운전 시간과 함께 앞당김이 풀린다 */
    val todayAdvanceKeptDriven = view(
        cdsSec = 40 * 60, advanced = AdvanceReason.SLEEP_SHORT, cautionSec = 4500, stopSec = 5400,
        checkin = CheckinAnswer.GOOD, driving = false,
    )

    val recoverFatigue = view(DriverState.RECOVER, setOf(Reason.FATIGUE), cdsSec = 7680, restElapsed = 180, restRemaining = 720, driving = false)
    val recoverGlucoseTimer = view(
        DriverState.RECOVER, setOf(Reason.GLUCOSE), cdsSec = 2400, restElapsed = 1200, restRemaining = 0,
        recoveryRemaining = 25 * 60L, mmol = 5.4, trend = Trend.RISING, measuredAt = NOW_MS - 3 * MINUTE, simulated = true, driving = false,
    )
    val recoverGlucoseWaiting = view(
        DriverState.RECOVER, setOf(Reason.GLUCOSE), cdsSec = 2400, restElapsed = 300, restRemaining = 600,
        mmol = 4.2, trend = Trend.RISING, measuredAt = NOW_MS - MINUTE, simulated = true, driving = false,
    )
    val hold = view(
        DriverState.RECOVER, setOf(Reason.GLUCOSE), cdsSec = 0, restElapsed = 5, restRemaining = 895, hold = true,
        mmol = 3.8, trend = Trend.FLAT, measuredAt = NOW_MS - MINUTE, simulated = true, driving = false,
    )
    val recoverUnconfirmed = view(
        DriverState.RECOVER, setOf(Reason.FATIGUE, Reason.GLUCOSE_UNCONFIRMED), cdsSec = 7680,
        restElapsed = 180, restRemaining = 720, unavailable = true, driving = false,
    )

    val glucoseHistory = listOf(6.4, 6.0, 5.4, 4.8, 4.2, 3.9, 3.8, 4.1, 4.6, 5.1, 5.4).mapIndexed { i, v ->
        GlucosePoint(NOW_MS - (10 - i) * 5 * MINUTE, v)
    }

    val trips = listOf(
        TripRecord(
            tripId = "sample",
            startedAtMs = NOW_MS - 300 * MINUTE,
            endedAtMs = null,
            segments = listOf(
                TripSegment("drive", NOW_MS - 300 * MINUTE, NOW_MS - 188 * MINUTE),
                TripSegment("rest", NOW_MS - 188 * MINUTE, NOW_MS - 173 * MINUTE),
                TripSegment("drive", NOW_MS - 173 * MINUTE, NOW_MS - 65 * MINUTE),
                TripSegment("rest", NOW_MS - 65 * MINUTE, NOW_MS - 47 * MINUTE),
            ),
            stopAdvised = 2, declined = 1, restCompleted = 2, conditionStops = 1,
        ),
    )

    private const val DAY = 24 * 60 * MINUTE

    /** 구간을 이어 붙인 지난 운행. 시작은 NOW 기준 며칠 전 몇 분 */
    private fun pastTrip(daysAgo: Int, startOffsetMin: Long, segments: List<Pair<String, Long>>, advised: Int, rested: Int): TripRecord {
        val start = NOW_MS - daysAgo * DAY + startOffsetMin * MINUTE
        var at = start
        val list = segments.map { (type, minutes) -> TripSegment(type, at, at + minutes * MINUTE).also { at += minutes * MINUTE } }
        return TripRecord("past-$daysAgo", start, at, list, stopAdvised = advised, declined = 0, restCompleted = rested, conditionStops = 0)
    }

    /** S4 "최근 7일" 견본. 오늘(trips) + 2·4·6일 전, 8일 전 운행은 창 밖이다. 운행 4회, 운전 8시간 37분, 안내 4회, 휴식 3회 */
    val recentTrips = trips + listOf(
        pastTrip(2, -510, listOf("drive" to 125L, "rest" to 15L, "drive" to 50L), advised = 2, rested = 1),
        pastTrip(4, 127, listOf("drive" to 42L), advised = 0, rested = 0),
        pastTrip(6, -563, listOf("drive" to 80L), advised = 0, rested = 0),
        pastTrip(8, -300, listOf("drive" to 200L, "rest" to 15L), advised = 1, rested = 1),
    )

    val settingsPaired = SettingsFacts(pairing = PairingFacts("K7M2QX9T", "conditiondrive://pair?v=1&code=K7M2QX9T"))
    val demoBanner = "시연 모드 · 시간 압축 ×240 · 시뮬레이션 시각 09:45"
    val noActions = object : SettingsActions {}
}

@Preview(name = "12.9 세로", widthDp = 628, heightDp = 855)
@Preview(name = "17 세로", widthDp = 840, heightDp = 1184)
@Preview(name = "12.9 가로", widthDp = 1274, heightDp = 855)
@Preview(name = "17 가로", widthDp = 1700, heightDp = 1184)
@Preview(name = "17 가로 야간", widthDp = 1700, heightDp = 1184, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR)
annotation class CdPreviews

@CdPreviews
@Composable
private fun S1NormalPreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.normal), null) }

@CdPreviews
@Composable
private fun S1CautionPreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.cautionSoon, UiSamples.place, 6000), null) }

@CdPreviews
@Composable
private fun S1StopPreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.stopFatigue, UiSamples.place, 6000), null) }

@CdPreviews
@Composable
private fun S1StopGlucosePreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.stopGlucose, UiSamples.place), UiSamples.demoBanner) }

@CdPreviews
@Composable
private fun S1ListeningPreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.cautionSoon, listening = true), null) }

@CdPreviews
@Composable
private fun S1RecoverPreview() = CdTheme { S1Screen(S1Presenter.present(UiSamples.departGrace), null) }

@CdPreviews
@Composable
private fun S2FullPreview() = CdTheme {
    S2Screen(S2Presenter.present(UiSamples.todayFull, false, "Galaxy S25", UiSamples.NOW_MS), null, {})
}

@CdPreviews
@Composable
private fun S2BasicPreview() = CdTheme { S2Screen(S2Presenter.present(UiSamples.todayBasic, null, null, UiSamples.NOW_MS), null, {}) }

@CdPreviews
@Composable
private fun S2AdvanceKeptPreview() = CdTheme {
    S2Screen(S2Presenter.present(UiSamples.todayAdvanceKept, false, null, UiSamples.NOW_MS), null, {})
}

@CdPreviews
@Composable
private fun S5HoldPreview() = CdTheme {
    S5Screen(
        S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "", glucoseHold = true),
        UiSamples.noActions,
        onTab = {},
        onServerDraft = {},
        onDemoStarted = {},
        nowMs = { UiSamples.NOW_MS },
    )
}

@CdPreviews
@Composable
private fun S2PassengerPreview() = CdTheme {
    S2Screen(S2Presenter.present(UiSamples.todayFull, true, "Galaxy S25", UiSamples.NOW_MS), null, {})
}

@CdPreviews
@Composable
private fun S3FatiguePreview() = CdTheme { S3Screen(S3Presenter.present(UiSamples.recoverFatigue, false, UiSamples.NOW_MS), emptyList(), null, {}) }

@CdPreviews
@Composable
private fun S3GlucosePreview() = CdTheme {
    S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, false, UiSamples.NOW_MS), UiSamples.glucoseHistory, null, {})
}

@CdPreviews
@Composable
private fun S3GlucosePassengerPreview() = CdTheme {
    S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, null, UiSamples.NOW_MS), UiSamples.glucoseHistory, null, {})
}

@CdPreviews
@Composable
private fun S3HoldPreview() = CdTheme {
    S3Screen(S3Presenter.present(UiSamples.hold, false, UiSamples.NOW_MS), UiSamples.glucoseHistory.take(3), null, {})
}

@CdPreviews
@Composable
private fun S3UnconfirmedPreview() = CdTheme {
    S3Screen(S3Presenter.present(UiSamples.recoverUnconfirmed, false, UiSamples.NOW_MS), emptyList(), null, {})
}

@CdPreviews
@Composable
private fun S4Preview() = CdTheme {
    S4Screen(S4Presenter.present(UiSamples.trips, UiSamples.NOW_MS, UiSamples.ZONE, 324.0, recent = UiSamples.recentTrips), null, {})
}

@CdPreviews
@Composable
private fun S5Preview() = CdTheme {
    S5Screen(
        S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, ""),
        UiSamples.noActions,
        onTab = {},
        onServerDraft = {},
        onDemoStarted = {},
        nowMs = { UiSamples.NOW_MS },
    )
}

@CdPreviews
@Composable
private fun OnboardingIntroPreview() = CdTheme {
    OnboardingScreen(OnboardingStep.INTRO, VoiceTrial.Idle, null, {}, {}, {}, {})
}

@CdPreviews
@Composable
private fun OnboardingPermissionPreview() = CdTheme {
    OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, {}, {}, {}, {})
}

@CdPreviews
@Composable
private fun OnboardingListenPreview() = CdTheme {
    OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Heard(CheckinAnswer.GOOD), null, {}, {}, {}, {})
}
