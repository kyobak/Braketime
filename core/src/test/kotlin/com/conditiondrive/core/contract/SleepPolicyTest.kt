package com.conditiondrive.core.contract

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepPolicyTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun until(generated: String) = SleepPolicy.validUntil(OffsetDateTime.parse(generated), seoul)

    @Test
    fun isShortBoundaryIs360() {
        assertTrue(SleepPolicy.isShort(359))
        assertFalse(SleepPolicy.isShort(360))
        assertTrue(SleepPolicy.isShort(290))
    }

    @Test
    fun morningGeneratesNextBoundary() {
        assertEquals(OffsetDateTime.parse("2026-11-21T04:00:00+09:00"), until("2026-11-20T08:12:00+09:00"))
    }

    @Test
    fun underSixHoursSkipsToFollowingBoundary() {
        assertEquals(OffsetDateTime.parse("2026-11-21T04:00:00+09:00"), until("2026-11-20T01:00:00+09:00"))
        assertEquals(OffsetDateTime.parse("2026-11-21T04:00:00+09:00"), until("2026-11-20T03:59:59+09:00"))
        // 정확히 6시간은 "미만"이 아니라 그 경계를 쓴다
        assertEquals(OffsetDateTime.parse("2026-11-20T04:00:00+09:00"), until("2026-11-19T22:00:00+09:00"))
        assertEquals(OffsetDateTime.parse("2026-11-22T04:00:00+09:00"), until("2026-11-20T22:00:01+09:00"))
    }

    @Test
    fun exactlyAtBoundaryMeansNextDay() {
        assertEquals(OffsetDateTime.parse("2026-11-21T04:00:00+09:00"), until("2026-11-20T04:00:00+09:00"))
    }

    @Test
    fun otherOffsetsAreConvertedToZoneFirst() {
        assertEquals(OffsetDateTime.parse("2026-11-21T04:00:00+09:00"), until("2026-11-19T23:12:00Z"))
    }

    /** 12.7 표 9번 "30시간 이하"를 어떤 생성 시각에서도 만족해야 폰 값이 거절되지 않는다 */
    @Test
    fun resultAlwaysFitsValidatorLimit() {
        val start = OffsetDateTime.parse("2026-11-20T00:00:00+09:00")
        for (minutes in 0L until 24 * 60) {
            val generated = start.plusMinutes(minutes)
            val duration = Duration.between(generated, SleepPolicy.validUntil(generated, seoul))
            assertTrue(duration.seconds in 1..SummaryLimits.SLEEP_VALID_MAX_SEC, "$generated → $duration")
        }
    }
}
