package com.conditiondrive.vehicle.ui.theme

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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.conditiondrive.vehicle.R

/** design-tokens.md 2장. hex는 이 파일에만 있다. CdThemeTokensTest가 문서 표와 대조한다 */
@Immutable
data class CdColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val track: Color,
    val hairline: Color,
    val faceStrong: Color,
    val onFaceStrong: Color,
    val onFaceCaption: Color,     // 표지 자막. 표지 문구와 구분되게 한 단계 낮춘다
    val select: Color,
    val cautionFace: Color,
    val onCaution: Color,
    val cautionOutline: Color?,   // 흰 배경에서 면이 묻혀 주간에만 윤곽이 필요하다
    val stopFace: Color,
    val onStop: Color,
    val stopText: Color,
    val recover: Color,
) {
    /** QR은 항상 어두운 칸이 밝은 면 위에 있어야 읽힌다. 야간에는 값을 뒤집는다 */
    val qrDark: Color get() = if (bg.luminanceIsDark()) bg else text1
    val qrLight: Color get() = if (bg.luminanceIsDark()) text1 else bg

    private fun Color.luminanceIsDark() = (red + green + blue) / 3f < 0.5f
}

val CdDayColors = CdColors(
    bg = Color(0xFFFFFFFF),
    surface1 = Color(0xFFF6F8FC),
    surface2 = Color(0xFFF0F0F0),
    text1 = Color(0xFF131417),
    text2 = Color(0xFF44464E),
    text3 = Color(0xFF5E6068),
    track = Color(0xFF66686F),
    hairline = Color(0xFFBDBDBD),
    faceStrong = Color(0xFF000000),
    onFaceStrong = Color(0xFFFFFFFF),
    onFaceCaption = Color(0xFFC7C9CE),
    select = Color(0xFFFF9900),
    cautionFace = Color(0xFFFFC224),
    onCaution = Color(0xFF131417),
    cautionOutline = Color(0xFF7A5200),
    stopFace = Color(0xFFB3261E),
    onStop = Color(0xFFFFFFFF),
    stopText = Color(0xFFB3261E),
    recover = Color(0xFF00704A),
)

val CdNightColors = CdColors(
    bg = Color(0xFF131417),
    surface1 = Color(0xFF1D1E21),
    surface2 = Color(0xFF2A2B2F),
    text1 = Color(0xFFEDEEF2),
    text2 = Color(0xFFC7C9CE),
    text3 = Color(0xFFA4A8AE),
    track = Color(0xFF8A8E96),
    hairline = Color(0xFF44464E),
    faceStrong = Color(0xFFEDEEF2),
    onFaceStrong = Color(0xFF131417),
    onFaceCaption = Color(0xFF44464E),
    select = Color(0xFFFF9900),
    cautionFace = Color(0xFFFFC224),
    onCaution = Color(0xFF131417),
    cautionOutline = null,
    stopFace = Color(0xFFFF4C28),
    onStop = Color(0xFF131417),
    stopText = Color(0xFFFF6A4D),
    recover = Color(0xFF3DDC97),
)

/** design-tokens.md 3.2. 12sp 같은 Pleos Label은 3mm에 미달해 스케일에 없다 */
@Immutable
data class CdType(
    val display: TextStyle,
    val headlineM: TextStyle,
    val keyLabel: TextStyle,
    val titleL: TextStyle,
    val titleM: TextStyle,
    val titleS: TextStyle,
    val bodyL: TextStyle,
    val bodyM: TextStyle,
    val bodyS: TextStyle,
)

// 원본 파일을 그대로 쓴다. OFL Reserved Font Name이라 서브셋하면 이름을 바꿔야 한다
private val Pretendard = FontFamily(
    pretendardAt(FontWeight.W400),
    pretendardAt(FontWeight.W500),
    pretendardAt(FontWeight.W600),
)

// 가변 글꼴은 축을 직접 지정해야 굵기가 바뀐다
@OptIn(ExperimentalTextApi::class)
private fun pretendardAt(weight: FontWeight) = Font(
    resId = R.font.pretendard,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** 제목은 줄 길이를 고르게, 본문은 품질 우선. 둘 다 어절 단위 줄바꿈을 요청한다(3.3) */
private val HeadingBreak = LineBreak.Heading
private val BodyBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

// 어절 줄바꿈 사전은 로캘로 고른다. 기기 언어가 영어여도 한국어 규칙을 쓰게 한다
private val Korean = LocaleList("ko-KR")

private fun style(size: TextUnit, weight: FontWeight, lineHeight: Float, lineBreak: LineBreak, features: String = "tnum") = TextStyle(
    fontFamily = Pretendard,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight.em,
    fontFeatureSettings = features,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    lineBreak = lineBreak,
    localeList = Korean,
)

val CdTypeScale = CdType(
    // 큰 숫자만 비례 숫자다. tnum이면 "12"가 "1 2"로 벌어진다. 왼쪽 정렬에 뒤는 단위뿐이라 폭이 바뀌어도 단위만 몇 dp 움직인다
    display = style(160.sp, FontWeight.W400, 1.1f, HeadingBreak, features = "pnum"),
    // 나머지는 tnum: 분이 바뀔 때 숫자 폭이 흔들리면 시선을 끈다
    headlineM = style(56.sp, FontWeight.W500, 1.2f, HeadingBreak),
    keyLabel = style(48.sp, FontWeight.W600, 1.2f, HeadingBreak),
    titleL = style(40.sp, FontWeight.W500, 1.3f, HeadingBreak),
    titleM = style(36.sp, FontWeight.W500, 1.3f, HeadingBreak),
    titleS = style(32.sp, FontWeight.W400, 1.4f, HeadingBreak),
    bodyL = style(30.sp, FontWeight.W400, 1.5f, BodyBreak),
    bodyM = style(28.sp, FontWeight.W400, 1.5f, BodyBreak),
    bodyS = style(26.sp, FontWeight.W400, 1.5f, BodyBreak),
)

/** design-tokens.md 4장 */
object CdDimens {
    val Space8: Dp = 8.dp
    val Space16: Dp = 16.dp
    val Space24: Dp = 24.dp
    val Space32: Dp = 32.dp
    val Space48: Dp = 48.dp
    val Space64: Dp = 64.dp
    val Space96: Dp = 96.dp

    val Line: Dp = 1.dp
    val TickLine: Dp = 2.dp
    val GlyphStroke: Dp = 6.dp
    val Glyph: Dp = 56.dp

    val StatusHeader: Dp = 96.dp
    val TabBar: Dp = 88.dp
    val ListRow: Dp = 88.dp
    val DemoBanner: Dp = 56.dp
    val PortraitPane: Dp = 840.dp
    val PrimaryButton: Dp = 100.dp
    val SecondaryButton: Dp = 80.dp
    val MinTouch: Dp = 76.dp
    val CheckBox: Dp = 44.dp
    val Qr: Dp = 400.dp

    /** 이 폭 이상이면 창 2개(design-tokens 6장) */
    val TwoPaneMinWidth: Dp = 1100.dp
    private val WidePaddingMinWidth: Dp = 800.dp

    fun windowPadding(windowWidth: Dp): Dp = if (windowWidth >= WidePaddingMinWidth) Space48 else 40.dp
}

private val LocalCdColors = staticCompositionLocalOf { CdDayColors }

object CdTheme {
    val colors: CdColors
        @Composable get() = LocalCdColors.current
    val type: CdType
        get() = CdTypeScale
}

@Composable
fun CdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCdColors provides if (dark) CdNightColors else CdDayColors, content = content)
}
