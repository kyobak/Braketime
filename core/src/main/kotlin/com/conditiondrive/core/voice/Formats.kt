package com.conditiondrive.core.voice

import kotlin.math.floor

object Formats {
    private const val MMOL_TO_MGDL = 18.016
    private val PARENTHESES = Regex("""[(（][^)）]*[)）]?""")
    private val SPACES = Regex("""\s+""")

    fun driveTime(sec: Long): String = hoursAndMinutes(sec.coerceAtLeast(0) / 60)        // 분 내림: "1시간 45분", "2시간"

    fun remaining(sec: Long): String = hoursAndMinutes((sec.coerceAtLeast(0) + 59) / 60)  // 분 올림: "12분"

    /** "800미터", "3.2킬로미터", "12킬로미터". TTS가 "삼 점 영"으로 읽지 않게 소수 0은 뺀다 */
    fun distance(meters: Int): String {
        val m = meters.coerceAtLeast(0)
        if (m < 1000) return "${m}미터"
        val tenths = (m + 50) / 100
        if (tenths >= 100) return "${(m + 500) / 1000}킬로미터"
        return if (tenths % 10 == 0) "${tenths / 10}킬로미터" else "${tenths / 10}.${tenths % 10}킬로미터"
    }

    /** 괄호 제거. 닫는 괄호가 잘린 데이터도 방향 표기가 음성으로 새지 않게 끝까지 지운다 */
    fun placeName(raw: String): String = raw.replace(PARENTHESES, " ").replace(SPACES, " ").trim()

    fun s1Time(sec: Long): String = if (sec < 60) "곧" else driveTime(sec)               // 분 내림, 1분 미만 "곧"

    fun mgdl(mmol: Double): Int = floor(mmol * MMOL_TO_MGDL).toInt()                     // floor(mmol * 18.016)

    private fun hoursAndMinutes(totalMinutes: Long): String {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes}분"
            minutes == 0L -> "${hours}시간"
            else -> "${hours}시간 ${minutes}분"
        }
    }
}
