package com.conditiondrive.core.tokens

import com.conditiondrive.core.voice.RepoFiles
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** H7. design-tokens.md 표의 조합을 WCAG 공식으로 다시 계산한다. 표의 숫자가 값과 어긋나도 실패한다 */
class TokenContrastTest {
    private val doc = RepoFiles.text("docs/design-tokens.md")

    private fun luminance(hex: String): Double {
        val rgb = hex.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }
        val linear = rgb.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
    }

    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private class Token(val name: String, val hex: String, val claimed: List<Double>)

    private fun tokens(heading: String): List<Token> =
        RepoFiles.section(doc, heading).lines().mapNotNull { line ->
            val match = Regex("""^\| (\w+) \| (#[0-9A-F]{6}) \| (.*?) \|""").find(line) ?: return@mapNotNull null
            val claimed = Regex("""\d+\.\d+""").findAll(match.groupValues[3]).map { it.value.toDouble() }.toList()
            Token(match.groupValues[1], match.groupValues[2], claimed)
        }

    /** 표에 없는 면의 대비를 잘못 단언하지 않도록 hex를 표에서 읽는다 */
    private fun checkTable(heading: String, exemptTokens: Set<String>, forbiddenOnSurface2: Set<String>) {
        val all = tokens(heading).associateBy { it.name }
        val surfaces = listOf("bg", "surface1", "surface2").map { all.getValue(it).hex }
        val checked = all.values.filter { it.claimed.size == 3 }
        assertTrue(checked.size >= 5, "$heading 표를 찾지 못함")

        for (token in checked) {
            surfaces.forEachIndexed { i, surface ->
                val actual = contrast(token.hex, surface)
                assertEquals(token.claimed[i], actual, 0.011, "$heading ${token.name} 표기값")
                val mustPass = token.name !in exemptTokens && !(i == 2 && token.name in forbiddenOnSurface2)
                if (mustPass) assertTrue(actual >= 4.5, "$heading ${token.name} / ${surface}: $actual")
            }
        }
    }

    @Test
    fun dayTokensPass() = checkTable("### 2.1", exemptTokens = setOf("hairline", "select"), forbiddenOnSurface2 = emptySet())

    /** 야간 track은 표에 "surface2 위 금지"로 적힌 예외다 */
    @Test
    fun nightTokensPass() = checkTable("### 2.2", exemptTokens = setOf("hairline", "select"), forbiddenOnSurface2 = setOf("track"))

    @Test
    fun faceStrongPairsPass() {
        assertClaim("#000000", "#FFFFFF", 21.00)
        assertClaim("#EDEEF2", "#131417", 15.89)
    }

    /** 2.3 상태 표. 값 옆에 적힌 대비를 그대로 대조한다 */
    @Test
    fun statePairsPass() {
        val dayBg = "#FFFFFF"
        val nightBg = "#131417"
        val nightS1 = "#1D1E21"
        val nightS2 = "#2A2B2F"
        val dayS1 = "#F6F8FC"
        val dayS2 = "#F0F0F0"

        // CAUTION: 면만으로는 흰 배경 대비가 낮아 윤곽이 필수다. 윤곽과 면 사이 4.28은 표에 적힌 비글자 조합이다
        assertClaim("#FFC224", "#131417", 11.40)
        assertClaim("#7A5200", dayBg, 6.92)
        assertEquals(4.28, contrast("#7A5200", "#FFC224"), 0.011)
        assertEquals(1.62, contrast("#FFC224", dayBg), 0.011)
        assertClaim("#FFC224", nightBg, 11.40)

        // STOP
        assertClaim("#B3261E", dayBg, 6.54)
        assertClaim("#FF4C28", nightBg, 5.54)
        assertClaim("#FF4C28", nightS1, 5.01)
        assertEquals(4.25, contrast("#FF4C28", nightS2), 0.011)   // 표에 "s2 위 금지"로 적힌 예외
        assertClaim("#FF6A4D", nightBg, 6.51)
        assertClaim("#FF6A4D", nightS1, 5.89)
        assertClaim("#FF6A4D", nightS2, 5.00)

        // RECOVER
        assertClaim("#00704A", dayBg, 6.15)
        assertClaim("#00704A", dayS1, 5.78)
        assertClaim("#00704A", dayS2, 5.39)
        assertClaim("#3DDC97", nightBg, 10.42)
        assertClaim("#3DDC97", nightS1, 9.43)
        assertClaim("#3DDC97", nightS2, 8.00)

        val section = RepoFiles.section(doc, "### 2.3")
        for (hex in listOf("#FFC224", "#7A5200", "#B3261E", "#FF4C28", "#FF6A4D", "#00704A", "#3DDC97")) {
            assertTrue(hex in section, "$hex 가 표에서 사라졌다")
        }
    }

    private fun assertClaim(a: String, b: String, claimed: Double) {
        val actual = contrast(a, b)
        assertEquals(claimed, actual, 0.011, "$a / $b")
        assertTrue(actual >= 4.5, "$a / $b: $actual")
    }
}
