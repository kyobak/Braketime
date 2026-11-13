package com.conditiondrive.vehicle.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.components.BigTime
import com.conditiondrive.vehicle.ui.components.BigValueSentence
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.CdWindow
import com.conditiondrive.vehicle.ui.components.GlucoseGraph
import com.conditiondrive.vehicle.ui.components.Hairline
import com.conditiondrive.vehicle.ui.components.Horizon
import com.conditiondrive.vehicle.ui.components.InfoRow
import com.conditiondrive.vehicle.ui.components.LocalTwoPanes
import com.conditiondrive.vehicle.ui.components.LocalWindowPadding
import com.conditiondrive.vehicle.ui.components.SourceLine
import com.conditiondrive.vehicle.ui.components.StatusBand
import com.conditiondrive.vehicle.ui.components.StatusHeader
import com.conditiondrive.vehicle.ui.components.TitleHeader
import com.conditiondrive.vehicle.ui.components.touch.SecondaryButton
import com.conditiondrive.vehicle.ui.components.touch.TabBar
import com.conditiondrive.vehicle.ui.model.BigValue
import com.conditiondrive.vehicle.ui.model.GlucosePoint
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.REST_RESET_MINUTES
import com.conditiondrive.vehicle.ui.model.S2Model
import com.conditiondrive.vehicle.ui.model.S3Model
import com.conditiondrive.vehicle.ui.model.S4Model
import com.conditiondrive.vehicle.ui.model.S4Recent
import com.conditiondrive.vehicle.ui.model.StatusKind
import com.conditiondrive.vehicle.ui.model.text
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

const val TAG_S2 = "S2"
const val TAG_S3 = "S3"
const val TAG_S4 = "S4"

/** 위쪽은 내용, 아래쪽은 출처와 탭 고정. 탭 위치가 화면마다 흔들리지 않게 공통으로 둔다 */
@Composable
private fun ColumnScope.ParkedLeft(
    selected: ParkedTab,
    onTab: (ParkedTab) -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    header()
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
    footer()
    TabBar(selected, onTab)
}

@Composable
private fun ColumnScope.RightPaneBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

@Composable
private fun PaneTitle(text: String) {
    TitleHeader(text)
}

@Composable
private fun TextLine(text: String, style: TextStyle, modifier: Modifier = Modifier, secondary: Boolean = false) {
    CdText(
        text,
        style,
        modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current),
        color = if (secondary) CdTheme.colors.text2 else CdTheme.colors.text1,
    )
}

/** 출발 보류 띠. 버튼은 없다. 운전을 막지 않는다 */
@Composable
private fun HoldBand() {
    StatusBand(StatusKind.STOP, stringResource(R.string.hold_band), CdTheme.type.titleL)
}

@Composable
fun S2Screen(model: S2Model, demoLabel: String?, onTab: (ParkedTab) -> Unit, modifier: Modifier = Modifier) {
    CdWindow(
        demoLabel = demoLabel,
        modifier = modifier.testTag(TAG_S2),
        right = { S2Right(model) },
    ) {
        ParkedLeft(
            selected = ParkedTab.TODAY,
            onTab = onTab,
            footer = {
                // 창 2개면 화물차 규정과 주의 시점 설계값은 규칙이 보이는 오른쪽 "안내 시점" 아래로 간다
                if (!LocalTwoPanes.current) {
                    SourceLine(stringResource(R.string.source_rest))
                    SourceLine(model.cautionSource.text())
                }
                if (model.showSleepSource && !LocalTwoPanes.current) SourceLine(stringResource(R.string.source_sleep))
                if (model.glucose != null) GlucoseSource(model.glucoseSourceAsButton)
            },
            header = {
                if (model.hold) HoldBand() else TitleHeader(stringResource(R.string.s2_title))
            },
        ) {
            InfoRow(stringResource(R.string.s2_checkin), model.checkin.text())
            model.sleep?.let { sleep ->
                InfoRow(
                    stringResource(R.string.s2_sleep),
                    sleep.text(),
                    subs = listOfNotNull(model.advancedNote?.text()),
                )
            }
            model.glucose?.let { glucose ->
                // 추세는 값의 설명이라 값 아래, 시뮬레이션 표시는 출처 주석이라 왼쪽이다
                InfoRow(
                    model.glucoseLabel.text(),
                    glucose.value.text(),
                    subs = listOfNotNull(glucose.simulated?.text()),
                    valueSubs = listOfNotNull(glucose.trend?.text()),
                )
            }
            // U-29. 수면 행이 없으면 앞당김 보조 글도 없다. 오른쪽 규칙 창이 없는 창 1개에서도 보이게 왼쪽에 둔다
            if (model.sleep == null) {
                model.advanceKeptNote?.let { TextLine(it.text(), CdTheme.type.bodyL, Modifier.padding(vertical = CdDimens.Space16), secondary = true) }
            }
            // 14장: 다른 운전자의 폰이 연결된 경우를 알아채도록 창 1개에서도 뺄 수 없다
            if (model.phone != null && !LocalTwoPanes.current) {
                InfoRow(stringResource(R.string.s2_phone), model.phone, subs = listOfNotNull(model.measured?.text()))
            }
        }
    }
}

@Composable
private fun ColumnScope.S2Right(model: S2Model) {
    RightPaneBody {
        PaneTitle(stringResource(R.string.s2_rules_title))
        RuleRows(model.cautionAt, model.stopAt)
        Spacer(Modifier.height(CdDimens.Space24))
        TextLine(stringResource(R.string.s2_rule_reset, REST_RESET_MINUTES), CdTheme.type.bodyL, secondary = true)
        Spacer(Modifier.height(CdDimens.Space24))
        Hairline()
        if (model.phone != null) {
            InfoRow(stringResource(R.string.s2_phone), model.phone, subs = listOfNotNull(model.measured?.text()))
        }
    }
    SourceLine(stringResource(R.string.source_rest))
    SourceLine(model.cautionSource.text())
    if (model.showSleepSource) SourceLine(stringResource(R.string.source_sleep))
    TabBarGap()
}

/** 왼쪽 출처 줄은 탭 바 위에 있다. 오른쪽 출처 줄도 같은 높이에 오도록 탭 바 높이만큼 비운다 */
@Composable
private fun TabBarGap() {
    Spacer(Modifier.height(CdDimens.TabBar + CdDimens.Line))
}

/** "주의 1시간 45분"만으로는 연속 운전 시간인지 남은 시간인지 알 수 없다 */
@Composable
private fun RuleRows(cautionAt: String, stopAt: String) {
    InfoRow(stringResource(R.string.s2_rule_at, cautionAt), stringResource(R.string.s2_rule_caution))
    InfoRow(stringResource(R.string.s2_rule_at, stopAt), stringResource(R.string.s2_rule_stop))
}

@Composable
fun S3Screen(
    model: S3Model,
    history: List<GlucosePoint>,
    demoLabel: String?,
    onTab: (ParkedTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    CdWindow(
        demoLabel = demoLabel,
        modifier = modifier.testTag(TAG_S3),
        right = { S3Right(model, history) },
    ) {
        ParkedLeft(
            selected = ParkedTab.TODAY,
            onTab = onTab,
            footer = { S3Footer(model) },
            header = { if (model.hold) HoldBand() else StatusHeader(model.status) },
        ) {
            Spacer(Modifier.height(CdDimens.Space32))
            TextLine(model.label.text(), CdTheme.type.titleS, secondary = true)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalWindowPadding.current),
            ) {
                Spacer(Modifier.height(CdDimens.Space8))
                when (val big = model.big) {
                    is BigValue.Time -> BigTime(big.text)
                    is BigValue.Sentence -> BigValueSentence(big.text)
                }
                model.horizon?.let {
                    Spacer(Modifier.height(CdDimens.Space16))
                    Horizon(it)
                }
                Spacer(Modifier.height(CdDimens.Space24))
            }
            model.hint?.let { TextLine(it.text(), CdTheme.type.bodyM, secondary = true) }
            // 창 2개면 오른쪽 그래프 위로 옮긴다. 수치가 양쪽에 겹치면 정보 3개 원칙을 넘는다
            if (!LocalTwoPanes.current) {
                model.glucoseLine?.let { TextLine(it.text(), CdTheme.type.bodyL) }
                model.simulated?.let { TextLine(it.text(), CdTheme.type.bodyL, secondary = true) }
            }
        }
    }
}

/** U-10. 창 2개면 왼쪽은 휴식 기준, 오른쪽은 혈당 기준이다. 왼쪽에 보이는 값과 맞는 기준만 적는다 */
@Composable
private fun S3Footer(model: S3Model) {
    val detail = model.glucoseDetail
    if (model.showRestSource) SourceLine(stringResource(R.string.source_rest))
    if (detail != null && !LocalTwoPanes.current) GlucoseSource(detail.sourceAsButton)
}

@Composable
private fun GlucoseSource(asButton: Boolean) {
    if (!asButton) {
        SourceLine(stringResource(R.string.source_glucose))
        return
    }
    var open by remember { mutableStateOf(false) }
    if (open) SourceLine(stringResource(R.string.source_glucose))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space8),
    ) {
        SecondaryButton(stringResource(R.string.source_button), onClick = { open = !open })
    }
}

@Composable
private fun ColumnScope.S3Right(model: S3Model, history: List<GlucosePoint>) {
    val detail = model.glucoseDetail
    if (detail == null) {
        RightPaneBody {
            PaneTitle(stringResource(R.string.s2_rules_title))
            RuleRows(model.cautionAt, model.stopAt)
            Spacer(Modifier.height(CdDimens.Space24))
            TextLine(stringResource(R.string.s2_rule_reset, REST_RESET_MINUTES), CdTheme.type.bodyL, secondary = true)
        }
        return
    }
    RightPaneBody {
        PaneTitle(model.trendTitle.text())
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space24),
            verticalArrangement = Arrangement.spacedBy(CdDimens.Space8),
        ) {
            model.glucoseLine?.let { CdText(it.text(), CdTheme.type.titleM) }
            if (detail.showGraph) GlucoseGraph(history)
            model.simulated?.let { CdText(it.text(), CdTheme.type.bodyL, color = CdTheme.colors.text2) }
        }
        Hairline()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space24),
            verticalArrangement = Arrangement.spacedBy(CdDimens.Space8),
        ) {
            // 회복 타이머가 왼쪽 큰 숫자면 같은 값을 다시 말하지 않는다. 오른쪽 창은 창 2개일 때만 있다
            detail.recoveryLine?.takeUnless { detail.recoveryShownBig }?.let { CdText(it.text(), CdTheme.type.bodyL) }
            detail.receivedLine?.let { CdText(it.text(), CdTheme.type.bodyL, color = CdTheme.colors.text2) }
        }
    }
    GlucoseSource(detail.sourceAsButton)
    TabBarGap()
}

@Composable
fun S4Screen(model: S4Model, demoLabel: String?, onTab: (ParkedTab) -> Unit, modifier: Modifier = Modifier) {
    CdWindow(
        demoLabel = demoLabel,
        modifier = modifier.testTag(TAG_S4),
        right = {
            RightPaneBody {
                TitleHeader(stringResource(R.string.s4_title))
                S4Rows(model)
            }
        },
    ) {
        val twoPanes = LocalTwoPanes.current
        // 7일 묶음을 맨 위에 둔다. 장거리 운전이 드문 날에도 탭을 열면 쌓인 기록부터 보인다(business.md 4장)
        ParkedLeft(
            selected = ParkedTab.LOG,
            onTab = onTab,
            header = { TitleHeader(stringResource(R.string.s4_recent_title)) },
        ) {
            S4RecentBlock(model.recent)
            // 오늘 운행이 없으면 0 합계를 빼고 빈 상태 한 줄만 둔다. 7일 요약의 빈 상태와 같은 방식이다
            if (model.todayEmpty) {
                if (!twoPanes) {
                    TitleHeader(stringResource(R.string.s4_title))
                    S4Rows(model)
                }
            } else {
                S4Totals(model, twoPanes)
            }
        }
    }
}

@Composable
private fun S4Totals(model: S4Model, twoPanes: Boolean) {
    TitleHeader(stringResource(if (twoPanes) R.string.s4_total_title else R.string.s4_title))
    Spacer(Modifier.height(CdDimens.Space24))
    if (twoPanes) {
        TextLine(stringResource(R.string.s4_drive_total, model.driveTotal), CdTheme.type.headlineM)
        TextLine(stringResource(R.string.s4_rest_total, REST_RESET_MINUTES, model.restCount), CdTheme.type.headlineM)
        Spacer(Modifier.height(CdDimens.Space16))
    } else {
        TextLine(stringResource(R.string.s4_total_line, model.driveTotal, REST_RESET_MINUTES, model.restCount), CdTheme.type.titleM)
    }
    TextLine(stringResource(R.string.s4_advised_total, model.advisedCount), CdTheme.type.bodyL, secondary = true)
    if (model.conditionStops > 0) {
        TextLine(stringResource(R.string.s4_condition_total, model.conditionStops), CdTheme.type.bodyL, secondary = true)
    }
    model.distanceKm?.let { TextLine(stringResource(R.string.s4_distance, it), CdTheme.type.bodyL, secondary = true) }
    if (model.gapNote) TextLine(stringResource(R.string.s4_gap), CdTheme.type.bodyL, secondary = true)
    Spacer(Modifier.height(CdDimens.Space24))
    if (!twoPanes) {
        Hairline()
        S4Rows(model)
    }
}

/** 값 3줄만. 따르지 않은 안내 수는 보이지 않는다. 안내와 휴식을 나란히 두면 그 차이로 충분하고, 탓하는 숫자는 끄는 이유가 된다 */
@Composable
private fun S4RecentBlock(recent: S4Recent?) {
    if (recent == null) {
        Spacer(Modifier.height(CdDimens.Space24))
        TextLine(stringResource(R.string.s4_recent_empty), CdTheme.type.titleS, secondary = true)
        Spacer(Modifier.height(CdDimens.Space24))
        Hairline()
        return
    }
    // 횟수는 운행 수라 라벨이 "운행"이다. 휴식으로 갈린 운전 행이 여럿인 날에도 행 수와 다투지 않는다(U-24)
    InfoRow(stringResource(R.string.s4_recent_drive), stringResource(R.string.s4_recent_drive_value, recent.trips, recent.driveTotal))
    InfoRow(stringResource(R.string.s4_recent_advised), stringResource(R.string.s4_count, recent.advised))
    InfoRow(stringResource(R.string.s4_recent_rested, REST_RESET_MINUTES), stringResource(R.string.s4_count, recent.rested))
}

@Composable
private fun S4Rows(model: S4Model) {
    if (model.rows.isEmpty()) {
        Spacer(Modifier.height(CdDimens.Space32))
        TextLine(stringResource(R.string.s4_empty), CdTheme.type.titleS, secondary = true)
        return
    }
    model.rows.forEach { row -> S4RowLine(row.time, row.text.text(), row.note?.text()) }
}

@Composable
private fun S4RowLine(time: String, text: String, note: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CdDimens.ListRow)
                .padding(horizontal = LocalWindowPadding.current),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CdDimens.Space32),
        ) {
            CdText(time, CdTheme.type.titleS, color = CdTheme.colors.text3)
            // 짧은 멈춤은 운전 줄 뒤 보조 글로 둔다. 본문은 합친 운전 시간이다(U-23)
            CdText(text, CdTheme.type.titleM)
            // 좁은 폭에서는 보조 글만 줄을 바꾼다. 본문 시간이 밀려 잘리면 안 된다
            if (note != null) CdText(note, CdTheme.type.bodyL, Modifier.weight(1f, fill = false), color = CdTheme.colors.text2)
        }
        Hairline()
    }
}
