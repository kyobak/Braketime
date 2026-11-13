package com.conditiondrive.vehicle.ui.components.touch

import android.view.SoundEffectConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.Hairline
import com.conditiondrive.vehicle.ui.components.LocalWindowPadding
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

/*
 * 정차 화면 전용 터치 부품. S1은 이 패키지를 import하지 않는다(S1NoTouchSourceTest).
 * ripple을 쓰지 않고 눌림은 면 교체로만 보인다. 전환 효과가 없으니 100ms 안에 끝난다(8.4).
 */

private data class Press(val source: MutableInteractionSource, val pressed: Boolean)

@Composable
private fun rememberPress(): Press {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return Press(source, pressed)
}

@Composable
private fun Modifier.tap(press: Press, role: Role, onClick: () -> Unit): Modifier {
    val view = LocalView.current
    return clickable(interactionSource = press.source, indication = null, role = role) {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        onClick()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    val press = rememberPress()
    Box(
        modifier
            .heightIn(min = CdDimens.PrimaryButton)
            .background(if (press.pressed) c.surface2 else c.faceStrong)
            .tap(press, Role.Button, onClick)
            .padding(horizontal = CdDimens.Space48),
        contentAlignment = Alignment.Center,
    ) {
        CdText(text, CdTheme.type.titleM, color = if (press.pressed) c.text1 else c.onFaceStrong)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    val press = rememberPress()
    Box(
        modifier
            .heightIn(min = CdDimens.SecondaryButton)
            .background(if (press.pressed) c.surface2 else Color.Transparent)
            .border(CdDimens.Line, c.text3)
            .tap(press, Role.Button, onClick)
            .padding(horizontal = CdDimens.Space32),
        contentAlignment = Alignment.Center,
    ) {
        CdText(text, CdTheme.type.titleS)
    }
}

/** 지우기처럼 돌이킬 수 없는 동작. 정차 중이어도 실수 한 번으로 실행되지 않게 두 번 누르게 한다 */
@Composable
fun ConfirmButton(text: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(false) }
    SecondaryButton(
        text = if (armed) stringResource(R.string.confirm_again) else text,
        onClick = {
            if (armed) onConfirmed()
            armed = !armed
        },
        modifier = modifier,
    )
}

/** design-tokens 5장 탭. 면 없이 굵기와 2dp 밑줄로만 선택을 보인다. S2~S5 전용 */
@Composable
fun TabBar(selected: ParkedTab, onSelect: (ParkedTab) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .height(CdDimens.TabBar)
                .padding(horizontal = LocalWindowPadding.current),
            horizontalArrangement = Arrangement.spacedBy(CdDimens.Space24),
        ) {
            TabCell(R.string.tab_today, selected == ParkedTab.TODAY) { onSelect(ParkedTab.TODAY) }
            TabCell(R.string.tab_log, selected == ParkedTab.LOG) { onSelect(ParkedTab.LOG) }
            TabCell(R.string.tab_settings, selected == ParkedTab.SETTINGS) { onSelect(ParkedTab.SETTINGS) }
        }
    }
}

@Composable
private fun TabCell(label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = CdTheme.colors
    val press = rememberPress()
    val type = CdTheme.type.titleS
    Box(
        Modifier
            .sizeIn(minWidth = 112.dp)
            .height(CdDimens.TabBar)
            .background(if (press.pressed) c.surface2 else Color.Transparent)
            .drawBehind {
                if (!selected) return@drawBehind
                val line = CdDimens.TickLine.toPx()
                drawRect(c.select, Offset(0f, size.height - line), Size(size.width, line))
            }
            .tap(press, Role.Tab, onClick),
        contentAlignment = Alignment.Center,
    ) {
        CdText(
            stringResource(label),
            if (selected) type.copy(fontWeight = FontWeight.W600) else type,
            color = if (selected) c.text1 else c.text3,
        )
    }
}

/** 설정 목록 한 줄. 왼쪽 라벨, 오른쪽 값, 아래 hairline, `>`는 드릴다운 */
@Composable
fun NavRow(label: String, value: String?, selected: Boolean = false, onClick: () -> Unit) {
    val c = CdTheme.colors
    val press = rememberPress()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CdDimens.ListRow)
                .background(if (press.pressed || selected) c.surface2 else Color.Transparent)
                .tap(press, Role.Button, onClick)
                .padding(horizontal = LocalWindowPadding.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CdText(label, CdTheme.type.titleS, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (value != null) CdText(value, CdTheme.type.titleM, color = c.text2, maxLines = 1)
            Spacer(Modifier.width(CdDimens.Space16))
            Chevron(c.text3)
        }
        Hairline()
    }
}

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.35f, size.height * 0.15f)
            lineTo(size.width * 0.7f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height * 0.85f)
        }
        drawPath(path, color, style = Stroke(stroke, join = StrokeJoin.Miter))
    }
}

/** 직각 44dp, 터치 영역 76dp, 기본 해제 */
@Composable
fun CdCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = CdTheme.colors
    val press = rememberPress()
    Row(
        modifier
            .heightIn(min = CdDimens.MinTouch)
            .background(if (press.pressed) c.surface2 else Color.Transparent)
            .tap(press, Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CdDimens.Space16),
    ) {
        Box(
            Modifier
                .size(CdDimens.CheckBox)
                .border(CdDimens.TickLine, c.text1)
                .drawBehind {
                    if (!checked) return@drawBehind
                    val stroke = 4.dp.toPx()
                    drawLine(c.text1, Offset(size.width * 0.22f, size.height * 0.52f), Offset(size.width * 0.42f, size.height * 0.72f), stroke)
                    drawLine(c.text1, Offset(size.width * 0.42f, size.height * 0.72f), Offset(size.width * 0.8f, size.height * 0.28f), stroke)
                },
        )
        CdText(label, CdTheme.type.titleS)
    }
}
