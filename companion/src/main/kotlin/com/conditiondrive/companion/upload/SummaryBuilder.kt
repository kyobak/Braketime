package com.conditiondrive.companion.upload

import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.SleepPolicy
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryLimits
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/** 지난 24시간 수면 합계와 그 값을 계산한 시각. 세션 목록은 이 앞에서 사라진다 */
@Serializable
data class SleepValue(val minutes: Int, val computedAtMs: Long)

data class GlucoseReading(
    val mmol: Double,
    val trend: GlucoseTrendDto?,
    val measuredAtMs: Long,
    val source: GlucoseSourceDto = GlucoseSourceDto.SIMULATED,
)

/** 재시도 슬롯에 두는 값. 완성된 DTO를 두면 재전송 때 시각이 낡아 clock_skew, summary_expired로 거절된다 */
data class SummaryInput(val sleep: SleepValue?, val glucose: GlucoseReading?) {
    /** 시계 보정이 바뀌면 이미 잰 시각도 같은 축으로 옮긴다. 안 옮기면 멀쩡한 값이 15분 지난 값으로 버려진다 */
    fun shifted(deltaMs: Long): SummaryInput = copy(
        sleep = sleep?.copy(computedAtMs = sleep.computedAtMs + deltaMs),
        glucose = glucose?.copy(measuredAtMs = glucose.measuredAtMs + deltaMs),
    )
}

/**
 * 12.7의 완전한 스냅샷을 만든다. 입력에 원본 샘플을 담을 자리가 없어서 원본이 섞일 수 없다.
 * 결과는 언제나 SummaryValidator.validate를 통과한다(SummaryBuilderTest).
 */
object SummaryBuilder {
    private const val TTL_MS = SummaryLimits.GLUCOSE_TTL_SEC * 1000
    private const val MEASURED_AHEAD_MS = SummaryLimits.MEASURED_AHEAD_MAX_SEC * 1000
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun build(input: SummaryInput, nowMs: Long, zone: ZoneId): SummaryDto {
        // 초로 내려 비교한다. 밀리초를 남긴 채 내림하면 valid_until이 generated_at과 같아지는 틈이 생긴다
        val generated = toSecondMs(nowMs)
        val glucose = input.glucose?.takeIf { isSendable(it, generated) }
        val measured = glucose?.let { toSecondMs(it.measuredAtMs) }
        val validUntil = if (measured == null) generated + TTL_MS else minOf(generated + TTL_MS, measured + TTL_MS)
        val sleepUntil = input.sleep?.let { sleepValidUntil(it, generated, zone) }
        val sleep = input.sleep?.takeIf { sleepUntil != null && it.minutes in 0..SummaryLimits.SLEEP_MAX_MIN }

        return SummaryDto(
            generatedAt = format(generated, zone),
            validUntil = format(validUntil, zone),
            sleepMinutesLastNight = sleep?.minutes,
            sleepShort = sleep?.let { SleepPolicy.isShort(it.minutes) },
            sleepValidUntil = sleep?.let { format(checkNotNull(sleepUntil), zone) },
            glucoseMmol = glucose?.let { floorToHundredths(it.mmol) },
            glucoseTrend = glucose?.trend,
            glucoseSource = glucose?.source,
            glucoseMeasuredAt = measured?.let { format(it, zone) },
        )
    }

    /**
     * 만료는 값을 계산한 시각 기준이다. generated_at 기준으로 다시 재면 어제 읽은 값이 전송할 때마다
     * 다음 날로 연장되어, 안 찬 밤의 수면이 "어젯밤 수면"으로 나간다.
     */
    private fun sleepValidUntil(sleep: SleepValue, generatedMs: Long, zone: ZoneId): Long? {
        val computed = minOf(sleep.computedAtMs, generatedMs)
        val until = SleepPolicy.validUntil(offsetAt(computed, zone), zone).toInstant().toEpochMilli()
        return until.takeIf { it > generatedMs }
    }

    private fun isSendable(reading: GlucoseReading, generatedMs: Long): Boolean {
        val measured = toSecondMs(reading.measuredAtMs)
        if (generatedMs >= measured + TTL_MS) return false            // 측정 후 15분이 지난 값은 보내지 않는다
        if (measured > generatedMs + MEASURED_AHEAD_MS) return false  // 시계가 되감긴 경우. 서버가 거절할 값이다
        val mmol = floorToHundredths(reading.mmol)
        return mmol >= SummaryLimits.GLUCOSE_MIN && mmol <= SummaryLimits.GLUCOSE_MAX
    }

    // 소수 둘째 자리까지만 허용된다(표 10번). 내림이라 저혈당 쪽 값이 위로 넘어가지 않는다
    private fun floorToHundredths(mmol: Double): Double = floor(mmol * 100 + 1e-9) / 100

    private fun toSecondMs(ms: Long): Long = Math.floorDiv(ms, 1000L) * 1000

    private fun offsetAt(ms: Long, zone: ZoneId): OffsetDateTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    private fun format(ms: Long, zone: ZoneId): String = FORMAT.format(offsetAt(ms, zone))
}
