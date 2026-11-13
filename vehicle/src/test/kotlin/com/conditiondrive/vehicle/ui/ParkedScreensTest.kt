package com.conditiondrive.vehicle.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.model.UiText
import com.conditiondrive.vehicle.ui.components.TAG_GLUCOSE_GRAPH
import com.conditiondrive.vehicle.ui.components.TAG_RIGHT_PANE
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.S2Presenter
import com.conditiondrive.vehicle.ui.model.S3Presenter
import com.conditiondrive.vehicle.ui.model.S4Presenter
import com.conditiondrive.vehicle.ui.model.S5Presenter
import com.conditiondrive.vehicle.ui.preview.UiSamples
import com.conditiondrive.vehicle.ui.screens.OnboardingScreen
import com.conditiondrive.vehicle.ui.screens.OnboardingStep
import com.conditiondrive.vehicle.ui.screens.S2Screen
import com.conditiondrive.vehicle.ui.screens.S3Screen
import com.conditiondrive.vehicle.ui.screens.S4Screen
import com.conditiondrive.vehicle.ui.screens.S5Screen
import com.conditiondrive.vehicle.ui.screens.S5State
import com.conditiondrive.vehicle.ui.screens.TAG_S5_TITLE
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import com.conditiondrive.vehicle.ui.model.BigValue
import com.conditiondrive.vehicle.ui.model.DeniedPermission
import com.conditiondrive.vehicle.ui.theme.CdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = UiSamples.NOW_MS
private const val ADVANCE_KEPT = "수면 값은 없지만 15분 쉴 때까지 안내 시점을 30분 앞당겨 둬요"
private const val ADVANCE_KEPT_IDLE = "수면 값은 없지만 앞서 받은 값으로 안내 시점을 30분 앞당겨 뒀어요"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class ParkedScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(width: Int = 840, height: Int = 1184, content: @Composable () -> Unit) {
        rule.setContent {
            CdTheme(dark = false) { Box(Modifier.requiredSize(width.dp, height.dp)) { content() } }
        }
        rule.waitForIdle()
    }

    private fun assertNoMedicalOrRaw(text: String) = assertEquals(emptyList(), ForbiddenWords.find(text), text)

    /** S2 표. 1단계 사용자는 체크인과 기준만 본다 */
    @Test
    fun s2BasicUserSeesCheckinAndSourceOnly() {
        show { S2Screen(S2Presenter.present(UiSamples.todayBasic, null, null, NOW), null, {}) }
        val text = rule.screenText()
        assertTrue("컨디션 응답" in text && "좋음" in text, text)
        assertTrue("2시간·15분 참고: 화물자동차 운수사업법 시행규칙" in text, text)
        assertFalse("수면" in text, text)
        assertFalse("혈당" in text, text)
        assertFalse("연결된 폰" in text, text)
        assertNoMedicalOrRaw(text)
    }

    /** F6-7, F7-11, H11, 8.3 S2 */
    @Test
    fun s2FullAlone() {
        show { S2Screen(S2Presenter.present(UiSamples.todayFull, false, "Galaxy S25", NOW), null, {}) }
        val text = rule.screenText()
        for (expected in listOf("4시간 50분", "안내 시점을 30분 앞당겼어요", "97 mg/dL (5.4 mmol/L)", "내려가는 중", "시뮬레이션 혈당", "Galaxy S25", "측정 3분 전", "수면 기준: 설계값")) {
            assertTrue(expected in text, "$expected / $text")
        }
        assertNoMedicalOrRaw(text)
    }

    /** F11-2, F11-3. 동승 있음과 모름 모두 수치를 숨긴다 */
    @Test
    fun s2PassengerHidesNumbers() {
        for (passenger in listOf(true, null)) {
            val model = S2Presenter.present(UiSamples.todayFull, passenger, "Galaxy S25", NOW)
            assertEquals("기준 위", model.glucose?.value?.str())
            assertEquals("기준보다 짧음", model.sleep?.str())
            assertEquals(null, model.glucose?.trend)
            assertEquals("시뮬레이션 데이터", model.glucose?.simulated?.str())
            assertEquals("컨디션", model.glucoseLabel.str())
        }
        show { S2Screen(S2Presenter.present(UiSamples.todayFull, null, null, NOW), null, {}) }
        val text = rule.screenText()
        assertFalse("mg/dL" in text, text)
        assertFalse("4시간 50분" in text, text)
        // 수치를 가려도 "혈당"이 보이면 혈당을 관리한다는 사실이 드러난다(F11)
        assertFalse("혈당" in text, text)
        assertFalse("Diabetes" in text, text)
    }

    /** 8.3 범위 문구 네 가지 */
    @Test
    fun rangePhrases() {
        fun range(view: com.conditiondrive.core.engine.EngineView) = S2Presenter.present(view, true, null, NOW).glucose?.value?.str()
        assertEquals("기준 아래", range(UiSamples.stopGlucose))
        assertEquals("회복 대기", range(UiSamples.recoverGlucoseTimer))
        assertEquals("회복 대기", range(UiSamples.recoverGlucoseWaiting))
        assertEquals("기준에 가까움", range(UiSamples.view(DriverState.CAUTION, setOf(Reason.GLUCOSE), mmol = 4.9, trend = Trend.FLAT)))
        assertEquals("기준에 가까움", range(UiSamples.view(DriverState.CAUTION, setOf(Reason.GLUCOSE), mmol = 5.4, trend = Trend.FALLING)))
        assertEquals("기준 위", range(UiSamples.view(mmol = 6.4, trend = Trend.FLAT)))
    }

    @Test
    fun s2GlucoseLostIsASentenceNotBlank() {
        show { S2Screen(S2Presenter.present(UiSamples.todayNoGlucose, false, null, NOW), null, {}) }
        val text = rule.screenText()
        assertTrue("혈당 정보 없음" in text, text)
        assertFalse("--" in text, text)
    }

    /** 8.3 출발 보류. 버튼을 두지 않아 운전을 막지 않는다 */
    @Test
    fun holdBandHasNoExtraButton() {
        show { S2Screen(S2Presenter.present(UiSamples.hold, false, null, NOW), null, {}) }
        assertTrue("출발을 미루는 게 좋아요" in rule.screenText())
        assertEquals(3, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size, "탭 3개만")
    }

    /** F5-7 "터치는 보기만": 혈당 없는 S3의 터치는 탭 3개다 */
    @Test
    fun s3FatigueOnlyTabsAreTouchable() {
        show { S3Screen(S3Presenter.present(UiSamples.recoverFatigue, false, NOW), emptyList(), null, {}) }
        val text = rule.screenText()
        for (expected in listOf("쉬는 중", "남은 휴식", "12분", "시동을 켜 두면 다 쉬었을 때 알려 드려요", "2시간·15분 참고: 화물자동차 운수사업법 시행규칙")) {
            assertTrue(expected in text.replace("\n", ""), "$expected / $text")
        }
        assertEquals(3, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse("혈당" in text, text)
    }

    /** 8.3 혈당 사유 Portrait 한 줄, H11, U-10 */
    @Test
    fun s3GlucosePortrait() {
        show { S3Screen(S3Presenter.present(UiSamples.recoverGlucoseWaiting, false, NOW), emptyList(), null, {}) }
        val text = rule.screenText()
        for (expected in listOf("75 mg/dL (4.2 mmol/L), 올라가는 중", "시뮬레이션 혈당", "기준: Diabetes Canada 2018 (캐나다 지침)")) {
            assertTrue(expected in text, "$expected / $text")
        }
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertDoesNotExist()
    }

    @Test
    fun s3GlucoseLandscapeHasGraphAndDetail() {
        show(1700) { S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, false, NOW), UiSamples.glucoseHistory, null, {}) }
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertExists()
        val text = rule.screenText()
        for (expected in listOf("값 수신 3분 전", "혈당 추이", "기준: Diabetes Canada 2018 (캐나다 지침)", "최근 50분")) {
            assertTrue(expected in text, "$expected / $text")
        }
        // 왼쪽 큰 숫자가 회복 타이머다. 오른쪽에서 같은 값을 다시 말하지 않는다
        assertFalse("회복 후 40분 중 25분 남음" in text, text)
        assertTrue("25분" in text.replace("\n", ""), text)
        assertTrue("15분 지남" in text, text)
    }

    /** F03: 기준선 라벨은 수치 줄과 같은 mg/dL 변환이다. Policy 값에서 계산한다(그래프 글자는 Canvas라 의미 트리에 없다) */
    @Test
    fun graphLinesUseMgdlFromPolicy() {
        val p = com.conditiondrive.core.engine.Policy.DEFAULT
        val mgdl = com.conditiondrive.core.voice.Formats::mgdl
        assertEquals("회복 기준 90 mg/dL", UiText.res(R.string.graph_recover_line, mgdl(p.GLUCOSE_RECOVER_AT)).str())
        assertEquals("정차 권고 기준 72 mg/dL", UiText.res(R.string.graph_stop_line, mgdl(p.GLUCOSE_STOP_BELOW)).str())
        assertEquals(50, com.conditiondrive.vehicle.ui.components.graphSpanMinutes(UiSamples.glucoseHistory))
        assertEquals(30, com.conditiondrive.vehicle.ui.components.graphSpanMinutes(UiSamples.glucoseHistory.takeLast(2)))
    }

    /** 리뷰: 혈당 범위가 넓어 두 기준선이 붙으면 정차 권고 라벨을 선 아래로 내려 라벨끼리 겹치지 않는다 */
    @Test
    fun graphStopLabelMovesBelowWhenLinesCrowd() {
        val below = { top: Double, bottom: Double, height: Float, label: Float ->
            com.conditiondrive.vehicle.ui.components.glucoseStopLabelBelow(top, bottom, height, label)
        }
        // 기본 범위(3~8 mmol/L): 1 mmol/L = 56dp로 라벨(약 45dp)보다 넓다
        assertFalse(below(8.0, 3.0, 280f, 45f))
        // 최고 13.5 mmol/L: 1 mmol/L = 약 27dp라 위에 두면 겹친다
        assertTrue(below(13.5, 3.0, 280f, 45f))
    }

    /** 휴식이 큰 숫자면 회복 줄은 다른 정보라 오른쪽에 남는다 */
    @Test
    fun s3BothReasonsKeepsRecoveryLineOnRight() {
        val view = UiSamples.recoverFatigue.copy(reasons = setOf(Reason.FATIGUE, Reason.GLUCOSE), glucoseRecoveryRemainingSec = 1500, glucoseMmol = 5.4, glucoseMeasuredAtMs = NOW)
        show(1700) { S3Screen(S3Presenter.present(view, false, NOW), UiSamples.glucoseHistory, null, {}) }
        assertTrue("회복 후 40분 중 25분 남음" in rule.screenText(), rule.screenText())
    }

    /** U-10: 동승이면 출처는 버튼. 누르기 전에는 지침 이름이 보이지 않는다 */
    @Test
    fun s3PassengerSourceIsButton() {
        show(1700) { S3Screen(S3Presenter.present(UiSamples.recoverGlucoseTimer, null, NOW), UiSamples.glucoseHistory, null, {}) }
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertDoesNotExist()
        var text = rule.screenText()
        assertFalse("mg/dL" in text, text)
        assertFalse("Diabetes Canada" in text, text)
        rule.onNodeWithText("기준 출처").performClick()
        rule.waitForIdle()
        text = rule.screenText()
        assertTrue("Diabetes Canada" in text, text)
    }

    /** F5-9, 8.3: 끊겼으면 수치와 그래프를 빼고 문장으로 */
    @Test
    fun s3GlucoseLostDropsNumbersAndGraph() {
        show(1700) { S3Screen(S3Presenter.present(UiSamples.recoverUnconfirmed, false, NOW), UiSamples.glucoseHistory, null, {}) }
        rule.onNodeWithTag(TAG_GLUCOSE_GRAPH).assertDoesNotExist()
        val text = rule.screenText()
        assertTrue("혈당 정보 없음. 직접 확인하고 출발하세요" in text, text)
        assertFalse("mg/dL" in text, text)
    }

    /** 혈당만 사유면 15분 휴식이 아니라 40분을 센다(SC1 "S3, 40분 감소") */
    @Test
    fun s3GlucoseOnlyCountsRecoveryNotRest() {
        val model = S3Presenter.present(UiSamples.recoverGlucoseTimer.copy(restRemainingSec = 500), false, NOW)
        assertEquals("회복 후 40분 중 남은 시간", model.label.str())
        assertEquals(listOf("0분", "40분"), model.horizon?.ticks?.map { it.label })
        assertEquals("15분 지남", model.horizon?.nowLabel?.str())
    }

    @Test
    fun s3BothReasonsCountsRestFirst() {
        val view = UiSamples.recoverFatigue.copy(reasons = setOf(Reason.FATIGUE, Reason.GLUCOSE), glucoseRecoveryRemainingSec = 1500, glucoseMmol = 5.4)
        val model = S3Presenter.present(view, false, NOW)
        assertEquals("남은 휴식", model.label.str())
        assertEquals("회복 후 40분 중 25분 남음", model.glucoseDetail?.recoveryLine?.str())
        assertEquals(listOf("0분", "15분"), model.horizon?.ticks?.map { it.label })
    }

    @Test
    fun s3RecoveryLineUnderOneMinuteStaysASentence() {
        val view = UiSamples.recoverFatigue.copy(reasons = setOf(Reason.GLUCOSE), glucoseRecoveryRemainingSec = 30, glucoseMmol = 5.4)
        val model = S3Presenter.present(view, false, NOW)
        assertEquals("회복 후 40분이 곧 끝나요", model.glucoseDetail?.recoveryLine?.str())
    }

    /** 8.3 S4, F8-5: 점수, 등급, 위반 표현 없음 */
    @Test
    fun s4ListsSegmentsWithoutJudgement() {
        show { S4Screen(S4Presenter.present(UiSamples.trips, NOW, UiSamples.ZONE, 324.0), null, {}) }
        val text = rule.screenText()
        for (expected in listOf("오늘의 기록", "운전 3시간 40분, 15분 이상 휴식 2회", "휴식 안내 2회", "컨디션 정차 1회", "주행 324km", "운전 1시간 52분", "휴식 15분", "휴식 18분")) {
            assertTrue(expected in text, "$expected / $text")
        }
        for (banned in listOf("점수", "등급", "위반", "혈당")) assertFalse(banned in text, "$banned / $text")
        assertNoMedicalOrRaw(text)
    }

    @Test
    fun s4EmptyAndOdometerHidden() {
        show { S4Screen(S4Presenter.present(emptyList(), NOW, UiSamples.ZONE), null, {}) }
        val text = rule.screenText()
        assertTrue("오늘 기록이 아직 없어요" in text, text)
        assertTrue("최근 7일 동안 운행 기록이 없어요" in text, text)
        assertFalse("주행" in text, text)
        // 리뷰: 0 합계와 "기록이 없어요"가 같은 사실을 두 번 말하지 않는다
        assertFalse("운전 0분" in text, text)
        assertFalse("휴식 안내 0회" in text, text)
    }

    /** business.md 4장: 기록 탭 맨 위의 7일 요약. 값 3줄, 건강값과 탓하는 말 없음 */
    @Test
    fun s4RecentWeekOnTopSinglePane() = s4RecentWeekOnTop(840, todayTitle = "오늘의 기록")

    @Test
    fun s4RecentWeekOnTopTwoPanes() = s4RecentWeekOnTop(1700, todayTitle = "오늘의 합계")

    private fun s4RecentWeekOnTop(width: Int, todayTitle: String) {
        show(width) {
            S4Screen(S4Presenter.present(UiSamples.trips, NOW, UiSamples.ZONE, 324.0, recent = UiSamples.recentTrips), null, {})
        }
        val texts = rule.allTexts()
        val text = texts.joinToString("\n")
        for (expected in listOf("최근 7일", "운행", "4회, 운전 8시간 37분", "휴식 안내", "4회", "15분 이상 휴식", "3회")) {
            assertTrue(expected in texts, "$expected / $text")
        }
        // 7일 묶음이 오늘 합계보다 먼저 읽혀야 맨 위다
        val week = texts.indexOf("최근 7일")
        assertTrue(week in 0 until texts.indexOf(todayTitle), text)
        for (banned in listOf("혈당", "거절", "무시", "안 쉬", "놓친", "점수", "위반", "최근 7일 동안 운행 기록이 없어요")) {
            assertFalse(banned in text, "$banned / $text")
        }
        assertNoMedicalOrRaw(text)
    }

    /** U-23: 25-s4-week-before-fix.png 실제 기록. 합계 "휴식 0회" 옆에 "휴식 12분" 행이 서던 모순이 없어야 한다 */
    @Test
    fun s4ScreenshotDataReadsWithoutContradiction() {
        val now = ShortStopSample.NOW_MS
        val model = S4Presenter.present(ShortStopSample.trips, now, UiSamples.ZONE, recent = ShortStopSample.trips)
        assertEquals(listOf("19:05"), model.rows.map { it.time })
        assertEquals("운전 1분", model.rows.single().text.str())
        assertEquals("중간 멈춤 12분", model.rows.single().note?.str())
        show(1700) { S4Screen(model, null, {}) }
        val texts = rule.allTexts()
        val text = texts.joinToString("\n")
        for (expected in listOf("운행", "1회, 운전 1분", "15분 이상 휴식", "0회", "운전 1분", "15분 이상 휴식 0회", "휴식 안내 0회", "19:05", "중간 멈춤 12분")) {
            assertTrue(expected in texts, "$expected / $text")
        }
        for (gone in listOf("휴식 12분", "운전 0분", "휴식 0분", "19:06", "19:18", "19:19", "휴식 0회")) {
            assertFalse(gone in texts, "$gone / $text")
        }
        assertNoMedicalOrRaw(text)
    }

    /**
     * U-24: 25-s4-week-before-u24.png 모양. 7일 횟수는 운행 수이고 오늘 목록의 운전 행 수와 다르다.
     * 라벨이 "운행"이라 "운행 2회, 운전 8분" 옆에 운전 행 2개(그중 오늘 운행은 1개)가 서도 서로 다투지 않는다
     */
    @Test
    fun s4WeekCountSaysTripsSoDriveRowsDoNotContradict() {
        val now = TripVsRowSample.NOW_MS
        val today = listOf(TripVsRowSample.today)
        val model = S4Presenter.present(today, now, UiSamples.ZONE, recent = TripVsRowSample.week)
        val driveRows = model.rows.map { it.text.str() }.filter { it.startsWith("운전 ") }
        assertEquals(listOf("운전 4분", "운전 2분"), driveRows)
        assertEquals(listOf("00:00", "00:07", "00:24", "00:26"), model.rows.map { it.time })
        assertEquals(1, com.conditiondrive.vehicle.ui.model.TripTimeline.tripCount(today, now))
        assertEquals(2, model.recent?.trips)
        show(1700) { S4Screen(model, null, {}) }
        val texts = rule.allTexts()
        val text = texts.joinToString("\n")
        for (expected in listOf("운행", "2회, 운전 8분", "운전 6분", "15분 이상 휴식 1회", "운전 4분", "중간 멈춤 3분", "휴식 16분", "운전 2분", "멈춤 3분")) {
            assertTrue(expected in texts, "$expected / $text")
        }
        // 횟수에 "운전"을 붙인 말이 화면 어디에도 없어야 운전 행 수와 비교되지 않는다
        assertTrue(texts.none { Regex("""운전\s*\d+회""").containsMatchIn(it) }, text)
        assertFalse("운전" in texts, text)
        // 라벨이 값보다 먼저 읽힌다(같은 행 왼쪽)
        assertTrue(texts.indexOf("운행") == texts.indexOf("2회, 운전 8분") - 1, text)
        assertNoMedicalOrRaw(text)
    }

    /** U-23: 15분 미만 정차 행은 "멈춤", 15분 이상만 "휴식" */
    @Test
    fun s4ShortStopRowSaysPauseAndFullRestSaysRest() {
        val m = UiSamples.MINUTE
        val start = NOW - 200 * m
        val trip = com.conditiondrive.vehicle.port.TripRecord(
            "w", start, null,
            listOf(
                com.conditiondrive.vehicle.port.TripSegment("drive", start, start + 60 * m),
                com.conditiondrive.vehicle.port.TripSegment("rest", start + 60 * m, start + 80 * m),
                com.conditiondrive.vehicle.port.TripSegment("drive", start + 80 * m, start + 120 * m),
                com.conditiondrive.vehicle.port.TripSegment("rest", start + 120 * m, null),
            ),
            0, 0, 1, 0,
        )
        val model = S4Presenter.present(listOf(trip), start + 132 * m, UiSamples.ZONE)
        assertEquals(listOf("운전 1시간", "휴식 20분", "운전 40분", "멈춤 12분"), model.rows.map { it.text.str() })
        assertEquals(1, model.restCount)
    }

    /** U-23: 7일 요약, 오늘 합계, 목록이 같은 말("15분 이상 휴식", "휴식 N분")로 같은 것을 센다 */
    @Test
    fun s4RestWordMeansTheSameInAllThreePlaces() {
        val model = S4Presenter.present(UiSamples.trips, NOW, UiSamples.ZONE, 324.0, recent = UiSamples.recentTrips)
        val restRows = model.rows.map { it.text.str() }.filter { it.startsWith("휴식 ") }
        assertEquals(model.restCount, restRows.size)
        // 휴식 행은 모두 15분 이상이다
        for (row in restRows) {
            val minutes = Regex("""(?:(\d+)시간)?\s*(?:(\d+)분)?$""").find(row.removePrefix("휴식 "))!!.groupValues
            val total = (minutes[1].toIntOrNull() ?: 0) * 60 + (minutes[2].toIntOrNull() ?: 0)
            assertTrue(total >= 15, row)
        }
        show(1700) { S4Screen(model, null, {}) }
        val texts = rule.allTexts()
        val label = "${com.conditiondrive.vehicle.ui.model.REST_RESET_MINUTES}분 이상 휴식"
        assertTrue(label in texts, texts.joinToString())
        assertTrue("$label ${model.restCount}회" in texts, texts.joinToString())
        // "휴식"만 쓴 라벨이나 합계는 남지 않는다
        assertFalse("휴식" in texts, texts.joinToString())
        assertTrue(texts.none { it.matches(Regex("휴식 \\d+회")) }, texts.joinToString())
        assertNoMedicalOrRaw(texts.joinToString("\n"))
    }

    @Test
    fun s4SegmentTimesUseLocalZone() {
        val model = S4Presenter.present(UiSamples.trips, NOW, java.time.ZoneOffset.UTC)
        val seoul = S4Presenter.present(UiSamples.trips, NOW, UiSamples.ZONE)
        assertEquals(seoul.rows.first().text, model.rows.first().text)
        assertTrue(seoul.rows.first().time != model.rows.first().time)
    }

    private fun s5(
        width: Int = 840,
        facts: SettingsFacts = UiSamples.settingsPaired,
        link: LinkState = LinkState.UNPAIRED,
        phone: String? = null,
        actions: SettingsActions = UiSamples.noActions,
        onDemoStarted: () -> Unit = {},
        tab: (ParkedTab) -> Unit = {},
        now: () -> Long = { NOW },
    ) = show(width) {
        S5Screen(S5State(facts, link, phone, emptySet(), null, ""), actions, tab, {}, onDemoStarted, now)
    }

    /** 8.3 S5 행. "단계 선택"이 아니라 "연결된 기기"다 */
    @Test
    fun s5Rows() {
        s5()
        val text = rule.screenText()
        for (row in listOf("연결된 기기", "보호자 등록", "외부 장소 검색", "지원 현황", "기준과 출처", "데이터와 동의", "시연 모드")) {
            assertTrue(row in text, "$row / $text")
        }
        assertFalse("단계" in text, text)
        assertFalse("서버 주소" in text, text)
    }

    @Test
    fun s5ServerRowUnlocksAfterFiveTapsOnly() {
        var clock = NOW
        s5(now = { clock })
        repeat(4) {
            rule.onNodeWithTag(TAG_S5_TITLE).performClick()
            clock += 200
        }
        rule.waitForIdle()
        assertFalse("서버 주소" in rule.screenText())
        rule.onNodeWithTag(TAG_S5_TITLE).performClick()
        rule.waitForIdle()
        assertTrue("서버 주소" in rule.screenText())
    }

    /** 동작하지 않는 설정을 켜게 하면 위치 고지가 실제 처리와 달라진다 */
    @Test
    fun s5ExternalSearchSaysNotInThisVersion() {
        s5()
        rule.onNodeWithText("외부 장소 검색").performClick()
        val text = rule.screenText()
        assertTrue("이 버전에는 아직 없어요" in text, text)
        assertEquals(0, rule.onAllNodesWithText("켜기").fetchSemanticsNodes().size)
    }

    /** 15.2 시연 시작과 실제 차량 신호 옵션 */
    @Test
    fun s5DemoStartsScenarioWithOption() {
        val started = mutableListOf<Pair<String, Boolean>>()
        var left = false
        s5(
            actions = object : SettingsActions {
                override fun startDemo(scenarioId: String, realSignals: Boolean) { started += scenarioId to realSignals }
            },
            onDemoStarted = { left = true },
        )
        rule.onNodeWithText("시연 모드").performClick()
        // 일반 사용자가 켜고 출발하면 실제 운전 측정이 멈춘다. 숨김 설정을 열어야 보인다
        rule.onAllNodesWithText("실제 차량 신호 사용").assertCountEquals(0)
        rule.onNodeWithText("혈당 안내 시나리오").performClick()
        assertEquals(listOf("sc1" to false), started)
        assertTrue(left)
    }

    @Test
    fun s5RealSignalOptionIsBehindHiddenSetting() {
        val started = mutableListOf<Pair<String, Boolean>>()
        s5(
            width = 1700,
            actions = object : SettingsActions {
                override fun startDemo(scenarioId: String, realSignals: Boolean) { started += scenarioId to realSignals }
            },
            now = { NOW },
        )
        repeat(5) { rule.onNodeWithTag(com.conditiondrive.vehicle.ui.screens.TAG_S5_TITLE).performClick() }
        rule.onNodeWithText("시연 모드").performClick()
        rule.onNodeWithText("실제 차량 신호 사용").performClick()
        rule.onNodeWithText("혈당 안내 시나리오").performClick()
        assertEquals(listOf("sc1" to true), started)
    }

    /** R12 돌고 있는 시연을 멈출 수 있어야 한다 */
    @Test
    fun s5RunningDemoCanStopAndRestart() {
        var stops = 0
        var restarts = 0
        s5(
            facts = SettingsFacts(demo = DemoFacts(running = true)),
            actions = object : SettingsActions {
                override fun stopDemo() { stops++ }
                override fun restartDemo() { restarts++ }
            },
        )
        rule.onNodeWithText("시연 모드").performClick()
        rule.onAllNodesWithText("혈당 안내 시나리오").assertCountEquals(0)
        rule.onNodeWithText("시연 끝내기").performClick()
        rule.onNodeWithText("처음부터 다시").performClick()
        assertEquals(1, stops)
        assertEquals(1, restarts)
    }

    /** R11 실제 내비로는 사람이 눌러야 간다. 무슨 일이 일어나는지 먼저 적는다 */
    @Test
    fun s5PendingDemoPlaceIsSentOnlyByButton() {
        var sends = 0
        s5(
            facts = SettingsFacts(demo = DemoFacts(pendingPlace = "칠곡휴게소(부산방향)")),
            actions = object : SettingsActions {
                override fun sendDemoPlace() { sends++ }
            },
        )
        rule.onNodeWithText("시연 모드").performClick()
        assertTrue("지도가 앱 화면을 덮고" in rule.screenText())
        assertEquals(0, sends)
        rule.onNodeWithText("이 장소를 실제 내비로 보내기").performClick()
        assertEquals(1, sends)
    }

    /** 연결이 없으면 서버에 지울 것이 있는 것처럼 보이지 않는다. 결과는 문장으로 알린다 */
    @Test
    fun s5DataButtonsFollowLinkAndReportResult() {
        s5(facts = SettingsFacts(dataNotice = DataNotice.SERVER_PENDING))
        rule.onNodeWithText("데이터와 동의").performClick()
        rule.onAllNodesWithText("서버 데이터 삭제").assertCountEquals(0)
        rule.onAllNodesWithText("연결 해제").assertCountEquals(0)
        assertTrue("아직 지우지 못했어요. 연결되면 마저 지울게요" in rule.screenText())
    }

    @Test
    fun s5DataButtonsShowWhenLinked() {
        s5(link = LinkState.OFFLINE, phone = null)
        rule.onNodeWithText("데이터와 동의").performClick()
        rule.onNodeWithText("서버 데이터 삭제").assertExists()
        rule.onNodeWithText("연결 해제").assertExists()
    }

    /** 온보딩에서 "나중에"를 고른 뒤 다시 허용할 길 */
    @Test
    fun s5DeniedPermissionHasRequestButton() {
        var requests = 0
        show {
            S5Screen(
                S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, setOf(DeniedPermission.MIC), null, ""),
                UiSamples.noActions, {}, {}, {}, { NOW },
                onRequestPermissions = { requests++ },
            )
        }
        rule.onNodeWithText("지원 현황").performClick()
        rule.onNodeWithText("허용 요청").performScrollTo().performClick()
        assertEquals(1, requests)
    }

    /** 40분과 혈당 판정이 화물차 규정에서 온 것처럼 읽히면 안 된다 */
    @Test
    fun s3GlucoseOnlyHasNoTruckSourceOnLeft() {
        val model = S3Presenter.present(UiSamples.recoverGlucoseTimer, false, NOW)
        assertFalse(model.showRestSource)
        assertTrue(S3Presenter.present(UiSamples.recoverFatigue, false, NOW).showRestSource)
        show(1700) { S3Screen(model, emptyList(), null, {}) }
        val text = rule.screenText()
        assertFalse("화물차" in text, text)
        assertTrue("Diabetes Canada" in text, text)
    }

    /** 화물차 규정은 규칙이 있는 안내 시점 창으로, 혈당 행이 있으면 혈당 출처도 함께 보인다 */
    @Test
    fun s2SourcesFollowWhatIsShown() {
        show(1700) { S2Screen(S2Presenter.present(UiSamples.todayFull, false, "폰", NOW), null, {}) }
        val text = rule.screenText()
        assertTrue("2시간·15분 참고: 화물자동차 운수사업법 시행규칙" in text, text)
        assertTrue("Diabetes Canada" in text, text)
    }

    @Test
    fun s3HiddenAvoidsGlucoseWord() {
        val model = S3Presenter.present(UiSamples.recoverGlucoseTimer, null, NOW)
        assertEquals("회복 대기", model.trendTitle.str())
        show(1700) { S3Screen(model, emptyList(), null, {}) }
        val text = rule.screenText()
        assertFalse("혈당" in text, text)
        assertFalse("mg/dL" in text, text)
    }

    @Test
    fun s3HiddenLossLineHasNoGlucoseWord() {
        val view = UiSamples.recoverGlucoseTimer.copy(glucoseMmol = null, glucoseUnavailable = true)
        assertEquals("연결 끊김. 직접 확인하고 출발하세요", S3Presenter.present(view, null, NOW).glucoseLine?.str())
        assertEquals("혈당 정보 없음. 직접 확인하고 출발하세요", S3Presenter.present(view, false, NOW).glucoseLine?.str())
        assertEquals("연결 끊김", S2Presenter.present(UiSamples.todayNoGlucose, null, null, NOW).glucose?.value?.str())
    }

    /** 눈금과 남은 시간을 더하면 전체가 된다 */
    @Test
    fun s3ElapsedPlusRemainingIsTotal() {
        val rest = UiSamples.recoverFatigue.copy(restRemainingSec = 14 * 60 + 30, restElapsedSec = 30)
        val model = S3Presenter.present(rest, false, NOW)
        assertEquals("14분", (model.big as BigValue.Time).text)
        assertEquals("1분 지남", model.horizon?.nowLabel?.str())
        val start = UiSamples.recoverFatigue.copy(restRemainingSec = 15 * 60L, restElapsedSec = 0)
        assertEquals("", S3Presenter.present(start, false, NOW).horizon?.nowLabel?.str())
    }

    /** 지우기는 한 번 눌러서는 실행되지 않는다 */
    @Test
    fun s5DeleteNeedsTwoTaps() {
        var cleared = 0
        s5(actions = object : SettingsActions {
            override fun clearTrips() { cleared++ }
        })
        rule.onNodeWithText("데이터와 동의").performClick()
        rule.onNodeWithText("기록 지우기").performClick()
        assertEquals(0, cleared)
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun s5LandscapeOpensDetailBesideList() {
        s5(width = 1700)
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertExists()
        assertTrue("K7M2QX9T" in rule.screenText())
        rule.onNodeWithText("기준과 출처").performClick()
        rule.waitForIdle()
        val text = rule.screenText()
        // 왼쪽 목록은 그대로다
        assertTrue("연결된 기기" in text && "Diabetes Canada 2018 21장" in text, text)
    }

    @Test
    fun s5PairedShowsPhoneAndUnlink() {
        s5(link = LinkState.FRESH, phone = "Galaxy S25")
        rule.onNodeWithText("연결된 기기").performClick()
        val text = rule.screenText()
        assertTrue("Galaxy S25 연결됨" in text && "연결 해제" in text, text)
        assertFalse("K7M2QX9T" in text, text)
    }

    /** 터치 타깃: 탭 88dp, 설정 행 88dp, 버튼 100dp와 80dp */
    @Test
    fun touchTargetsMeetTokens() {
        s5()
        rule.onNodeWithText("오늘").assertExists()
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            assertTrue(node.boundsInRoot.height >= 76f, "터치 높이 ${node.boundsInRoot}")
            assertTrue(node.boundsInRoot.width >= 76f, "터치 폭 ${node.boundsInRoot}")
        }
    }

    @Test
    fun s5TabOpensOtherScreen() {
        val tabs = mutableListOf<ParkedTab>()
        s5(tab = { tabs += it })
        rule.onNodeWithText("기록").performClick()
        assertEquals(listOf(ParkedTab.LOG), tabs)
    }

    /** 8.3 온보딩 3화면과 고지 */
    @Test
    fun onboardingIntroHasNoticeAndNoHealthConsent() {
        var next = 0
        show { OnboardingScreen(OnboardingStep.INTRO, VoiceTrial.Idle, null, {}, { next++ }, {}, {}) }
        val text = rule.screenText()
        assertTrue("쉴 때를 음성으로 알려 드려요" in text, text)
        assertTrue("제조사 공식 앱이 아니에요" in text, text)
        assertEquals(emptyList(), ForbiddenWords.findTrademarks(text))
        assertFalse("동의" in text, "건강 동의는 폰에서 받는다: $text")
        rule.onNodeWithText("시작").performClick()
        assertEquals(1, next)
    }

    @Test
    fun onboardingPermissionsListsWhy() {
        var requested = 0
        var later = 0
        show { OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, { requested++ }, { later++ }, {}, {}) }
        val text = rule.screenText()
        for (row in listOf("차량 속도", "운전 시간", "위치", "쉼터 찾기", "마이크", "음성 응답", "알림", "정차 권고")) {
            assertTrue(row in text, "$row / $text")
        }
        rule.onNodeWithText("허용 요청").performClick()
        rule.onNodeWithText("나중에").performClick()
        assertEquals(1 to 1, requested to later)
    }

    @Test
    fun onboardingVoiceTrialStates() {
        show { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Heard(CheckinAnswer.GOOD), null, {}, {}, {}, {}) }
        val text = rule.screenText()
        assertTrue("지금 컨디션은 어떠세요?" in text, text)
        assertTrue("인식 결과: 좋음" in text, text)
        assertTrue("끝내기" in text, text)
        assertFalse("건너뛰기" in text, text)
    }

    @Test
    fun onboardingListenStepBeforeTrialOffersSkipOnly() {
        show { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        val text = rule.screenText()
        assertTrue("말해 보기" in text && "좋아요, 피곤해요처럼" in text && "그만이라고 하세요" in text, text)
        assertTrue("건너뛰기" in text, text)
        assertFalse("끝내기" in text, text)
    }

    /** R-02: 들을 수 없으면 온보딩에서도 묻지 않는다. "말해 보기"를 누르면 질문만 듣고 "알아듣지 못했어요"가 됐다 */
    @Test
    fun onboardingWithoutMicDoesNotOfferVoiceTrial() {
        var finished = 0
        show { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Idle, null, {}, {}, {}, { finished++ }, voiceBlocked = true) }
        val text = rule.screenText()
        assertFalse("말해 보기" in text, text)
        assertFalse("어떠세요" in text, text)
        assertFalse("그만이라고" in text, text)
        assertTrue("마이크를 쓸 수 없어" in text, text)
        rule.onNodeWithText("끝내기").performClick()
        assertEquals(1, finished)
    }

    /** 권한은 있는데 엔진이 듣지 못하는 경우. 누른 뒤에 같은 화면으로 바뀐다 */
    @Test
    fun onboardingUnavailableTrialShowsSameNotice() {
        show { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Unavailable, null, {}, {}, {}, {}) }
        val text = rule.screenText()
        assertTrue("마이크를 쓸 수 없어" in text, text)
        assertFalse("알아듣지 못했어요" in text, text)
    }

    /** 앱이 말하는 동안 "듣고 있어요"가 뜨면 언제 답할지 알 수 없다 */
    @Test
    fun onboardingSpeakingStateIsNotListening() {
        show { OnboardingScreen(OnboardingStep.LISTEN, VoiceTrial.Speaking, null, {}, {}, {}, {}) }
        assertFalse("듣고 있어요" in rule.screenText())
    }

    @Test
    fun onboardingIntroExplainsRule() {
        show { OnboardingScreen(OnboardingStep.INTRO, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        val text = rule.screenText()
        assertTrue("2시간 연속 운전하면 가까운 쉼터를 골라 드려요" in text, text)
        // 제목과 같은 어미를 되풀이하지 않는다
        assertEquals(1, Regex("음성으로 알려 드려요").findAll(text).count(), text)
        assertTrue("제조사 공식 앱이 아니에요" in text, text)
        assertTrue("1/3" in text, text)
    }

    /** 나란한 두 버튼의 높이가 달라 어긋나면 안 된다 */
    @Test
    fun onboardingPermissionButtonsSameHeight() {
        show { OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        val request = rule.onNodeWithText("허용 요청", useUnmergedTree = false).fetchSemanticsNode().boundsInRoot.height
        val later = rule.onNodeWithText("나중에", useUnmergedTree = false).fetchSemanticsNode().boundsInRoot.height
        assertEquals(request, later)
    }

    /** R4. 표지가 어떤 화면에도 빠지지 않고 터치가 없다 */
    @Test
    fun demoBannerOnParkedScreens() {
        val banner = UiSamples.demoBanner
        show { S3Screen(S3Presenter.present(UiSamples.recoverFatigue, false, NOW), emptyList(), banner, {}) }
        rule.onNodeWithText(banner).assertExists()
        rule.onAllNodes(hasClickAction().and(androidx.compose.ui.test.hasText(banner))).assertCountEquals(0)
    }

    /** 긴 글자가 없어도 주 버튼 100dp를 보장한다 */
    @Test
    fun primaryButtonHeight() {
        show { OnboardingScreen(OnboardingStep.INTRO, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        rule.onAllNodesWithText("시작").assertCountEquals(1)
        rule.onNode(hasClickAction()).assertHeightIsAtLeast(100.dp)
    }

    /** "주의 1시간 45분"만으로는 연속 운전 시간인지 남은 시간인지 알 수 없다 */
    @Test
    fun s2RulesSayWhatTheyCount() {
        show(1700) { S2Screen(S2Presenter.present(UiSamples.normal, false, null, NOW), null, {}) }
        val text = rule.screenText()
        assertTrue("연속 운전 1시간 45분이면" in text && "연속 운전 2시간이면" in text, text)
        assertTrue("15분 쉬면 운전 시간을 다시 세요" in text, text)
    }

    /** "기준 위로 오면 40분을 세요"는 직접 재는 절차처럼 읽힌다 */
    @Test
    fun s3WaitValueDoesNotCommandDriver() {
        val model = S3Presenter.present(UiSamples.recoverGlucoseWaiting, false, NOW)
        val big = (model.big as BigValue.Sentence).text.str()
        assertEquals("회복 기준 위로 오르면 40분 뒤 알려 드릴게요", big)
    }

    /** 14장 재시작 뒤 서버에 닿지 않아도 연결이 있으면 해제할 수 있어야 한다 */
    @Test
    fun s5DevicesShowLinkedWithoutName() {
        s5(link = LinkState.OFFLINE, phone = null)
        rule.onNodeWithText("연결된 기기").performClick()
        val text = rule.screenText()
        assertTrue("지금은 서버에 닿지 않아요" in text, text)
        assertTrue("연결 코드 받기" !in text, text)
        rule.onNodeWithText("연결 해제").assertExists()
    }

    /** F07: 온보딩은 오른쪽 창이 비어 회색 면만 남지 않게 창 하나(최대 840dp)다 */
    @Test
    fun onboardingIsOnePaneOnWideScreen() {
        show(1700) { OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertDoesNotExist()
        val title = rule.onNodeWithText("필요한 권한").fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= 840f, title.toString())
        assertTrue("2/3" in rule.screenText(), rule.screenText())
    }

    /** F09: O2는 무엇을 허용하는지가 요점이라 권한 이름이 용도보다 크다 */
    @Test
    fun onboardingPermissionNameOutweighsPurpose() {
        show { OnboardingScreen(OnboardingStep.PERMISSIONS, VoiceTrial.Idle, null, {}, {}, {}, {}) }
        val name = rule.onNodeWithText("차량 속도").fetchSemanticsNode().boundsInRoot
        val purpose = rule.onNodeWithText("운전 시간").fetchSemanticsNode().boundsInRoot
        assertTrue(name.height > purpose.height, "$name / $purpose")
    }

    /** F11: 추세는 값 아래 오른쪽, 시뮬레이션 표시는 왼쪽이다. 리뷰: 둘이 한 줄을 나눠 써 행이 지그재그 3줄이 되지 않는다 */
    @Test
    fun s2TrendSitsUnderValue() {
        show { S2Screen(S2Presenter.present(UiSamples.todayFull, false, null, NOW), null, {}) }
        val value = rule.onNodeWithText("97 mg/dL (5.4 mmol/L)").fetchSemanticsNode().boundsInRoot
        val trend = rule.onNodeWithText("내려가는 중").fetchSemanticsNode().boundsInRoot
        val note = rule.onNodeWithText("시뮬레이션 혈당").fetchSemanticsNode().boundsInRoot
        assertEquals(value.right, trend.right, 1f)
        assertTrue(trend.top >= value.bottom - 1f, "$value / $trend")
        assertTrue(note.left < value.left, "$note / $value")
        assertEquals(trend.top, note.top, 1f)
    }

    /** 감사 누락분: S2 두 창의 출처 줄이 같은 높이에 있다 */
    @Test
    fun s2SourceLinesShareBaseline() {
        show(1700) { S2Screen(S2Presenter.present(UiSamples.todayFull, false, null, NOW), null, {}) }
        val left = rule.onNodeWithText("기준: Diabetes Canada 2018 (캐나다 지침)").fetchSemanticsNode().boundsInRoot
        val right = rule.onAllNodesWithText("수면 기준: 설계값").fetchSemanticsNodes().single().boundsInRoot
        assertEquals(left.bottom, right.bottom, 1f)
    }

    /** F10: 지원 현황은 항목과 상태를 나눈 행이다. 화면 정보는 맨 아래 출처 줄이다 */
    @Test
    fun s5SupportIsRows() {
        show {
            S5Screen(
                S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, setOf(DeniedPermission.MIC), null, ""),
                UiSamples.noActions, {}, {}, {}, { NOW },
            )
        }
        rule.onNodeWithText("지원 현황").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("마이크 권한").assertExists()
        rule.onAllNodesWithText("허용 안 됨").assertCountEquals(1)
        val text = rule.screenText()
        assertTrue(text.trimEnd().lines().any { it.startsWith("화면 정보: 밀도") }, text)
    }

    /** H24, U-28. 규칙 창 제목은 "안내 시점"이고 1시간 45분은 설계값이라고 적는다. 창 1개와 2개 모두 */
    @Test
    fun s2GuidancePointLabelSinglePane() = s2GuidancePointLabel(840)

    @Test
    fun s2GuidancePointLabelTwoPanes() = s2GuidancePointLabel(1700)

    private fun s2GuidancePointLabel(width: Int) {
        show(width) { S2Screen(S2Presenter.present(UiSamples.normal, false, null, NOW), null, {}) }
        val text = rule.screenText()
        assertFalse("오늘 기준" in text, text)
        assertTrue("기본 주의 시점 1시간 45분: 설계값" in text, text)
        assertTrue("2시간·15분 참고: 화물자동차 운수사업법 시행규칙" in text, text)
        if (width == 1700) assertTrue("안내 시점" in text && "연속 운전 1시간 45분이면" in text, text)
    }

    /** U-28. 앞당겨 규칙 행이 1시간 15분이어도 설계값 줄은 기본값 1시간 45분이다. 앞당김은 수면 줄이 말한다 */
    @Test
    fun cautionSourceStaysDefaultWhenAdvanced() {
        val model = S2Presenter.present(UiSamples.todayFull, false, null, NOW)
        assertEquals("1시간 15분", model.cautionAt)
        show(1700) { S2Screen(model, null, {}) }
        val text = rule.screenText()
        assertTrue("기본 주의 시점 1시간 45분: 설계값" in text && "수면 기준: 설계값" in text, text)
    }

    /** S3 오른쪽 규칙 창도 같은 제목을 쓴다 */
    @Test
    fun s3RulesPaneUsesGuidancePointTitle() {
        show(1700) { S3Screen(S3Presenter.present(UiSamples.recoverFatigue, false, NOW), emptyList(), null, {}) }
        val text = rule.screenText()
        assertTrue("안내 시점" in text, text)
        assertFalse("오늘 기준" in text, text)
    }

    /** H21, U-29. 수면 값이 없는데 앞당김이 남으면 왼쪽에 사유 줄이 있다. 창 1개에서도 보인다 */
    @Test
    fun s2AdvanceKeptShowsReasonSinglePane() {
        val model = S2Presenter.present(UiSamples.todayAdvanceKeptDriven, false, null, NOW)
        assertEquals(ADVANCE_KEPT, model.advanceKeptNote?.str())
        show(840) { S2Screen(model, null, {}) }
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertDoesNotExist()
        val text = rule.screenText()
        assertTrue(ADVANCE_KEPT in text, text)
        assertTrue(text.lines().none { it == "수면" }, text)
        assertNoMedicalOrRaw(text)
    }

    @Test
    fun s2AdvanceKeptShowsReasonOnLeftOfTwoPanes() {
        show(1700) { S2Screen(S2Presenter.present(UiSamples.todayAdvanceKept, false, null, NOW), null, {}) }
        val text = rule.screenText()
        assertTrue("연속 운전 1시간 15분이면" in text, text)
        val note = rule.onNodeWithText(ADVANCE_KEPT_IDLE).fetchSemanticsNode().boundsInRoot
        val pane = rule.onNodeWithTag(TAG_RIGHT_PANE).fetchSemanticsNode().boundsInRoot
        assertTrue(note.right <= pane.left + 1f, "$note / $pane")
    }

    /** U-29. 수면 행이 있으면 보조 글이 이미 말하므로 사유 줄을 겹치지 않는다. 앞당김이 없으면 없다 */
    @Test
    fun s2AdvanceKeptOnlyWhenSleepMissing() {
        assertEquals(null, S2Presenter.present(UiSamples.todayFull, false, null, NOW).advanceKeptNote)
        assertEquals(null, S2Presenter.present(UiSamples.todayBasic, false, null, NOW).advanceKeptNote)
        // 동승자가 있어도 수면 값은 숨김 대상이 아니라 같은 문장이다
        assertTrue(S2Presenter.present(UiSamples.todayAdvanceKept, null, null, NOW).advanceKeptNote != null)
    }

    /** 쌓인 운전이 0이면 15분 쉬어도 풀 것이 없다. "15분 쉴 때까지"라고 하지 않는다 */
    @Test
    fun s2AdvanceKeptWithoutDriveDoesNotPromiseRest() {
        val idle = S2Presenter.present(UiSamples.todayAdvanceKept, false, null, NOW).advanceKeptNote?.str()
        assertEquals(ADVANCE_KEPT_IDLE, idle)
        assertFalse("쉴 때까지" in idle.orEmpty())
        assertEquals(ADVANCE_KEPT, S2Presenter.present(UiSamples.todayAdvanceKeptDriven, false, null, NOW).advanceKeptNote?.str())
    }

    /** H23, U-31. 동의 철회는 폰에서 한다는 한 줄이 있고 차량에는 철회 버튼이 없다 */
    @Test
    fun s5DataSaysWithdrawOnPhoneOnly() {
        s5(link = LinkState.FRESH, phone = "Galaxy S25")
        rule.onNodeWithText("데이터와 동의").performClick()
        val text = rule.screenText()
        assertTrue("동의 철회는 폰 동반 앱의 데이터 탭에서 해요" in text, text)
        rule.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasText("철회", substring = true)).assertCountEquals(0)
    }

    private fun s5Hold(
        hold: Boolean,
        actions: SettingsActions = UiSamples.noActions,
        link: LinkState = LinkState.UNPAIRED,
        facts: SettingsFacts = UiSamples.settingsPaired,
        releasableAtMs: Long? = null,
        live: Boolean = false,
    ) = show {
        S5Screen(
            S5State(facts, link, null, emptySet(), null, "", glucoseHold = hold, holdReleasableAtMs = releasableAtMs, glucoseLive = live),
            actions, {}, {}, {}, { NOW },
        )
    }

    private class CountingRelease(val answer: HoldRelease = HoldRelease.RELEASED) : SettingsActions {
        var released = 0
        override suspend fun releaseGlucoseHold(): HoldRelease {
            released++
            return answer
        }
    }

    /** H22, U-30. 값이 끊긴 보류일 때만 버튼이 있고 두 번 눌러야 한 번 실행된다. 본문은 상태와 무관한 사실과 누른 뒤의 결과다 */
    @Test
    fun s5HoldReleaseNeedsTwoTaps() {
        val actions = CountingRelease()
        s5Hold(true, actions)
        rule.onNodeWithText("연결된 기기").performClick()
        val text = rule.screenText()
        assertTrue("컨디션 값이 끊겨 있어요. 풀면 새 값이 올 때까지 시간 기준으로만 안내해요" in text, text)
        assertFalse("멈췄어요" in text, text)
        assertFalse("혈당" in text, text)
        assertNoMedicalOrRaw(text)
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().performClick()
        assertEquals(0, actions.released)
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, actions.released)
    }

    @Test
    fun s5HoldReleaseHiddenWithoutHold() {
        s5Hold(false, link = LinkState.FRESH)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("연결 해제").assertExists()
        rule.onAllNodesWithText("끊김 보류 풀기").assertCountEquals(0)
        rule.onAllNodesWithText("컨디션 값이 끊겨", substring = true).assertCountEquals(0)
    }

    /** 연결이 있어도 값은 끊길 수 있다. 연결 해제와 따로 보인다 */
    @Test
    fun s5HoldReleaseShownWhileLinked() {
        s5Hold(true, link = LinkState.OFFLINE)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("연결 해제").assertExists()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().assertExists()
    }

    /** D-33. 낮은 값 뒤 40분 전에는 엔진이 받지 않는다. 버튼 대신 남은 시간을 한 줄로 보인다 */
    @Test
    fun s5HoldWaitsAfterLowValue() {
        s5Hold(true, releasableAtMs = NOW + 25 * 60_000L)
        rule.onNodeWithText("연결된 기기").performClick()
        assertTrue("마지막 값이 낮아 25분 뒤에 풀 수 있어요" in rule.screenText())
        rule.onAllNodesWithText("끊김 보류 풀기").assertCountEquals(0)
    }

    /** 풀 수 있는 시각이 지났으면 버튼이다 */
    @Test
    fun s5HoldButtonOnceWaitIsOver() {
        s5Hold(true, releasableAtMs = NOW - 1)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().assertExists()
    }

    /** 엔진이 받았을 때만 "풀었어요"다. 결과는 버튼이 있던 페이지에만 나오고 데이터 페이지에는 없다 */
    @Test
    fun s5HoldReleasedNoticeOnlyWhenEngineAccepted() {
        var hold by mutableStateOf(true)
        val actions = object : SettingsActions {
            override suspend fun releaseGlucoseHold(): HoldRelease {
                hold = false
                return HoldRelease.RELEASED
            }
        }
        show {
            S5Screen(S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "", glucoseHold = hold), actions, {}, {}, {}, { NOW })
        }
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().performClick()
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("끊김 보류를 풀었어요. 새 값이 오면 다시 판단해요" in rule.screenText())
        rule.onNodeWithText("목록으로").performClick()
        rule.onNodeWithText("데이터와 동의").performClick()
        assertFalse("풀었어요" in rule.screenText())
    }

    /** 엔진이 거절했으면(그 사이 출발, 값 복귀) 실패 한 줄이고 "풀었어요"는 없다 */
    @Test
    fun s5HoldRefusedSaysFailed() = holdFails(HoldRelease.REFUSED)

    /** 런타임이 없거나 답이 없으면 아무 반응 없이 끝나지 않는다 */
    @Test
    fun s5HoldWithoutRuntimeSaysFailed() = holdFails(HoldRelease.UNAVAILABLE)

    private fun holdFails(answer: HoldRelease) {
        s5Hold(true, CountingRelease(answer))
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().performClick()
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performScrollTo().performClick()
        rule.waitForIdle()
        val text = rule.screenText()
        assertTrue("풀지 못했어요. 잠시 뒤 다시 해 주세요" in text, text)
        assertFalse("풀었어요" in text, text)
        // 다시 시도할 수 있게 버튼은 남는다
        rule.onNodeWithText("끊김 보류 풀기").assertExists()
    }

    /** 새 값이 오면 지난 결과 문장은 사실이 아니다 */
    @Test
    fun s5HoldReleasedNoticeClearsOnNewValue() {
        var hold by mutableStateOf(true)
        var live by mutableStateOf(false)
        val actions = object : SettingsActions {
            override suspend fun releaseGlucoseHold(): HoldRelease {
                hold = false
                return HoldRelease.RELEASED
            }
        }
        show {
            S5Screen(S5State(UiSamples.settingsPaired, LinkState.UNPAIRED, null, emptySet(), null, "", glucoseHold = hold, glucoseLive = live), actions, {}, {}, {}, { NOW })
        }
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo().performClick()
        rule.onNodeWithText("한 번 더 누르면 실행돼요").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("끊김 보류를 풀었어요" in rule.screenText())
        live = true
        rule.waitForIdle()
        assertFalse("풀었어요" in rule.screenText())
    }

    /** 창 하나 목록에서도 보류가 있다는 단서가 있다. S2와 같은 "연결 끊김"이다 */
    @Test
    fun s5ListShowsHoldOnDevicesRow() {
        assertEquals(UiText.res(R.string.glucose_unavailable_hidden), S5Presenter.rows(LinkState.UNPAIRED, null, UiSamples.settingsPaired, glucoseHold = true).devices)
        assertEquals(UiText.res(R.string.value_none), S5Presenter.rows(LinkState.UNPAIRED, null, UiSamples.settingsPaired).devices)
    }

    /** 버튼 크기는 design-tokens 보조 버튼(80dp)이다 */
    @Test
    fun s5HoldReleaseMeetsTouchTarget() {
        s5Hold(true)
        rule.onNodeWithText("연결된 기기").performClick()
        rule.onNodeWithText("끊김 보류 풀기").performScrollTo()
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            assertTrue(node.boundsInRoot.height >= 76f, "터치 높이 ${node.boundsInRoot}")
        }
    }
}
