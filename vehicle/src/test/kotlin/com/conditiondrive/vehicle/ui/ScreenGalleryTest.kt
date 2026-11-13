package com.conditiondrive.vehicle.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.ui.model.S1Presenter
import com.conditiondrive.vehicle.ui.model.S2Presenter
import com.conditiondrive.vehicle.ui.model.S3Presenter
import com.conditiondrive.vehicle.ui.model.S4Presenter
import com.conditiondrive.vehicle.ui.preview.UiSamples
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 화면을 PNG로 남겨 사람이 본다. 에뮬레이터 없이 시각 품질을 확인하는 경로이고 CD_SHOTS=경로 환경 변수가 있을 때만 돈다 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class ScreenGalleryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir: String? = System.getenv("CD_SHOTS")?.takeIf { it.isNotBlank() }

    private fun shot(name: String, widthDp: Int, heightDp: Int, dark: Boolean = false, prepare: () -> Unit, content: @Composable () -> Unit) {
        rule.setContent {
            CdTheme(dark = dark) {
                Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { content() }
            }
        }
        rule.waitForIdle()
        prepare()
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val width = root.width.coerceAtLeast(1)
        val height = root.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bitmap)) }
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, minOf(widthDp, width), minOf(heightDp, height))
        File(dir, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun s1Normal() = gallery("s1_normal") { S1Screen(S1Presenter.present(UiSamples.normal), null) }

    @Test
    fun s1Caution() = gallery("s1_caution") { S1Screen(S1Presenter.present(UiSamples.cautionSoon, UiSamples.place, 6000), null) }

    @Test
    fun s1Stop() = gallery("s1_stop") { S1Screen(S1Presenter.present(UiSamples.stopFatigue, UiSamples.place, 6000), null) }

    @Test
    fun s1StopGlucoseDemo() = gallery("s1_stop_glucose_demo") {
        S1Screen(S1Presenter.present(UiSamples.stopGlucose, UiSamples.place), "${UiSamples.demoBanner} · 혈당 시뮬레이션")
    }

    @Test
    fun s1Recover() = gallery("s1_recover") { S1Screen(S1Presenter.present(UiSamples.departGrace), null) }

    @Test
    fun s2Full() = gallery("s2_full") { S2Screen(S2Presenter.present(UiSamples.todayFull, false, "Galaxy S25", UiSamples.NOW_MS), null, {}) }

    @Test
    fun s2Basic() = gallery("s2_basic") { S2Screen(S2Presenter.present(UiSamples.todayBasic, false, null, UiSamples.NOW_MS), null, {}) }

    @Test
    fun s2AdvanceKept() = gallery("s2_advance_kept") {
        S2Screen(S2Presenter.present(UiSamples.todayAdvanceKept, false, null, UiSamples.NOW_MS), null, {})
    }

    @Test
    fun s3Fatigue() = gallery("s3_fatigue") { S3Screen(S3Presenter.present(UiSamples.recoverFatigue, false, UiSamples.NOW_MS), emptyList(), null, {}) }

    @Test
    fun s3Glucose() = gallery("s3_glucose") {
        S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, false, UiSamples.NOW_MS), UiSamples.glucoseHistory, null, {})
    }

    @Test
    fun s3GlucosePassenger() = gallery("s3_glucose_passenger") {
        S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, null, UiSamples.NOW_MS), UiSamples.glucoseHistory, null, {})
    }

    /** 리뷰: 고혈당에서 내려오는 곡선. 두 기준선이 붙을 때 라벨이 겹치지 않는지 본다 */
    @Test
    fun s3GlucoseHigh() = gallery("s3_glucose_high") {
        val high = listOf(13.5, 12.8, 11.6, 10.2, 8.9, 7.4, 6.1, 5.2, 4.6, 4.9, 5.4)
        S3Screen(
            S3Presenter.present(UiSamples.recoverGlucoseTimer, false, UiSamples.NOW_MS),
            UiSamples.glucoseHistory.zip(high) { p, v -> p.copy(mmol = v) },
            null,
            {},
        )
    }

    @Test
    fun s3Hold() = gallery("s3_hold") { S3Screen(S3Presenter.present(UiSamples.hold, false, UiSamples.NOW_MS), UiSamples.glucoseHistory.take(3), null, {}) }

    @Test
    fun s4() = gallery("s4") {
        S4Screen(S4Presenter.present(UiSamples.trips, UiSamples.NOW_MS, UiSamples.ZONE, 324.0, recent = UiSamples.recentTrips), null, {})
    }

    /** U-23: 25-s4-week-before-fix.png 실제 기록을 새 규칙으로 */
    @Test
    fun s4ShortStops() = gallery("s4_short_stops") {
        S4Screen(S4Presenter.present(ShortStopSample.trips, ShortStopSample.NOW_MS, UiSamples.ZONE, recent = ShortStopSample.trips), null, {})
    }

    /** U-24: 25-s4-week-before-u24.png 모양. 7일 "운행 2회, 운전 8분" 옆 운전 행 2개 */
    @Test
    fun s4TripVsRows() = gallery("s4_trip_vs_rows") {
        S4Screen(S4Presenter.present(listOf(TripVsRowSample.today), TripVsRowSample.NOW_MS, UiSamples.ZONE, recent = TripVsRowSample.week), null, {})
    }

    @Test
    fun s4Empty() = gallery("s4_empty") { S4Screen(S4Presenter.present(emptyList(), UiSamples.NOW_MS, UiSamples.ZONE), null, {}) }

    @Test
    fun s5() = gallery("s5") {
        S5Screen(
            S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, ""),
            UiSamples.noActions, onTab = {}, onServerDraft = {}, onDemoStarted = {}, nowMs = { UiSamples.NOW_MS },
        )
    }

    @Test
    fun o1() = gallery("o1") { OnboardingScreen(OnboardingStep.INTRO, VoiceTrial.Idle, null, {}, {}, {}, {}) }

    @Test
    fun o2() = gallery("o2") { OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, {}, {}, {}, {}) }

    @Test
    fun o3() = gallery("o3") { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Heard(CheckinAnswer.GOOD), null, {}, {}, {}, {}) }

    private fun s5Page(name: String, row: String) = gallery(name, prepare = { rule.onNodeWithText(row).performClick() }) {
        S5Screen(
            S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "https://"),
            UiSamples.noActions, onTab = {}, onServerDraft = {}, onDemoStarted = {}, nowMs = { UiSamples.NOW_MS },
        )
    }

    @Test
    fun s5Demo() = s5Page("s5_demo", "시연 모드")

    @Test
    fun s5Support() = s5Page("s5_support", "지원 현황")

    @Test
    fun s5Sources() = s5Page("s5_sources", "기준과 출처")

    @Test
    fun s5Data() = s5Page("s5_data", "데이터와 동의")

    @Test
    fun s5Hold() = gallery("s5_hold", prepare = { rule.onNodeWithText("연결된 기기").performClick() }) {
        S5Screen(
            S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "", glucoseHold = true),
            UiSamples.noActions, onTab = {}, onServerDraft = {}, onDemoStarted = {}, nowMs = { UiSamples.NOW_MS },
        )
    }

    /** D-33. 낮은 값 뒤 40분 전에는 버튼 대신 남은 시간 한 줄이다 */
    @Test
    fun s5HoldWait() = gallery("s5_hold_wait", prepare = { rule.onNodeWithText("연결된 기기").performClick() }) {
        S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "", glucoseHold = true, holdReleasableAtMs = UiSamples.NOW_MS + 25 * 60_000L)
            .let { S5Screen(it, UiSamples.noActions, onTab = {}, onServerDraft = {}, onDemoStarted = {}, nowMs = { UiSamples.NOW_MS }) }
    }

    private fun gallery(name: String, prepare: () -> Unit = {}, content: @Composable () -> Unit) {
        assumeTrue(dir != null)
        File(dir!!).mkdirs()
        val variant = System.getenv("CD_SHOTS_VARIANT") ?: "land"
        when (variant) {
            "port" -> shot("${name}_840", 840, 1184, prepare = prepare, content = content)
            "narrow" -> shot("${name}_628", 628, 855, prepare = prepare, content = content)
            "night" -> shot("${name}_night", 1700, 1184, dark = true, prepare = prepare, content = content)
            else -> shot("${name}_1700", 1700, 1184, prepare = prepare, content = content)
        }
    }
}
