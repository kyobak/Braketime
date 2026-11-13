package com.conditiondrive.vehicle

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.conditiondrive.vehicle.platform.PlatformPorts
import com.conditiondrive.vehicle.platform.guarded
import com.conditiondrive.vehicle.ui.SetupContent
import com.conditiondrive.vehicle.ui.UiGraph
import com.conditiondrive.vehicle.ui.UiPrefs
import com.conditiondrive.vehicle.ui.model.DeniedPermission
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.theme.CdTheme

/** S5와 온보딩. DO를 선언하지 않아 주행 중에는 플랫폼도 함께 막는다(8.1) */
class SetupActivity : ComponentActivity() {
    private var denied by mutableStateOf(emptySet<DeniedPermission>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = UiPrefs(this)
        val scenario = intent?.getStringExtra(EXTRA_SCENARIO)
        val realSignals = intent?.getBooleanExtra(EXTRA_REAL_SIGNALS, false) ?: false
        denied = deniedPermissions()
        var step by mutableStateOf(prefs.onboardingStep)
        var draft by mutableStateOf(prefs.serverDraft)
        // 시연 자동화 경로에서 온보딩이 끼면 시나리오가 시작되지 않는다
        // 건너뛴 사실을 남기지 않으면 시연 도중 MainActivity가 온보딩을 다시 띄운다
        if (scenario != null) prefs.onboardingDone = true
        var showOnboarding by mutableStateOf(!prefs.onboardingDone)

        // 온보딩 첫 단계에서 끝내면 MainActivity가 곧 다시 띄운다. 받을 화면이 없으면 숨긴다(U-27)
        hideTaskOnUnhandledBack()
        setContent {
            val source by UiGraph.source.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                val unchanged = denied == deniedPermissions()
                denied = deniedPermissions()
                // 차량 STT와 신호 구독은 권한이 생겨도 저절로 살아나지 않는다
                PlatformPorts.get(this@SetupActivity).refreshPermissions()
                // 두 번 거부하면 대화상자가 더 뜨지 않는다. 눌러도 아무 일이 없으면 안 되므로 시스템 앱 정보로 보낸다
                if (unchanged && denied.isNotEmpty() && !showOnboarding) openAppDetails()
                // 8.3 거부해도 다음으로 간다. 머물면 "허용 요청"만 다시 보여 끝난 줄 모른다
                if (step == OnboardingStep.PERMISSIONS) {
                    step = OnboardingStep.LISTEN
                    prefs.onboardingStep = OnboardingStep.LISTEN
                }
            }
            CdTheme {
                SetupContent(
                    source = source,
                    showOnboarding = showOnboarding,
                    onboardingStep = step,
                    denied = denied,
                    serverDraft = draft,
                    pendingScenario = scenario,
                    pendingRealSignals = realSignals,
                    onStepChange = {
                        step = it
                        prefs.onboardingStep = it
                    },
                    onOnboardingDone = {
                        prefs.onboardingDone = true
                        showOnboarding = false
                        leave(null)
                    },
                    onRequestPermissions = { permissionLauncher.launch(DeniedPermission.REQUESTED.keys.toTypedArray()) },
                    onServerDraft = {
                        draft = it
                        prefs.serverDraft = it   // 입력마다 저장해 Driving으로 닫혀도 잃지 않는다
                    },
                    onTab = { tab -> if (tab != ParkedTab.SETTINGS) leave(tab) },
                    onLeave = { leave(null) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startDriveService()   // adb 시연 경로는 MainActivity를 거치지 않는다
    }

    override fun onResume() {
        super.onResume()
        denied = deniedPermissions()
    }

    private fun openAppDetails() {
        guarded("app details", Unit) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun leave(tab: ParkedTab?) {
        startActivity(MainActivity.intent(this, tab))
        finish()
    }

    private fun deniedPermissions(): Set<DeniedPermission> =
        DeniedPermission.REQUESTED.filterKeys { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .values
            .toSet()

    companion object {
        private const val EXTRA_SCENARIO = "scenario"
        private const val EXTRA_REAL_SIGNALS = "realSignals"

        fun intent(context: Context): Intent = Intent(context, SetupActivity::class.java)
    }
}
