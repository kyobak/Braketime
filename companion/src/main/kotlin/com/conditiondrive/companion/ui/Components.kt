package com.conditiondrive.companion.ui

import android.view.SoundEffectConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// design-tokens.md 5장. 모서리 0, 그림자 0, ripple 없음. 눌림은 surface2 면으로만 표시한다

@Composable
fun CdText(
    text: String,
    style: TextStyle = CdTheme.type.bodyM,
    color: Color = CdTheme.colors.text1,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    BasicText(text = text, modifier = modifier, style = if (textAlign == null) style.copy(color = color) else style.copy(color = color, textAlign = textAlign))
}

/** 한 문장 안 일부만 굵게(동의 화면의 보관 기간 같은 핵심 값) */
@Composable
fun CdText(
    text: AnnotatedString,
    style: TextStyle = CdTheme.type.bodyM,
    color: Color = CdTheme.colors.text1,
    modifier: Modifier = Modifier,
) {
    BasicText(text = text, modifier = modifier, style = style.copy(color = color))
}

/** [words]가 처음 나오는 자리를 W600으로. 문장은 그대로 두고 굵기만 바꾼다 */
fun emphasize(text: String, words: List<String>): AnnotatedString = buildAnnotatedString {
    append(text)
    words.forEach { word ->
        val start = text.indexOf(word)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.W600), start, start + word.length)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(CdSize.hairline).background(CdTheme.colors.hairline))
}

@Composable
private fun pressable(
    role: Role,
    onClick: () -> Unit,
): Pair<Modifier, Boolean> {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val view = LocalView.current
    val modifier = Modifier.clickable(interactionSource = source, indication = null, role = role) {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        onClick()
    }
    return modifier to pressed
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val (press, pressed) = pressable(Role.Button, onClick)
    val colors = CdTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(CdSize.primaryButton)
            .background(if (pressed) colors.surface2 else colors.faceStrong)
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        CdText(text, CdTheme.type.titleM, if (pressed) colors.text1 else colors.onFaceStrong)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    large: Boolean = false,
) {
    val (press, pressed) = pressable(Role.Button, onClick)
    val colors = CdTheme.colors
    val base = if (large) CdTheme.type.titleM else CdTheme.type.bodyM
    Box(
        modifier
            .heightIn(min = if (large) CdSize.primaryButton else CdSize.secondaryButton)
            .widthIn(min = CdSize.touchTarget)
            .background(if (pressed) colors.surface2 else colors.bg)
            .border(CdSize.hairline, colors.text3)
            // 선택 표시는 주황 밑줄 하나뿐이다(1.1). 위에 덧그려 칩 높이와 글자 기준선이 선택과 무관하게 같다
            .drawBehind {
                if (selected) {
                    val line = CdSize.selectLine.toPx()
                    val width = 24.dp.toPx()
                    val y = size.height - 4.dp.toPx() - line
                    drawRect(colors.select, Offset((size.width - width) / 2, y), Size(width, line))
                }
            }
            .then(press)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 색만으로 구분하지 않게 굵기도 함께 바꾼다
        CdText(text, if (selected) base.copy(fontWeight = FontWeight.W600) else base, textAlign = TextAlign.Center)
    }
}

/**
 * 정보 행. 값이 없을 때([present] false)는 값 자리의 문장을 bodyL text2로 낮춰, 값이 있는 행과 한눈에 갈린다.
 * [extra]는 행 안(설명 아래)에 두는 보조 버튼 자리다. 구분선에 버튼 테두리가 붙지 않는다
 */
@Composable
fun CdRow(
    label: String,
    value: String? = null,
    note: String? = null,
    present: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = CdTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(min = CdSize.row).padding(vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            CdText(label, CdTheme.type.bodyM, colors.text3)
            if (value != null) {
                Spacer(Modifier.height(4.dp))
                if (present) CdText(value, CdTheme.type.titleM) else CdText(value, CdTheme.type.bodyL, colors.text2)
            }
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                CdText(note, CdTheme.type.bodyS, colors.text2)
            }
            if (extra != null) {
                Spacer(Modifier.height(12.dp))
                extra()
            }
        }
        Hairline()
    }
}

/** 다른 화면으로 넘어가는 행. ">"는 이 행에만 붙는다 */
@Composable
fun NavRow(label: String, onClick: () -> Unit) {
    val (press, pressed) = pressable(Role.Button, onClick)
    val colors = CdTheme.colors
    Column(Modifier.fillMaxWidth().background(if (pressed) colors.surface2 else Color.Transparent).then(press)) {
        Row(Modifier.heightIn(min = CdSize.row).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CdText(label, CdTheme.type.titleS, modifier = Modifier.weight(1f))
            CdText(">", CdTheme.type.titleS, colors.text2)
        }
        Hairline()
    }
}

/**
 * 그 자리에서 실행되는 행(되돌릴 수 없는 삭제). 이름은 text1로 써서 꺼진 항목처럼 보이지 않게 한다.
 * [armed]일 때 면을 뒤집지 않는다: 시연 표지(faceStrong 막대)와 같은 모양이 되면 표지와 확인 대기가 섞인다
 */
@Composable
fun ActionRow(label: String, note: String, armed: Boolean, confirm: String, onClick: () -> Unit) {
    val (press, pressed) = pressable(Role.Button, onClick)
    val colors = CdTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) colors.surface2 else Color.Transparent)
            // 테두리는 좌우 여백 쪽으로 넘겨 그린다. 글자 위치가 armed 여부로 움직이지 않는다.
            // 위 행의 hairline 자리까지 올려 그려 테두리가 위아래 선을 대신한다. 선 두 개가 붙어 보이지 않는다
            .drawBehind {
                if (armed) {
                    val stroke = 2.dp.toPx()
                    val out = 12.dp.toPx()
                    val above = CdSize.hairline.toPx()
                    drawRect(
                        colors.text1,
                        Offset(-out + stroke / 2, -above + stroke / 2),
                        Size(size.width + out * 2 - stroke, size.height + above - stroke),
                        style = Stroke(stroke),
                    )
                }
            }
            .then(press),
    ) {
        Column(Modifier.heightIn(min = CdSize.row).padding(vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            CdText(label, CdTheme.type.titleS)
            Spacer(Modifier.height(4.dp))
            // 확인 문장(bodyM)이 설명(bodyS)보다 한 줄이 높다. 칸 높이를 맞춰 아래 행이 밀리지 않게 한다
            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                if (armed) {
                    CdText(confirm, CdTheme.type.bodyM.copy(fontWeight = FontWeight.W600))
                } else {
                    CdText(note, CdTheme.type.bodyS, colors.text2)
                }
            }
        }
        if (!armed) Hairline() else Spacer(Modifier.height(CdSize.hairline))
    }
}

@Composable
fun CdCheckbox(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val (press, pressed) = pressable(Role.Checkbox) { onChange(!checked) }
    val colors = CdTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = CdSize.touchTarget)
            .background(if (pressed) colors.surface2 else Color.Transparent)
            .then(press)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(CdSize.checkbox)
                .border(2.dp, colors.text1)
                .background(if (checked) colors.faceStrong else Color.Transparent)
                .drawBehind {
                    if (checked) {
                        val stroke = 2.dp.toPx()
                        val mark = colors.onFaceStrong
                        drawLine(mark, Offset(size.width * .22f, size.height * .52f), Offset(size.width * .42f, size.height * .72f), stroke)
                        drawLine(mark, Offset(size.width * .42f, size.height * .72f), Offset(size.width * .78f, size.height * .3f), stroke)
                    }
                },
        )
        Spacer(Modifier.width(16.dp))
        CdText(label, CdTheme.type.bodyL.copy(fontWeight = FontWeight.W500), modifier = Modifier.weight(1f))
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    CdText(text, CdTheme.type.titleL, modifier = modifier.semantics { heading() })
}

@Composable
fun SectionLabel(text: String) {
    CdText(text, CdTheme.type.titleS, CdTheme.colors.text3, Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = CdTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.bg)) {
        Hairline()
        Row(Modifier.fillMaxWidth().height(CdSize.tabBar)) {
            labels.forEachIndexed { index, label -> TabItem(label, index == selected) { onSelect(index) } }
        }
    }
}

@Composable
private fun RowScope.TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val (press, pressed) = pressable(Role.Tab, onClick)
    val colors = CdTheme.colors
    Box(
        Modifier.weight(1f).fillMaxHeight().background(if (pressed) colors.surface2 else colors.bg).then(press),
        contentAlignment = Alignment.Center,
    ) {
        CdText(label, if (selected) CdTheme.type.bodyM.copy(fontWeight = FontWeight.W600) else CdTheme.type.bodyM)
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CdSize.selectLine).background(colors.select))
        }
    }
}

/** 시연 표지와 같은 면. 터치가 없고 상태색을 쓰지 않는다 */
@Composable
fun SimulationMarker(text: String) {
    val colors = CdTheme.colors
    // "혈당 보내지 않음" 문구는 글자 150%에서 두 줄이 된다. 높이를 고정하면 둘째 줄이 잘려 최소 높이로 두고 늘어나게 한다
    Box(
        Modifier.fillMaxWidth().heightIn(min = CdSize.marker).background(colors.faceStrong).padding(horizontal = CdSize.gutter, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CdText(text, CdTheme.type.bodyM, colors.onFaceStrong, textAlign = TextAlign.Center)
    }
}
