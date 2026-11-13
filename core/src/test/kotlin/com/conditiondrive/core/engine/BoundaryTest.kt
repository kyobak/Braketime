package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** test-scenarios.md G4(3.3, 5.2 경계 ±1초)와 D4 표. */
class BoundaryTest {

    private fun fatigueAt(sec: Long, sleep: Int? = null): Level {
        val d = Driver()
        d.startDriving()
        if (sleep != null) d.sleep(sleep)
        d.advance(sec)
        return d.state.fatigue
    }

    @Test
    fun g4DefaultThresholds() {
        assertEquals(Level.NONE, fatigueAt(6299))
        assertEquals(Level.CAUTION, fatigueAt(6300))
        assertEquals(Level.CAUTION, fatigueAt(6301))
        assertEquals(Level.CAUTION, fatigueAt(7199))
        assertEquals(Level.STOP, fatigueAt(7200))
        assertEquals(Level.STOP, fatigueAt(7201))
    }

    @Test
    fun g4ShortSleepThresholds() {
        assertEquals(Level.NONE, fatigueAt(4499, sleep = 290))
        assertEquals(Level.CAUTION, fatigueAt(4500, sleep = 290))
        assertEquals(Level.CAUTION, fatigueAt(5399, sleep = 290))
        assertEquals(Level.STOP, fatigueAt(5400, sleep = 290))
    }

    @Test
    fun g4SleepMinutesBoundary() {
        for ((minutes, caution) in listOf(359 to 4500L, 360 to 6300L, 361 to 6300L)) {
            val d = Driver()
            d.sleep(minutes)
            assertEquals(caution, d.view.cautionThresholdSec, "수면 $minutes")
        }
    }

    @Test
    fun g4RestBoundary() {
        fun cdsAfterRest(restSec: Long): Long {
            val d = Driver()
            d.startDriving()
            d.advance(3600)
            d.park()
            d.advance(restSec)
            return d.view.continuousDriveSec
        }
        assertEquals(3600, cdsAfterRest(899))
        assertEquals(0, cdsAfterRest(900))
        assertEquals(0, cdsAfterRest(901))
    }

    @Test
    fun g4DepartConfirmBoundary() {
        fun driving(sec: Long, speed: Double): Boolean {
            val d = Driver()
            d.startDriving()
            d.advance(600)
            d.park()
            d.advance(60)
            d.drive(speed)
            d.advance(sec)
            return d.view.driving
        }
        assertFalse(driving(29, 5.0))
        assertTrue(driving(30, 5.0))
        assertFalse(driving(0, 9.99))
        assertTrue(driving(0, 10.0))
    }

    @Test
    fun departWithoutSpeedUsesThirtySecondsOnly() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        d.park()
        d.advance(60)
        d.vehicle(Gear.DRIVE, null)
        d.advance(29)
        assertFalse(d.view.driving)
        d.advance(1)
        assertTrue(d.view.driving)
        assertEquals(630, d.view.continuousDriveSec)
    }

    private fun d4(mmol: Double, trend: Trend?): Level {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(mmol, trend)
        return d.state.glucose
    }

    @Test
    fun d4ValueBoundaries() {
        val rows = listOf(
            Triple(4.0, Trend.RISING, Level.NONE),
            Triple(4.0, Trend.FLAT, Level.CAUTION),
            Triple(3.99, Trend.RISING, Level.STOP),
            Triple(5.0, Trend.FLAT, Level.NONE),
            Triple(4.99, Trend.FLAT, Level.CAUTION),
            Triple(5.7, Trend.FALLING, Level.NONE),
            Triple(5.69, Trend.FALLING, Level.CAUTION),
            Triple(7.3, Trend.FALLING_FAST, Level.NONE),
            Triple(7.29, Trend.FALLING_FAST, Level.CAUTION),
            Triple(4.5, Trend.RISING, Level.NONE),
            Triple(4.5, null, Level.CAUTION),
        )
        for ((mmol, trend, expected) in rows) assertEquals(expected, d4(mmol, trend), "$mmol $trend")
    }

    private fun recovering(): Driver {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8)
        d.park()
        return d
    }

    @Test
    fun d4TimerStartsAtFiveExactly() {
        val d = recovering()
        d.advance(60)
        d.glucose(4.99)
        assertEquals(null, d.view.glucoseRecoveryRemainingSec)
        d.advance(60)
        d.glucose(5.0)
        assertEquals(2400, d.view.glucoseRecoveryRemainingSec)
    }

    @Test
    fun d4TimerExpiresAtFortyMinutesExactly() {
        val d = recovering()
        d.advance(60)
        d.glucose(5.0, Trend.RISING, ttlSec = 3 * 3600)
        d.spoken.clear()
        d.advance(2399)
        assertEquals(Level.STOP, d.state.glucose)
        assertEquals(1, d.view.glucoseRecoveryRemainingSec)
        d.advance(1)
        assertEquals(Level.NONE, d.state.glucose)
        assertEquals(listOf(VoiceId.V16), d.spoken)
    }

    @Test
    fun glucoseTtlBoundary() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = 900)
        d.advance(899)
        assertEquals(6.4, d.view.glucoseMmol)
        d.advance(1)
        assertEquals(null, d.view.glucoseMmol)
        assertTrue(d.view.glucoseUnavailable)
    }

    @Test
    fun alreadyExpiredInputCountsAsNoValue() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.0, ttlSec = 900, measuredAt = d.now - 900_000)
        assertEquals(Level.NONE, d.state.glucose)
    }

    @Test
    fun rearmBoundary() {
        fun lostAgain(validSec: Long): Int {
            val d = Driver()
            d.startDriving()
            d.glucose(6.0)
            d.advance(60)
            d.send(ConditionReceived(d.now, null))
            d.advance(60)
            d.glucose(6.0)
            d.spoken.clear()
            d.advance(validSec)
            d.send(ConditionReceived(d.now, null))
            return d.spoken.count { it == VoiceId.V19 }
        }
        assertEquals(0, lostAgain(599))
        assertEquals(1, lostAgain(600))
    }

    @Test
    fun resumeGapBoundary() {
        fun afterGap(gapSec: Long): List<VoiceId> {
            val d = recovering()
            d.advance(60)
            d.glucose(5.2, Trend.RISING, ttlSec = 60)
            d.advance(60)
            d.spoken.clear()
            d.advance(gapSec)
            d.glucose(5.3, Trend.RISING)
            return d.spoken.toList()
        }
        assertEquals(emptyList(), afterGap(900))
        assertEquals(listOf(VoiceId.V15), afterGap(901))
    }

    /** D-32. C-34 시간 해제를 지웠다. 공백이 며칠이어도 값을 확인하지 못했으면 래치다 */
    @Test
    fun latchSurvivesAnyGap() {
        fun afterGap(gapSec: Long): Pair<Boolean, Level> {
            val d = Driver()
            d.startDriving()
            d.advance(60)
            d.glucose(3.8)
            d.park()
            val readAt = d.now
            d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
            d.now = readAt + gapSec * 1000
            d.park()
            d.advance(5)
            return d.state.glucoseLatched to d.state.glucose
        }
        // 재평가 시각은 시동 5초 뒤다. 옛 C-34 경계(2395초)와 하루, 사흘을 본다
        for (gap in listOf(2394L, 2395L, 86_400L, 3 * 86_400L)) {
            assertEquals(true to Level.NONE, afterGap(gap), "gap=$gap")
        }
    }

    @Test
    fun nightWindowBoundary() {
        fun v03At(start: String): Boolean {
            val d = Driver(start = start)
            d.startDriving()
            d.advance(3600)
            return VoiceId.V03 in d.spoken
        }
        // 출발 시각이 아니라 60분에 닿은 현지 시각으로 본다. 시작 시각은 V01 전 5초를 뺀 값이다.
        assertFalse(v03At("2026-11-20T22:59:54+09:00"))
        assertTrue(v03At("2026-11-20T22:59:55+09:00"))
        assertTrue(v03At("2026-11-20T04:59:54+09:00"))
        assertFalse(v03At("2026-11-20T04:59:55+09:00"))
    }
}
