package com.conditiondrive.vehicle.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

const val TAG_RIGHT_PANE = "RightPane"

/** 화면이 창 개수에 따라 줄을 옮길 때 CdWindow와 같은 판단을 쓰게 한다 */
val LocalTwoPanes = compositionLocalOf { false }

/**
 * 8.1 창 분기. Portrait 창 1개, 폭 1100dp 이상이면 창 2개이고 왼쪽 내용은 그대로다.
 * 표지를 여기 넣어 어떤 화면도 표지를 빠뜨릴 수 없게 한다(R4).
 * keepEmptyRight=false면 오른쪽에 둘 내용이 없을 때 창을 하나(최대 840dp)만 그린다. 온보딩 전용이다.
 */
@Composable
fun CdWindow(
    demoLabel: String?,
    modifier: Modifier = Modifier,
    keepEmptyRight: Boolean = true,
    right: (@Composable ColumnScope.() -> Unit)? = null,
    left: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(CdTheme.colors.bg),
    ) {
        val wide = maxWidth >= CdDimens.TwoPaneMinWidth
        val singleCapped = wide && right == null && !keepEmptyRight
        val twoPanes = wide && !singleCapped
        val paneWidth = if (singleCapped) CdDimens.PortraitPane else maxWidth
        CompositionLocalProvider(
            LocalWindowPadding provides CdDimens.windowPadding(paneWidth),
            LocalTwoPanes provides twoPanes,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (demoLabel != null) {
                    val (label, caption) = splitDemoMark(demoLabel)
                    DemoBanner(label, caption)
                    // 표지와 바로 아래 상태 띠가 붙으면 무거운 면 두 개가 한 덩어리로 보인다
                    Spacer(Modifier.height(CdDimens.Space8))
                }
                Row(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .then(if (singleCapped) Modifier.widthIn(max = CdDimens.PortraitPane).fillMaxWidth() else Modifier.weight(1f))
                            .fillMaxHeight(),
                        content = left,
                    )
                    if (twoPanes) {
                        Box(
                            Modifier
                                .width(CdDimens.Line)
                                .fillMaxHeight()
                                .background(CdTheme.colors.hairline),
                        )
                        // 제한으로 내용이 비어도 창은 남긴다. 왼쪽 위치가 변하면 안 된다(6장)
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(CdTheme.colors.surface1)
                                .testTag(TAG_RIGHT_PANE),
                            content = right ?: {},
                        )
                    }
                }
            }
        }
    }
}
