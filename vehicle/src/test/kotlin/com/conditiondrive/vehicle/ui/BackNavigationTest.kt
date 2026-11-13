package com.conditiondrive.vehicle.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.vehicle.MainActivity
import com.conditiondrive.vehicle.SetupActivity
import com.conditiondrive.vehicle.hideTaskOnUnhandledBack
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.ui.model.BackPolicy
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.SetupBack
import com.conditiondrive.vehicle.ui.model.SetupGateAction
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.screens.TAG_ONBOARDING
import com.conditiondrive.vehicle.ui.screens.TAG_S1
import com.conditiondrive.vehicle.ui.screens.TAG_S2
import com.conditiondrive.vehicle.ui.screens.TAG_S4
import com.conditiondrive.vehicle.ui.screens.TAG_S5
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import com.conditiondrive.vehicle.ui.screens.previous
import com.conditiondrive.vehicle.ui.theme.CdTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pleos CCP Back 숨김 정책(U-27, H18). 첫 화면의 Back은 moveTaskToBack(true)이고 finish하지 않는다.
 * 하위 화면(기록 탭, 설정, S5 페이지, 온보딩 2·3단계)은 기존대로 그것만 닫는다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class BackNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** 실제 Activity와 같은 순서로 숨김 콜백을 화면보다 먼저 등록한다 */
    @Before
    fun installHide() {
        rule.runOnUiThread { rule.activity.hideTaskOnUnhandledBack() }
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private val hidden get() = shadowOf(rule.activity).isTaskMovedToBack

    private fun assertHiddenNotFinished() {
        assertTrue(hidden, "첫 화면 Back은 태스크를 뒤로 보낸다")
        assertFalse(rule.activity.isFinishing, "첫 화면 Back으로 끝내면 심사 반려다")
    }

    private fun showMain(fake: FakeSource) {
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(840.dp, 1184.dp)) { MainContent(fake.source, null, {}, {}) }
            }
        }
        rule.waitForIdle()
    }

    private fun showSetup(
        fake: FakeSource,
        onboarding: Boolean,
        step: OnboardingStep = OnboardingStep.INTRO,
        onStep: (OnboardingStep) -> Unit = {},
        onLeave: () -> Unit = {},
    ) {
        var current by mutableStateOf(step)
        rule.setContent {
            CdTheme(dark = false) {
                // 창 하나 폭. S5 페이지가 목록을 덮는 경우다
                Box(Modifier.requiredSize(840.dp, 1184.dp)) {
                    SetupContent(
                        source = fake.source,
                        showOnboarding = onboarding,
                        onboardingStep = current,
                        denied = emptySet(),
                        serverDraft = "",
                        pendingScenario = null,
                        pendingRealSignals = false,
                        onStepChange = {
                            current = it
                            onStep(it)
                        },
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

    @Test
    fun policyTable() {
        for (tab in ParkedTab.entries) assertFalse(BackPolicy.mainHandlesBack(UiMode.DRIVING, tab, waiting = false), "주행 화면은 숨긴다")
        assertFalse(BackPolicy.mainHandlesBack(UiMode.PARKED, ParkedTab.TODAY, waiting = false))
        assertTrue(BackPolicy.mainHandlesBack(UiMode.PARKED, ParkedTab.LOG, waiting = false))
        assertFalse(BackPolicy.mainHandlesBack(UiMode.PARKED, ParkedTab.LOG, waiting = true))

        assertEquals(SetupBack.LEAVE, BackPolicy.setupBack(SetupGateAction.SHOW, onboarding = false, firstStep = true), "설정은 오늘 탭으로")
        assertEquals(SetupBack.LEAVE, BackPolicy.setupBack(SetupGateAction.WAIT, onboarding = false, firstStep = true))
        assertEquals(SetupBack.HIDE, BackPolicy.setupBack(SetupGateAction.SHOW, onboarding = true, firstStep = true), "온보딩 첫 단계는 숨긴다")
        assertEquals(SetupBack.PREVIOUS_STEP, BackPolicy.setupBack(SetupGateAction.SHOW, onboarding = true, firstStep = false))
        assertEquals(SetupBack.HIDE, BackPolicy.setupBack(SetupGateAction.WAIT, onboarding = true, firstStep = false), "보이지 않는 단계를 바꾸지 않는다")
        // 이미 스스로 닫히는 중이다. 단계를 바꾸거나 onLeave를 두 번 부르지 않고 숨기지도 않는다
        for (onboarding in listOf(true, false)) for (firstStep in listOf(true, false)) {
            assertEquals(SetupBack.SWALLOW, BackPolicy.setupBack(SetupGateAction.FINISH, onboarding, firstStep))
        }

        assertNull(OnboardingStep.INTRO.previous())
        assertEquals(OnboardingStep.INTRO, OnboardingStep.PERMISSIONS.previous())
        assertEquals(OnboardingStep.PERMISSIONS, OnboardingStep.LISTEN.previous())
    }

    /** 오늘 탭은 앱의 첫 화면이다 */
    @Test
    fun todayTabHides() {
        showMain(fakeSource(mode = UiMode.PARKED))
        rule.onNodeWithTag(TAG_S2).assertExists()
        pressBack()
        assertHiddenNotFinished()
        rule.onNodeWithTag(TAG_S2).assertExists()
    }

    /** 기록 탭은 하위 화면이다. Back은 오늘 탭으로 가고 숨기지 않는다. 한 번 더 누르면 숨긴다 */
    @Test
    fun logTabGoesToTodayThenHides() {
        showMain(fakeSource(mode = UiMode.PARKED))
        rule.onNodeWithText("기록").performClick()
        rule.onNodeWithTag(TAG_S4).assertExists()
        pressBack()
        rule.onNodeWithTag(TAG_S2).assertExists()
        assertFalse(hidden)
        pressBack()
        assertHiddenNotFinished()
    }

    /** 주행 화면도 숨긴다. 끝내면 서비스는 돌아도 앱 화면을 다시 여는 데 콜드 스타트가 든다 */
    @Test
    fun drivingScreenHides() {
        showMain(fakeSource(mode = UiMode.DRIVING))
        rule.onNodeWithTag(TAG_S1).assertExists()
        pressBack()
        assertHiddenNotFinished()
        rule.onNodeWithTag(TAG_S1).assertExists()
    }

    /** 열린 S5 페이지는 Back으로 그것만 닫힌다. 다음 Back은 설정을 떠나 오늘 탭으로 간다 */
    @Test
    fun s5PageClosesFirstThenLeavesSettings() {
        var left = 0
        showSetup(fakeSource(mode = UiMode.PARKED), onboarding = false, onLeave = { left++ })
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("목록으로").assertExists()
        pressBack()
        rule.onNodeWithText("목록으로").assertDoesNotExist()
        rule.onNodeWithTag(TAG_S5).assertExists()
        assertEquals(0, left)
        assertFalse(hidden)
        pressBack()
        assertEquals(1, left)
        assertFalse(hidden)
    }

    /** 백그라운드에 다녀와 콜백이 다시 등록돼도 열린 페이지가 숨김보다 먼저다 */
    @Test
    fun pageStillClosesFirstAfterStopAndStart() {
        showSetup(fakeSource(mode = UiMode.PARKED), onboarding = false)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        rule.onNodeWithText("목록으로").assertExists()
        pressBack()
        rule.onNodeWithText("목록으로").assertDoesNotExist()
        assertFalse(hidden)
    }

    /** 온보딩 2·3단계는 이전 단계로, 첫 단계는 숨긴다 */
    @Test
    fun onboardingStepsBackThenHides() {
        val steps = mutableListOf<OnboardingStep>()
        var left = 0
        showSetup(fakeSource(mode = UiMode.PARKED), onboarding = true, step = OnboardingStep.LISTEN, onStep = { steps += it }, onLeave = { left++ })
        rule.onNodeWithTag(TAG_ONBOARDING).assertExists()
        pressBack()
        pressBack()
        assertEquals(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.INTRO), steps)
        assertFalse(hidden)
        pressBack()
        assertHiddenNotFinished()
        assertEquals(0, left)
        assertEquals(2, steps.size)
    }

    /** 듣기 시험 중 Back이면 이전 단계로 가며 말하기·마이크를 멈춘다. 돌아와도 옛 "듣고 있어요"가 남지 않는다 */
    @Test
    fun backDuringVoiceTrialCancelsIt() {
        var started = 0
        var cancelled = 0
        val actions = object : SettingsActions {
            override suspend fun tryVoice(onListening: () -> Unit): VoiceTrial {
                started++
                onListening()
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }
        }
        val steps = mutableListOf<OnboardingStep>()
        showSetup(fakeSource(mode = UiMode.PARKED, actions = actions), onboarding = true, step = OnboardingStep.LISTEN, onStep = { steps += it })
        rule.onNodeWithText("말해 보기").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("듣고 있어요").assertExists()
        assertEquals(1, started)
        pressBack()
        assertEquals(listOf(OnboardingStep.PERMISSIONS), steps)
        assertEquals(1, cancelled, "권한 단계 뒤에서 마이크가 열려 있으면 안 된다")
        assertFalse(hidden)
        rule.onNodeWithText("나중에").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("듣고 있어요").assertDoesNotExist()
        rule.onNodeWithText("말해 보기").assertExists()
    }
}

/** 실제 Activity에 하드키 Back(KEYCODE_BACK)을 넣는다. CCP Back 키가 오는 표준 경로다 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class BackKeyActivityTest {
    private fun ComponentActivity.pressBackKey() {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun setOnboardingDone(done: Boolean) {
        UiPrefs(ApplicationProvider.getApplicationContext()).onboardingDone = done
    }

    /** 서비스와 런타임이 없는 상태는 DRIVING이라 S1이다. 최상단 Back은 숨김이고 끝내지 않는다 */
    @Test
    fun mainActivityHidesOnBackKey() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.pressBackKey()
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertFalse(activity.isFinishing)
    }

    /** 온보딩이 첫 화면인 SetupActivity도 숨긴다. 끝내면 MainActivity가 곧 다시 띄운다 */
    @Test
    fun setupActivityDuringOnboardingHidesOnBackKey() {
        setOnboardingDone(false)
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        activity.pressBackKey()
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertFalse(activity.isFinishing)
    }

    /** 설정은 탭이라 Back이면 오늘 탭(MainActivity)으로 간다. 숨기지 않는다 */
    @Test
    fun setupActivitySettingsLeavesToMain() {
        setOnboardingDone(true)
        val activity = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        activity.pressBackKey()
        assertFalse(shadowOf(activity).isTaskMovedToBack)
        assertTrue(activity.isFinishing)
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
    }
}
