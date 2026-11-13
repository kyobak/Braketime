package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Level
import com.conditiondrive.core.engine.PromptAnswer
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.Trend

/**
 * 설계서 6.4 금칙어. 음성, 화면, 문자 검사가 같은 목록을 쓰도록 한곳에 둔다.
 * 고지문 전용 문자열(strings_legal.xml)에는 [MEDICAL]을 적용하지 않는다. 상표는 모든 사용자 문자열에 적용한다.
 */
object ForbiddenWords {
    val MEDICAL: List<String> = listOf(
        "저혈당", "당뇨", "진단", "치료", "처치", "인슐린", "복용", "약을", "탄수화물", "사탕", "드세요", "쇼크", "증상",
        "운전 금지", "운전하지 마", "운전 불가", "운전하면 안", "출발해도 좋아요", "출발해도 돼", "출발해도 됩", "출발하셔도",
        "안전해요", "안전합니다", "정상이에요", "정상입니다", "졸음을 감지", "AI",
    )
    val TRADEMARKS: List<String> = listOf("현대", "Hyundai", "Pleos", "플레오스", "42dot", "Gleo")

    /** 원시 값이 화면이나 음성으로 샜는지 본다. 내부 enum 이름은 사용자 문장에 나올 일이 없다 */
    val RAW_VALUES: List<String> = buildList {
        addAll(listOf("null", "NaN", "Exception", "10.0.2.2"))
        addAll(DriverState.entries.map { it.name })
        addAll(Reason.entries.map { it.name })
        addAll(Trend.entries.map { it.name })
        addAll(Level.entries.map { it.name })
        addAll(Gear.entries.map { it.name })
        addAll(CheckinAnswer.entries.map { it.name })
        addAll(PromptAnswer.entries.map { it.name })
        addAll(VoiceId.entries.map { it.name })
    }.distinct()

    /** 금칙어 전체 검사. 걸린 단어 목록을 돌려준다. */
    fun find(text: String): List<String> = findMedical(text) + findTrademarks(text) + findRawValues(text)

    fun findMedical(text: String): List<String> = MEDICAL.filter { matches(text, it, ignoreCase = false) }

    fun findTrademarks(text: String): List<String> = TRADEMARKS.filter { matches(text, it, ignoreCase = true) }

    fun findRawValues(text: String): List<String> = RAW_VALUES.filter { matches(text, it, ignoreCase = it == "null") }

    // "MAIN"의 AI, "annull"의 null처럼 영문 단어 일부가 걸리지 않게 영문·숫자는 단어 경계를 본다
    private fun matches(text: String, word: String, ignoreCase: Boolean): Boolean {
        // "운전금지"처럼 띄어쓰기만 다른 문구도 같은 말이다
        if (word.none { it in 'A'..'Z' || it in 'a'..'z' }) return word.withoutSpaces() in text.withoutSpaces()
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex("""(?<![A-Za-z0-9_])${Regex.escape(word)}(?![A-Za-z0-9_])""", options).containsMatchIn(text)
    }

    private fun String.withoutSpaces(): String = filterNot { it.isWhitespace() }
}
