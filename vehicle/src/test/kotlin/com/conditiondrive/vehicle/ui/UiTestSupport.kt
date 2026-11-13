package com.conditiondrive.vehicle.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.UxLimits
import com.conditiondrive.vehicle.ui.model.UiText
import com.conditiondrive.vehicle.ui.preview.UiSamples
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** 설계서 폭 표(design-tokens 6장)와 각 폭의 실제 앱 영역 높이 */
val WINDOW_SIZES = listOf(628 to 855, 840 to 1184, 1274 to 855, 1700 to 1184)

fun SemanticsNode.allNodes(): List<SemanticsNode> = listOf(this) + children.flatMap { it.allNodes() }

fun ComposeTestRule.allTexts(): List<String> =
    onRoot(useUnmergedTree = true).fetchSemanticsNode().allNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

fun ComposeTestRule.screenText(): String = allTexts().joinToString("\n")

/** 프레젠터 결과를 실제 리소스로 읽는다 */
fun UiText.str(): String {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    return when (this) {
        is UiText.Plain -> value
        is UiText.Res -> {
            val args = args.map { if (it is UiText) it.str() else it }.toTypedArray()
            ctx.getString(id, *args)
        }
    }
}

/** 저장소 문서와 소스를 읽기 위한 루트. Gradle과 IDE의 작업 폴더가 달라도 찾는다 */
val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
}

fun fakeSource(
    view: EngineView = UiSamples.normal,
    mode: UiMode = UiMode.DRIVING,
    demoLabel: String? = null,
    passenger: Boolean? = false,
    trips: List<TripRecord> = emptyList(),
    settings: SettingsFacts = SettingsFacts(),
    actions: SettingsActions = UiSamples.noActions,
) = FakeSource(view, mode, demoLabel, passenger, trips, settings, actions)

class FakeSource(
    view: EngineView,
    mode: UiMode,
    demoLabel: String?,
    passenger: Boolean?,
    trips: List<TripRecord>,
    settings: SettingsFacts,
    actions: SettingsActions,
) {
    val viewFlow = MutableStateFlow(view)
    val modeFlow = MutableStateFlow(mode)
    val source = VehicleUiSource(
        view = viewFlow,
        uiMode = modeFlow,
        limits = MutableStateFlow(UxLimits(noKeyboard = false, noSetup = false, limitContent = false, limitStringLength = false)),
        promptPlace = MutableStateFlow(null),
        demoLabel = MutableStateFlow(demoLabel),
        route = MutableStateFlow(null),
        passengerPresent = MutableStateFlow(passenger),
        summary = MutableStateFlow(SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)),
        link = MutableStateFlow(LinkState.UNPAIRED),
        tripsToday = flowOf(trips),
        listening = MutableStateFlow(false),
        settings = MutableStateFlow(settings),
        actions = actions,
        nowMs = { UiSamples.NOW_MS },
        zone = UiSamples.ZONE,
    )
}

/**
 * docs/screenshots/25-s4-week-before-fix.png의 실제 기록(2026-10-31 서울, 지금 19:19:40).
 * 옛 화면: 19:05 운전 1분, 19:06 휴식 12분, 19:18 운전 0분, 19:19 휴식 0분, 합계 "휴식 0회"(U-23)
 */
object ShortStopSample {
    private fun at(h: Int, m: Int, s: Int) =
        java.time.ZonedDateTime.of(2026, 10, 31, h, m, s, 0, UiSamples.ZONE).toInstant().toEpochMilli()

    val NOW_MS = at(19, 19, 40)
    val trips = listOf(
        TripRecord(
            tripId = "week-shot",
            startedAtMs = at(19, 5, 10),
            endedAtMs = null,
            segments = listOf(
                com.conditiondrive.vehicle.port.TripSegment("drive", at(19, 5, 10), at(19, 6, 20)),
                com.conditiondrive.vehicle.port.TripSegment("rest", at(19, 6, 20), at(19, 18, 30)),
                com.conditiondrive.vehicle.port.TripSegment("drive", at(19, 18, 30), at(19, 19, 0)),
                com.conditiondrive.vehicle.port.TripSegment("rest", at(19, 19, 0), null),
            ),
            stopAdvised = 0, declined = 0, restCompleted = 0, conditionStops = 0,
        ),
    )
}

/**
 * docs/screenshots/25-s4-week-before-u24.png(U-23 수정 뒤, U-24 전 실기)와 같은 모양(2026-11-02 서울, 지금 00:29:30).
 * 오늘 운행 1개에 운전 행 2개(00:00 운전 4분 · 중간 멈춤 3분, 00:07 휴식 16분, 00:24 운전 2분, 00:26 멈춤 3분),
 * 어제 운행 1개(운전 2분, 휴식 15분). 옛 문구는 7일 "운전 2회, 8분"이라 오늘 운전 행 2개를 센 것처럼 읽혔다(U-24)
 */
object TripVsRowSample {
    private fun at(day: Int, h: Int, m: Int, s: Int = 0) =
        java.time.ZonedDateTime.of(2026, 11, day, h, m, s, 0, UiSamples.ZONE).toInstant().toEpochMilli()

    private fun seg(type: String, start: Long, end: Long?) = com.conditiondrive.vehicle.port.TripSegment(type, start, end)

    val NOW_MS = at(2, 0, 29, 30)
    val today = TripRecord(
        tripId = "today",
        startedAtMs = at(2, 0, 0),
        endedAtMs = null,
        segments = listOf(
            seg("drive", at(2, 0, 0), at(2, 0, 2)),
            seg("rest", at(2, 0, 2), at(2, 0, 5)),
            seg("drive", at(2, 0, 5), at(2, 0, 7, 30)),
            seg("rest", at(2, 0, 7, 30), at(2, 0, 24)),
            seg("drive", at(2, 0, 24), at(2, 0, 26)),
            seg("rest", at(2, 0, 26), null),
        ),
        stopAdvised = 0, declined = 0, restCompleted = 1, conditionStops = 0,
    )
    val yesterday = TripRecord(
        tripId = "yesterday",
        startedAtMs = at(1, 18, 0),
        endedAtMs = at(1, 18, 17),
        segments = listOf(seg("drive", at(1, 18, 0), at(1, 18, 2)), seg("rest", at(1, 18, 2), at(1, 18, 17))),
        stopAdvised = 0, declined = 0, restCompleted = 1, conditionStops = 0,
    )
    val week = listOf(yesterday, today)
}
