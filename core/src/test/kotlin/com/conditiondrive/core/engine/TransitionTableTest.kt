package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 설계서 5.4 상태 × 이벤트 표에서 시나리오 표가 지나가지 않는 칸. 이벤트 번호로 찾을 수 있게 이름에 적는다. */
class TransitionTableTest {
    private val long = 6 * 3600L

    private fun Driver.fresh() {
        spoken.clear()
        effects.clear()
    }

    /** 체크인 TIRED 2회로 STOP(C)까지 간다. */
    private fun Driver.toCheckinStop() {
        park()
        advance(5)
        answerCheckin(CheckinAnswer.TIRED)
        drive()
        advance(1800)
        answerCheckin(CheckinAnswer.TIRED)
    }

    @Test
    fun e01InStopAddsFatigueSilently() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.ACCEPT)
        d.advance(1200)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.fresh()
        d.advance(600)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.advance(600)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.fresh()
        // 운전 70분에서 105분(주의 임계)까지 간다
        d.advance(2100)
        assertEquals(setOf(Reason.FATIGUE, Reason.CHECKIN), d.view.reasons)
        assertFalse(VoiceId.V02 in d.spoken)
        assertFalse(VoiceId.V03 in d.spoken)
    }

    @Test
    fun e02InCheckinStopSwitchesNextPromptToV04() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.MUTE)
        d.fresh()
        // 운전 30분 + 60분 간격 뒤 90분 시점이 V06이다
        d.advance(3600)
        assertEquals(listOf(VoiceId.V06), d.spoken)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.fresh()
        // 2시간 임계에서는 말하지 않고, 다음 프롬프트부터 V04다
        d.advance(1800)
        assertEquals(Level.STOP, d.state.fatigue)
        assertTrue(d.spoken.isEmpty())
        d.advance(1800)
        assertEquals(listOf(VoiceId.V04), d.spoken)
    }

    @Test
    fun e03NormalAndCautionAreSilent() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        d.park()
        assertTrue(d.spoken.isEmpty())
        assertEquals(DriverState.NORMAL, d.view.state)
        assertEquals(0, d.view.restElapsedSec)
    }

    @Test
    fun e04CautionWithCheckinAsksV03() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.park()
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V03), d.spoken)
        assertEquals(setOf(Reason.CHECKIN), d.view.reasons)
    }

    @Test
    fun e04CautionFatigueOnlyLatched() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.9, ttlSec = 6300)
        d.advance(6300)
        assertTrue(d.state.glucoseLatched)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.fresh()
        d.advance(900)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun e04CheckinOnlyRecoverWithoutFatigueSilentForGlucose() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        d.glucose(3.8, ttlSec = long)
        d.park()
        d.fresh()
        d.advance(900)
        assertTrue(d.spoken.isEmpty())
        assertEquals(DriverState.RECOVER, d.view.state)
    }

    @Test
    fun e05CautionClearsRestAndAnnouncesGlucoseOnce() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.glucose(4.5, Trend.FLAT)
        d.fresh()
        d.drive()
        assertEquals(listOf(VoiceId.V08F), d.spoken)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.park()
        d.advance(60)
        d.fresh()
        d.drive()
        assertTrue(d.spoken.isEmpty())
        assertNull(d.view.restElapsedSec)
    }

    @Test
    fun e05CheckinStopWithRestDoneDropsToCaution() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.ACCEPT)
        d.park()
        d.advance(900)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.fresh()
        d.drive(40.0)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertTrue(d.spoken.isEmpty())
        assertEquals(1, d.state.tiredStreak)
    }

    @Test
    fun e05FatigueAndGlucoseStopSaysOnlyGlucose() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = long)
        d.advance(7200)
        d.park()
        d.advance(60)
        d.glucose(3.8, ttlSec = long)
        d.fresh()
        d.drive()
        assertEquals(listOf(VoiceId.V09), d.spoken)
    }

    @Test
    fun e07ParkedTiredDoesNotAskV06() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.advance(1800)
        val prompt = d.state.activePromptId!!
        d.park()
        d.fresh()
        d.send(CheckinHeard(d.now, prompt, CheckinAnswer.TIRED))
        assertEquals(DriverState.RECOVER, d.view.state)
        assertTrue(d.spoken.isEmpty(), "6.3: P에서는 V06을 내지 않는다")
    }

    @Test
    fun e08NormalKeepsStateAndRecords() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(DriverState.NORMAL, d.view.state)
        assertEquals(CheckinAnswer.GOOD, d.view.lastCheckin)
    }

    @Test
    fun e08RecoverWithGlucoseSaysV13() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(3.8, ttlSec = long)
        d.park()
        d.advance(900)
        d.fresh()
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(listOf(VoiceId.V13), d.spoken)
        assertEquals(DriverState.RECOVER, d.view.state)
    }

    @Test
    fun e08RecoverLatchedSaysV19R() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(3.8, ttlSec = 300)
        d.park()
        d.advance(300)
        d.advance(600)
        d.fresh()
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun e09ToE13GlucosePromptOnlyRecords() {
        for ((answer, voices) in listOf(
            PromptAnswer.DECLINE to emptyList(),
            PromptAnswer.MUTE to emptyList(),
            PromptAnswer.NO_SPEECH to emptyList(),
            PromptAnswer.ACCEPT to listOf(VoiceId.V29),
            PromptAnswer.UNCLASSIFIED to listOf(VoiceId.V21S),
        )) {
            val d = Driver()
            d.startDriving()
            d.advance(60)
            d.glucose(5.6, Trend.FALLING)
            d.fresh()
            d.answerPrompt(answer)
            assertEquals(voices, d.spoken, "$answer")
            assertEquals(0, d.view.ignoredCount, "$answer")
            assertEquals(false, d.state.muted, "$answer")
            assertEquals(DriverState.CAUTION, d.view.state, "$answer")
        }
    }

    @Test
    fun e13SecondUnclassifiedOnGlucosePromptIsSilence() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(5.6, Trend.FALLING)
        d.answerPrompt(PromptAnswer.UNCLASSIFIED)
        d.fresh()
        d.answerPrompt(PromptAnswer.UNCLASSIFIED)
        assertTrue(d.spoken.isEmpty())
        assertNull(d.state.activePromptId)
    }

    @Test
    fun e14CheckinStopRepromptsV06AndFirmAfterFour() {
        val d = Driver()
        d.toCheckinStop()
        repeat(4) {
            d.answerPrompt(PromptAnswer.DECLINE)
            d.fresh()
            d.advance(600)
            val expected = if (it < 3) VoiceId.V06 else VoiceId.V05
            assertEquals(listOf(expected), d.spoken, "거절 ${it + 1}회 뒤")
        }
        assertEquals(PromptKind.CHECKIN, d.effects.filterIsInstance<AskStop>().single().kind)
    }

    @Test
    fun e15InStopAndRecoverOnlyRecords() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.fresh()
        d.glucose(5.6, Trend.FALLING)
        assertEquals(Level.CAUTION, d.state.glucose)
        assertTrue(d.spoken.isEmpty())
        assertEquals(DriverState.STOP, d.view.state)

        val parked = Driver()
        parked.toCheckinStop()
        parked.answerPrompt(PromptAnswer.NO_SPEECH)
        parked.park()
        parked.fresh()
        parked.glucose(5.6, Trend.FALLING)
        assertEquals(Level.CAUTION, parked.state.glucose)
        assertTrue(parked.spoken.isEmpty())
        assertEquals(DriverState.RECOVER, parked.view.state)
    }

    @Test
    fun e15EscalatesOnlyOncePerEpisode() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(5.6, Trend.FALLING)
        d.fresh()
        d.advance(300)
        d.glucose(5.2, Trend.FALLING_FAST)
        assertEquals(listOf(VoiceId.V08), d.spoken)
        d.fresh()
        d.advance(300)
        d.glucose(4.8, Trend.FALLING)
        d.advance(300)
        d.glucose(4.6, Trend.FALLING_FAST)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun e16ClearInStopHasNoEffect() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(5.6, Trend.FALLING)
        d.advance(300)
        d.glucose(6.5, Trend.FLAT)
        d.advance(1)
        d.fresh()
        d.glucose(6.5, Trend.FLAT, measuredAt = d.now)
        assertEquals(Level.NONE, d.state.glucose)
        assertEquals(DriverState.STOP, d.view.state)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun e17RecoverOutsideHoldSaysV14AndDeletesTimer() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8, ttlSec = long)
        d.park()
        d.advance(60)
        d.glucose(5.2, Trend.RISING, ttlSec = long)
        d.fresh()
        d.advance(60)
        d.glucose(3.9, ttlSec = long)
        assertEquals(listOf(VoiceId.V14), d.spoken)
        assertNull(d.view.glucoseRecoveryRemainingSec)
    }

    @Test
    fun e17InStopPreemptsActivePrompt() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        val prompt = d.state.activePromptId
        d.fresh()
        d.glucose(3.8, ttlSec = long)
        assertEquals(listOf(VoiceId.V09), d.spoken)
        assertTrue(CancelVoice in d.effects)
        assertNull(d.state.activePromptId)
        d.send(PromptHeard(d.now, prompt!!, PromptAnswer.ACCEPT))
        assertTrue(d.effects.none { it is StartGuidance && !it.auto })
    }

    @Test
    fun e19DrivingStartsTimerAndSlowsRepeat() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8, ttlSec = long)
        d.advance(60)
        d.fresh()
        d.glucose(5.2, Trend.RISING, ttlSec = long)
        assertEquals(listOf(VoiceId.V15), d.spoken)
        d.fresh()
        d.advance(599)
        assertTrue(d.spoken.isEmpty())
        d.advance(1)
        assertEquals(listOf(VoiceId.V24), d.spoken)
    }

    @Test
    fun e20DrivingDeletesTimerSilently() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8, ttlSec = long)
        d.advance(60)
        d.glucose(5.2, Trend.RISING, ttlSec = long)
        d.fresh()
        d.advance(60)
        d.glucose(4.5, ttlSec = long)
        assertTrue(d.spoken.isEmpty())
        assertNull(d.view.glucoseRecoveryRemainingSec)
        assertEquals(DriverState.STOP, d.view.state)
    }

    @Test
    fun e21ResidualKeepsSilent() {
        val d = Driver()
        d.toCheckinStop()
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(3.8, ttlSec = long)
        d.park()
        d.advance(60)
        d.glucose(5.2, Trend.RISING, ttlSec = long)
        d.fresh()
        d.advance(840)
        assertEquals(listOf(VoiceId.V03), d.spoken)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.fresh()
        d.advance(1560)
        assertEquals(Level.NONE, d.state.glucose)
        assertTrue(d.spoken.isEmpty(), "체크인 사유가 남아 음성이 없다")
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    @Test
    fun e21ThenCautionReEvaluated() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.8, ttlSec = long)
        d.advance(60)
        d.glucose(5.2, Trend.FALLING, ttlSec = long)
        d.fresh()
        d.advance(2400)
        assertEquals(Level.CAUTION, d.state.glucose)
        assertEquals(VoiceId.V16D, d.spoken.filterNot { it == VoiceId.V24 }.first())
        assertEquals(VoiceId.V08, d.spoken.last())
    }

    @Test
    fun e22NormalParkedSaysV19R() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.glucose(6.4)
        d.fresh()
        d.send(ConditionReceived(d.now, null))
        // 정차 중이면 래치가 없어도 V19R이다. 값을 받던 사용자에게 "출발하세요"가 이어지지 않게 한다(D-31)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertTrue(d.view.glucoseUnavailable)
    }

    @Test
    fun e22StopWithFatigueStaysStop() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(3.8, ttlSec = 120)
        d.fresh()
        d.advance(120)
        assertEquals(DriverState.STOP, d.view.state)
        assertEquals(setOf(Reason.FATIGUE, Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        assertEquals(VoiceId.V19, d.spoken.last())
    }

    @Test
    fun e24RecoverOnlyRecordsThreshold() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.park()
        d.fresh()
        d.sleep(290)
        assertTrue(d.spoken.isEmpty())
        assertEquals(5400, d.view.stopThresholdSec)
    }

    @Test
    fun e24CautionToStopAtOnce() {
        val d = Driver()
        d.startDriving()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.fresh()
        d.sleep(290)
        assertEquals(listOf(VoiceId.V04), d.spoken)
        assertEquals(AdvanceReason.SLEEP_SHORT, d.effects.filterIsInstance<AskStop>().single().vars.advance)
    }

    @Test
    fun e24StopStaysQuiet() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.fresh()
        d.sleep(290)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun e24NormalToCaution() {
        val d = Driver()
        d.startDriving()
        d.advance(4600)
        d.sleep(290)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)
    }

    @Test
    fun shortSleepStillValidAfterRestStaysAdvanced() {
        val d = Driver()
        d.sleep(290)
        d.startDriving()
        d.advance(4500)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.advance(900)
        assertEquals(0, d.view.continuousDriveSec)
        assertEquals(4500, d.view.cautionThresholdSec, "수면 값이 살아 있으면 다음 구간도 앞당긴다")
    }

    @Test
    fun e25CommsLossChangesNothingUntilExpiry() {
        val d = Driver()
        d.startDriving()
        d.glucose(5.6, Trend.FALLING, ttlSec = 900)
        val before = d.view.state
        d.fresh()
        d.advance(899)
        assertEquals(before, d.view.state)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun e27NormalRestartSameDayNoQuestion() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 60_000
        d.fresh()
        d.park()
        d.advance(5)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun e28CautionWithGlucoseResidualIsQuiet() {
        val d = Driver()
        d.startDriving()
        d.glucose(5.6, Trend.FALLING, ttlSec = long)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.advance(6300)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.fresh()
        d.park()
        d.advance(5)
        assertEquals(0, d.view.continuousDriveSec)
        assertFalse(VoiceId.V20 in d.spoken)
        assertFalse(VoiceId.V12 in d.spoken)
    }

    @Test
    fun e28ResetsNightCheckinPerTrip() {
        val d = Driver(start = "2026-11-20T00:30:00+09:00")
        d.startDriving()
        d.advance(3600)
        assertEquals(listOf(VoiceId.V03), d.spoken)
        d.answerCheckin(CheckinAnswer.GOOD)
        // 기어 P로 15분 쉬어도 같은 운행이라 다시 묻지 않는다(5.1)
        d.park()
        d.advance(900)
        d.fresh()
        d.drive()
        d.advance(3600)
        assertTrue(d.spoken.isEmpty())
        // 시동을 끄고 15분 넘게 쉬면 새 운행이다
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.park()
        d.drive()
        d.fresh()
        d.advance(3600)
        assertEquals(listOf(VoiceId.V03), d.spoken)
    }

    @Test
    fun e29ValueLowBeatsE28Notice() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.8, ttlSec = long)
        d.advance(7200)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.fresh()
        d.park()
        assertTrue(d.spoken.isEmpty())
        d.advance(5)
        assertEquals(listOf(VoiceId.V17), d.spoken)
        assertTrue(d.view.preDepartureHold)
    }

    @Test
    fun e30RecoverEvidenceIgnoredWhenAlreadyParked() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.park()
        val saved = d.state
        val restored = d.engine.reduce(saved, ProcessRestored(d.now + 300_000, GapEvidence.UNKNOWN)).state
        assertEquals(saved.driveMs, restored.driveMs)
        assertEquals(saved.restStartMs, restored.restStartMs)
    }

    @Test
    fun e30GapNoneAddsNothing() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        val restored = d.engine.reduce(d.state, ProcessRestored(d.now + 300_000, GapEvidence.NONE)).state
        assertEquals(d.state.driveMs, restored.driveMs)
        assertEquals(d.now + 300_000, restored.lastEventMs)
    }

    @Test
    fun e30ParkedGapCompletingRest() {
        val d = Driver()
        d.startDriving()
        d.advance(600)
        d.now += 900_000
        d.send(ProcessRestored(d.now, GapEvidence.PARKED))
        assertEquals(0, d.view.continuousDriveSec)
        assertEquals(DriverState.NORMAL, d.view.state)
    }

    @Test
    fun e31WithoutPauseDoesNothing() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        val next = d.state.nextPromptAtMs
        d.send(WaypointArrived(d.now))
        assertEquals(next, d.state.nextPromptAtMs)
    }

    @Test
    fun e31KeepsEarlierPause() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.answerPrompt(PromptAnswer.ACCEPT)
        d.advance(1100)
        val pause = d.state.nextPromptAtMs
        d.send(WaypointArrived(d.now))
        assertEquals(pause, d.state.nextPromptAtMs, "남은 정지가 3분보다 짧으면 늘리지 않는다")
    }

    @Test
    fun e33ClearsGlucoseInStop() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.8, ttlSec = long)
        d.fresh()
        d.send(ConditionReceived(d.now, null, unpaired = true))
        assertEquals(DriverState.NORMAL, d.view.state)
        assertTrue(d.spoken.isEmpty())
        assertNull(d.state.nextGlucoseRepeatAtMs)
        assertFalse(d.view.glucoseUnavailable)
        d.advance(3600)
        assertFalse(VoiceId.V19 in d.spoken)
    }

    @Test
    fun f5ThirteenBeforeSixteenOrder() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = long)
        d.advance(7200)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.glucose(3.8, ttlSec = long)
        d.park()
        d.advance(60)
        d.glucose(5.2, Trend.RISING, ttlSec = long)
        d.fresh()
        d.advance(2400)
        assertEquals(listOf(VoiceId.V13, VoiceId.V16), d.spoken)
    }

    @Test
    fun f2RestartedSegmentSpeaksV02Again() {
        val d = Driver()
        d.startDriving()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.advance(900)
        d.fresh()
        d.drive()
        d.advance(6300)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)
    }

    @Test
    fun clockBackwardKeepsPendingDepartConsistent() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.park()
        d.advance(60)
        d.vehicle(Gear.DRIVE, 5.0)
        d.send(Tick(d.now - 10_000))
        assertFalse(d.view.driving)
        assertEquals(60, d.view.continuousDriveSec)
    }

    @Test
    fun viewShowsRecoveryCounters() {
        val d = Driver()
        d.startDriving()
        d.advance(7200)
        d.park()
        d.now += 61_000
        val view = d.view
        assertEquals(61, view.restElapsedSec)
        assertEquals(839, view.restRemainingSec)
        assertNull(view.remainingToRestSec)
        assertEquals(7200, view.continuousDriveSec)
    }

    @Test
    fun viewCountsBetweenEvents() {
        val d = Driver()
        d.startDriving()
        assertEquals(1234, d.engine.view(d.state, d.now + 1_234_000).continuousDriveSec)
        assertEquals(7200 - 1234, d.engine.view(d.state, d.now + 1_234_000).remainingToRestSec)
    }
}
