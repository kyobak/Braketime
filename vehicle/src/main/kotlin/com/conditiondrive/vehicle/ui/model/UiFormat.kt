package com.conditiondrive.vehicle.ui.model

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.vehicle.R
import java.util.Locale
import kotlin.math.floor

/** 화면 전용 표기. 음성 표기는 :core Formats에 있고 형식이 다르다("3.2km"와 "3.2킬로미터") */
object UiFormat {
    fun km(meters: Int): String {
        val m = meters.coerceAtLeast(0)
        if (m < 1000) return "${m}m"
        val tenths = (m + 50) / 100
        if (tenths >= 100) return "${(m + 500) / 1000}km"
        return if (tenths % 10 == 0) "${tenths / 10}km" else "${tenths / 10}.${tenths % 10}km"
    }

    /** mg/dL이 내림이므로 소수도 내림이다. 3.98을 "4.0"으로 보여 기준 위처럼 읽히면 안 된다 */
    fun mmol(value: Double): String =
        String.format(Locale.ROOT, "%.1f", floor(value * 10 + 1e-9) / 10)

    /** 눈금 라벨 "1:45" */
    fun clock(sec: Long): String {
        val minutes = sec.coerceAtLeast(0) / 60
        return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
    }

    fun placeKind(kind: PlaceKind): Int = when (kind) {
        PlaceKind.REST_AREA -> R.string.kind_rest_area
        PlaceKind.DROWSY_SHELTER -> R.string.kind_drowsy_shelter
        PlaceKind.PARKING -> R.string.kind_parking
        PlaceKind.CONVENIENCE -> R.string.kind_convenience
    }

    fun trend(trend: Trend): Int = when (trend) {
        Trend.FALLING_FAST -> R.string.trend_falling_fast
        Trend.FALLING -> R.string.trend_falling
        Trend.FLAT -> R.string.trend_flat
        Trend.RISING -> R.string.trend_rising
    }

    /** F4-9 분류 실패는 보통으로 처리되므로 그대로 보통이다 */
    fun checkin(answer: CheckinAnswer?): Int = when (answer) {
        null -> R.string.s2_checkin_none
        CheckinAnswer.GOOD -> R.string.s2_checkin_good
        CheckinAnswer.NEUTRAL, CheckinAnswer.UNCLASSIFIED -> R.string.s2_checkin_neutral
        CheckinAnswer.TIRED -> R.string.s2_checkin_tired
        CheckinAnswer.NO_SPEECH -> R.string.s2_checkin_silent
    }

    /** "1시간 12분"을 숫자와 단위 조각으로 나눈다. 큰 시간은 숫자만 display 크기다 */
    fun timeParts(text: String): List<TimePart> =
        Regex("""\d+|\D+""").findAll(text).map { TimePart(it.value, it.value.first().isDigit()) }.toList()
}

data class TimePart(val text: String, val isNumber: Boolean)
