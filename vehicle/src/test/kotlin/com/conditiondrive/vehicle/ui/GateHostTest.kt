package com.conditiondrive.vehicle.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.scenario.ScenarioSummary
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.summary.ScriptedSummarySource
import com.conditiondrive.vehicle.ui.components.TAG_GLUCOSE_GRAPH
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.Screen
import com.conditiondrive.vehicle.ui.model.SetupGate
import com.conditiondrive.vehicle.ui.model.SetupGateAction
import com.conditiondrive.vehicle.ui.model.routeScreen
import com.conditiondrive.vehicle.ui.preview.UiSamples
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.screens.TAG_ONBOARDING
import com.conditiondrive.vehicle.ui.screens.TAG_S1
import com.conditiondrive.vehicle.ui.screens.TAG_S2
import com.conditiondrive.vehicle.ui.screens.TAG_S3
import com.conditiondrive.vehicle.ui.screens.TAG_S4
import com.conditiondrive.vehicle.ui.screens.TAG_S5
import com.conditiondrive.vehicle.ui.theme.CdTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** H2, H3, H8과 8.1 게이트. 라우터와 실제 호스트를 함께 본다 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class GateHostTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val allViews: List<EngineView> = listOf(
        UiSamples.normal, UiSamples.cautionSoon, UiSamples.stopFatigue, UiSamples.stopGlucose,
        UiSamples.departGrace, UiSamples.hold, UiSamples.recoverGlucoseTimer,
    )

    /** H2: 어떤 탭, 어떤 상태든 DRIVING이면 S1 */
    @Test
    fun drivingAlwaysRoutesToS1() {
        for (tab in ParkedTab.entries) for (view in allViews) {
            assertEquals(Screen.S1, routeScreen(UiMode.DRIVING, tab, view))
        }
    }

    /** 8.1 표 */
    @Test
    fun parkedRouting() {
        assertEquals(Screen.S2, routeScreen(UiMode.PARKED, ParkedTab.TODAY, UiSamples.normal))
        assertEquals(Screen.S2, routeScreen(UiMode.PARKED, ParkedTab.TODAY, UiSamples.cautionSoon))
        assertEquals(Screen.S3, routeScreen(UiMode.PARKED, ParkedTab.TODAY, UiSamples.recoverFatigue))
        assertEquals(Screen.S3, routeScreen(UiMode.PARKED, ParkedTab.TODAY, UiSamples.hold))
        assertEquals(Screen.S4, routeScreen(UiMode.PARKED, ParkedTab.LOG, UiSamples.hold))
        assertEquals(Screen.S5, routeScreen(UiMode.PARKED, ParkedTab.SETTINGS, UiSamples.normal))
    }

    private fun showMain(fake: FakeSource, requested: ParkedTab? = null, onSettings: () -> Unit = {}) {
        var request by mutableStateOf(requested)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(840.dp, 1184.dp)) {
                    MainContent(fake.source, request, { request = null }, onSettings)
                }
            }
        }
        rule.waitForIdle()
    }

    /** H8: 런타임이 없으면 DRIVING이다. 정차 화면이 열리지 않고 터치도 없다 */
    @Test
    fun unconnectedSourceShowsS1WithoutTouch() {
        val source = UiGraph.unconnected { UiSamples.NOW_MS }
        assertEquals(UiMode.DRIVING, source.uiMode.value)
        rule.setContent {
            CdTheme(dark = false) { MainContent(source, null, {}, {}) }
        }
        rule.onNodeWithTag(TAG_S1).assertExists()
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    /** H3: 출발 즉시 S1. 지연 없이 다음 프레임이다 */
    @Test
    fun leavingParkSwitchesToS1AndForgetsTab() {
        val fake = fakeSource(mode = UiMode.PARKED)
        showMain(fake)
        rule.onNodeWithTag(TAG_S2).assertExists()
        rule.onNodeWithText("기록").performClick()
        rule.onNodeWithTag(TAG_S4).assertExists()

        fake.modeFlow.value = UiMode.DRIVING
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S1).assertExists()
        rule.onNodeWithTag(TAG_S4).assertDoesNotExist()
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)

        // 8.1: 다시 정차하면 저장된 탭이 아니라 현재 상태로 고른다
        fake.modeFlow.value = UiMode.PARKED
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S2).assertExists()
    }

    /** 8.3 "출발하면 전환 효과 없이": RECOVER 정차에서 S3, 출발에서 S1 */
    @Test
    fun recoverParkedThenDeparts() {
        val fake = fakeSource(view = UiSamples.recoverFatigue, mode = UiMode.PARKED)
        showMain(fake)
        rule.onNodeWithTag(TAG_S3).assertExists()
        fake.modeFlow.value = UiMode.DRIVING
        fake.viewFlow.value = UiSamples.view(DriverState.STOP, UiSamples.recoverFatigue.reasons, cdsSec = 7680)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S1).assertExists()
        assertTrue("쉬어 가세요" in rule.screenText())
    }

    @Test
    fun settingsTabOpensSetupActivityWithoutChangingTab() {
        var opened = 0
        val fake = fakeSource(mode = UiMode.PARKED)
        showMain(fake) { opened++ }
        rule.onNodeWithText("설정").performClick()
        assertEquals(1, opened)
        rule.onNodeWithTag(TAG_S2).assertExists()
    }

    @Test
    fun requestedTabFromSetupActivityIsHonoured() {
        val fake = fakeSource(mode = UiMode.PARKED)
        showMain(fake, requested = ParkedTab.LOG)
        rule.onNodeWithTag(TAG_S4).assertExists()
    }

    /** 8.1 복귀: 백그라운드에 갔다 오면 저장된 탭이 아니라 현재 상태로 고른다 */
    @Test
    fun returningToForegroundForgetsTab() {
        val fake = fakeSource(view = UiSamples.recoverFatigue, mode = UiMode.PARKED)
        showMain(fake)
        rule.onNodeWithText("기록").performClick()
        rule.onNodeWithTag(TAG_S4).assertExists()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S3).assertExists()
    }

    /** R4: 호스트를 지나도 모든 화면에 표지가 있다. 혈당 시뮬레이션은 값에서 붙는다 */
    @Test
    fun demoLabelAppendsSimulationFromValue() {
        val fake = fakeSource(view = UiSamples.todayFull, mode = UiMode.PARKED, demoLabel = UiSamples.demoBanner)
        showMain(fake)
        rule.onNodeWithText("${UiSamples.demoBanner} · 혈당 시뮬레이션").assertExists()
        fake.viewFlow.value = UiSamples.todayBasic
        rule.waitForIdle()
        rule.onNodeWithText(UiSamples.demoBanner).assertExists()
        fake.modeFlow.value = UiMode.DRIVING
        rule.waitForIdle()
        rule.onNodeWithText(UiSamples.demoBanner).assertExists()
        assertEquals(0, rule.onAllNodes(hasClickAction().and(hasText(UiSamples.demoBanner))).fetchSemanticsNodes().size)
    }

    private fun showSetup(
        fake: FakeSource,
        onboarding: Boolean = false,
        scenario: String? = null,
        onLeave: () -> Unit = {},
    ) {
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(840.dp, 1184.dp)) {
                    SetupContent(
                        source = fake.source,
                        showOnboarding = onboarding,
                        onboardingStep = OnboardingStep.INTRO,
                        denied = emptySet(),
                        serverDraft = "",
                        pendingScenario = scenario,
                        pendingRealSignals = true,
                        onStepChange = {},
                        onOnboardingDone = {},
                        onRequestPermissions = {},
                        onServerDraft = {},
                        onTab = {},
                        onLeave = onLeave,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** H3: S5에서 출발하면 finish */
    @Test
    fun setupFinishesWhenDriving() {
        var left = 0
        val fake = fakeSource(mode = UiMode.PARKED)
        showSetup(fake, onLeave = { left++ })
        rule.onNodeWithTag(TAG_S5).assertExists()
        assertEquals(0, left)
        fake.modeFlow.value = UiMode.DRIVING
        rule.waitForIdle()
        assertEquals(1, left)
        rule.onNodeWithTag(TAG_S5).assertDoesNotExist()
    }

    /** H22, U-30. 설정 화면의 보류 풀기 버튼은 엔진 뷰 glucoseUnavailable을 따르고, 두 번 누르면 조작이 한 번 간다 */
    @Test
    fun setupHoldReleaseFollowsEngineView() {
        var released = 0
        val fake = fakeSource(
            view = UiSamples.todayNoGlucose,
            mode = UiMode.PARKED,
            actions = object : SettingsActions {
                override suspend fun releaseGlucoseHold(): HoldRelease {
                    released++
                    return HoldRelease.RELEASED
                }
            },
        )
        showSetup(fake)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performClick()
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performClick()
        rule.waitForIdle()
        assertEquals(1, released)
        // 엔진이 풀면 끊김 표시가 사라지고 버튼도 사라진다
        fake.viewFlow.value = UiSamples.todayBasic
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText("끊김 보류 풀기")).fetchSemanticsNodes().size)
    }

    /** 콜드 스타트 유예 중에는 터치 요소가 없고, 유예가 끝나면 닫는다 */
    @Test
    fun setupWaitsThenFinishesOnColdStartWhileDriving() {
        var left = 0
        rule.mainClock.autoAdvance = false
        showSetupNoIdle(fakeSource(mode = UiMode.DRIVING)) { left++ }
        rule.mainClock.advanceTimeBy(SetupGate.COLD_START_GRACE_MS - 1000)
        assertTrue("주차 상태를 확인하고 있어요" in rule.screenText())
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, left)
        rule.mainClock.advanceTimeBy(2000)
        assertEquals(1, left)
    }

    private fun showSetupNoIdle(fake: FakeSource, onLeave: () -> Unit) {
        rule.setContent {
            CdTheme(dark = false) {
                SetupContent(
                    fake.source, false, OnboardingStep.INTRO, emptySet(), "", null, false,
                    {}, {}, {}, {}, {}, onLeave,
                )
            }
        }
    }

    @Test
    fun setupGateTable() {
        assertEquals(SetupGateAction.SHOW, SetupGate.decide(UiMode.PARKED, everParked = false, graceOver = false))
        assertEquals(SetupGateAction.SHOW, SetupGate.decide(UiMode.PARKED, everParked = true, graceOver = true))
        assertEquals(SetupGateAction.WAIT, SetupGate.decide(UiMode.DRIVING, everParked = false, graceOver = false))
        assertEquals(SetupGateAction.FINISH, SetupGate.decide(UiMode.DRIVING, everParked = true, graceOver = false))
        assertEquals(SetupGateAction.FINISH, SetupGate.decide(UiMode.DRIVING, everParked = false, graceOver = true))
    }

    /** 15.2 adb 경로: P가 확인되면 시나리오를 시작하고 화면을 넘긴다. R5: 주행 중이면 시작하지 않는다 */
    @Test
    fun scenarioExtraStartsOnlyAfterParked() {
        val started = mutableListOf<Pair<String, Boolean>>()
        var left = 0
        val fake = fakeSource(
            mode = UiMode.DRIVING,
            actions = object : SettingsActions {
                override fun startDemo(scenarioId: String, realSignals: Boolean) { started += scenarioId to realSignals }
            },
        )
        showSetup(fake, scenario = "sc1", onLeave = { left++ })
        assertEquals(emptyList(), started)
        fake.modeFlow.value = UiMode.PARKED
        rule.waitForIdle()
        assertEquals(listOf("sc1" to true), started)
        assertEquals(1, left)
    }

    @Test
    fun onboardingShownWhenParkedAndRequested() {
        val fake = fakeSource(mode = UiMode.PARKED)
        showSetup(fake, onboarding = true)
        rule.onNodeWithTag(TAG_ONBOARDING).assertExists()
        rule.onNodeWithTag(TAG_S5).assertDoesNotExist()
    }

    /** 8.1 켠 직후 P인데 "여유 / 다음 휴식까지"가 먼저 보이면 화면이 틀렸다. 터치 0은 그대로다 */
    @Test
    fun coldStartShowsWaitingInsteadOfS1() {
        val fake = fakeSource(view = UiSamples.view(driving = false), mode = UiMode.DRIVING)
        rule.setContent {
            CdTheme(dark = false) { MainContent(fake.source, null, {}, {}, holdColdStart = true) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S1).assertDoesNotExist()
        assertTrue("주차 상태를 확인하고 있어요" in rule.screenText())
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
        // 실제로 달리면 판정을 기다리지 않는다
        fake.viewFlow.value = UiSamples.view(driving = true)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S1).assertExists()
    }

    /** 온보딩이 곧 덮을 Parked는 S2를 먼저 보이지 않는다. 끝나면 S2다 */
    @Test
    fun parkedWithPendingOnboardingWaits() {
        val fake = fakeSource(mode = UiMode.PARKED)
        var pending by mutableStateOf(true)
        rule.setContent {
            CdTheme(dark = false) { MainContent(fake.source, null, {}, {}, onboardingPending = pending) }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S2).assertDoesNotExist()
        pending = false
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_S2).assertExists()
    }

    /** R10 시연이면 소리 없이도 값을 볼 수 있다. H10 실사용 S1에는 혈당 수치가 없다 */
    @Test
    fun s1ShowsGlucoseOnlyInDemo() {
        val view = UiSamples.view(DriverState.CAUTION, setOf(com.conditiondrive.core.engine.Reason.GLUCOSE), mmol = 5.4, measuredAt = UiSamples.NOW_MS)
        val demo = fakeSource(view = view, mode = UiMode.DRIVING, demoLabel = "시연 모드")
        var fake by mutableStateOf(demo)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(1700.dp, 1184.dp)) { MainContent(fake.source, null, {}, {}) }
            }
        }
        rule.waitForIdle()
        assertTrue("mg/dL" in rule.screenText(), rule.screenText())
        fake = fakeSource(view = view, mode = UiMode.DRIVING)
        rule.waitForIdle()
        assertTrue("mg/dL" !in rule.screenText(), rule.screenText())
        assertTrue("혈당" !in rule.screenText(), rule.screenText())
    }

    /** R10 자막은 표지 아래 한 줄이다. 터치가 없다 */
    @Test
    fun captionFollowsBanner() {
        val source = fakeSource(mode = UiMode.DRIVING, demoLabel = "시연 모드").source
        val withCaption = VehicleUiSource(
            view = source.view, uiMode = source.uiMode, limits = source.limits, promptPlace = source.promptPlace,
            demoLabel = source.demoLabel, route = source.route, passengerPresent = source.passengerPresent,
            summary = source.summary, link = source.link, tripsToday = source.tripsToday, listening = source.listening,
            settings = source.settings, actions = source.actions, nowMs = source.nowMs,
            demoCaption = kotlinx.coroutines.flow.MutableStateFlow("시나리오 응답: 아니"),
        )
        rule.setContent { CdTheme(dark = false) { MainContent(withCaption, null, {}, {}) } }
        rule.waitForIdle()
        assertTrue("시연 모드\n시나리오 응답: 아니" in rule.screenText(), rule.screenText())
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** 실제 앱 영역(창 2개). 시연 수치와 그래프는 오른쪽 창에 있다 */
    private fun showWide(fake: FakeSource) {
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(1682.dp, 1120.dp)) { MainContent(fake.source, null, {}, {}) }
            }
        }
        rule.waitForIdle()
    }

    /** 재검증: 16장은 주행 중 움직이는 그래프를 금지하는데 시연 S1에서 값이 들어올 때마다 선이 늘어났다. 수치 한 줄만 남긴다 */
    @Test
    fun demoS1ShowsGlucoseLineButNoGraph() {
        val fake = fakeSource(view = UiSamples.stopGlucose, mode = UiMode.DRIVING, demoLabel = "시연 모드")
        showWide(fake)
        rule.onNodeWithTag(TAG_S1).assertExists()
        assertTrue("70 mg/dL" in rule.screenText(), rule.screenText())
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertDoesNotExist()
    }

    /** 정차하면 같은 호스트의 S3가 추이를 그린다 */
    @Test
    fun graphStaysOnS3() {
        val fake = fakeSource(view = UiSamples.recoverGlucoseTimer, mode = UiMode.PARKED, demoLabel = "시연 모드")
        showWide(fake)
        rule.onNodeWithTag(TAG_S3).assertExists()
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertExists()
    }

    /** 재검증: 시나리오 요약이 FRESH라 폰을 연결한 적 없는데 S5가 "폰이 연결되어 있어요"와 해제 버튼을 보였다 */
    @Test
    fun s5DuringDemoReportsRealPhoneLinkNotScenarioSummary() {
        val scripted = ScriptedSummarySource().apply {
            push(ScenarioSummary(glucoseMmol = 6.4), UiSamples.NOW_MS, null)
        }
        assertEquals(LinkState.FRESH, scripted.link.value)
        val base = fakeSource(mode = UiMode.PARKED, demoLabel = "시연 모드").source
        val realLink = MutableStateFlow(LinkState.UNPAIRED)
        val source = VehicleUiSource(
            view = base.view, uiMode = base.uiMode, limits = base.limits, promptPlace = base.promptPlace,
            demoLabel = base.demoLabel, route = base.route, passengerPresent = base.passengerPresent,
            summary = scripted.snapshot,
            link = realLink,
            tripsToday = base.tripsToday, listening = base.listening, settings = base.settings, actions = base.actions,
            nowMs = base.nowMs,
            linkedSummary = MutableStateFlow(SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)),
        )
        rule.setContent {
            CdTheme(dark = false) {
                SetupContent(source, false, OnboardingStep.INTRO, emptySet(), "", null, false, {}, {}, {}, {}, {}, {})
            }
        }
        rule.waitForIdle()
        val text = rule.screenText()
        assertFalse("폰이 연결되어 있어요" in text, text)
        assertFalse("연결 해제" in text, text)
        assertTrue("연결 코드 받기" in text, text)
    }
}
