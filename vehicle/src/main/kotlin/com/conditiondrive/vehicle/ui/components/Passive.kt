package com.conditiondrive.vehicle.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.conditiondrive.vehicle.ui.model.HorizonModel
import com.conditiondrive.vehicle.ui.model.StatusKind
import com.conditiondrive.vehicle.ui.model.UiFormat
import com.conditiondrive.vehicle.ui.model.UiText
import com.conditiondrive.vehicle.ui.model.badge
import com.conditiondrive.vehicle.ui.model.text
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

/*
 * 이 파일은 S1이 쓰는 표시 전용 부품이다. 터치, 포커스, 스크롤, 애니메이션을 넣지 않는다.
 * 터치 부품은 components.touch 패키지에 따로 두고 S1NoTouchSourceTest가 두 경계를 검사한다.
 */

/** 창 폭에서 정해지는 좌우 패딩. 패널 안쪽 부품이 같은 값을 쓰도록 내려 준다 */
val LocalWindowPadding = compositionLocalOf { CdDimens.Space48 }

@Composable
fun CdText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = CdTheme.colors.text1,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    // 한글 어절 줄바꿈은 TextStyle의 LineBreak와 ko-KR 로캘이 맡는다(design-tokens 3.3)
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign),
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(CdDimens.Line)
            .background(CdTheme.colors.hairline),
    )
}

/** design-tokens 2.3의 도형. 색 없이도 구분되도록 상태마다 모양이 다르다 */
@Composable
fun StatusGlyph(kind: StatusKind, color: Color, modifier: Modifier = Modifier, size: Dp = CdDimens.Glyph) {
    Canvas(modifier.size(size)) {
        val stroke = CdDimens.GlyphStroke.toPx()
        when (kind) {
            StatusKind.NORMAL -> drawRing(color, stroke)
            StatusKind.CAUTION -> drawWarningTriangle(color, stroke)
            StatusKind.STOP -> drawStopOctagon(color)
            StatusKind.RECOVER -> {
                drawRing(color, stroke)
                drawPauseBars(color, stroke)
            }
        }
    }
}

private fun DrawScope.drawRing(color: Color, stroke: Float) {
    drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
}

private fun DrawScope.drawPauseBars(color: Color, stroke: Float) {
    val barHeight = size.height * 0.34f
    val gap = size.width * 0.11f
    listOf(center.x - gap, center.x + gap).forEach { x ->
        drawLine(color, Offset(x, center.y - barHeight / 2), Offset(x, center.y + barHeight / 2), stroke * 0.8f)
    }
}

private fun DrawScope.drawWarningTriangle(color: Color, stroke: Float) {
    val inset = stroke / 2
    val triangle = Path().apply {
        moveTo(size.width / 2, inset + stroke * 0.4f)
        lineTo(size.width - inset, size.height - inset)
        lineTo(inset, size.height - inset)
        close()
    }
    drawPath(triangle, color, style = Stroke(stroke, join = StrokeJoin.Miter))
    val markTop = size.height * 0.40f
    val markBottom = size.height * 0.68f
    drawLine(color, Offset(center.x, markTop), Offset(center.x, markBottom), stroke * 0.8f, StrokeCap.Butt)
    val dot = stroke * 0.8f
    drawRect(color, Offset(center.x - dot / 2, size.height * 0.73f), Size(dot, dot))
}

/** 채운 정팔각형만 그린다. 가운데 막대를 넣으면 작은 크기에서 진입 금지 표지로 읽힌다. 권고만 하는 앱이다 */
private fun DrawScope.drawStopOctagon(color: Color) {
    val w = size.width
    val h = size.height
    val cut = w * 0.29f   // 1/(2+√2) = 0.293, 정팔각형
    val octagon = Path().apply {
        moveTo(cut, 0f)
        lineTo(w - cut, 0f)
        lineTo(w, cut)
        lineTo(w, h - cut)
        lineTo(w - cut, h)
        lineTo(cut, h)
        lineTo(0f, h - cut)
        lineTo(0f, cut)
        close()
    }
    drawPath(octagon, color)
}

/**
 * 8.3 상단 배지. NORMAL과 RECOVER는 면이 없고, CAUTION과 STOP은 창 너비 띠다.
 * 높이를 같게 둬 상태가 바뀌어도 아래 내용이 움직이지 않는다.
 */
@Composable
fun StatusHeader(kind: StatusKind, modifier: Modifier = Modifier, trailing: String? = null) {
    StatusBand(kind = kind, label = stringResource(kind.badge()), textStyle = CdTheme.type.keyLabel, modifier = modifier, trailing = trailing)
}

@Composable
fun StatusBand(kind: StatusKind, label: String, textStyle: TextStyle, modifier: Modifier = Modifier, trailing: String? = null) {
    val c = CdTheme.colors
    val (face, content, outline) = when (kind) {
        StatusKind.NORMAL -> Triple(Color.Transparent, c.text1, null)
        StatusKind.RECOVER -> Triple(Color.Transparent, c.recover, null)
        StatusKind.CAUTION -> Triple(c.cautionFace, c.onCaution, c.cautionOutline)
        StatusKind.STOP -> Triple(c.stopFace, c.onStop, null)
    }
    val outlined = if (outline != null) Modifier.border(CdDimens.TickLine, outline) else Modifier
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CdDimens.StatusHeader)
                .background(face)
                .then(outlined)
                .padding(horizontal = LocalWindowPadding.current),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CdDimens.Space16),
        ) {
            StatusGlyph(kind, color = content)
            CdText(label, textStyle, color = content, maxLines = 1)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                CdText(trailing, CdTheme.type.titleS, color = content, maxLines = 1)
            }
        }
        Hairline()
    }
}

/** 파일 제목 머리. 상태 머리와 같은 높이라 탭을 바꿔도 본문 시작선이 그대로다 */
@Composable
fun TitleHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CdDimens.StatusHeader)
                .padding(horizontal = LocalWindowPadding.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CdText(title, CdTheme.type.titleL, maxLines = 1)
        }
        Hairline()
    }
}

/** 숫자는 display, 단위는 headlineM. 숫자가 없는 "곧"은 그 자체가 값이라 display다 */
@Composable
fun BigTime(text: String, modifier: Modifier = Modifier) {
    val type = CdTheme.type
    val unit = SpanStyle(fontSize = type.headlineM.fontSize, fontWeight = type.headlineM.fontWeight)
    val parts = UiFormat.timeParts(text)
    val hasNumber = parts.any { it.isNumber }
    BasicText(
        text = buildAnnotatedString {
            parts.forEach { part ->
                if (part.isNumber || !hasNumber) append(part.text) else withStyle(unit) { append(part.text) }
            }
        },
        modifier = modifier,
        style = type.display.copy(color = CdTheme.colors.text1),
        maxLines = 1,
    )
}

@Composable
fun BigValueSentence(text: UiText, modifier: Modifier = Modifier) {
    CdText(text.text(), CdTheme.type.headlineM, modifier)
}

/** design-tokens 5장 Horizon. 선 위에 지금 표시, 아래에 눈금 라벨. 위치는 값이 바뀔 때 즉시 교체된다 */
@Composable
fun Horizon(model: HorizonModel, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    Column(modifier.fillMaxWidth()) {
        AnchoredLabels(listOf(model.nowFraction to model.nowLabel.text()), c.text1, endAlignTicks = false)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CdDimens.Space24),
        ) {
            val line = CdDimens.TickLine.toPx()
            val axisY = size.height / 2
            drawLine(c.track, Offset(0f, axisY), Offset(size.width, axisY), line)
            model.ticks.forEach { tick ->
                val x = tick.fraction * size.width
                val half = size.height * 0.25f
                drawLine(c.track, Offset(x.keepInside(line, size.width), axisY - half), Offset(x.keepInside(line, size.width), axisY + half), line)
            }
            val nowX = (model.nowFraction * size.width).keepInside(line, size.width)
            drawLine(c.text1, Offset(nowX, 0f), Offset(nowX, size.height), line)
        }
        AnchoredLabels(model.ticks.map { it.fraction to it.label }, c.text3, endAlignTicks = true)
    }
}

private fun Float.keepInside(line: Float, width: Float) = coerceIn(line / 2, width - line / 2)

@Composable
private fun AnchoredLabels(items: List<Pair<Float, String>>, color: Color, endAlignTicks: Boolean) {
    Layout(
        content = { items.forEach { (_, label) -> CdText(label, CdTheme.type.bodyL, color = color, maxLines = 1) } },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, placeable ->
                val anchor = (items[i].first * width).toInt()
                // 1:45와 2:00은 좁은 창에서 13%만 떨어져 있다. 눈금 왼쪽으로 붙여야 겹치지 않는다
                val x = if (endAlignTicks) anchor - placeable.width else anchor - placeable.width / 2
                placeable.place(x.coerceIn(0, (width - placeable.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

/** 15.2. 혈당 시뮬레이션 표시는 값에서 파생한다. 런타임 표지 문구에 이미 있으면 겹치지 않게 한다 */
fun demoBannerText(label: String, simulated: Boolean, simulatedNote: String): String =
    if (simulated && simulatedNote !in label) "$label · $simulatedNote" else label

/**
 * 표지와 자막을 한 문자열로 화면까지 내린다. 화면마다 시그니처를 늘리지 않으려는 것이다.
 * 표지 문구에는 줄바꿈이 없고, 자막은 여러 줄일 수 있어 첫 줄바꿈에서만 나눈다.
 */
fun demoMark(label: String, caption: String?): String = if (caption.isNullOrEmpty()) label else "$label\n$caption"

fun splitDemoMark(mark: String): Pair<String, String?> {
    val at = mark.indexOf('\n')
    return if (at < 0) mark to null else mark.substring(0, at) to mark.substring(at + 1).ifEmpty { null }
}

// "앱 음성:", "시나리오 응답:"처럼 누가 말했는지 알리는 머리말. 문장 속 콜론을 머리말로 잡지 않게 길이를 제한한다
private val CaptionSpeaker = Regex("""^([^:\n]{1,10}):""", RegexOption.MULTILINE)

/** 15.2 시연 표지. 표지 문구는 bodyL, 자막은 한 단계 낮춘 색과 굵은 머리말로 구분한다(R10) */
@Composable
fun DemoBanner(label: String, caption: String?, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = CdDimens.DemoBanner)
            .background(c.faceStrong)
            .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space8),
        verticalArrangement = Arrangement.spacedBy(CdDimens.Space8, Alignment.CenterVertically),
    ) {
        CdText(label, CdTheme.type.bodyL, color = c.onFaceStrong)
        if (caption != null) {
            val speaker = SpanStyle(fontWeight = FontWeight.W600)
            BasicText(
                text = buildAnnotatedString {
                    append(caption)
                    CaptionSpeaker.findAll(caption).forEach { addStyle(speaker, it.range.first, it.range.last) }
                },
                style = CdTheme.type.bodyL.copy(color = c.onFaceCaption),
            )
        }
    }
}
