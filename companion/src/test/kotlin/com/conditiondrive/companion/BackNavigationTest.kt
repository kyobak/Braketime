package com.conditiondrive.companion

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.companion.pairing.ConsentPolicy
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.ui.CdTheme
import com.conditiondrive.companion.ui.CompanionRoot
import com.conditiondrive.companion.ui.Copy
import com.conditiondrive.companion.ui.LocalQrScanner
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.core.time.ManualClock
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실기 44: 하위 화면에서 시스템 뒤로 가기를 누르면 앞 화면이 아니라 앱 밖으로 나갔다 */
@RunWith(AndroidJUnit4::class)
// CompanionApp.onCreate는 WorkManager를 직접 초기화하는 앱 전체 그래프를 만든다. 화면만 보려고 기본 Application으로 띄운다
@Config(sdk = [34], application = Application::class)
class BackNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val paired = StoredState(consentVersion = ConsentPolicy.VERSION, tokenBlob = testCipher.seal("cdp_token"))

    @After
    fun tearDown() = scope.cancel()

    // 실제 화면 상태(controller.ui)를 따라가야 뒤로 가기가 컨트롤러를 바꾸는 경우(연결 바꾸기 취소)도 볼 수 있다
    private fun show(initial: StoredState, realCamera: Boolean = false): CompanionController {
        val clock = ManualClock(1_800_000_000_000)
        val store = MemoryStore(initial)
        val vault = vaultOf(store)
        val api = FakeApi()
        lateinit var created: CompanionController
        val uploads = UploadCoordinator(
            api, store, vault, clock, { ZoneId.of("Asia/Seoul") },
            onUnauthorized = { created.onUnauthorized() },
            onConsentRequired = { created.onConsentRequired() },
        )
        created = CompanionController(
            store, PairingService(api, store, vault, clock, { "Galaxy" }, "0.1.0"), uploads, GlucoseSimulator(),
            FakeHealth(), FakeWork(), { }, clock, scope,
        )
        rule.setContent {
            val ui by created.ui.collectAsState()
            // Robolectric에는 카메라가 없어 실제 스캐너는 곧 "쓸 수 없음"으로 닫힌다. 스캔 열림 상태를 보려면 빈 칸으로 바꿔 끼운다
            val scanner = if (realCamera) LocalQrScanner.current else { _, _, modifier -> Box(modifier) }
            CompositionLocalProvider(LocalQrScanner provides scanner) {
                CdTheme { CompanionRoot(created, ui) }
            }
        }
        rule.waitForIdle()
        return created
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun backFromLicenseReturnsToDataTab() {
        show(paired)
        rule.onNodeWithText(Copy.TAB_DATA).performClick()
        rule.onNodeWithText(Copy.DATA_LICENSE).performScrollTo().performClick()
        rule.onNodeWithText(Copy.DATA_LICENSE_NAME).assertExists()

        pressBack()

        rule.onNodeWithText(Copy.DATA_LICENSE_NAME).assertDoesNotExist()
        rule.onNodeWithText(Copy.DATA_TITLE).assertExists()
        assertFalse(rule.activity.isFinishing, "라이선스에서 뒤로 가기는 데이터 탭으로 돌아간다")

        // 대조: 탭 화면 자체는 첫 화면이라 뒤로 가기가 그대로 앱을 닫는다. 위 검사가 헛돌지 않음을 보인다
        pressBack()
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun backFromDeclinedReturnsToConsent() {
        show(StoredState())
        rule.onNodeWithText(Copy.CONSENT_DECLINE).performScrollTo().performClick()
        rule.onNodeWithText(Copy.DECLINED_TITLE).assertExists()

        pressBack()

        rule.onNodeWithText(Copy.DECLINED_TITLE).assertDoesNotExist()
        rule.onNodeWithText(Copy.CONSENT_TITLE).assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backFromReplacePairingKeepsCurrentCar() {
        val controller = show(paired)
        controller.onDeepLink("conditiondrive://pair?v=1&code=K7M2QX9T")
        rule.waitForIdle()
        rule.onNodeWithText(Copy.PAIR_REPLACE_TITLE).assertExists()

        pressBack()

        assertNull(controller.ui.value.pair.prefilledCode, "뒤로 가기는 '지금 연결 유지'와 같다")
        rule.onNodeWithText(Copy.PAIR_REPLACE_TITLE).assertDoesNotExist()
        rule.onNodeWithText(Copy.STATUS_TITLE).assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    /** 연결 화면에서 QR 스캔을 연 채 뒤로 가기: 스캔만 닫고 연결 화면에 남는다 */
    @Test
    fun backClosesQrScanFirst() {
        grantCamera()
        show(StoredState(consentVersion = ConsentPolicy.VERSION))
        rule.onNodeWithText(Copy.PAIR_SCAN).performScrollTo().performClick()
        rule.onNodeWithText(Copy.PAIR_SCAN_CLOSE).assertExists()

        pressBack()

        rule.onNodeWithText(Copy.PAIR_SCAN_CLOSE).assertDoesNotExist()
        rule.onNodeWithText(Copy.PAIR_TITLE).assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    /** 연결 바꾸기 화면에서 스캔이 열려 있으면 첫 뒤로 가기는 스캔만, 둘째가 연결 바꾸기 취소다(나중에 등록한 처리기가 먼저) */
    @Test
    fun backOnReplaceClosesScanBeforeCancelling() {
        grantCamera()
        val controller = show(paired)
        controller.onDeepLink("conditiondrive://pair?v=1&code=K7M2QX9T")
        rule.waitForIdle()
        rule.onNodeWithText(Copy.PAIR_SCAN).performScrollTo().performClick()
        rule.onNodeWithText(Copy.PAIR_SCAN_CLOSE).assertExists()

        pressBack()

        rule.onNodeWithText(Copy.PAIR_SCAN_CLOSE).assertDoesNotExist()
        rule.onNodeWithText(Copy.PAIR_REPLACE_TITLE).assertExists()
        assertNotNull(controller.ui.value.pair.prefilledCode, "스캔만 닫고 연결 바꾸기는 그대로다")

        pressBack()

        rule.onNodeWithText(Copy.PAIR_REPLACE_TITLE).assertDoesNotExist()
        assertNull(controller.ui.value.pair.prefilledCode)
        assertFalse(rule.activity.isFinishing)
    }

    /** 권한은 있는데 뒤 카메라가 없으면 CameraX가 던져 앱이 닫혔다(이 시험을 만들며 발견). 코드 입력 안내로 돌아간다 */
    @Test
    fun missingCameraFallsBackToCodeEntry() {
        grantCamera()
        show(StoredState(consentVersion = ConsentPolicy.VERSION), realCamera = true)
        rule.onNodeWithText(Copy.PAIR_SCAN).performScrollTo().performClick()

        rule.waitUntil(10_000) { rule.onAllNodesWithText(Copy.PAIR_NO_CAMERA).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText(Copy.PAIR_SCAN).assertExists()
        rule.onNodeWithText(Copy.PAIR_SCAN_CLOSE).assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)
    }

    // 권한이 있으면 버튼이 권한 창 없이 바로 scanning을 켠다
    private fun grantCamera() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
    }

    /** 실기 27, 28: 센서 끊김 재현 중에도 맨 위 표지가 "시뮬레이션 혈당 전송 중"이었다 */
    @Test
    fun markerSaysNotSendingWhileSensorCut() {
        val controller = show(paired)
        controller.simulator.start()
        rule.waitForIdle()
        rule.onNodeWithText(Copy.SIM_RUNNING_MARKER).assertExists()

        controller.simulator.setSensorCut(true)
        rule.waitForIdle()
        rule.onNodeWithText(Copy.SIM_RUNNING_MARKER).assertDoesNotExist()
        rule.onNodeWithText(Copy.SIM_IDLE_MARKER).assertExists()

        controller.simulator.stop()
        rule.waitForIdle()
        rule.onNodeWithText(Copy.SIM_IDLE_MARKER).assertDoesNotExist()
    }
}
