package com.conditiondrive.companion

import com.conditiondrive.companion.upload.GlucoseReading
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.companion.upload.SummaryBuilder
import com.conditiondrive.companion.upload.SummaryInput
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryLimits
import com.conditiondrive.core.contract.SummaryValidator
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryBuilderTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun ms(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()
    private val now = ms("2026-11-20T08:12:00+09:00")
    private val measured = ms("2026-11-20T08:11:30+09:00")
    private fun reading(mmol: Double = 5.4, at: Long = measured, trend: GlucoseTrendDto? = GlucoseTrendDto.FALLING) =
        GlucoseReading(mmol, trend, at)

    private fun fixture(name: String): SummaryDto {
        val text = checkNotNull(javaClass.getResourceAsStream("/contract/$name")) { name }.bufferedReader().readText()
        return ContractJson.Strict.decodeFromString(SummaryDto.serializer(), text)
    }

    private fun assertValid(dto: SummaryDto, at: Long = now) {
        assertEquals(emptyList(), SummaryValidator.validate(dto, at, allowPartner = false))
    }

    /** 12.7 예시이자 서버·차량 테스트가 쓰는 골든 픽스처. 폰이 만든 값이 그 파일과 글자까지 같아야 한다 */
    @Test
    fun reproducesGoldenFullFixture() {
        val dto = SummaryBuilder.build(SummaryInput(SleepValue(290, now), reading()), now, seoul)
        assertEquals(fixture("summary_full.json"), dto)
        assertValid(dto)
    }

    @Test
    fun glucoseOnlyAndAllNullMatchFixtureShape() {
        val fixtureGlucose = fixture("summary_glucose_only.json")
        val fixtureNow = ms(fixtureGlucose.generatedAt)
        val glucoseOnly = SummaryBuilder.build(
            SummaryInput(null, GlucoseReading(checkNotNull(fixtureGlucose.glucoseMmol), fixtureGlucose.glucoseTrend, ms(checkNotNull(fixtureGlucose.glucoseMeasuredAt)))),
            fixtureNow,
            seoul,
        )
        assertEquals(fixtureGlucose, glucoseOnly)
        assertValid(glucoseOnly, fixtureNow)
        assertNull(glucoseOnly.sleepMinutesLastNight)
        assertNull(glucoseOnly.sleepShort)
        assertNull(glucoseOnly.sleepValidUntil)

        val fixtureNull = fixture("summary_all_null.json")
        assertEquals(fixtureNull, SummaryBuilder.build(SummaryInput(null, null), ms(fixtureNull.generatedAt), seoul))

        val allNull = SummaryBuilder.build(SummaryInput(null, null), now, seoul)
        assertEquals("2026-11-20T08:12:00+09:00", allNull.generatedAt)
        assertEquals("2026-11-20T08:27:00+09:00", allNull.validUntil)  // 혈당이 null이어도 900초로 채운다(12.7)
        assertNull(allNull.glucoseMmol)
        assertNull(allNull.glucoseSource)
        assertNull(allNull.glucoseMeasuredAt)
        assertNull(allNull.glucoseTrend)
        assertValid(allNull)
    }

    /** 12.7 "모든 키가 항상 있어야" 한다. null을 생략하면 서버가 required로 거절한다 */
    @Test
    fun encodedJsonAlwaysHasExactlyNineScalarKeys() {
        for (input in listOf(SummaryInput(null, null), SummaryInput(SleepValue(290, now), reading()), SummaryInput(null, reading()))) {
            val text = ContractJson.Strict.encodeToString(SummaryDto.serializer(), SummaryBuilder.build(input, now, seoul))
            val obj = ContractJson.Strict.parseToJsonElement(text).jsonObject
            assertEquals(SummaryLimits.ALLOWED_KEYS, obj.keys)
            assertEquals(emptyList(), SummaryValidator.validateRawKeys(obj))
            assertTrue(text.toByteArray().size < SummaryLimits.BODY_MAX_BYTES)
        }
    }

    @Test
    fun validUntilIsCappedByMeasurement() {
        val dto = SummaryBuilder.build(SummaryInput(null, reading()), now, seoul)
        assertEquals("2026-11-20T08:26:30+09:00", dto.validUntil)
        assertEquals("2026-11-20T08:11:30+09:00", dto.glucoseMeasuredAt)
    }

    /** 11.3 측정 후 15분이 지난 값은 보내지 않는다 */
    @Test
    fun glucoseExpiresExactlyAtFifteenMinutes() {
        val just = SummaryBuilder.build(SummaryInput(null, reading(at = now - 899_000)), now, seoul)
        assertNotNull(just.glucoseMmol)
        assertValid(just)
        assertEquals("2026-11-20T08:12:01+09:00", just.validUntil)

        val expired = SummaryBuilder.build(SummaryInput(null, reading(at = now - 900_000)), now, seoul)
        assertNull(expired.glucoseMmol)
        assertNull(expired.glucoseTrend)
        assertNull(expired.glucoseSource)
        assertNull(expired.glucoseMeasuredAt)
        assertEquals("2026-11-20T08:27:00+09:00", expired.validUntil)
        assertValid(expired)
    }

    /** 밀리초가 섞여도 valid_until이 generated_at과 같아지지 않는다 */
    @Test
    fun subSecondTimesStayValid() {
        val measuredWithMs = now - 899_000 - 900
        val at = now + 500
        val dto = SummaryBuilder.build(SummaryInput(null, reading(at = measuredWithMs)), at, seoul)
        assertEquals(null, dto.glucoseMmol)
        assertValid(dto, at)
    }

    @Test
    fun futureMeasurementBeyondSixtySecondsIsDropped() {
        assertNotNull(SummaryBuilder.build(SummaryInput(null, reading(at = now + 60_000)), now, seoul).glucoseMmol)
        assertNull(SummaryBuilder.build(SummaryInput(null, reading(at = now + 61_000)), now, seoul).glucoseMmol)
    }

    @Test
    fun outOfRangeGlucoseIsNotSent() {
        assertNull(SummaryBuilder.build(SummaryInput(null, reading(mmol = 0.99)), now, seoul).glucoseMmol)
        assertNull(SummaryBuilder.build(SummaryInput(null, reading(mmol = 33.31)), now, seoul).glucoseMmol)
        assertEquals(1.0, SummaryBuilder.build(SummaryInput(null, reading(mmol = 1.0)), now, seoul).glucoseMmol)
        assertEquals(33.3, SummaryBuilder.build(SummaryInput(null, reading(mmol = 33.3)), now, seoul).glucoseMmol)
    }

    /** 3.999가 4.0으로 올라가면 STOP이 사라진다 */
    @Test
    fun glucoseIsFlooredNeverRoundedUp() {
        assertEquals(3.99, SummaryBuilder.build(SummaryInput(null, reading(mmol = 3.999)), now, seoul).glucoseMmol)
        assertEquals(4.0, SummaryBuilder.build(SummaryInput(null, reading(mmol = 4.0)), now, seoul).glucoseMmol)
        assertEquals(5.4, SummaryBuilder.build(SummaryInput(null, reading(mmol = 5.4)), now, seoul).glucoseMmol)
        for (hundredths in 100..3330) {
            val mmol = hundredths / 100.0
            assertEquals(mmol, SummaryBuilder.build(SummaryInput(null, reading(mmol = mmol)), now, seoul).glucoseMmol)
        }
    }

    @Test
    fun trendMayBeNullWithValue() {
        val dto = SummaryBuilder.build(SummaryInput(null, reading(trend = null)), now, seoul)
        assertEquals(5.4, dto.glucoseMmol)
        assertNull(dto.glucoseTrend)
        assertEquals(GlucoseSourceDto.SIMULATED, dto.glucoseSource)
        assertValid(dto)
    }

    @Test
    fun sleepShortBoundary() {
        assertEquals(true, SummaryBuilder.build(SummaryInput(SleepValue(359, now), null), now, seoul).sleepShort)
        assertEquals(false, SummaryBuilder.build(SummaryInput(SleepValue(360, now), null), now, seoul).sleepShort)
        assertEquals(true, SummaryBuilder.build(SummaryInput(SleepValue(0, now), null), now, seoul).sleepShort)
    }

    /** 어제 읽은 값이 전송 시각 기준으로 계속 연장되면, 안 찬 밤이 어젯밤 수면으로 나간다 */
    @Test
    fun sleepExpiresFromComputedTimeNotFromGeneratedTime() {
        val computed = ms("2026-11-20T08:00:00+09:00")
        val sleep = SleepValue(290, computed)

        val sameDay = SummaryBuilder.build(SummaryInput(sleep, null), ms("2026-11-20T22:00:00+09:00"), seoul)
        assertEquals("2026-11-21T04:00:00+09:00", sameDay.sleepValidUntil)

        val beforeBoundary = SummaryBuilder.build(SummaryInput(sleep, null), ms("2026-11-21T03:59:59+09:00"), seoul)
        assertEquals(290, beforeBoundary.sleepMinutesLastNight)
        assertEquals("2026-11-21T04:00:00+09:00", beforeBoundary.sleepValidUntil)

        val atBoundary = SummaryBuilder.build(SummaryInput(sleep, null), ms("2026-11-21T04:00:00+09:00"), seoul)
        assertNull(atBoundary.sleepMinutesLastNight)
        assertNull(atBoundary.sleepShort)
        assertNull(atBoundary.sleepValidUntil)
    }

    /** C-36: 경계까지 6시간 미만이면 다음 날로 */
    @Test
    fun sleepReadLateAtNightRollsToNextBoundary() {
        val at = ms("2026-11-20T23:00:00+09:00")
        val dto = SummaryBuilder.build(SummaryInput(SleepValue(290, at), null), at, seoul)
        assertEquals("2026-11-22T04:00:00+09:00", dto.sleepValidUntil)
        assertValid(dto, at)
    }

    @Test
    fun sleepFromFutureClockStaysInsideThirtyHours() {
        val dto = SummaryBuilder.build(SummaryInput(SleepValue(290, now + 100 * 3_600_000), null), now, seoul)
        assertValid(dto)
    }

    @Test
    fun invalidSleepMinutesAreNotSent() {
        assertNull(SummaryBuilder.build(SummaryInput(SleepValue(1441, now), null), now, seoul).sleepMinutesLastNight)
        assertNull(SummaryBuilder.build(SummaryInput(SleepValue(-1, now), null), now, seoul).sleepMinutesLastNight)
    }

    /** 어떤 입력 조합이든 서버 검증표를 통과한다. 거절되면 차량이 끊김으로 착각한다 */
    @Test
    fun everyCombinationPassesServerValidation() {
        val random = Random(20261120)
        val zones = listOf("Asia/Seoul", "UTC", "America/New_York", "Asia/Kathmandu", "Pacific/Chatham").map(ZoneId::of)
        repeat(5000) {
            val zone = zones.random(random)
            val at = now + random.nextLong(0, 400L * 86_400_000) + random.nextInt(1000)
            val glucose = if (random.nextBoolean()) null else GlucoseReading(
                mmol = random.nextDouble(0.5, 35.0),
                trend = GlucoseTrendDto.entries.plus<GlucoseTrendDto?>(null).random(random),
                measuredAtMs = at + random.nextLong(-1_200_000, 120_000),
            )
            val sleep = if (random.nextBoolean()) null else SleepValue(random.nextInt(-5, 1500), at - random.nextLong(-3_600_000, 200_000_000))
            val dto = SummaryBuilder.build(SummaryInput(sleep, glucose), at, zone)
            assertEquals(emptyList(), SummaryValidator.validate(dto, at, allowPartner = false), "입력 $sleep $glucose $zone")
        }
    }

    @Test
    fun shiftedMovesBothTimes() {
        val shifted = SummaryInput(SleepValue(290, 1000), GlucoseReading(5.0, null, 2000)).shifted(500)
        assertEquals(1500, shifted.sleep?.computedAtMs)
        assertEquals(2500, shifted.glucose?.measuredAtMs)
    }
}
