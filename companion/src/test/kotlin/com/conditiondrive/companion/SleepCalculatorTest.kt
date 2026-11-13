package com.conditiondrive.companion

import com.conditiondrive.companion.sleep.SleepCalculator
import com.conditiondrive.companion.sleep.SleepSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SleepCalculatorTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_000 * hour

    private fun span(startHoursAgo: Double, endHoursAgo: Double) =
        SleepSpan(now - (startHoursAgo * hour).toLong(), now - (endHoursAgo * hour).toLong())

    /** F6: 세션이 없으면 null. 0분이 아니다. 시계를 안 차고 잔 밤을 수면 부족으로 만들지 않는다 */
    @Test
    fun noSessionsIsNullNotZero() {
        assertNull(SleepCalculator.minutesLast24h(emptyList(), now))
        assertNull(SleepCalculator.valueFrom(emptyList(), now))
    }

    @Test
    fun oneNight() {
        assertEquals(290, SleepCalculator.minutesLast24h(listOf(SleepSpan(now - 290 * minute, now)), now))
    }

    @Test
    fun separateSessionsAdd() {
        assertEquals(6 * 60, SleepCalculator.minutesLast24h(listOf(span(10.0, 6.0), span(3.0, 1.0)), now))
    }

    /** 시계와 폰이 같은 밤을 기록해도 두 배가 되지 않는다. 수면 부족을 가리면 안 된다 */
    @Test
    fun overlappingSourcesAreUnioned() {
        assertEquals(5 * 60, SleepCalculator.minutesLast24h(listOf(span(8.0, 3.0), span(8.0, 3.0)), now))
        assertEquals(6 * 60, SleepCalculator.minutesLast24h(listOf(span(8.0, 4.0), span(6.0, 2.0)), now))
        assertEquals(5 * 60, SleepCalculator.minutesLast24h(listOf(span(8.0, 3.0), span(6.0, 5.0)), now))
    }

    @Test
    fun orderDoesNotMatter() {
        assertEquals(6 * 60, SleepCalculator.minutesLast24h(listOf(span(3.0, 1.0), span(10.0, 6.0)), now))
    }

    @Test
    fun clipsToWindow() {
        assertEquals(2 * 60, SleepCalculator.minutesLast24h(listOf(span(26.0, 22.0)), now))
        assertEquals(60, SleepCalculator.minutesLast24h(listOf(SleepSpan(now - hour, now + 5 * hour)), now))
    }

    @Test
    fun sessionsOutsideWindowAreNull() {
        assertNull(SleepCalculator.minutesLast24h(listOf(span(30.0, 25.0)), now))
        assertNull(SleepCalculator.minutesLast24h(listOf(SleepSpan(now + hour, now + 2 * hour)), now))
        assertNull(SleepCalculator.minutesLast24h(listOf(SleepSpan(now - hour, now - hour)), now))
    }

    @Test
    fun maximumIsFullDay() {
        assertEquals(1440, SleepCalculator.minutesLast24h(listOf(span(40.0, 0.0), span(10.0, 1.0)), now))
    }

    @Test
    fun partialMinuteFloors() {
        assertEquals(1, SleepCalculator.minutesLast24h(listOf(SleepSpan(now - 119_000, now)), now))
    }

    @Test
    fun valueRemembersWhenItWasComputed() {
        val value = SleepCalculator.valueFrom(listOf(SleepSpan(now - 290 * minute, now)), now)
        assertEquals(290, value?.minutes)
        assertEquals(now, value?.computedAtMs)
    }
}
