package com.conditiondrive.vehicle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.model.GlucosePoint
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

const val TAG_GLUCOSE_GRAPH = "GlucoseGraph"

/**
 * 정차 화면의 값 한 줄. 왼쪽 라벨, 오른쪽 값, 아래 보조 줄, 맨 아래 hairline.
 * valueSubs는 값을 설명하는 줄(추세)이라 값 아래 오른쪽에 붙인다. subs는 출처 주석이나 규칙 설명이라 왼쪽이다.
 * 둘이 함께 있으면 같은 줄의 왼쪽과 오른쪽을 쓴다.
 */
@Composable
fun InfoRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    subs: List<String> = emptyList(),
    valueSubs: List<String> = emptyList(),
    valueStyle: TextStyle = CdTheme.type.titleM,
) {
    val c = CdTheme.colors
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CdDimens.ListRow)
                .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space16),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                CdText(label, CdTheme.type.titleS, color = c.text2, maxLines = 1)
                if (value != null) {
                    Spacer(Modifier.width(CdDimens.Space24))
                    // 긴 값은 라벨을 밀지 않고 오른쪽 칸 안에서 줄을 바꾼다
                    CdText(value, valueStyle, Modifier.weight(1f, fill = false), textAlign = TextAlign.End)
                }
            }
            // 왼쪽 보조문과 오른쪽 설명 줄은 같은 줄을 나눠 쓴다. 따로 쌓으면 한쪽이 빈 줄이 생겨 행이 길어지고 정렬이 지그재그가 된다
            for (i in 0 until maxOf(subs.size, valueSubs.size)) {
                Row(Modifier.fillMaxWidth()) {
                    val left = subs.getOrNull(i)
                    if (left != null) CdText(left, CdTheme.type.bodyL, Modifier.weight(1f), color = c.text2) else Spacer(Modifier.weight(1f))
                    valueSubs.getOrNull(i)?.let {
                        Spacer(Modifier.width(CdDimens.Space24))
                        CdText(it, CdTheme.type.bodyL, color = c.text2, textAlign = TextAlign.End)
                    }
                }
            }
        }
        Hairline()
    }
}

/** 출처 한 줄. 정차 화면 하단 고정 */
@Composable
fun SourceLine(text: String, modifier: Modifier = Modifier) {
    CdText(
        text,
        CdTheme.type.bodyS,
        modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space8),
        color = CdTheme.colors.text3,
    )
}

/**
 * 혈당 그래프. Canvas 정지 이미지이고 값이 바뀔 때만 다시 그린다.
 * 기준선 값은 판단과 같은 Policy에서 읽어 그래프와 판단이 어긋나지 않는다.
 */
@Composable
fun GlucoseGraph(points: List<GlucosePoint>, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    val policy = Policy.DEFAULT
    val measurer = rememberTextMeasurer()
    val labelStyle = CdTheme.type.bodyL.copy(color = c.text3)
    // 수치 줄과 같은 변환(Formats.mgdl)을 써야 선과 수치가 같은 단위, 같은 반올림으로 읽힌다
    val recoverLabel = stringResource(R.string.graph_recover_line, Formats.mgdl(policy.GLUCOSE_RECOVER_AT))
    val stopLabel = stringResource(R.string.graph_stop_line, Formats.mgdl(policy.GLUCOSE_STOP_BELOW))
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .testTag(TAG_GLUCOSE_GRAPH),
        ) {
            val top = maxOf(points.maxOfOrNull { it.mmol } ?: 0.0, policy.GLUCOSE_RECOVER_AT + 3.0)
            val bottom = minOf(points.minOfOrNull { it.mmol } ?: 99.0, policy.GLUCOSE_STOP_BELOW - 1.0)

            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
            val line = CdDimens.TickLine.toPx()
            val recoverMeasured = measurer.measure(recoverLabel, labelStyle)
            val stopMeasured = measurer.measure(stopLabel, labelStyle)
            val labelHeight = maxOf(recoverMeasured.size.height, stopMeasured.size.height).toFloat()
            // 범위가 넓으면 두 기준선이 붙어 라벨이 겹친다. 그때는 정차 권고 라벨을 선 아래로 내리고 그 자리만큼 아래를 비운다
            val stopBelow = glucoseStopLabelBelow(top, bottom, size.height, labelHeight)
            val plotHeight = if (stopBelow) size.height - labelHeight else size.height
            val y = { value: Double -> ((top - value) / (top - bottom) * plotHeight).toFloat() }
            // 곡선이 라벨을 지나가지 않게 오른쪽에 라벨 칸을 비워 둔다
            val gutter = (maxOf(recoverMeasured.size.width, stopMeasured.size.width) + CdDimens.Space16.toPx()).coerceAtMost(size.width / 2)
            val plotWidth = size.width - gutter
            listOf(
                Triple(policy.GLUCOSE_RECOVER_AT, recoverMeasured, false),
                Triple(policy.GLUCOSE_STOP_BELOW, stopMeasured, stopBelow),
            ).forEach { (value, measured, below) ->
                val lineY = y(value)
                drawLine(c.track, Offset(0f, lineY), Offset(size.width, lineY), line, pathEffect = dash)
                val labelY = if (below) lineY else (lineY - measured.size.height).coerceAtLeast(0f)
                drawText(measured, topLeft = Offset(size.width - measured.size.width, labelY))
            }

            if (points.isEmpty()) return@Canvas
            val startMs = points.first().atMs
            val spanMs = maxOf(points.last().atMs - startMs, GRAPH_MIN_SPAN_MS)
            val x = { atMs: Long -> ((atMs - startMs).toFloat() / spanMs) * plotWidth }
            if (points.size > 1) {
                val path = Path().apply {
                    points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.atMs), y(p.mmol)) else lineTo(x(p.atMs), y(p.mmol)) }
                }
                drawPath(path, c.text1, style = Stroke(line))
            }
            // 지금 값 자리. 점이 하나일 때와 같은 정지 도형이고 움직이지 않는다
            val last = points.last()
            val side = 8.dp.toPx()
            drawRect(c.text1, Offset(x(last.atMs) - side / 2, y(last.mmol) - side / 2), Size(side, side))
        }
        // 곡선이 몇 분 동안의 변화인지 알려 준다. 축 눈금 대신 한 줄이다
        if (points.isNotEmpty()) {
            CdText(stringResource(R.string.graph_range, graphSpanMinutes(points)), CdTheme.type.bodyS, color = c.text3)
        }
    }
}

/**
 * 회복 기준선과 정차 권고 기준선의 간격이 라벨 한 줄보다 좁으면 true. 두 라벨을 모두 선 위에 그리면 세로로 겹친다.
 * 아래로 내린 라벨 자리만큼 곡선 높이가 줄어도 간격은 더 좁아질 뿐이라 한 번만 판단하면 된다.
 */
fun glucoseStopLabelBelow(top: Double, bottom: Double, heightPx: Float, labelHeightPx: Float): Boolean {
    val policy = Policy.DEFAULT
    val gap = (policy.GLUCOSE_RECOVER_AT - policy.GLUCOSE_STOP_BELOW) / (top - bottom) * heightPx
    return gap < labelHeightPx
}

/** 그래프 가로 범위(분). 그리는 폭과 같은 값이어야 한다 */
fun graphSpanMinutes(points: List<GlucosePoint>): Int =
    if (points.isEmpty()) 0 else (maxOf(points.last().atMs - points.first().atMs, GRAPH_MIN_SPAN_MS) / 60_000).toInt()

private const val GRAPH_MIN_SPAN_MS = 30 * 60_000L

/** S5 연결 QR. 코드만 담고 서버 URL은 넣지 않는다(12.4) */
@Composable
fun PairingQr(payload: String, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    val matrix = remember(payload) { encodeQr(payload) }
    Canvas(modifier.size(CdDimens.Qr)) {
        drawRect(c.qrLight)
        val cell = size.width / matrix.width
        for (row in 0 until matrix.height) {
            for (col in 0 until matrix.width) {
                if (matrix.get(col, row)) drawRect(c.qrDark, Offset(col * cell, row * cell), Size(cell, cell))
            }
        }
    }
}

/** 조용한 영역이 있어야 카메라가 읽으므로 여백은 라이브러리 기본 4칸을 쓴다 */
fun encodeQr(payload: String): BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0)
