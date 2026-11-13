package com.conditiondrive.core.contract

import com.conditiondrive.core.engine.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappersTest {
    private val full = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_full.json"))
    private val serverNow = ContractFixtures.ms("2026-11-20T08:13:00+09:00")
    private val engineNow = 5_000_000L

    /** V14. 엔진 시계가 서버 시계와 전혀 달라도 남은 시간은 같다 */
    @Test
    fun expiryComesFromServerDifferenceNotDeviceClock() {
        val input = Mappers.toEngineInput(full, serverNow, engineNow)
        val remainingMs = ContractFixtures.ms(full.validUntil) - serverNow
        assertEquals(engineNow + remainingMs, input.glucoseExpiresAtMs)
        assertEquals(engineNow - 90_000, input.glucoseMeasuredAtMs)
        assertEquals(engineNow + (ContractFixtures.ms(full.sleepValidUntil!!) - serverNow), input.sleepExpiresAtMs)

        val shifted = Mappers.toEngineInput(full, serverNow, engineNow + 1_000_000)
        assertEquals(input.glucoseExpiresAtMs!! + 1_000_000, shifted.glucoseExpiresAtMs)
    }

    @Test
    fun mapsValues() {
        val input = Mappers.toEngineInput(full, serverNow, engineNow)
        assertEquals(290, input.sleepMinutes)
        assertEquals(5.4, input.glucoseMmol)
        assertEquals(Trend.FALLING, input.glucoseTrend)
        assertTrue(input.glucoseSimulated)
    }

    @Test
    fun everyTrendMaps() {
        val expected = mapOf(
            GlucoseTrendDto.FALLING_FAST to Trend.FALLING_FAST, GlucoseTrendDto.FALLING to Trend.FALLING,
            GlucoseTrendDto.FLAT to Trend.FLAT, GlucoseTrendDto.RISING to Trend.RISING,
        )
        for ((dto, trend) in expected) {
            assertEquals(trend, Mappers.toEngineInput(full.copy(glucoseTrend = dto), serverNow, engineNow).glucoseTrend)
        }
        assertEquals(GlucoseTrendDto.entries.size, expected.size)
    }

    @Test
    fun partnerSourceIsNotSimulated() {
        val input = Mappers.toEngineInput(full.copy(glucoseSource = GlucoseSourceDto.PARTNER_REALTIME), serverNow, engineNow)
        assertFalse(input.glucoseSimulated)
        assertEquals(5.4, input.glucoseMmol)
    }

    @Test
    fun nullTrendWithValueStaysNull() {
        val input = Mappers.toEngineInput(full.copy(glucoseTrend = null), serverNow, engineNow)
        assertEquals(5.4, input.glucoseMmol)
        assertNull(input.glucoseTrend)
    }

    @Test
    fun allNullSummaryMapsToAllNull() {
        val allNull = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_all_null.json"))
        val input = Mappers.toEngineInput(allNull, serverNow, engineNow)
        assertNull(input.sleepMinutes)
        assertNull(input.sleepExpiresAtMs)
        assertNull(input.glucoseMmol)
        assertNull(input.glucoseTrend)
        assertNull(input.glucoseMeasuredAtMs)
        assertNull(input.glucoseExpiresAtMs)
        assertFalse(input.glucoseSimulated)
    }

    /** 12.8. 혈당만 지났으면 혈당만 버리고 수면은 쓴다 */
    @Test
    fun expiredGlucoseIsDroppedAndSleepKept() {
        val input = Mappers.toEngineInput(full, ContractFixtures.ms(full.validUntil), engineNow)
        assertNull(input.glucoseMmol)
        assertNull(input.glucoseExpiresAtMs)
        assertFalse(input.glucoseSimulated)
        assertEquals(290, input.sleepMinutes)
    }

    @Test
    fun expiredSleepIsDroppedAndGlucoseKept() {
        val input = Mappers.toEngineInput(full.copy(sleepValidUntil = "2026-11-20T08:12:30+09:00"), serverNow, engineNow)
        assertNull(input.sleepMinutes)
        assertNull(input.sleepExpiresAtMs)
        assertEquals(5.4, input.glucoseMmol)
    }

    /** 끊긴 센서의 오래된 값이 valid_until만 믿고 새 값처럼 쓰이면 안 된다 */
    @Test
    fun expiryIsCappedAtMeasuredPlusTtl() {
        val staleMeasure = full.copy(glucoseMeasuredAt = "2026-11-20T08:00:00+09:00", validUntil = "2026-11-20T08:26:30+09:00")
        val input = Mappers.toEngineInput(staleMeasure, serverNow, engineNow)
        val cap = ContractFixtures.ms("2026-11-20T08:15:00+09:00")
        assertEquals(engineNow + (cap - serverNow), input.glucoseExpiresAtMs)
    }

    @Test
    fun malformedGlucoseIsTreatedAsNoGlucose() {
        for (dto in listOf(full.copy(glucoseMeasuredAt = null), full.copy(glucoseMeasuredAt = "어제"), full.copy(glucoseSource = null), full.copy(validUntil = "어제"))) {
            val input = Mappers.toEngineInput(dto, serverNow, engineNow)
            assertNull(input.glucoseMmol, "$dto")
            assertNull(input.glucoseExpiresAtMs, "$dto")
        }
    }

    @Test
    fun scenarioOverloadUsesEngineClockAsServerClock() {
        val now = ContractFixtures.ms("2026-11-20T08:13:00+09:00")
        val input = Mappers.toEngineInput(full, now)
        assertEquals(ContractFixtures.ms(full.validUntil), input.glucoseExpiresAtMs)
        assertEquals(ContractFixtures.ms(full.glucoseMeasuredAt!!), input.glucoseMeasuredAtMs)
    }

    /** 12.8 표: 마스킹 응답은 차량에서 수면만 남는다 */
    @Test
    fun maskedLatestFixtureMapsToSleepOnly() {
        val masked = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>(ContractFixtures.text("latest_masked.json"))
        val input = Mappers.toEngineInput(masked.summary!!, ContractFixtures.ms(masked.serverTime), engineNow)
        assertEquals(290, input.sleepMinutes)
        assertNull(input.glucoseMmol)
    }
}
