package com.conditiondrive.core.docs

import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.RepoFiles
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 접수양식으로 옮길 원문(spec.md, business.md)과 사람 몫 표가 결정과 어긋나지 않게 본다.
 * 사람이 양식에 옮겨 쓰는 순간 코드와 문서가 다른 말을 하면 심사위원은 어느 쪽도 믿지 않는다.
 */
class SubmissionDocsTest {
    private val decisions by lazy { RepoFiles.text("docs/decisions.md") }
    private val spec by lazy { RepoFiles.text("docs/spec.md") }
    private val business by lazy { RepoFiles.text("docs/business.md") }
    private val alone = PrivacyContext(passengerPresentOrUnknown = false)

    private fun humanSection(): String = RepoFiles.section(decisions, "## 제출 전 사람이 정할 것")

    /** 2026-11-06 사용자 위임: 사람만 할 수 있는 행만 남긴다. 번호는 다른 문서의 "제출 전 항목 N"이 그대로 가리키게 옛 번호를 쓴다 */
    @Test
    fun humanTableKeepsOnlyHumanRows() {
        val numbers = Regex("""(?m)^\| (\d+) \|""").findAll(humanSection()).map { it.groupValues[1].toInt() }.toList()
        // 19는 2026-11-07에 더했다. 주최 측 문의(J-08)는 사람만 할 수 있는데 표 밖에 있어 잊힐 수 있었다
        // 1(서명)은 2026-11-13에 배포 키로 다시 빌드해 지웠다
        assertEquals(listOf(12, 14, 15, 16, 17, 19), numbers)
        for (word in listOf("서명", "접수양식", "동의서", "appdevchallenge@hyundai-partners.com", "2026-11-15", "GitHub", "`apk/`", "CONTROL_CAR_CLIMATE")) {
            assertTrue(word in humanSection(), word)
        }
        // 지운 1행 기록과 README가 다른 APK를 가리키면 어느 쪽이 제출본인지 알 수 없다
        val readme = RepoFiles.text("README.md")
        val prefixes = Regex("""(?m)^\| (?:차량|폰) `apk/hyunkyo_\w+_release\.apk`[^|]*\| `([0-9a-f]{16})` \|""")
            .findAll(readme).map { it.groupValues[1] }.toList()
        assertEquals(2, prefixes.size, "README APK 표의 해시 행")
        val signingRow = humanSection().lines().first { it.startsWith("2026-11-13에 지운 행: 1 ") }
        for (prefix in prefixes) assertTrue(prefix in signingRow, prefix)
        // 웹을 읽어 Claude가 할 수 있는 일은 사람 몫에 두지 않는다
        assertTrue("원자료로 한 번 더 본다" !in humanSection())
        // 화물차휴게소 종류는 2026-11-07에 포털 표를 읽어 채웠다(P-15). 지운 18행을 가리키는 글이 남으면 없는 행을 찾게 된다
        assertTrue("내려받기 허락" !in humanSection())
        for (path in listOf("README.md", "docs/decisions.md", "docs/poi-sources.md", "docs/test-scenarios.md")) {
            val text = RepoFiles.text(path)
            for (stale in listOf("제출 전 항목 18", "사람 몫 18행", "18행에 두었다", "18행에 남았다")) assertTrue(stale !in text, "$path: $stale")
        }
    }

    /** 표에서 뺀 항목은 위 절의 결정 행으로 옮겨야 한다. 행이 없거나 되돌리는 법이 없으면 위임한 결정을 추적할 수 없다 */
    @Test
    fun resolvedItemsPointToDecisionRows() {
        val pointer = humanSection().lines().first { it.startsWith("2026-11-06 사용자 위임으로") }
        val ids = Regex("""[A-Z]-\d{2}""").findAll(pointer).map { it.value }.toSet()
        val expected = setOf("C-34", "S-11", "S-12", "V-07", "D-31", "D-32", "U-31", "U-28", "D-05", "S-05", "P-15", "B-12", "B-11", "U-29", "U-30", "D-33")
        assertEquals(expected, ids)
        val body = decisions.substringBefore("## 제출 전 사람이 정할 것")
        // C-34는 결번 상수, D-05와 S-05는 갱신만 한 옛 행이다
        for (id in ids - setOf("C-34", "D-05", "S-05")) {
            val row = body.lines().firstOrNull { it.startsWith("| $id |") }
            assertTrue(row != null, "$id 행이 없다")
            assertTrue("[뒤집기" in row, "$id 행에 되돌리는 법이 없다")
        }
        for (n in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13)) assertTrue(Regex("""(^|, |: )$n """).containsMatchIn(pointer), "항목 $n")
    }

    /** 기획서 원문이 엔진의 실제 음성을 그대로 인용해야 한다. 문구를 바꾸면 이 시험이 기획서도 고치라고 알린다(S-12) */
    @Test
    fun specQuotesActualVoiceLines() {
        val place = "칠곡졸음쉼터"
        val v09 = VoiceCatalog.render(VoiceId.V09, VoiceVars(distanceM = 800, placeName = place), alone).replace(place, "{장소}")
        val v09h = VoiceCatalog.render(VoiceId.V09, VoiceVars(highwayLikely = true), alone)
        val v10h = VoiceCatalog.render(VoiceId.V10, VoiceVars(highwayLikely = true), alone)
        val v09n = VoiceCatalog.render(VoiceId.V09, VoiceVars(), alone)
        val v12 = VoiceCatalog.render(VoiceId.V12, VoiceVars(), alone)
        for (line in listOf(v09, v09h, v12)) assertTrue("\"$line\"" in spec, line)
        assertTrue("\"${v10h.removeSuffix(".")}\"" in spec, v10h)
        assertTrue("\"${v09n.substringAfter(". ").removeSuffix(".")}\"" in spec, v09n)
        assertTrue("근처에 쉴 곳이 안 보여요. 계속 찾아볼게요." in spec)
    }

    /** 1장 표 19건 중 문구로 확인되는 것. 옛 문구가 하나라도 남으면 양식에 그대로 옮겨진다 */
    @Test
    fun specHasNoSupersededWording() {
        val stale = listOf(
            "낮아지고 있어요",            // V-01
            "정상·주의",                   // U-06 배지
            "주행 중 항상",               // U-07
            "단계 선택",                  // U-16
            "시뮬레이션 검증으로",         // D-04
            "평소보다 짧으면",             // D-03
            "전날 수면 시간, 심박",        // D-07
            "또 실패하면 \"보통\"",        // R-13
            "공개 가이드라인의 고정값만", // D-05
            "이후 1단계 규칙만",          // 1장 표 7
            "1·2단계 규칙으로 계속",      // D-14
            "위험이 작다",                // V-14
            "경험이 있으면 사고 위험",     // S-02
            "이 폰에만",                   // B-11
        )
        assertEquals(emptyList(), stale.filter { it in spec })
        // 금칙어는 금지 규칙 안에서만 나온다(V-02)
        val banned = spec.lines().filter { "출발해도 좋아요" in it }
        assertEquals(1, banned.size)
        assertTrue("쓰지 않고" in banned.single())
        // 갓길·차로를 가리키는 지시는 없다. 법 조문을 인용하는 문장에만 나온다(V-07)
        assertTrue(spec.lines().filter { "갓길" in it }.all { "도로교통법" in it || "가리키지" in it || "세우라고 하지" in it })
    }

    /** 사람이 식약처에 가져갈 목록이 기획서에 있어야 한다(S-12, V-01, V-08) */
    @Test
    fun specListsRegulatoryConsultation() {
        val section = RepoFiles.section(spec, "## 11.")
        val list = section.substringAfter("**규제 상담 항목**")
        assertTrue(list != section, "규제 상담 항목 목록이 없다")
        for (word in listOf("식약처", "혈당이 기준보다 낮아요", "회복 대기", "V-08", "도로교통법", "정보통신망법")) assertTrue(word in list, word)
    }

    /** 2천만 대는 2030년 전 세계 누적 목표다. 국내 MAU처럼 읽히면 사업성 수치 전체를 의심받는다(S-03, S-11) */
    @Test
    fun businessLabelsTargetsAndAssumptions() {
        assertTrue("2030년 목표이자 전 세계 누적" in business)
        assertTrue("31만" !in business, "옛 탑재 추정이 남았다")
        // 공개 자료가 없는 비율은 가정으로 표시돼야 한다
        for (row in business.lines().filter { it.startsWith("| 보수 |") || it.startsWith("| 낙관 |") }) assertTrue("%" in row)
        assertTrue("설치율 [가정]" in business && "월 활성률 [가정]" in business)
        assertTrue("사업용 화물차 운수종사자" in business)
        // 기획서와 사업성 문서가 같은 수치를 말한다
        for (n in listOf("28만", "1만 4천", "4,600", "4만")) assertTrue(n in spec && n in business, n)
    }
}
