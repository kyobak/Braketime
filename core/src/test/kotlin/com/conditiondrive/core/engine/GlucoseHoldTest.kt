package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-31(주의 중 끊김 래치), D-32(시간 해제 삭제), D-33(E34 S5 보류 풀기). test-scenarios D6, D16, D27, D28의 엔진 몫. */
class GlucoseHoldTest {

    /** 화면이 하는 계산과 같다. 뷰는 풀 수 있는 시각을 주고 남은 초는 지금 시각으로 낸다 */
    private val Driver.holdWaitSec: Long?
        get() = view.glucoseHoldReleasableAtMs?.let { ((it - now).coerceAtLeast(0) + 999) / 1000 }

    private fun Driver.fresh() {
        spoken.clear()
        effects.clear()
    }

    /** 주행 중 5.4 falling으로 주의(V08)에 든 뒤 값이 끊긴다. 만료까지 간다 */
    private fun Driver.cautionThenLost() {
        startDriving()
        advance(600)
        glucose(5.4, Trend.FALLING, ttlSec = 300)
        assertEquals(listOf(VoiceId.V08), spoken)
        answerPrompt(PromptAnswer.DECLINE)
        fresh()
        advance(300)
    }

    // ---- D-31 ----

    @Test
    fun cautionLossLatchesWithV19WhileDriving() {
        val d = Driver()
        d.cautionThenLost()
        assertEquals(listOf(VoiceId.V19), d.spoken)
        assertTrue(d.state.glucoseLatched)
        assertTrue(d.state.glucoseLatchFromCaution)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        // 주의에는 반복 안내가 없다. 래치 뒤에도 말이 늘지 않는다
        d.fresh()
        d.advance(1200)
        assertTrue(d.spoken.isEmpty(), "${d.spoken}")
    }

    @Test
    fun cautionLossWhileParkedSaysV19R() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        d.glucose(5.4, Trend.FALLING, ttlSec = 300)
        d.answerPrompt(PromptAnswer.DECLINE)
        d.park()
        d.fresh()
        d.advance(300)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun cautionLatchSaysV19RNotV12AfterFatigueRest() {
        val d = Driver()
        d.startDriving()
        d.advance(4800)
        d.glucose(5.4, Trend.FALLING, ttlSec = 300)
        d.answerPrompt(PromptAnswer.DECLINE)
        d.advance(1500)
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(Level.CAUTION, d.state.fatigue)
        assertTrue(d.state.glucoseLatched)
        d.park()
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertFalse(VoiceId.V12 in d.spoken)
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun cautionLatchReturnInMidRangeStaysCautionNotStop() {
        val d = Driver()
        d.cautionThenLost()
        d.park()
        d.fresh()
        // STOP 래치였다면 G=STOP과 V14다. 주의에서 온 래치는 주의를 잇는다
        d.glucose(4.6, Trend.FLAT)
        assertEquals(Level.CAUTION, d.state.glucose)
        assertFalse(d.state.glucoseLatched)
        assertFalse(d.state.glucoseLatchFromCaution)
        assertTrue(d.spoken.isEmpty(), "${d.spoken}")
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun cautionLatchReturnBelowFourIsLow() {
        val d = Driver()
        d.cautionThenLost()
        d.fresh()
        d.glucose(3.8, Trend.FALLING)
        assertEquals(Level.STOP, d.state.glucose)
        assertEquals(listOf(VoiceId.V09), d.spoken)
    }

    @Test
    fun cautionLatchClearsOnlyAfterTwoGoodReads() {
        val d = Driver()
        d.cautionThenLost()
        d.fresh()
        d.glucose(6.4, Trend.FLAT)
        assertEquals(Level.CAUTION, d.state.glucose, "끊긴 뒤 첫 값 하나로는 풀지 않는다(C-29)")
        d.advance(300)
        d.glucose(6.5, Trend.FLAT)
        assertEquals(Level.NONE, d.state.glucose)
        assertEquals(DriverState.NORMAL, d.view.state)
        assertTrue(d.spoken.isEmpty(), "${d.spoken}")
    }

    @Test
    fun cautionLatchReturnDoesNotRepeatV08() {
        val d = Driver()
        d.cautionThenLost()
        d.fresh()
        d.glucose(5.3, Trend.FALLING)
        assertEquals(Level.CAUTION, d.state.glucose)
        assertTrue(d.spoken.isEmpty(), "이미 들은 V08을 다시 묻지 않는다: ${d.spoken}")
    }

    /** 끊긴 사이 5.0 아래로 내려왔으면 들은 V08은 지난 값에 대한 것이다. 출발할 때 다시 알린다 */
    @Test
    fun cautionLatchReturnWorseAsksAgainOnDepart() {
        val d = Driver()
        d.cautionThenLost()
        d.park()
        d.fresh()
        d.glucose(4.6, Trend.FLAT)
        assertTrue(d.spoken.isEmpty(), "정차 중에는 말하지 않는다: ${d.spoken}")
        d.drive()
        d.advance(30)
        assertEquals(listOf(VoiceId.V08F), d.spoken)
    }

    @Test
    fun cautionLatchReturnWorseTrendAsksWhileDriving() {
        val d = Driver()
        d.cautionThenLost()
        d.fresh()
        d.glucose(5.6, Trend.FALLING_FAST)
        assertEquals(listOf(VoiceId.V08), d.spoken)
    }

    @Test
    fun cautionLatchSurvivesCodecRoundTrip() {
        val d = Driver()
        d.cautionThenLost()
        val restored = EngineStateCodec.decode(EngineStateCodec.encode(d.state))
        assertEquals(d.state, restored)
    }

    @Test
    fun oldSaveWithoutLatchSourceDecodesAsStopLatch() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.9, ttlSec = 300)
        d.advance(300)
        val json = EngineStateCodec.encode(d.state).replace(",\"glucoseLatchFromCaution\":false", "")
        assertFalse("glucoseLatchFromCaution" in json)
        val decoded = EngineStateCodec.decode(json)
        assertEquals(d.state, decoded)
    }

    // ---- D-31 값을 받은 뒤 끊김(G=NONE) ----

    @Test
    fun lostAfterValueSaysV19RNotV12() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, Trend.FLAT, ttlSec = 300)
        d.advance(300)
        assertEquals(DriverState.NORMAL, d.view.state)
        assertFalse(d.state.glucoseLatched, "주의도 아니었으면 래치는 없다")
        d.advance(6000)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertEquals(DriverState.NORMAL, d.view.state)
    }

    @Test
    fun lostAfterValueRestartSaysV19RNotV20() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, Trend.FLAT, ttlSec = 300)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.advance(1200)
        d.fresh()
        d.park()
        d.advance(5)
        assertFalse(VoiceId.V20 in d.spoken, "${d.spoken}")
        assertTrue(VoiceId.V19R in d.spoken, "${d.spoken}")
    }

    /** 시동이 꺼진 동안 G=NONE 값이 만료됐다. 다음 시동에서 V19가 아니라 V19R이 한 번 나간다 */
    @Test
    fun lostWhileOffThenRestartSaysV19ROnce() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, Trend.FLAT, ttlSec = 900)
        d.advance(300)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 1_200_000L
        d.fresh()
        d.park()
        d.advance(5)
        assertFalse(VoiceId.V19 in d.spoken, "${d.spoken}")
        assertEquals(1, d.spoken.count { it == VoiceId.V19R }, "${d.spoken}")
        assertFalse(d.state.glucoseLatched, "G=NONE에서 끊겼으면 래치는 없다")
    }

    /** 끊김 안내 다시 알림 간격(C-35)에 걸려 만료 때 말하지 않았으면, 휴식 뒤 재시동 안내 자리에서 V19R을 말한다 */
    @Test
    fun restartNoticeSaysV19RWhenExpiryWasSilent() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, Trend.FLAT, ttlSec = 300)
        d.advance(300)
        assertTrue(VoiceId.V19 in d.spoken, "${d.spoken}")
        d.advance(6000)
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(Level.CAUTION, d.state.fatigue)
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000L
        // 시동이 꺼진 사이 값 하나가 잠깐 왔다가 끊긴다. 받은 지 10분이 안 돼 만료 안내는 다시 울리지 않는다
        d.glucose(6.4, Trend.FLAT, ttlSec = 60)
        d.now += 300_000L
        d.fresh()
        d.park()
        d.advance(5)
        assertFalse(VoiceId.V20 in d.spoken, "${d.spoken}")
        assertEquals(listOf(VoiceId.V19R), d.spoken.filter { it == VoiceId.V19R || it == VoiceId.V19 })
    }

    @Test
    fun neverReceivedStillSaysV12() {
        val d = Driver()
        d.startDriving()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V12), d.spoken)
    }

    // ---- D-32 ----

    @Test
    fun stopLatchSurvivesNextDayRestart() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 20 * 3_600_000L
        d.fresh()
        d.park()
        d.advance(5)
        assertTrue(d.state.glucoseLatched)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertTrue(VoiceId.V19R in d.spoken, "${d.spoken}")
        assertFalse(VoiceId.V19 in d.spoken, "${d.spoken}")
    }

    /** 보류가 그대로인지 본다. 시간 해제(옛 C-34)가 되살아나면 여기서 걸린다 */
    private fun Driver.assertStillHeld(at: String) {
        assertTrue(state.glucoseLatched, "$at: 시간이 지났다고 값을 확인한 것은 아니다(D-32)")
        assertEquals(Level.CAUTION, state.effectiveGlucose(), at)
        assertTrue(Reason.GLUCOSE_UNCONFIRMED in view.reasons, "$at: ${view.reasons}")
        assertTrue(view.glucoseUnavailable, "$at: S1·S2의 연결 끊김 표시도 남는다")
    }

    /** 끊김 안내는 끊긴 때 한 번이다. 보류가 이어지는 동안 회복 음성(V16 계열)이나 출발 안내(V12)로 바뀌지 않는다 */
    private fun Driver.assertNoReleaseVoice(at: String) {
        val wrong = setOf(VoiceId.V12, VoiceId.V16, VoiceId.V16C, VoiceId.V16D, VoiceId.V19, VoiceId.V19R)
        assertTrue(spoken.none { it in wrong }, "$at: $spoken")
    }

    /** 시동을 켠 채 정차해 있다. 끊긴 뒤 41분, 3시간이 같은 시동 안에서 지나도 보류는 풀리지 않는다 */
    @Test
    fun stopLatchHoldsWhileParkedWithIgnitionOn() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.park()
        d.fresh()
        d.advance(300)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        d.assertStillHeld("끊긴 때")
        d.fresh()
        d.advance(41 * 60)
        d.assertStillHeld("41분")
        assertEquals(DriverState.CAUTION, d.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        d.assertNoReleaseVoice("41분")
        d.advance(3 * 3600 - 41 * 60)
        d.assertStillHeld("3시간")
        assertEquals(DriverState.CAUTION, d.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        assertTrue(d.spoken.isEmpty(), "정차 중 보류에는 반복 안내가 없다: ${d.spoken}")
        assertEquals(Ignition.ON, d.state.ignition)
    }

    /** 주행 중에도 같다. 3시간 주행이면 피로가 따로 올라가지만 혈당 몫은 주의 보류로 남는다 */
    @Test
    fun stopLatchHoldsWhileDrivingSameIgnition() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.fresh()
        d.advance(300)
        assertTrue(VoiceId.V19 in d.spoken, "${d.spoken}")
        d.fresh()
        d.advance(41 * 60)
        d.assertStillHeld("41분")
        assertEquals(DriverState.CAUTION, d.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        assertTrue(d.spoken.isEmpty(), "주의 보류에는 반복 안내가 없다: ${d.spoken}")
        d.advance(3 * 3600 - 41 * 60)
        d.assertStillHeld("3시간")
        d.assertNoReleaseVoice("3시간")
        assertTrue(d.state.isDriving())
        assertEquals(Ignition.ON, d.state.ignition)
    }

    /** 주의에서 온 보류도 시간으로 풀리지 않는다. 주행과 정차 모두 */
    @Test
    fun cautionLatchHoldsAfterFortyMinutesAndThreeHours() {
        val driving = Driver()
        driving.cautionThenLost()
        driving.fresh()
        driving.advance(41 * 60)
        driving.assertStillHeld("주행 41분")
        assertEquals(DriverState.CAUTION, driving.view.state)
        assertTrue(driving.spoken.isEmpty(), "${driving.spoken}")
        driving.advance(3 * 3600 - 41 * 60)
        driving.assertStillHeld("주행 3시간")
        driving.assertNoReleaseVoice("주행 3시간")

        val parked = Driver()
        parked.cautionThenLost()
        parked.park()
        parked.fresh()
        parked.advance(3 * 3600)
        parked.assertStillHeld("정차 3시간")
        assertEquals(DriverState.CAUTION, parked.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), parked.view.reasons)
        assertTrue(parked.spoken.isEmpty(), "${parked.spoken}")
    }

    // ---- D-33 E34 ----

    /** 3.9를 받은 뒤 끊겨 STOP 래치가 된다. 마지막 판독에서 40분을 채워 E34를 받을 수 있게 한다 */
    private fun Driver.latchedAndParked() {
        startDriving()
        advance(60)
        glucose(3.9, ttlSec = 300)
        park()
        advance(300)
        assertTrue(state.glucoseLatched)
        advance(2100)
        assertEquals(0L, holdWaitSec)
        fresh()
    }

    @Test
    fun stopLatchRefusesReleaseWithinFortyMinutes() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.park()
        d.advance(300)
        assertTrue(d.state.glucoseLatched)
        assertEquals(2100L, d.holdWaitSec, "마지막 판독에서 40분")
        val refused = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertTrue(refused.state.glucoseLatched, "3.9를 받고 5분 만에 풀면 회복 대기를 건너뛴다")
        assertNull(refused.log)
        d.advance(2099)
        assertNull(d.engine.reduce(d.state, GlucoseHoldReleased(d.now)).log, "39:59")
        d.advance(1)
        val accepted = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, accepted.log?.event, "40:00")
        assertFalse(accepted.state.glucoseLatched)
    }

    /**
     * 40분은 측정 시각이 아니라 받은 시각(glucoseLastValidMs)부터 센다(D-33). 13분 전 측정을 받으면 하한은 받은 때에서 40분이다.
     * 끊긴 뒤 같은 판독이 다시 오면 받은 시각이 늦춰져 하한도 뒤로 밀린다. 늦게 풀리는 쪽이라 안전하다
     */
    @Test
    fun holdFloorCountsFromReceiveTime() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        val measured = d.now - 780_000L
        d.glucose(3.5, Trend.FALLING, ttlSec = 900, measuredAt = measured)
        val firstReceived = d.now
        d.park()
        d.advance(120)
        assertTrue(d.state.glucoseLatched)
        assertEquals(firstReceived + 2_400_000L, d.view.glucoseHoldReleasableAtMs, "측정 시각 기준이면 27분 뒤다")
        assertEquals(2280L, d.holdWaitSec)
        // 앱이 같은 판독을 다시 보낸다. 유효 시각(측정 + 15분)이 이미 지나 받지 않는다
        d.glucose(3.5, Trend.FALLING, ttlSec = 900, measuredAt = measured)
        assertEquals(firstReceived + 2_400_000L, d.view.glucoseHoldReleasableAtMs)
        // 유효 시각이 남은 같은 판독(서버가 만료를 늘려 줌)은 끊긴 뒤라 다시 받는다. 하한이 받은 때로 밀린다
        d.advance(60)
        d.send(
            ConditionReceived(
                d.now,
                ConditionInput(null, null, 3.5, Trend.FALLING, measured, d.now + 60_000L, true),
            ),
        )
        val again = d.now
        d.advance(60)
        assertTrue(d.state.glucoseLatched)
        assertEquals(again + 2_400_000L, d.view.glucoseHoldReleasableAtMs)
        assertNull(d.engine.reduce(d.state, GlucoseHoldReleased(firstReceived + 2_400_000L)).log, "처음 받은 때에서 40분이면 아직 이르다")
        assertEquals(
            LogEvent.GLUCOSE_HOLD_RELEASED,
            d.engine.reduce(d.state, GlucoseHoldReleased(again + 2_400_000L)).log?.event,
        )
    }

    @Test
    fun stopRefusesReleaseRightAfterGlucoseLessSummary() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9)
        d.park()
        // 폰에서 혈당 공유를 끄면 혈당 없는 요약이 온다. 값이 그 자리에서 무효가 된다
        d.sleep(420)
        assertTrue(d.view.glucoseUnavailable)
        val refused = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertNull(refused.log)
        assertTrue(refused.state.glucose == Level.STOP || refused.state.glucoseLatched)
    }

    @Test
    fun staleStopWhileIgnitionOffRefusesRelease() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 600_000L
        // 시동이 꺼져 만료가 아직 처리되지 않았다. 오래된 G=STOP을 E34가 지우면 안 된다
        assertEquals(Level.STOP, d.state.glucose)
        val refused = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertNull(refused.log)
        assertEquals(Level.STOP, refused.state.glucose)
        d.now += 1_800_000L
        val accepted = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, accepted.log?.event)
    }

    @Test
    fun cautionLatchCanBeReleasedAtOnce() {
        val d = Driver()
        d.cautionThenLost()
        d.park()
        assertEquals(0L, d.holdWaitSec, "주의에서 온 보류는 기다리지 않는다")
        val decision = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, decision.log?.event)
    }

    @Test
    fun noHoldMeansNoWaitValue() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4)
        assertNull(d.holdWaitSec, "값이 유효하면 보류가 아니다")
        val never = Driver()
        never.park()
        assertNull(never.holdWaitSec)
    }

    @Test
    fun holdReleaseClearsLatchSilentlyAndLogs() {
        val d = Driver()
        d.latchedAndParked()
        val before = d.state
        val decision = d.engine.reduce(before, GlucoseHoldReleased(d.now))
        d.state = decision.state
        assertFalse(d.state.glucoseLatched)
        assertEquals(Level.NONE, d.state.glucose)
        assertNull(d.state.glucoseLastValidMs)
        assertFalse(d.view.glucoseUnavailable, "S1·S2의 연결 끊김 표시도 내려간다")
        assertEquals(DriverState.NORMAL, d.view.state)
        assertTrue(decision.effects.none { it is Say || it is AskCheckin || it is AskStop }, "${decision.effects}")
        val log = decision.log
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, log?.event)
        assertEquals(DriverState.CAUTION, log?.from)
        assertEquals(DriverState.NORMAL, log?.to)
    }

    @Test
    fun holdReleaseLogsEvenWhenStateStaysTheSame() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.9, ttlSec = 6000)
        d.advance(6000)
        d.advance(300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.advance(900)
        d.park()
        assertEquals(DriverState.RECOVER, d.view.state)
        val decision = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertEquals(DriverState.RECOVER, decision.log?.from)
        assertEquals(DriverState.RECOVER, decision.log?.to)
        assertEquals(setOf(Reason.FATIGUE), decision.log?.reasons)
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, decision.log?.event)
    }

    @Test
    fun holdReleaseIgnoredWhileDriving() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.advance(300)
        assertTrue(d.state.glucoseLatched)
        val decision = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertTrue(decision.state.glucoseLatched)
        assertNull(decision.log)
    }

    @Test
    fun holdReleaseIgnoredWithValidValue() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.6)
        d.park()
        val decision = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertEquals(Level.STOP, decision.state.glucose, "실제로 낮은 값은 사람이 풀 수 없다")
        assertNull(decision.log)
    }

    @Test
    fun holdReleaseIgnoredWhenNoGlucoseEver() {
        val d = Driver()
        d.park()
        d.advance(5)
        val decision = d.engine.reduce(d.state, GlucoseHoldReleased(d.now))
        assertNull(decision.log)
    }

    @Test
    fun holdReleaseKeepsSleepAdvance() {
        val d = Driver()
        d.latchedAndParked()
        // 요약 한 건에 수면과 혈당이 함께 온다. 혈당이 빠진 수면 요약이다
        d.sleep(300)
        assertTrue(d.state.advanceLatched)
        assertTrue(d.state.glucoseLatched)
        d.send(GlucoseHoldReleased(d.now))
        assertTrue(d.state.advanceLatched, "E33과 달리 수면 요약은 그대로다")
        assertEquals(300, d.state.sleepMinutes)
    }

    @Test
    fun afterReleaseNextValueIsFirstRead() {
        val d = Driver()
        d.latchedAndParked()
        d.send(GlucoseHoldReleased(d.now))
        d.advance(60)
        d.glucose(4.6, Trend.FLAT)
        // 첫 판독으로 본다: 4.6 flat은 주의(C-28)이지 STOP 래치 복귀가 아니다
        assertEquals(Level.CAUTION, d.state.glucose)
        assertTrue(d.spoken.isEmpty(), "정차 중 주의는 말하지 않는다: ${d.spoken}")
    }

    @Test
    fun releaseAfterLostAfterValueAllowsV12Again() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, Trend.FLAT, ttlSec = 300)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.send(GlucoseHoldReleased(d.now))
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V12), d.spoken)
    }
}
