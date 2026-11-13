package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.PromptAnswer
import java.text.Normalizer

/** 설계서 6.2. 학습 모델 없이 고정 규칙만 쓴다. 사전을 고치면 test-scenarios.md 분류 표도 함께 고친다. */
object VoiceClassifier {
    private val PUNCTUATION = Regex("""[\p{P}\p{S}]""")
    private val SPACES = Regex("""[\s 　]+""")
    // 인식기가 "네네", "응응"을 한 토큰으로 준다. 같은 말의 반복은 한 번으로 본다
    private val REPEATED_YES = Regex("""(?<![가-힣])(응|네|예|넵)\1+(?![가-힣])""")

    private val YES_TOKENS = setOf("응", "네", "예", "어", "넵")
    // "어 잠깐만"의 "어"는 망설임이다. 프롬프트에서 "어"는 문장 전체일 때만 수락이다
    private val FIRM_YES_TOKENS = setOf("응", "네", "예", "넵")

    private val NEGATED_FATIGUE = listOf(
        "안 피곤", "안피곤", "피곤하지는 않", "피곤하지 않", "피곤하진 않", "피곤 안", "안 졸", "안졸",
        "졸리지는 않", "졸리지 않", "졸리진 않", "졸음 안", "잠 안 와", "잠 안와",
        "안 힘들", "안힘들", "힘들지는 않", "힘들지 않", "힘들진 않",
        "안 지쳤", "안지쳤", "안 지쳐", "안지쳐", "안 지치", "지치지는 않", "지치지 않", "지치진 않",
        "안 나른", "안나른", "나른하지 않", "안 피로", "안 어지러", "어지럽지 않", "안 나빠", "나쁘지 않",
    )
    private val FATIGUE = listOf(
        "피곤", "졸려", "졸리", "졸립", "졸음", "피로", "힘들", "힘드", "힘듭", "지쳐", "지쳤", "지치", "나른",
        "잠 와", "잠와", "잠이 와", "잠이 온", "잠 온", "잠온", "어지러", "어지럽", "나빠", "나쁩", "최악",
        "안 좋", "안좋", "안 괜찮", "안괜찮", "괜찮지는 않", "괜찮지 않", "괜찮진 않", "좋지는 않", "좋지 않", "좋진 않",
    )
    // 부정 구와 함께 나오면 "별로 안 피곤해"처럼 부정을 거드는 말이다
    private const val BYEOLLO = "별로"
    private val GOOD = listOf("좋아", "좋습니다", "괜찮", "최고", "쌩쌩", "멀쩡")
    private val NEUTRAL = listOf("보통", "그냥", "그럭저럭", "그저 그래", "그저그래")
    // 묻지 말라는 뜻이다. 다시 물으면 더 귀찮고 "보통"으로 남기면 사실이 아니다
    private val CHECKIN_REFUSED = listOf("그만", "조용히", "됐어", "싫어", "나중에", "다음에", "이따")

    private val NEGATED_POSITIVE = listOf("안 좋", "안좋", "안 괜찮", "안괜찮")
    private val MUTE = listOf("그만", "조용히", "오늘은 됐어")
    private val ACCEPT_ANYWHERE = listOf(
        "좋아", "쉴게", "쉬자", "쉴래", "쉬고 싶", "들를게", "알았어", "알겠", "그러자", "그럴게", "오케이",
    )
    // "갈게", "가자"는 혼자서는 "그냥 갈게"와 구분되지 않는다. 쉬러 간다는 말이 함께 있어야 수락이다
    private val GO = listOf("갈게", "가자", "갈래")
    private val GO_TARGET = listOf("쉬어", "쉬었다", "들러", "거기")
    private val KEEP_GOING = listOf("그냥", "계속", "그대로", "이대로")
    // "안 쉴게"가 "쉴게"로 수락되면 거절한 운전자에게 경유지가 붙는다. 수락 검사 전에 떼어 거절로 센다
    private val NEGATED_ACCEPT = listOf(
        "안 가", "안가", "안 갈", "안갈", "안 쉬", "안쉬", "안 쉴", "안쉴", "안 들를", "안들를",
        "쉬고 싶지 않", "쉬고 싶진 않", "쉬고 싶지는 않", "쉬지 않", "쉬지 말",
    )
    private val DECLINE = listOf("아니", "아뇨", "괜찮아", "괜찮습니다", "됐어", "싫어", "나중에", "다음에", "이따")

    fun checkin(text: String?): CheckinAnswer {
        val normalized = normalize(text) ?: return CheckinAnswer.NO_SPEECH
        val negated = NEGATED_FATIGUE.any { it in normalized }
        val rest = normalized.without(NEGATED_FATIGUE)
        return when {
            FATIGUE.any { it in rest } || (!negated && BYEOLLO in rest) -> CheckinAnswer.TIRED
            negated -> CheckinAnswer.GOOD
            GOOD.any { it in rest } -> CheckinAnswer.GOOD
            CHECKIN_REFUSED.any { it in rest } -> CheckinAnswer.NO_SPEECH
            NEUTRAL.any { it in rest } || rest.tokens().any { it in YES_TOKENS } -> CheckinAnswer.NEUTRAL
            else -> CheckinAnswer.UNCLASSIFIED
        }
    }

    fun stopPrompt(text: String?): PromptAnswer = classifyPrompt(text, allowMute = true)

    /** V27용. MUTE 없이 stopPrompt 사전을 쓴다 */
    fun yesNo(text: String?): PromptAnswer = classifyPrompt(text, allowMute = false)

    private fun classifyPrompt(text: String?, allowMute: Boolean): PromptAnswer {
        val normalized = normalize(text) ?: return PromptAnswer.NO_SPEECH
        val cleaned = normalized.without(NEGATED_POSITIVE)
        val refused = NEGATED_ACCEPT.any { it in cleaned }
        val rest = cleaned.without(NEGATED_ACCEPT)
        val tokens = rest.tokens()

        val go = GO.any { it in rest }
        val goesToRest = go && GO_TARGET.any { it in rest }
        val keepGoing = go && !goesToRest && (KEEP_GOING.any { it in rest } || "더" in tokens)
        val acceptWord = goesToRest || ACCEPT_ANYWHERE.any { it in rest }

        // "그만 쉴게"는 쉬기를 그만둔다는 뜻과 이제 쉰다는 뜻이 다 된다. 줄이지 말고 다시 묻는다
        if (MUTE.any { it in cleaned }) {
            if (acceptWord || go) return PromptAnswer.UNCLASSIFIED
            if (allowMute) return PromptAnswer.MUTE
        }
        val spoken = tokens.dropWhile { it == "어" && tokens.size > 1 }
        val accepted = tokens.any { it in FIRM_YES_TOKENS } || normalized == "어" || spoken.firstOrNull() == "그래" || acceptWord
        val declined = refused || keepGoing || DECLINE.any { it in rest }
        return when {
            accepted && declined -> PromptAnswer.UNCLASSIFIED
            accepted -> PromptAnswer.ACCEPT
            declined -> PromptAnswer.DECLINE
            else -> PromptAnswer.UNCLASSIFIED
        }
    }

    private fun normalize(text: String?): String? {
        if (text == null) return null
        // 문장 부호를 공백으로 바꿔 "응,쉴게"에서도 "응"이 단독 토큰으로 남게 한다
        return Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(PUNCTUATION, " ")
            .replace(SPACES, " ")
            .trim()
            .replace(REPEATED_YES, "$1")
            .ifEmpty { null }
    }

    private fun String.without(phrases: List<String>): String =
        phrases.fold(this) { acc, phrase -> acc.replace(phrase, " ") }.replace(SPACES, " ").trim()

    private fun String.tokens(): List<String> = if (isEmpty()) emptyList() else split(' ')
}
