package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.PromptAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceClassifierTest {
    /** test-scenarios.md "분류 사전" 표 전체를 원문에서 읽어 대조한다 */
    @Test
    fun matchesEveryRowOfDictionaryTable() {
        val table = RepoFiles.text("docs/test-scenarios.md")
            .lines()
            .dropWhile { !it.startsWith("| 입력 | checkin | stopPrompt |") }
            .drop(2)
            .takeWhile { it.startsWith("|") }
        check(table.size >= 27) { "표를 찾지 못함: ${table.size}행" }

        val failures = table.flatMap { row ->
            val cells = row.trim('|').split('|').map { it.trim() }
            val input = cells[0].substringBefore(" (").let { if (it == "(null, 공백)") null else it }
            val checkin = CheckinAnswer.valueOf(cells[1])
            val prompt = PromptAnswer.valueOf(cells[2])
            listOfNotNull(
                "checkin($input)=${VoiceClassifier.checkin(input)} 기대 $checkin".takeIf { VoiceClassifier.checkin(input) != checkin },
                "stopPrompt($input)=${VoiceClassifier.stopPrompt(input)} 기대 $prompt".takeIf { VoiceClassifier.stopPrompt(input) != prompt },
            )
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun blankAndPunctuationOnlyAreNoSpeech() {
        for (text in listOf(null, "", "   ", "\n", "...", "?!", " 　")) {
            assertEquals(CheckinAnswer.NO_SPEECH, VoiceClassifier.checkin(text), "checkin [$text]")
            assertEquals(PromptAnswer.NO_SPEECH, VoiceClassifier.stopPrompt(text), "stopPrompt [$text]")
            assertEquals(PromptAnswer.NO_SPEECH, VoiceClassifier.yesNo(text), "yesNo [$text]")
        }
    }

    @Test
    fun normalizationStripsPunctuationAndCollapsesSpaces() {
        assertEquals(PromptAnswer.ACCEPT, VoiceClassifier.stopPrompt("  응!  "))
        assertEquals(PromptAnswer.ACCEPT, VoiceClassifier.stopPrompt("응,쉴게."))
        assertEquals(CheckinAnswer.TIRED, VoiceClassifier.checkin("안   좋아..."))
        assertEquals(CheckinAnswer.GOOD, VoiceClassifier.checkin("안\t피곤해요~"))
        assertEquals(CheckinAnswer.NEUTRAL, VoiceClassifier.checkin("그저   그래"))
    }

    @Test
    fun decomposedHangulIsClassifiedLikeComposed() {
        val nfd = java.text.Normalizer.normalize("괜찮아", java.text.Normalizer.Form.NFD)
        assertEquals(CheckinAnswer.GOOD, VoiceClassifier.checkin(nfd))
        assertEquals(PromptAnswer.DECLINE, VoiceClassifier.stopPrompt(nfd))
    }

    @Test
    fun checkinFollowsRuleOrder() {
        val cases = mapOf(
            "피곤해" to CheckinAnswer.TIRED,
            "졸려요" to CheckinAnswer.TIRED,
            "졸음이 와" to CheckinAnswer.TIRED,
            "피로해" to CheckinAnswer.TIRED,
            "힘들어" to CheckinAnswer.TIRED,
            "지쳐" to CheckinAnswer.TIRED,
            "지쳤어" to CheckinAnswer.TIRED,
            "나른해" to CheckinAnswer.TIRED,
            "잠 와" to CheckinAnswer.TIRED,
            "잠와" to CheckinAnswer.TIRED,
            "별로야" to CheckinAnswer.TIRED,
            "안좋아" to CheckinAnswer.TIRED,
            "안괜찮아" to CheckinAnswer.TIRED,
            "안피곤해" to CheckinAnswer.GOOD,
            "피곤하진 않아" to CheckinAnswer.GOOD,
            "안 졸려" to CheckinAnswer.GOOD,
            "안졸려" to CheckinAnswer.GOOD,
            "졸리지 않아" to CheckinAnswer.GOOD,
            "졸리진 않아" to CheckinAnswer.GOOD,
            "좋습니다" to CheckinAnswer.GOOD,
            "최고야" to CheckinAnswer.GOOD,
            "쌩쌩해" to CheckinAnswer.GOOD,
            "멀쩡해" to CheckinAnswer.GOOD,
            "그럭저럭" to CheckinAnswer.NEUTRAL,
            "그저그래" to CheckinAnswer.NEUTRAL,
            "예" to CheckinAnswer.NEUTRAL,
            "어" to CheckinAnswer.NEUTRAL,
            "어 그래" to CheckinAnswer.NEUTRAL,
            "글쎄" to CheckinAnswer.UNCLASSIFIED,
            "음" to CheckinAnswer.UNCLASSIFIED,
        )
        val failures = cases.filter { (text, expected) -> VoiceClassifier.checkin(text) != expected }.keys
        assertEquals(emptySet(), failures)
    }

    @Test
    fun stopPromptFollowsRuleOrder() {
        val cases = mapOf(
            "그만해" to PromptAnswer.MUTE,
            "조용히 해" to PromptAnswer.MUTE,
            "응 그만" to PromptAnswer.MUTE,
            "그래" to PromptAnswer.ACCEPT,
            "그래 가자" to PromptAnswer.ACCEPT,
            "쉴게" to PromptAnswer.ACCEPT,
            "거기 들를게" to PromptAnswer.ACCEPT,
            "갈게요" to PromptAnswer.UNCLASSIFIED,
            "어 그래" to PromptAnswer.ACCEPT,
            "어 쉴게" to PromptAnswer.ACCEPT,
            "응응" to PromptAnswer.ACCEPT,
            "알았어" to PromptAnswer.ACCEPT,
            "예" to PromptAnswer.ACCEPT,
            "아뇨" to PromptAnswer.DECLINE,
            "괜찮습니다" to PromptAnswer.DECLINE,
            "안 가" to PromptAnswer.DECLINE,
            "안가" to PromptAnswer.DECLINE,
            "싫어" to PromptAnswer.DECLINE,
            "다음에" to PromptAnswer.DECLINE,
            "이따가" to PromptAnswer.DECLINE,
            "그래 됐어" to PromptAnswer.UNCLASSIFIED,
            "안좋아" to PromptAnswer.UNCLASSIFIED,
            "안괜찮아" to PromptAnswer.UNCLASSIFIED,
            "글쎄" to PromptAnswer.UNCLASSIFIED,
        )
        val failures = cases.filter { (text, expected) -> VoiceClassifier.stopPrompt(text) != expected }.keys
        assertEquals(emptySet(), failures)
    }

    /** 거절한 운전자에게 경유지가 붙으면 안 된다 */
    @Test
    fun negatedAcceptWordsAreDeclines() {
        for (text in listOf("안 쉴게", "안쉴게", "안 갈게", "안갈게", "안 들를게", "안들를게", "안 가자", "안 쉴게요")) {
            assertEquals(PromptAnswer.DECLINE, VoiceClassifier.stopPrompt(text), text)
            assertEquals(PromptAnswer.DECLINE, VoiceClassifier.yesNo(text), text)
        }
        assertEquals(PromptAnswer.UNCLASSIFIED, VoiceClassifier.stopPrompt("응 안 쉴게"))
    }

    /** 쉬겠다는 말을 "그만"으로 읽어 안내를 줄이면 안 된다. "응 그만"은 그대로 줄인다 */
    @Test
    fun muteWithAcceptWordIsAskedAgain() {
        assertEquals(PromptAnswer.UNCLASSIFIED, VoiceClassifier.stopPrompt("이제 그만 쉴게"))
        assertEquals(PromptAnswer.UNCLASSIFIED, VoiceClassifier.yesNo("그만 쉴게"))
        assertEquals(PromptAnswer.MUTE, VoiceClassifier.stopPrompt("응 그만"))
    }

    /** "그냥"은 체크인에서는 보통이지만 "그냥 그래"와 달리 거절 뜻의 "그만"이 있으면 묻지 말라는 말이다 */
    @Test
    fun checkinRefusalEndsWithoutAsking() {
        assertEquals(CheckinAnswer.NO_SPEECH, VoiceClassifier.checkin("그만해"))
        assertEquals(CheckinAnswer.NO_SPEECH, VoiceClassifier.checkin("오늘은 됐어"))
        // 피곤하다는 말이 함께 있으면 그쪽이 먼저다
        assertEquals(CheckinAnswer.TIRED, VoiceClassifier.checkin("그만 물어봐 피곤해"))
        assertEquals(CheckinAnswer.GOOD, VoiceClassifier.checkin("됐어 괜찮아"))
    }

    @Test
    fun yesNoHasNoMuteButSharesDictionary() {
        assertEquals(PromptAnswer.ACCEPT, VoiceClassifier.yesNo("응"))
        assertEquals(PromptAnswer.DECLINE, VoiceClassifier.yesNo("아니"))
        assertEquals(PromptAnswer.DECLINE, VoiceClassifier.yesNo("오늘은 됐어"))
        assertEquals(PromptAnswer.UNCLASSIFIED, VoiceClassifier.yesNo("그만"))
        assertEquals(PromptAnswer.UNCLASSIFIED, VoiceClassifier.yesNo("아니 쉴게"))
    }
}
