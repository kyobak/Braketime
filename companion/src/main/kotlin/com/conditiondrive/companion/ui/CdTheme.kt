package com.conditiondrive.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.conditiondrive.companion.R

/** design-tokens.md 2장. 차량 앱과 같은 값이다. 폰 앱은 :vehicle을 참조할 수 없어 같은 규칙을 따로 옮겼다 */
@Immutable
data class CdColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val hairline: Color,
    val faceStrong: Color,
    val onFaceStrong: Color,
    val select: Color,
)

private val DayColors = CdColors(
    bg = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF6F8FC),
    surface2 = Color(0xFFF0F0F0),
    text1 = Color(0xFF131417),
    text2 = Color(0xFF44464E),
    text3 = Color(0xFF5E6068),
    hairline = Color(0xFFBDBDBD),
    faceStrong = Color(0xFF000000),
    onFaceStrong = Color(0xFFFFFFFF),
    select = Color(0xFFFF9900),
)

private val NightColors = CdColors(
    bg = Color(0xFF131417),
    surface1 = Color(0xFF1D1E21),
    surface2 = Color(0xFF2A2B2F),
    text1 = Color(0xFFEDEEF2),
    text2 = Color(0xFFC7C9CE),
    text3 = Color(0xFFA4A8AE),
    hairline = Color(0xFF44464E),
    faceStrong = Color(0xFFEDEEF2),
    onFaceStrong = Color(0xFF131417),
    select = Color(0xFFFF9900),
)

/** design-tokens.md 7장 폰 스케일. 팔 길이 거리에서 보는 화면이라 차량의 물리 크기 기준을 쓰지 않는다 */
@Immutable
data class CdTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
)

@Immutable
object CdSize {
    val gutter: Dp = 24.dp
    val primaryButton: Dp = 56.dp
    val secondaryButton: Dp = 48.dp
    val touchTarget: Dp = 48.dp
    val row: Dp = 56.dp
    val tabBar: Dp = 56.dp
    val checkbox: Dp = 24.dp
    val marker: Dp = 40.dp
    val hairline: Dp = 1.dp
    val selectLine: Dp = 2.dp
    val maxContent: Dp = 600.dp     // 가로·태블릿에서 본문 한 줄이 너무 길어지지 않게
}

// 원본 가변 TTF 그대로(OFL 예약 이름 Pretendard라 서브셋하면 이름을 바꿔야 한다). wght 축 범위는 design-tokens.md 7장
@OptIn(ExperimentalTextApi::class)
private val Pretendard = FontFamily(
    Font(R.font.pretendard, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.pretendard, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.pretendard, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

// 기기 로케일이 en-US여도 한국어 규칙으로 줄을 나누게 고정한다
private val Korean = LocaleList("ko-KR")

/** 제목은 두 줄 길이를 맞추고, 본문은 품질 우선. 둘 다 어절 단위(Phrase)로 나눈다 */
private val HeadingBreak = LineBreak.Heading
private val BodyBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

private fun style(size: TextUnit, weight: FontWeight, lineHeight: Float, tracking: Float = 0f, lineBreak: LineBreak = BodyBreak) = TextStyle(
    fontFamily = Pretendard,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight.em,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum",
    lineBreak = lineBreak,
    localeList = Korean,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

// Pretendard의 라틴·숫자는 큰 크기에서 넓어 보여 큰 글자만 좁힌다. 본문은 읽기 쉽게 0
private val Typography = CdTypography(
    display = style(64.sp, FontWeight.W400, 1.1f, tracking = -0.02f, lineBreak = HeadingBreak),
    headline = style(30.sp, FontWeight.W500, 1.2f, tracking = -0.01f, lineBreak = HeadingBreak),
    titleL = style(26.sp, FontWeight.W500, 1.3f, tracking = -0.01f, lineBreak = HeadingBreak),
    titleM = style(20.sp, FontWeight.W500, 1.3f, lineBreak = HeadingBreak),
    titleS = style(18.sp, FontWeight.W400, 1.4f),
    bodyL = style(17.sp, FontWeight.W400, 1.5f),
    bodyM = style(16.sp, FontWeight.W400, 1.5f),
    bodyS = style(14.sp, FontWeight.W400, 1.5f),
)

/** 연결 코드 칸. 8자를 한 글자씩 구분해 읽도록 넓힌다 */
const val CODE_TRACKING_EM = 0.08f

private val LocalColors = staticCompositionLocalOf { DayColors }
private val LocalTypography = staticCompositionLocalOf { Typography }

object CdTheme {
    val colors: CdColors @Composable get() = LocalColors.current
    val type: CdTypography @Composable get() = LocalTypography.current
}

@Composable
fun CdTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) NightColors else DayColors
    CompositionLocalProvider(LocalColors provides colors, LocalTypography provides Typography, content = content)
}
