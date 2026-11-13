package com.conditiondrive.vehicle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.demoBannerText
import com.conditiondrive.vehicle.ui.components.demoMark
import com.conditiondrive.vehicle.ui.model.BackPolicy
import com.conditiondrive.vehicle.ui.model.DeniedPermission
import com.conditiondrive.vehicle.ui.model.GlucoseHistory
import com.conditiondrive.vehicle.ui.model.GlucosePoint
import com.conditiondrive.vehicle.ui.model.MainGate
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.S1Presenter
import com.conditiondrive.vehicle.ui.model.S2Presenter
import com.conditiondrive.vehicle.ui.model.S3Presenter
import com.conditiondrive.vehicle.ui.model.S4Presenter
import com.conditiondrive.vehicle.ui.model.Screen
import com.conditiondrive.vehicle.ui.model.SetupGate
import com.conditiondrive.vehicle.ui.model.SetupBack
import com.conditiondrive.vehicle.ui.model.SetupGateAction
import com.conditiondrive.vehicle.ui.model.UiText
import com.conditiondrive.vehicle.ui.model.text
import com.conditiondrive.vehicle.ui.model.routeScreen
import com.conditiondrive.vehicle.ui.screens.OnboardingScreen
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.screens.S1Screen
import com.conditiondrive.vehicle.ui.screens.S2Screen
import com.conditiondrive.vehicle.ui.screens.S3Screen
import com.conditiondrive.vehicle.ui.screens.S4Screen
import com.conditiondrive.vehicle.ui.screens.S5Screen
import com.conditiondrive.vehicle.ui.screens.S5State
import com.conditiondrive.vehicle.ui.screens.SetupWaitingScreen
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import com.conditiondrive.vehicle.ui.screens.previous
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
private fun demoLabelFor(source: VehicleUiSource): String? {
    val label by source.demoLabel.collectAsStateWithLifecycle()
    val caption by source.demoCaption.collectAsStateWithLifecycle()
    val view by source.view.collectAsStateWithLifecycle()
    val simulated = stringResource(R.string.glucose_simulated_banner)
    // 소리 없는 환경에서도 시연 내용이 전달되게 표지 아래에 자막을 붙인다(R10). 표지 띠 안이라 터치가 없다
    return label?.let { banner -> demoMark(demoBannerText(banner, view.glucoseSimulated, simulated), caption) }
}

/**
 * MainActivity 내용. 전환 효과 없이 when으로 즉시 교체한다.
 * 탭 값은 게이트 뒤에서만 의미가 있다. DRIVING이면 routeScreen이 S1만 고른다.
 */
@Composable
fun MainContent(
    source: VehicleUiSource,
    requestedTab: ParkedTab?,
    onRequestedTabConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    holdColdStart: Boolean = false,
    onboardingPending: Boolean = false,
) {
    val view by source.view.collectAsStateWithLifecycle()
    val mode by source.uiMode.collectAsStateWithLifecycle()
    val demoLabel = demoLabelFor(source)

    var tab by rememberSaveable { mutableStateOf(ParkedTab.TODAY) }
    // 8.1 복귀 규칙: 저장된 탭이 아니라 현재 모드와 상태로 고른다
    LifecycleStartEffect(Unit) {
        onStopOrDispose { tab = ParkedTab.TODAY }
    }
    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            if (it != ParkedTab.SETTINGS) tab = it
            onRequestedTabConsumed()
        }
    }
    LaunchedEffect(mode) {
        if (mode == UiMode.DRIVING) tab = ParkedTab.TODAY
    }

    val onTab: (ParkedTab) -> Unit = { selected ->
        if (selected == ParkedTab.SETTINGS) onOpenSettings() else tab = selected
    }
    val history = rememberGlucoseHistory(source, view)

    var settled by rememberSaveable { mutableStateOf(!holdColdStart) }
    LaunchedEffect(mode, view.driving) {
        if (mode == UiMode.PARKED || view.driving) settled = true
    }
    LaunchedEffect(Unit) {
        delay(SetupGate.COLD_START_GRACE_MS)
        settled = true
    }
    val waiting = MainGate.showWaiting(settled, mode, view.driving, onboardingPending)
    // 기록 탭만 Back으로 오늘 탭에 돌아간다. 나머지는 받지 않아 Activity가 숨긴다(U-27)
    BackHandler(enabled = BackPolicy.mainHandlesBack(mode, tab, waiting)) { tab = ParkedTab.TODAY }
    if (waiting) {
        SetupWaitingScreen(demoLabel)
        return
    }

    when (routeScreen(mode, tab, view)) {
        Screen.S1 -> {
            val place by source.promptPlace.collectAsStateWithLifecycle()
            val route by source.route.collectAsStateWithLifecycle()
            val listening by source.listening.collectAsStateWithLifecycle()
            val limits by source.limits.collectAsStateWithLifecycle()
            // 수치는 시연 표지가 있을 때만이다. 실사용 S1에는 혈당을 표시하지 않는다(14장)
            val demoGlucose = if (demoLabel != null) S1Presenter.demoGlucose(view) else null
            S1Screen(
                S1Presenter.present(view, place, route?.remainingSec, listening, limits),
                demoLabel,
                demoPanel = demoGlucose?.let { line -> { DemoGlucosePanel(line) } },
            )
        }
        Screen.S2 -> {
            val passenger by source.passengerPresent.collectAsStateWithLifecycle()
            val summary by source.summary.collectAsStateWithLifecycle()
            S2Screen(S2Presenter.present(view, passenger, summary.phoneName, source.nowMs()), demoLabel, onTab)
        }
        Screen.S3 -> {
            val passenger by source.passengerPresent.collectAsStateWithLifecycle()
            S3Screen(S3Presenter.present(view, passenger, source.nowMs()), history.value, demoLabel, onTab)
        }
        Screen.S4 -> {
            val trips by source.tripsToday.collectAsStateWithLifecycle(initialValue = emptyList())
            val recent by source.tripsRecent.collectAsStateWithLifecycle(initialValue = emptyList())
            val facts by source.settings.collectAsStateWithLifecycle()
            S4Screen(
                S4Presenter.present(trips, source.nowMs(), source.zone, facts.todayDistanceKm, facts.measurementGap, recent),
                demoLabel,
                onTab,
            )
        }
        // S5는 SetupActivity 소유다. 설정 탭은 탭 상태를 바꾸지 않고 Activity를 연다
        Screen.S5 -> Unit
    }
}

/**
 * 소리 없이 봐도 왜 주의인지 알 수 있게 수치 한 줄만 둔다. 터치가 없다.
 * 그래프는 넣지 않는다. 값이 들어올 때마다 선이 늘어나면 주행 중 움직이는 그림이다(16장, 플랫폼 주행 제한). 추이는 S3에서 본다.
 */
@Composable
private fun DemoGlucosePanel(line: UiText) {
    Spacer(Modifier.height(CdDimens.Space16))
    CdText(line.text(), CdTheme.type.bodyL)
}

/**
 * 값이 들어올 때만 늘어난다. 메모리에만 있고 프로세스가 끝나면 사라진다(14장).
 * 시연과 실사용은 런타임이 다르다. 시각이 다른 두 런타임의 값을 한 곡선에 잇지 않게 런타임마다 새로 시작한다.
 */
@Composable
private fun rememberGlucoseHistory(source: VehicleUiSource, view: EngineView): State<List<GlucosePoint>> {
    val history = remember(source.view) { mutableStateOf(emptyList<GlucosePoint>()) }
    LaunchedEffect(source.view, view.glucoseMeasuredAtMs, view.glucoseUnavailable) {
        history.value = GlucoseHistory.append(history.value, view)
    }
    return history
}

/** SetupActivity 내용. PARKED가 아니면 S5와 온보딩을 그리지 않는다 */
@Composable
fun SetupContent(
    source: VehicleUiSource,
    showOnboarding: Boolean,
    onboardingStep: OnboardingStep,
    denied: Set<DeniedPermission>,
    serverDraft: String,
    pendingScenario: String?,
    pendingRealSignals: Boolean,
    onStepChange: (OnboardingStep) -> Unit,
    onOnboardingDone: () -> Unit,
    onRequestPermissions: () -> Unit,
    onServerDraft: (String) -> Unit,
    onTab: (ParkedTab) -> Unit,
    onLeave: () -> Unit,
) {
    val mode by source.uiMode.collectAsStateWithLifecycle()
    val demoLabel = demoLabelFor(source)

    var everParked by rememberSaveable { mutableStateOf(false) }
    var graceOver by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SetupGate.COLD_START_GRACE_MS)
        graceOver = true
    }
    LaunchedEffect(mode) {
        if (mode == UiMode.PARKED) everParked = true
    }

    val gate = SetupGate.decide(mode, everParked, graceOver)
    // 온보딩은 이전 단계로, 설정은 오늘 탭으로. 온보딩 첫 단계는 받지 않아 Activity가 숨긴다(U-27).
    // S5의 열린 페이지는 S5Screen이 나중에 등록한 핸들러로 먼저 닫는다
    val back = BackPolicy.setupBack(gate, showOnboarding, onboardingStep.previous() == null)
    BackHandler(enabled = back != SetupBack.HIDE) {
        when (back) {
            SetupBack.PREVIOUS_STEP -> onboardingStep.previous()?.let(onStepChange)
            SetupBack.LEAVE -> onLeave()
            SetupBack.SWALLOW, SetupBack.HIDE -> Unit
        }
    }
    when (gate) {
        SetupGateAction.WAIT -> SetupWaitingScreen(demoLabel)
        SetupGateAction.FINISH -> LaunchedEffect(Unit) { onLeave() }
        SetupGateAction.SHOW -> when {
            pendingScenario != null -> LaunchedEffect(pendingScenario) {
                source.actions.startDemo(pendingScenario, pendingRealSignals)
                onLeave()
            }
            showOnboarding -> OnboardingHost(source, onboardingStep, demoLabel, DeniedPermission.MIC in denied, onStepChange, onRequestPermissions, onOnboardingDone)
            else -> {
                val facts by source.settings.collectAsStateWithLifecycle()
                val link by source.link.collectAsStateWithLifecycle()
                val summary by source.linkedSummary.collectAsStateWithLifecycle()
                val view by source.view.collectAsStateWithLifecycle()
                S5Screen(
                    // U-30. 엔진 E34가 받는 조건(값을 받은 적 있고 지금 유효한 값 없음)과 같은 뷰 값으로 버튼을 보인다
                    state = S5State(
                        facts, link, summary.phoneName, denied, demoLabel, serverDraft,
                        glucoseHold = view.glucoseUnavailable,
                        holdReleasableAtMs = view.glucoseHoldReleasableAtMs,
                        glucoseLive = view.glucoseMmol != null,
                    ),
                    actions = source.actions,
                    onTab = onTab,
                    onServerDraft = onServerDraft,
                    onDemoStarted = onLeave,
                    nowMs = source.nowMs,
                    onRequestPermissions = onRequestPermissions,
                )
            }
        }
    }
}

@Composable
private fun OnboardingHost(
    source: VehicleUiSource,
    step: OnboardingStep,
    demoLabel: String?,
    micDenied: Boolean,
    onStepChange: (OnboardingStep) -> Unit,
    onRequestPermissions: () -> Unit,
    onDone: () -> Unit,
) {
    var trial by remember { mutableStateOf<VoiceTrial>(VoiceTrial.Idle) }
    var trialJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // Back으로 듣기 단계를 떠나면 말하기·마이크를 멈춘다. 그대로 두면 권한 단계 뒤에서 마이크가 열려 있다(U-27)
    LaunchedEffect(step) {
        if (step != OnboardingStep.LISTEN) {
            trialJob?.cancel()
            trialJob = null
            if (trial is VoiceTrial.Speaking || trial is VoiceTrial.Listening) trial = VoiceTrial.Idle
        }
    }
    LaunchedEffect(micDenied) {
        if (!micDenied && trial is VoiceTrial.Unavailable) trial = VoiceTrial.Idle
    }
    OnboardingScreen(
        step = step,
        trial = trial,
        demoLabel = demoLabel,
        onRequestPermissions = onRequestPermissions,
        onNext = {
            onStepChange(if (step == OnboardingStep.INTRO) OnboardingStep.PERMISSIONS else OnboardingStep.LISTEN)
        },
        onListen = {
            // 앱이 말하는 동안 "듣고 있어요"라고 하면 언제 답할지 알 수 없다. 마이크가 열린 뒤에 바꾼다
            trial = VoiceTrial.Speaking
            trialJob = scope.launch {
                trial = source.actions.tryVoice(onListening = { trial = VoiceTrial.Listening })
            }
        },
        onFinish = onDone,
        voiceBlocked = micDenied,
    )
}
