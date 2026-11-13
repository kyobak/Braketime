package com.conditiondrive.vehicle.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.vehicle.port.UxLimits
import com.conditiondrive.vehicle.ui.components.TAG_RIGHT_PANE
import com.conditiondrive.vehicle.ui.model.BigValue
import com.conditiondrive.vehicle.ui.model.S1Model
import com.conditiondrive.vehicle.ui.model.S1Presenter
import com.conditiondrive.vehicle.ui.model.StatusKind
import com.conditiondrive.vehicle.ui.preview.UiSamples
import com.conditiondrive.vehicle.ui.screens.S1Screen
import com.conditiondrive.vehicle.ui.screens.TAG_S1_BODY
import com.conditiondrive.vehicle.ui.theme.CdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** H1, H10, 8.2 표. 글자 넘침은 실제 글꼴로 재므로 NATIVE 그래픽이다 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class S1ScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val place = UiSamples.place

    private val views: Map<String, EngineView> = mapOf(
        "normal" to UiSamples.normal,
        "normal 최장" to UiSamples.view(cdsSec = 60),
        "caution 피로" to UiSamples.cautionSoon,
        "caution 체크인" to UiSamples.cautionCheckin,
        "caution 혈당" to UiSamples.view(DriverState.CAUTION, setOf(Reason.GLUCOSE), mmol = 5.4, trend = Trend.FALLING, measuredAt = UiSamples.NOW_MS, simulated = true),
        "caution 미확인" to UiSamples.view(DriverState.CAUTION, setOf(Reason.GLUCOSE_UNCONFIRMED), unavailable = true),
        "stop 피로" to UiSamples.stopFatigue,
        "stop 혈당" to UiSamples.stopGlucose,
        "recover 유예" to UiSamples.departGrace,
        "앱 시작 후" to UiSamples.view(sinceStart = true, simulated = true),
    )

    private fun models(): List<Pair<String, S1Model>> = views.flatMap { (name, view) ->
        listOf(
            name to S1Presenter.present(view),
            "$name 장소·경로" to S1Presenter.present(view, place, 6000),
            "$name 듣는 중" to S1Presenter.present(view, place, listening = true),
        )
    }

    /** 폭과 모델을 바꿔 가며 같은 검사를 돌린다. setContent는 테스트당 한 번만 가능하다 */
    private var size by mutableStateOf(WINDOW_SIZES.first())

    private fun forEachCase(banner: String?, check: (String) -> Unit) {
        var model by mutableStateOf(models().first().second)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(size.first.dp, size.second.dp)) { S1Screen(model, banner) }
            }
        }
        for (s in WINDOW_SIZES) {
            for ((name, m) in models()) {
                size = s
                model = m
                rule.waitForIdle()
                check("${s.first}x${s.second} $name")
            }
        }
    }

    @Test
    fun noTouchFocusScrollAtAnyWidth() = forEachCase(null) { case ->
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size, "클릭 $case")
        assertEquals(0, rule.onAllNodes(isFocusable(), useUnmergedTree = true).fetchSemanticsNodes().size, "포커스 $case")
        assertEquals(0, rule.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().size, "스크롤 $case")
    }

    /** R4 표지가 붙어도 터치가 생기지 않는다 */
    @Test
    fun noTouchWithDemoBanner() = forEachCase("${UiSamples.demoBanner} · 혈당 시뮬레이션") { case ->
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size, case)
    }

    @Test
    fun h10NoGlucoseWordOrNumberInBody() = forEachCase(null) { case ->
        val text = rule.screenText()
        assertFalse("혈당" in text, "$case: $text")
        assertFalse("mg/dL" in text, "$case: $text")
        assertFalse("mmol" in text, "$case: $text")
        assertEquals(emptyList(), ForbiddenWords.find(text), case)
    }

    /** 글자가 잘리거나 넘치면 1~2초 안에 못 읽는다. 시험 폭 전부를 실제 글꼴로 잰다 */
    @Test
    fun textFitsAtEveryWidth() = forEachCase(null) { case ->
        rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().allNodes().forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.forEach { result ->
                val text = result.layoutInput.text
                // didOverflowWidth는 말줄임 설정이면 넘치지 않아도 참이라 쓸 수 없다. 줄 수 초과와 말줄임으로 본다
                assertFalse(result.multiParagraph.didExceedMaxLines, "줄 넘침 $case: $text")
                assertFalse(result.isLineEllipsized(result.lineCount - 1), "말줄임 $case: $text")
            }
            assertTrue(node.boundsInRoot.bottom <= size.second.toFloat() + 1f, "화면 밖 $case: ${node.boundsInRoot}")
        }
    }

    /** Portrait 핵심은 왼쪽에 그대로 남고, 창 2개는 1100dp부터다(design-tokens 6장) */
    @Test
    fun rightPaneAppearsOnlyWhenWide() {
        size = 840 to 1184
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(size.first.dp, size.second.dp)) {
                    S1Screen(S1Presenter.present(UiSamples.cautionSoon, place, 6000), null)
                }
            }
        }
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertDoesNotExist()
        val portraitLeft = rule.onNodeWithTag(TAG_S1_BODY).getBoundsInRoot().left
        rule.onNodeWithTag(TAG_S1_BODY).assertTopPositionInRootIsEqualTo(145.dp)

        size = 1700 to 1184
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertExists()
        assertEquals(portraitLeft, rule.onNodeWithTag(TAG_S1_BODY).getBoundsInRoot().left)
        rule.onNodeWithTag(TAG_S1_BODY).assertTopPositionInRootIsEqualTo(145.dp)
    }

    /** 가장 짧게 봐야 하는 화면에서 같은 거리를 두 번 보이지 않는다. 창이 하나거나 오른쪽을 비우면 왼쪽에 남긴다 */
    @Test
    fun placeLineMovesWithRightPane() {
        var width by mutableStateOf(1700)
        var limits by mutableStateOf<UxLimits?>(null)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(width.dp, 1184.dp)) {
                    S1Screen(S1Presenter.present(UiSamples.stopFatigue, place, 6000, limits = limits), null)
                }
            }
        }
        fun count() = rule.screenText().split("3.2km").size - 1
        rule.waitForIdle()
        assertEquals(1, count(), rule.screenText())
        assertTrue("다음 쉼터" in rule.screenText())
        limits = UxLimits(noKeyboard = false, noSetup = false, limitContent = false, limitStringLength = true)
        rule.waitForIdle()
        assertEquals(1, count(), rule.screenText())
        assertFalse("다음 쉼터" in rule.screenText())
        limits = null
        width = 840
        rule.waitForIdle()
        assertEquals(1, count(), rule.screenText())
    }

    /** 듣는 중에도 라벨과 시간이 움직이지 않는다 */
    @Test
    fun listeningStaysInHeader() {
        var listening by mutableStateOf(false)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(840.dp, 1184.dp)) {
                    S1Screen(S1Presenter.present(UiSamples.cautionSoon, listening = listening), null)
                }
            }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithTag(TAG_S1_BODY).getBoundsInRoot()
        assertFalse("듣고 있어요" in rule.screenText())
        listening = true
        rule.waitForIdle()
        val text = rule.screenText()
        assertTrue("듣고 있어요" in text && "다음 휴식까지" in text, text)
        assertEquals(before, rule.onNodeWithTag(TAG_S1_BODY).getBoundsInRoot())
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** 눈금이 무엇을 재는지 적는다 */
    @Test
    fun horizonIsLabelled() {
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(1700.dp, 1184.dp)) { S1Screen(S1Presenter.present(UiSamples.normal), null) }
            }
        }
        rule.waitForIdle()
        assertTrue("연속 운전" in rule.screenText(), rule.screenText())
    }

    /** UX_RESTRICTIONS_LIMIT_STRING_LENGTH: 오른쪽 창을 비우고 왼쪽은 유지 */
    @Test
    fun stringLimitEmptiesRightPane() {
        val limited = UxLimits(noKeyboard = false, noSetup = false, limitContent = false, limitStringLength = true)
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(1700.dp, 1184.dp)) {
                    S1Screen(S1Presenter.present(UiSamples.cautionSoon, place, 6000, limits = limited), null)
                }
            }
        }
        rule.onNodeWithTag(TAG_RIGHT_PANE).assertExists()
        val text = rule.screenText()
        assertFalse("다음 쉼터" in text, text)
        assertTrue("12분" in text.replace("\n", ""), text)
    }

    /** 8.2 애니메이션 0개. 시계를 돌려도 한 픽셀도 바뀌지 않아야 한다 */
    @Test
    fun noMotionWhenClockAdvances() {
        var model by mutableStateOf(S1Presenter.present(UiSamples.normal, place, 6000))
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.requiredSize(1700.dp, 1184.dp)) { S1Screen(model, UiSamples.demoBanner) }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        for (next in listOf(UiSamples.cautionSoon, UiSamples.stopFatigue, UiSamples.departGrace)) {
            model = S1Presenter.present(next, place, 6000)
            assertTrue(firstFrameEqualsLater(), "화면이 움직였다: $next")
        }
    }

    /** 위 검사가 전환 효과를 정말 잡는지 일부러 애니메이션을 넣어 확인한다. 못 잡으면 위 통과는 의미가 없다 */
    @Test
    fun motionCheckDetectsRealAnimation() {
        var target by mutableStateOf(0f)
        rule.setContent {
            val alpha by animateFloatAsState(target, tween(1000))
            Box(Modifier.requiredSize(400.dp).alpha(alpha).background(Color.Red))
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        target = 1f
        assertFalse(firstFrameEqualsLater())
    }

    /** 변경을 반영한 첫 프레임과 5초 뒤 프레임을 비교한다. 즉시 교체면 같고 전환 효과가 있으면 다르다 */
    private fun firstFrameEqualsLater(): Boolean {
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(48)
        val first = capture()
        rule.mainClock.advanceTimeBy(5_000)
        return first.sameAs(capture())
    }

    private fun capture(): Bitmap {
        val root = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { root.draw(Canvas(bitmap)) }
        return bitmap
    }
}

/** 8.2 표의 칸을 그대로 옮긴 프레젠터 검사 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class S1PresenterTest {
    private fun big(model: S1Model): String = when (val b = model.big) {
        is BigValue.Time -> b.text
        is BigValue.Sentence -> b.text.str()
    }

    @Test
    fun normalShowsTimeToRest() {
        val m = S1Presenter.present(UiSamples.view(cdsSec = 7200 - 72 * 60))
        assertEquals(StatusKind.NORMAL, m.status)
        assertEquals("다음 휴식까지", m.label.str())
        assertEquals("1시간 12분", big(m))
    }

    @Test
    fun cautionShowsTimeAndNoGlucoseWord() {
        val m = S1Presenter.present(UiSamples.view(DriverState.CAUTION, setOf(Reason.GLUCOSE), cdsSec = 1200, mmol = 5.4, trend = Trend.FALLING))
        assertEquals(StatusKind.CAUTION, m.status)
        assertEquals("다음 휴식까지", m.label.str())
        assertEquals("1시간 40분", big(m))
    }

    @Test
    fun stopFatigueShowsDriveTime() {
        val m = S1Presenter.present(UiSamples.stopFatigue, UiSamples.place)
        assertEquals("2시간 8분째 운전 중", m.label.str())
        assertEquals("쉬어 가세요", big(m))
        assertEquals("졸음쉼터 약 3.2km", m.placeLine?.str())
    }

    @Test
    fun stopGlucoseAsksToStopSafely() {
        val m = S1Presenter.present(UiSamples.stopGlucose)
        assertEquals("안전한 곳에 차를 세워 주세요", m.label.str())
        assertEquals("쉬어 가세요", big(m))
        assertEquals(null, m.placeLine)
    }

    /** G=CAUTION(값 5.4)에 피로 STOP이 겹치면 사유는 피로다 */
    @Test
    fun stopWithGlucoseCautionKeepsFatigueLabel() {
        val m = S1Presenter.present(UiSamples.view(DriverState.STOP, setOf(Reason.FATIGUE, Reason.GLUCOSE), cdsSec = 7500, mmol = 5.4, trend = Trend.FALLING))
        assertEquals("2시간 5분째 운전 중", m.label.str())
    }

    /** 위험 쪽으로만 틀린다: 값을 모르면 안전 문구 */
    @Test
    fun stopWithGlucoseReasonAndNoValueIsSafeSide() {
        val m = S1Presenter.present(UiSamples.view(DriverState.STOP, setOf(Reason.GLUCOSE), cdsSec = 600))
        assertEquals("안전한 곳에 차를 세워 주세요", m.label.str())
    }

    @Test
    fun placeLineOnlyWhenStopped() {
        assertEquals(null, S1Presenter.present(UiSamples.cautionSoon, UiSamples.place).placeLine)
    }

    @Test
    fun recoverGraceShowsRestLeft() {
        val m = S1Presenter.present(UiSamples.departGrace)
        assertEquals(StatusKind.RECOVER, m.status)
        assertEquals("남은 휴식", m.label.str())
        assertEquals("12분", big(m))
    }

    @Test
    fun underOneMinuteIsSoon() {
        assertEquals("곧", big(S1Presenter.present(UiSamples.view(DriverState.CAUTION, setOf(Reason.FATIGUE), cdsSec = 7200 - 59))))
        assertEquals("곧", big(S1Presenter.present(UiSamples.view(DriverState.RECOVER, setOf(Reason.FATIGUE), restElapsed = 860, restRemaining = 40))))
    }

    /** 음수를 표시하지 않는다 */
    @Test
    fun remainingNullNeverGoesNegative() {
        assertEquals("곧", big(S1Presenter.present(UiSamples.view(DriverState.CAUTION, setOf(Reason.CHECKIN), cdsSec = 7300, remainingToRest = null))))
    }

    @Test
    fun recoverAfterRestDoneFallsBackWithoutGlucoseWord() {
        val timer = S1Presenter.present(UiSamples.view(DriverState.RECOVER, setOf(Reason.GLUCOSE), restRemaining = 0, recoveryRemaining = 1500))
        assertEquals("회복 대기", timer.label.str())
        assertEquals("25분", big(timer))
        val none = S1Presenter.present(UiSamples.view(DriverState.RECOVER, setOf(Reason.GLUCOSE)))
        assertEquals("쉬어 가세요", big(none))
    }

    /** 듣는 중: 라벨과 큰 표시는 그대로다. 머리 오른쪽에만 표시한다. "듣고 있어요 1시간 30분"이 되면 시간의 뜻이 사라진다 */
    @Test
    fun listeningKeepsLabelAndBig() {
        val base = S1Presenter.present(UiSamples.cautionSoon)
        val listening = S1Presenter.present(UiSamples.cautionSoon, listening = true)
        assertEquals(base.label, listening.label)
        assertEquals(base.status, listening.status)
        assertEquals(base.big, listening.big)
        assertTrue(listening.listening)
        assertFalse(base.listening)
    }

    /** 혈당만 사유면 15분은 회복 조건이 아니다. 음성과 S3는 40분을 말한다 */
    @Test
    fun glucoseOnlyRecoverDoesNotShowRest() {
        val view = UiSamples.recoverGlucoseTimer.copy(restRemainingSec = 500)
        assertEquals("회복 대기", S1Presenter.present(view).label.str())
        val fatigue = view.copy(reasons = view.reasons + Reason.FATIGUE)
        assertEquals("남은 휴식", S1Presenter.present(fatigue).label.str())
    }

    @Test
    fun checkinOnlyCautionSaysWhy() {
        val notes = S1Presenter.present(UiSamples.cautionCheckin).notes.map { it.str() }
        assertTrue("피곤하다고 하셨어요" in notes, notes.toString())
        assertFalse(S1Presenter.present(UiSamples.cautionSoon).notes.any { it.str() == "피곤하다고 하셨어요" })
    }

    /** 끊김 음성을 놓쳐도 눈으로 알 수 있어야 한다. 혈당 단어는 없다 */
    @Test
    fun linkLostNoteHasNoGlucoseWord() {
        val notes = S1Presenter.present(UiSamples.view(unavailable = true)).notes.map { it.str() }
        assertEquals(listOf("연결 끊김"), notes)
    }

    /** 13장 기어·차속 모두 없음. 시간을 세지 못하는데 "여유 2시간"을 보이면 거짓이다 */
    @Test
    fun noDriveSignalSaysSo() {
        val m = S1Presenter.present(UiSamples.normal.copy(noDriveSignal = true))
        assertEquals("운전 시간을 잴 수 없어요", m.label.str())
        assertEquals("음성 안내는 계속돼요", big(m))
    }

    /** 경로로 잰 거리는 "약" 없이 그 값을 쓴다 */
    @Test
    fun measuredDistanceIsExact() {
        val measured = UiSamples.place.copy(routeDistanceM = 4100)
        val m = S1Presenter.present(UiSamples.stopFatigue, measured)
        assertEquals("졸음쉼터 4.1km", m.placeLine?.str())
        assertEquals("4.1km", m.side?.placeDistance?.str())
    }

    @Test
    fun notes() {
        val simulated = S1Presenter.present(UiSamples.view(simulated = true))
        assertEquals(listOf("시뮬레이션 데이터"), simulated.notes.map { it.str() })
        val since = S1Presenter.present(UiSamples.view(sinceStart = true))
        assertEquals(listOf("앱 시작 후 측정"), since.notes.map { it.str() })
    }

    @Test
    fun sideLinesFollowAvailability() {
        val full = S1Presenter.present(UiSamples.cautionSoon, UiSamples.place, 6000).side!!
        assertEquals("칠곡졸음쉼터", full.placeName)
        assertEquals("약 3.2km", full.placeDistance?.str())
        assertEquals("목적지까지 1시간 40분", full.routeLine?.str())
        val bare = S1Presenter.present(UiSamples.cautionSoon).side!!
        assertEquals(null, bare.placeName)
        assertEquals(null, bare.routeLine)
    }

    /** Horizon은 분 단위로만 움직인다. 같은 분이면 모델이 같아 다시 그려지지 않는다 */
    @Test
    fun horizonMovesPerMinuteOnly() {
        val a = S1Presenter.present(UiSamples.view(cdsSec = 600))
        val b = S1Presenter.present(UiSamples.view(cdsSec = 659, remainingToRest = 7200 - 600))
        assertEquals(a, b)
        val c = S1Presenter.present(UiSamples.view(cdsSec = 660, remainingToRest = 7200 - 600))
        assertTrue(a.side!!.horizon.nowFraction < c.side!!.horizon.nowFraction)
    }

    @Test
    fun horizonFollowsAdvancedThresholds() {
        val h = S1Presenter.driveHorizon(UiSamples.view(cdsSec = 0, cautionSec = 4500, stopSec = 5400))
        assertEquals(listOf("0", "1:15", "1:30"), h.ticks.map { it.label })
        assertEquals(4500f / 5400f, h.ticks[1].fraction)
    }
}
