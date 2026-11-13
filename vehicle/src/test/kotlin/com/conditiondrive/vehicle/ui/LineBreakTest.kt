package com.conditiondrive.vehicle.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.theme.CdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * 3.3 어절 줄바꿈은 공백에서만 끊는다. 공백 없이 폭보다 긴 문자열은 그래도 강제로 끊겨 창 밖으로 넘치지 않아야 한다.
 * 어절 단위로 끊기는지 자체는 플랫폼 줄바꿈 엔진 몫이라 실기 캡처로 확인한다(Robolectric은 Phrase를 반영하지 않는다).
 */
@RunWith(AndroidJUnit4::class)
// 실제 글꼴로 재야 폭이 의미 있다. 기본 모드는 글자 수로 폭을 흉내 낸다
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1700dp-h1184dp-land-mdpi")
class LineBreakTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun measure(text: String, widthDp: Int): androidx.compose.ui.geometry.Rect {
        rule.setContent {
            CdTheme(dark = false) {
                Box(Modifier.width(widthDp.dp)) {
                    CdText(text, CdTheme.type.headlineM, Modifier.testTag("t"))
                }
            }
        }
        rule.waitForIdle()
        return rule.onNodeWithTag("t").fetchSemanticsNode().boundsInRoot
    }

    @Test
    fun longKoreanWordWrapsInsideWidth() {
        val bounds = measure("휴게소".repeat(30), 300)
        assertTrue(bounds.width <= 300f, bounds.toString())
        // headlineM 56sp 줄높이 1.2 = 67dp. 여러 줄이어야 폭 안에 들어간다
        assertTrue(bounds.height > 2 * 67f, bounds.toString())
    }

    @Test
    fun longLatinTokenWrapsInsideWidth() {
        val bounds = measure("https://conditiondrive.example.com/" + "a".repeat(80), 300)
        assertTrue(bounds.width <= 300f, bounds.toString())
        assertTrue(bounds.height > 2 * 67f, bounds.toString())
    }
}
