package com.conditiondrive.vehicle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.service.DriveMonitorService
import com.conditiondrive.vehicle.ui.MainContent
import com.conditiondrive.vehicle.ui.UiGraph
import com.conditiondrive.vehicle.ui.UiPrefs
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.theme.CdTheme

/** 승격은 서비스가 스스로 한다. startForegroundService는 승격 전 종료 시 프로세스를 죽여 쓰지 않는다 */
internal fun Context.startDriveService() {
    startService(Intent(this, DriveMonitorService::class.java))
}

/**
 * Pleos CCP Back 숨김 정책(U-27). 화면이 받지 않은 Back은 끝내지 않고 태스크를 뒤로 보낸다.
 * setContent 전에 등록해 Compose의 하위 화면 핸들러보다 우선순위가 낮다. 서비스와 엔진은 숨긴 뒤에도 돈다.
 */
internal fun ComponentActivity.hideTaskOnUnhandledBack() {
    onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
}

class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<ParkedTab?>(null)
    // 온보딩은 다른 Activity다. 돌아올 때마다 다시 읽어야 대기 화면에 갇히지 않는다
    private var onboardingPending by mutableStateOf(false)
    private var startCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = tabFrom(intent)
        val prefs = UiPrefs(this)
        hideTaskOnUnhandledBack()
        setContent {
            val source by UiGraph.source.collectAsStateWithLifecycle()
            val mode by source.uiMode.collectAsStateWithLifecycle()
            // 주행 중 첫 실행은 온보딩 없이 돌고(8.3) 다음 정차 때 이어서 진행한다
            // 뒤로 가기로 온보딩을 벗어나도 다시 띄운다. 안 그러면 대기 문장만 남는다. 건너뛰기가 있어 막히지 않는다
            LaunchedEffect(mode, startCount) {
                if (mode == UiMode.PARKED && !prefs.onboardingDone) startActivity(SetupActivity.intent(this@MainActivity))
            }
            CdTheme {
                MainContent(
                    source = source,
                    requestedTab = requestedTab,
                    onRequestedTabConsumed = { requestedTab = null },
                    onOpenSettings = { startActivity(SetupActivity.intent(this@MainActivity)) },
                    holdColdStart = true,
                    onboardingPending = onboardingPending,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        onboardingPending = !UiPrefs(this).onboardingDone
        startCount++
        startDriveService()   // 10.3 시작 경로 "앱 실행". 보이는 동안이라 백그라운드 시작 제한에 걸리지 않는다
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = tabFrom(intent)
    }

    private fun tabFrom(intent: Intent?): ParkedTab? =
        intent?.getStringExtra(EXTRA_TAB)?.let { name -> ParkedTab.entries.firstOrNull { it.name == name } }

    companion object {
        private const val EXTRA_TAB = "tab"

        /** 쌓지 않고 이미 있는 화면으로 돌아간다. 뒤로 가기로 S5가 다시 나오면 안 된다 */
        fun intent(context: Context, tab: ParkedTab? = null): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { tab?.let { putExtra(EXTRA_TAB, it.name) } }
    }
}
