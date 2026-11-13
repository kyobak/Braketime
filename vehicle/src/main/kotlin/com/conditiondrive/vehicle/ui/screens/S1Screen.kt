package com.conditiondrive.vehicle.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.components.BigTime
import com.conditiondrive.vehicle.ui.components.BigValueSentence
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.CdWindow
import com.conditiondrive.vehicle.ui.components.Hairline
import com.conditiondrive.vehicle.ui.components.Horizon
import com.conditiondrive.vehicle.ui.components.LocalTwoPanes
import com.conditiondrive.vehicle.ui.components.LocalWindowPadding
import com.conditiondrive.vehicle.ui.components.StatusHeader
import com.conditiondrive.vehicle.ui.model.BigValue
import com.conditiondrive.vehicle.ui.model.S1Model
import com.conditiondrive.vehicle.ui.model.S1Side
import com.conditiondrive.vehicle.ui.model.StatusKind
import com.conditiondrive.vehicle.ui.model.text
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

const val TAG_S1 = "S1"
const val TAG_S1_BODY = "S1Body"

/**
 * 8.2 주행 화면. 입력은 S1Model뿐이고 콜백을 받지 않는다.
 * 터치를 넣으려면 시그니처부터 바꿔야 하므로 구조상 주행 중 조작이 생길 수 없다(0장 규칙 2).
 */
@Composable
fun S1Screen(
    model: S1Model,
    demoLabel: String?,
    modifier: Modifier = Modifier,
    demoPanel: (@Composable () -> Unit)? = null,
) {
    CdWindow(
        demoLabel = demoLabel,
        modifier = modifier.testTag(TAG_S1),
        right = model.side?.let { side -> { S1Right(side, demoPanel) } },
    ) {
        S1Left(model)
    }
}

@Composable
private fun ColumnScope.S1Left(model: S1Model) {
    // 듣는 중 표시는 머리 오른쪽이다. 라벨과 시간이 움직이지 않는다
    StatusHeader(model.status, trailing = if (model.listening) stringResource(R.string.s1_listening) else null)
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current)
            .padding(top = CdDimens.Space48)
            .testTag(TAG_S1_BODY),
    ) {
        // 정차 권고의 라벨은 "안전한 곳에 차를 세워 주세요"처럼 실제 행동이다. 큰 문장보다 약하게 보이면 안 된다
        val labelColor = if (model.status == StatusKind.STOP) CdTheme.colors.text1 else CdTheme.colors.text2
        CdText(model.label.text(), CdTheme.type.titleS, color = labelColor)
        Spacer(Modifier.height(CdDimens.Space16))
        when (val big = model.big) {
            is BigValue.Time -> BigTime(big.text)
            is BigValue.Sentence -> BigValueSentence(big.text)
        }
        // 오른쪽 창에 같은 장소와 거리가 있으면 두 번 보이지 않는다. 다른 곳인지 비교하느라 시선을 더 쓴다
        model.placeLine?.takeUnless { LocalTwoPanes.current && model.side?.placeName != null }?.let { line ->
            Spacer(Modifier.height(CdDimens.Space32))
            CdText(line.text(), CdTheme.type.headlineM, maxLines = 1)
        }
        // 주석은 맨 뒤다. 라벨과 큰 값 사이, 큰 값과 장소 사이에 끼면 시선이 끊긴다(6장 상태, 라벨, 큰 값, 보조 순서)
        if (model.notes.isNotEmpty()) Spacer(Modifier.height(CdDimens.Space16))
        model.notes.forEach { note ->
            CdText(note.text(), CdTheme.type.bodyS, color = CdTheme.colors.text3)
        }
    }
}

@Composable
private fun ColumnScope.S1Right(side: S1Side, demoPanel: (@Composable () -> Unit)?) {
    val c = CdTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = LocalWindowPadding.current)
            .padding(top = CdDimens.Space48, bottom = CdDimens.Space48),
    ) {
        if (side.placeName != null && side.placeDistance != null) {
            CdText(stringResource(R.string.s1_next_place), CdTheme.type.titleS, color = c.text2)
            CdText(side.placeName, CdTheme.type.titleL, maxLines = 1)
            CdText(side.placeDistance.text(), CdTheme.type.headlineM, maxLines = 1)
            Spacer(Modifier.height(CdDimens.Space24))
            Hairline()
            Spacer(Modifier.height(CdDimens.Space24))
        }
        side.routeLine?.let { CdText(it.text(), CdTheme.type.bodyL, color = c.text2) }
        // 시연 전용 자리. 실사용 호출부는 넘기지 않는다. 표시 전용 부품만 온다
        demoPanel?.invoke()
        Spacer(Modifier.weight(1f))
        CdText(stringResource(R.string.s1_horizon_title), CdTheme.type.bodyL, color = c.text2)
        Horizon(side.horizon)
    }
}
