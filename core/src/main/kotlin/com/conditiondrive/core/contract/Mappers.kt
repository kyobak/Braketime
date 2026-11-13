package com.conditiondrive.core.contract

import com.conditiondrive.core.engine.ConditionInput
import com.conditiondrive.core.engine.Trend

object Mappers {
    /** 서버 응답 기준 잔여 시간으로 엔진 시계의 만료 시각을 만든다. */
    fun toEngineInput(dto: SummaryDto, serverTimeMs: Long, engineNowMs: Long): ConditionInput {
        // 기기 벽시계가 틀려도 만료가 어긋나지 않게 서버 시각과의 차이만 엔진 시계로 옮긴다
        fun onEngineClock(serverMs: Long) = engineNowMs + (serverMs - serverTimeMs)

        val sleepUntil = dto.sleepValidUntil?.let(Rfc3339::parseMs)?.takeIf { it > serverTimeMs }
        val sleepMinutes = dto.sleepMinutesLastNight?.takeIf { sleepUntil != null }

        val measured = dto.glucoseMeasuredAt?.let(Rfc3339::parseMs)
        val validUntil = Rfc3339.parseMs(dto.validUntil)
        // 판독 후 TTL을 넘긴 값이 valid_until만 믿고 새 값처럼 쓰이지 않게 측정 시각으로도 자른다
        val glucoseUntil = if (measured == null || validUntil == null) null else {
            minOf(validUntil, measured + SummaryLimits.GLUCOSE_TTL_SEC * 1000).takeIf { it > serverTimeMs }
        }
        val glucoseValid = dto.glucoseMmol != null && dto.glucoseSource != null && glucoseUntil != null

        return ConditionInput(
            sleepMinutes = sleepMinutes,
            sleepExpiresAtMs = sleepUntil?.takeIf { sleepMinutes != null }?.let(::onEngineClock),
            glucoseMmol = dto.glucoseMmol.takeIf { glucoseValid },
            glucoseTrend = dto.glucoseTrend.takeIf { glucoseValid }?.toEngine(),
            glucoseMeasuredAtMs = measured.takeIf { glucoseValid }?.let(::onEngineClock),
            glucoseExpiresAtMs = glucoseUntil.takeIf { glucoseValid }?.let(::onEngineClock),
            glucoseSimulated = glucoseValid && dto.glucoseSource == GlucoseSourceDto.SIMULATED,
        )
    }

    /** 내장 시나리오용. 요약의 시각이 이미 엔진 시계 기준이다 */
    fun toEngineInput(dto: SummaryDto, engineNowMs: Long): ConditionInput = toEngineInput(dto, engineNowMs, engineNowMs)

    private fun GlucoseTrendDto.toEngine(): Trend = when (this) {
        GlucoseTrendDto.FALLING_FAST -> Trend.FALLING_FAST
        GlucoseTrendDto.FALLING -> Trend.FALLING
        GlucoseTrendDto.FLAT -> Trend.FLAT
        GlucoseTrendDto.RISING -> Trend.RISING
    }
}
