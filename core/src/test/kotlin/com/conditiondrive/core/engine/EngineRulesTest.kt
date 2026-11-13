package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시나리오 표로 드러나지 않는 효과와 3.2, 5.4, 5.5, 수용 기준(F1~F7)의 엔진 몫. */
class EngineRulesTest {

    private fun Driver.toStop() {
        advance(6300)
        answerCheckin(CheckinAnswer.GOOD)
        advance(900)
        spoken.clear()
        effects.clear()
    }

    // ---- F1, 3.2 ----

    @Test
    fun f1OneHourAtSpeed() {
        val d = Driver()
        d.startDriving()
        d.advance(3600)
        assertEquals(3600, d.view.continuousDriveSec)
    }

    @Test
    fun f1StoppedInDriveGearAccrues() {
        val d = Driver()
        d.startDriving()
        d.drive(0.0)
        d.advance(180)
        assertEquals(180, d.view.continuousDriveSec)
    }

    @Test
    fun f1SplitRestDoesNotAdd() {
        val d = Driver()
        d.startDriving()
        d.advance(3600)
        d.park()
        d.advance(300)
        d.drive()
        d.advance(60)
        d.park()
        d.advance(600)
        assertEquals(3660, d.view.continuousDriveSec)
    }

    @Test
    fun f1FallbackParkedNoGearSignal() {
        val d = Driver()
        d.vehicle(Gear.UNKNOWN, 80.0)
        d.advance(3600)
        assertEquals(3600, d.view.continuousDriveSec)
        d.vehicle(Gear.UNKNOWN, 0.0)
        d.advance(899)
        assertEquals(4499, d.view.continuousDriveSec)
        assertEquals(899, d.view.restElapsedSec)
        d.advance(1)
        assertEquals(0, d.view.continuousDriveSec)
    }

    @Test
    fun f1FallbackMovingClearsRest() {
        val d = Driver()
        d.vehicle(Gear.UNKNOWN, 80.0)
        d.advance(600)
        d.vehicle(Gear.UNKNOWN, 0.0)
        d.advance(300)
        d.vehicle(Gear.UNKNOWN, 50.0)
        assertNull(d.view.restElapsedSec)
        assertEquals(900, d.view.continuousDriveSec)
    }

    @Test
    fun f1NoSignalsStopsCounting() {
        val d = Driver()
        d.vehicle(Gear.UNKNOWN, null)
        d.advance(3600)
        assertEquals(0, d.view.continuousDriveSec)
        assertNull(d.view.restElapsedSec)
    }

    @Test
    fun f1MeasuredSinceAppStart() {
        val d = Driver()
        d.drive()
        assertTrue(d.view.measuredSinceAppStart)
        assertTrue(d.view.driving)
        val parked = Driver()
        parked.park()
        assertFalse(parked.view.measuredSinceAppStart)
    }

    // ---- 효과 ----

    @Test
    fun a5FirstDeclineOffersVentilationOnce() {
        val d = Driver()
        d.startDriving()
        d.toStop()
        d.answerPrompt(PromptAnswer.DECLINE)
        assertEquals(1, d.effects.count { it is OfferVentilation })
        d.advance(600)
        d.answerPrompt(PromptAnswer.DECLINE)
        assertEquals(1, d.effects.count { it is OfferVentilation })
    }

    @Test
    fun ventilationNotOfferedWhenGlucoseActive() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = 5 * 3600)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.glucose(5.6, Trend.FALLING, ttlSec = 5 * 3600)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.advance(900)
        d.answerPrompt(PromptAnswer.DECLINE)
        assertTrue(d.effects.none { it is OfferVentilation })
    }

    @Test
    fun arrivalSoonHasNoMicrophone() {
        val d = Driver()
        d.startDriving()
        d.advance(6200)
        d.send(RouteChanged(d.now, 500))
        d.advance(100)
        assertEquals(listOf(VoiceId.V22), d.spoken)
        assertTrue(d.effects.none { it is AskCheckin || it is AskStop })
        assertNull(d.state.activePromptId)
    }

    @Test
    fun routeChangeDoesNotMoveThresholds() {
        val d = Driver()
        d.startDriving()
        d.send(RouteChanged(d.now, 100))
        assertEquals(6300, d.view.cautionThresholdSec)
        assertEquals(7200, d.view.stopThresholdSec)
    }

    @Test
    fun v04OnlyFirstHasAdvanceSentence() {
        val d = Driver()
        d.sleep(290)
        d.startDriving()
        d.advance(4500)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.effects.clear()
        d.advance(900)
        val first = d.effects.filterIsInstance<AskStop>().single()
        assertEquals(AdvanceReason.SLEEP_SHORT, first.vars.advance)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.effects.clear()
        d.advance(600)
        val second = d.effects.filterIsInstance<AskStop>().single()
        assertEquals(VoiceId.V04, second.voice)
        assertNull(second.vars.advance)
    }

    @Test
    fun parkFromStopCancelsPrompts() {
        val d = Driver()
        d.startDriving()
        d.toStop()
        d.park()
        assertTrue(CancelPendingPrompts in d.effects)
        assertNull(d.state.nextPromptAtMs)
        assertNull(d.state.activePromptId)
    }

    @Test
    fun ignitionOffCancelsVoice() {
        val d = Driver()
        d.startDriving()
        d.toStop()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        assertTrue(CancelVoice in d.effects)
        assertEquals(DriverState.RECOVER, d.view.state)
        assertNull(d.engine.nextDeadlineMs(d.state))
    }

    @Test
    fun v09PreemptsAndGuidanceNotDuplicated() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9)
        assertEquals(listOf(CancelVoice), d.effects.filter { it == CancelVoice })
        assertEquals(1, d.effects.count { it is StartGuidance && it.auto })
        d.advance(60)
        d.glucose(4.5)
        d.advance(60)
        d.glucose(3.7)
        assertEquals(1, d.effects.count { it is StartGuidance && it.auto })
    }

    @Test
    fun glucoseLossKeepsWaypointAndStopsRepeat() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.advance(300)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertEquals(setOf(Reason.GLUCOSE_UNCONFIRMED), d.view.reasons)
        assertNull(d.state.nextGlucoseRepeatAtMs)
        d.spoken.clear()
        d.advance(600)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun latchedDrivingLossSaysV19NotV19R() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 300)
        d.spoken.clear()
        d.advance(300)
        assertEquals(VoiceId.V19, d.spoken.last())
    }

    @Test
    fun simulatedFlagReachesView() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4)
        assertTrue(d.view.glucoseSimulated)
        assertEquals(6.4, d.view.glucoseMmol)
    }

    @Test
    fun sleepExpiryLeavesViewButKeepsAdvance() {
        val d = Driver()
        d.sleep(290, ttlSec = 60)
        assertEquals(290, d.view.sleepMinutes)
        d.advance(60)
        assertNull(d.view.sleepMinutes)
        assertEquals(AdvanceReason.SLEEP_SHORT, d.view.advanced)
    }

    @Test
    fun lastCheckinRecorded() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        assertEquals(CheckinAnswer.NO_SPEECH, d.view.lastCheckin)
    }

    @Test
    fun v05KeepsPromptKindOfReason() {
        val d = Driver()
        d.startDriving()
        d.toStop()
        repeat(4) {
            d.answerPrompt(PromptAnswer.DECLINE)
            d.effects.clear()
            d.advance(600)
        }
        assertEquals(PromptKind.FATIGUE, d.effects.filterIsInstance<AskStop>().single().kind)
    }

    @Test
    fun acceptedGlucosePromptGuidesButKeepsState() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(5.6, Trend.FALLING)
        d.answerPrompt(PromptAnswer.ACCEPT)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertTrue(d.effects.any { it is StartGuidance && !it.auto })
        assertEquals(VoiceId.V29, d.spoken.last())
    }

    @Test
    fun flatCautionUsesV08F() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(4.5, Trend.FLAT)
        assertEquals(listOf(VoiceId.V08F), d.spoken)
        assertEquals(PromptKind.GLUCOSE_CAUTION, d.effects.filterIsInstance<AskStop>().single().kind)
    }

    @Test
    fun trendNullIsFlat() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(4.5, null)
        assertEquals(listOf(VoiceId.V08F), d.spoken)
    }

    // ---- 5.5 ----

    @Test
    fun sameTimeFatigueAndCheckinCautionAsksOnce() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.spoken.clear()
        // 운전 90분에 재질문하면 다음은 120분이지만 그 전 105분의 주의 진입과 합쳐진다
        d.advance(5400)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V04), d.spoken)
    }

    @Test
    fun fatigueCautionPlusGlucoseCautionSaysV02ThenV08() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = 5 * 3600)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)

        val same = Driver()
        same.startDriving()
        same.advance(6299)
        same.now += 1000
        same.glucose(5.6, Trend.FALLING)
        assertEquals(listOf(VoiceId.V02, VoiceId.V08), same.spoken)
        assertEquals(1, same.effects.count { it is AskStop })
        assertTrue(same.effects.none { it is AskCheckin })
    }

    @Test
    fun fatigueStopWithCheckinCautionAsksV04() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.advance(1800)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.advance(1800)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.advance(2700)
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V04), d.spoken)
        assertEquals(DriverState.STOP, d.view.state)
    }

    @Test
    fun fatigueAndCheckinStopRecoveryNeedsBoth() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.advance(1800)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.advance(5400)
        assertEquals(setOf(Reason.FATIGUE, Reason.CHECKIN), d.view.reasons)
        d.park()
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V03), d.spoken)
        assertEquals(setOf(Reason.CHECKIN), d.view.reasons)
        d.answerCheckin(CheckinAnswer.GOOD)
        assertEquals(DriverState.NORMAL, d.view.state)
        assertEquals(listOf(VoiceId.V03, VoiceId.V12), d.spoken)
    }

    @Test
    fun latchPlusFatigueStopSaysV04() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.9, ttlSec = 6000)
        d.advance(6000)
        assertTrue(d.state.glucoseLatched)
        d.spoken.clear()
        d.advance(300)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V04), d.spoken)
        d.park()
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V19R), d.spoken)
        assertEquals(DriverState.CAUTION, d.view.state)
    }

    // ---- 재시동 ----

    @Test
    fun cautionAtFifteenMinuteRestartSaysV20() {
        val d = Driver()
        d.startDriving()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.spoken.clear()
        d.park()
        assertEquals(listOf(VoiceId.V20), d.spoken)
        assertEquals(0, d.view.continuousDriveSec)
    }

    @Test
    fun checkinReasonAtRestartAsksV03() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.spoken.clear()
        d.park()
        assertEquals(listOf(VoiceId.V03), d.spoken)
    }

    @Test
    fun glucoseUserRestartWaitsForFirstSummary() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = 3 * 3600)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.spoken.clear()
        d.park()
        assertTrue(d.spoken.isEmpty(), "첫 요약 전에는 출발 안내를 하지 않는다")
        d.glucose(3.8)
        d.advance(5)
        assertEquals(listOf(VoiceId.V17), d.spoken)
        assertFalse(VoiceId.V20 in d.spoken)
    }

    @Test
    fun glucoseUserRestartWithFineValueSaysV20After5s() {
        val d = Driver()
        d.startDriving()
        d.glucose(6.4, ttlSec = 3 * 3600)
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.park()
        d.vehicle(Gear.PARK, 0.0, Ignition.OFF)
        d.now += 900_000
        d.park()
        d.spoken.clear()
        d.advance(5)
        assertEquals(listOf(VoiceId.V20), d.spoken)
    }

    @Test
    fun deferredV01AfterGlucoseRecovery() {
        val d = Driver()
        d.park()
        d.glucose(3.8, ttlSec = 3 * 3600)
        d.advance(5)
        assertEquals(listOf(VoiceId.V17), d.spoken)
        assertTrue(d.state.deferredCheckin)
        d.spoken.clear()
        d.advance(60)
        d.glucose(5.0, Trend.RISING, ttlSec = 3 * 3600)
        d.advance(2400)
        assertEquals(listOf(VoiceId.V15, VoiceId.V16, VoiceId.V01), d.spoken)
    }

    @Test
    fun recoveringValueDriveThenLowRestartsRepeatAtTwoMinutes() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(3.9, ttlSec = 3 * 3600)
        d.advance(60)
        d.glucose(4.5, ttlSec = 3 * 3600)
        d.spoken.clear()
        d.advance(600)
        assertEquals(listOf(VoiceId.V25), d.spoken)
    }

    // ---- 음성 조건 ----

    @Test
    fun h4EveryVoiceProceedsWithoutAnswer() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.drive()
        d.spoken.clear()
        d.advance(6300)
        assertEquals(listOf(VoiceId.V02, VoiceId.V03), d.spoken)
        d.advance(900)
        assertEquals(VoiceId.V04, d.spoken.last())
        assertEquals(DriverState.STOP, d.view.state)
        d.spoken.clear()
        d.advance(600)
        assertEquals(listOf(VoiceId.V04), d.spoken)
        assertEquals(0, d.view.ignoredCount)
        d.park()
        d.spoken.clear()
        d.advance(900)
        assertEquals(listOf(VoiceId.V12), d.spoken)
        assertEquals(DriverState.NORMAL, d.view.state)
    }

    @Test
    fun h4GlucoseFlowProceedsWithoutAnswer() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.drive()
        d.glucose(5.6, Trend.FALLING, ttlSec = 3 * 3600)
        d.advance(600)
        d.glucose(3.9, ttlSec = 3 * 3600)
        assertEquals(DriverState.STOP, d.view.state)
        d.park()
        d.advance(60)
        d.glucose(5.1, Trend.RISING, ttlSec = 3 * 3600)
        d.advance(2400)
        assertEquals(DriverState.NORMAL, d.view.state)
    }

    @Test
    fun parkedGlucoseCautionIsSilent() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.spoken.clear()
        d.glucose(5.6, Trend.FALLING)
        assertEquals(DriverState.CAUTION, d.view.state)
        assertTrue(d.spoken.isEmpty())
    }

    @Test
    fun clearReadsResetOnConditionTrueAgain() {
        val d = Driver()
        d.startDriving()
        d.advance(60)
        d.glucose(5.6, Trend.FALLING)
        d.advance(300)
        d.glucose(6.5)
        d.advance(300)
        d.glucose(5.6, Trend.FALLING)
        d.advance(300)
        d.glucose(6.5)
        assertEquals(Level.CAUTION, d.state.glucose)
        d.advance(300)
        d.glucose(6.5)
        assertEquals(Level.NONE, d.state.glucose)
    }

    @Test
    fun deadlineAlwaysMovesForward() {
        val d = Driver()
        d.startDriving()
        d.advance(10_000)
        val deadline = assertNotNull(d.engine.nextDeadlineMs(d.state))
        assertTrue(deadline > d.now)
    }

    // ---- 온보딩 중 체크인 ----

    @Test
    fun b11OnboardingHoldsV01WithoutUsingTheDay() {
        val d = Driver()
        d.send(CheckinHold(d.now, hold = true))
        d.park()
        d.advance(30)
        assertFalse(VoiceId.V01 in d.spoken, "온보딩 중에는 묻지 않는다")
        assertNull(d.state.checkinAskedDay)
        d.send(CheckinHold(d.now, hold = false))
        assertEquals(listOf(VoiceId.V01), d.spoken)
        assertNotNull(d.state.checkinAskedDay)
    }

    @Test
    fun b11ReleaseWithinStartupDelayAsksOnce() {
        val d = Driver()
        d.send(CheckinHold(d.now, hold = true))
        d.park()
        d.send(CheckinHold(d.now, hold = false))
        assertTrue(d.spoken.isEmpty())
        d.advance(5)
        assertEquals(listOf(VoiceId.V01), d.spoken)
    }

    @Test
    fun b11ReleaseWhileDrivingDoesNotAsk() {
        val d = Driver()
        d.send(CheckinHold(d.now, hold = true))
        d.park()
        d.advance(10)
        d.drive()
        d.send(CheckinHold(d.now, hold = false))
        assertFalse(VoiceId.V01 in d.spoken)
    }

    // ---- 체크인 미분류 ----

    @Test
    fun b3bUnclassifiedTwiceKeepsCheckinCaution() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.TIRED)
        d.drive()
        d.advance(1800)
        assertEquals(Level.CAUTION, d.state.checkin)
        d.answerCheckin(CheckinAnswer.UNCLASSIFIED)
        d.answerCheckin(CheckinAnswer.UNCLASSIFIED)
        assertEquals(Level.CAUTION, d.state.checkin, "졸리다고 말한 운전자가 못 알아들었다는 이유로 여유가 되면 안 된다")
        assertEquals(CheckinAnswer.NEUTRAL, d.state.lastCheckin)
    }

    @Test
    fun checkinRefusalIsSilentAndNotAskedAgain() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.spoken.clear()
        d.answerCheckin(CheckinAnswer.NO_SPEECH)
        assertTrue(d.spoken.isEmpty())
        assertEquals(CheckinAnswer.NO_SPEECH, d.state.lastCheckin)
    }

    // ---- 혈당 주의 음성 ----

    /** 해제 판독을 기다리는 창: G=CAUTION인데 지금 값은 오르는 중이다 */
    @Test
    fun cautionVoiceIsNotSpokenWhileRising() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.glucose(5.6, Trend.FALLING)
        assertEquals(Level.CAUTION, d.state.glucose)
        d.advance(300)
        d.glucose(5.8, Trend.RISING)
        assertEquals(Level.CAUTION, d.state.glucose)
        d.spoken.clear()
        d.drive()
        d.advance(30)
        assertFalse(VoiceId.V08 in d.spoken || VoiceId.V08F in d.spoken, "${d.spoken}")
    }

    @Test
    fun flatCautionUsesFlatVoiceAndFallingUsesFalling() {
        val flat = Driver()
        flat.startDriving()
        flat.glucose(4.6, Trend.FLAT)
        assertEquals(listOf(VoiceId.V08F), flat.spoken)
        val falling = Driver()
        falling.startDriving()
        falling.glucose(5.4, Trend.FALLING)
        assertEquals(listOf(VoiceId.V08), falling.spoken)
    }

    // ---- E21 ----

    /** V15가 "40분 뒤에 알려 드릴게요"라고 했으니 값이 주의 범위여도 끝났다고 말한다 */
    @Test
    fun timerEndInCautionRangeStillAnnounces() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.8)
        d.park()
        d.advance(60)
        d.glucose(5.1, Trend.RISING)
        assertNotNull(d.state.glucoseTimerStartMs)
        d.spoken.clear()
        repeat(8) {
            d.glucose(5.3, Trend.FALLING)
            d.advance(300)
        }
        assertEquals(Level.CAUTION, d.state.glucose)
        assertTrue(VoiceId.V16C in d.spoken, "${d.spoken}")
        assertFalse(VoiceId.V16 in d.spoken)
    }

    // ---- E33 ----

    /** 수면 값이 사라졌는데 "어젯밤 잠이 짧아" 판단이 남으면 안 된다 */
    @Test
    fun e33ClearsAdvanceAndLowersFatigue() {
        val d = Driver()
        d.startDriving()
        d.sleep(290)
        d.advance(4800)     // 1:20. 앞당김 임계 1:15를 넘었다
        assertEquals(Level.CAUTION, d.state.fatigue)
        d.send(ConditionReceived(d.now, null, unpaired = true))
        assertFalse(d.state.advanceLatched)
        assertEquals(Level.NONE, d.state.fatigue)
        assertEquals(6300, d.view.cautionThresholdSec)
        assertEquals(7200, d.view.stopThresholdSec)
    }

    @Test
    fun parkedUnpairClearsAdvance() {
        val d = Driver()
        d.park()
        d.sleep(290)
        assertTrue(d.state.advanceLatched)
        d.send(ConditionReceived(d.now, null, unpaired = true))
        assertEquals(6300, d.view.cautionThresholdSec)
    }

    /** 시동 신호가 없는 차종: 수면이 만료된 뒤 쌓인 운전 없이 출발하면 앞당김을 끌고 가지 않는다 */
    @Test
    fun newTripAfterSleepExpiryReleasesAdvance() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.sleep(290, ttlSec = 60)
        assertTrue(d.state.advanceLatched)
        d.advance(120)
        d.drive(40.0)
        d.advance(31)
        assertFalse(d.state.advanceLatched)
    }

    // ---- 재부팅 복원 ----

    /** 40분 완료 시각이 재부팅 공백에 걸려도 확인 안 된 값으로 회복을 처리하면 안 된다(규칙 5) */
    @Test
    fun rebootGapDoesNotFinishTimerOnUnconfirmedValue() {
        val d = Driver()
        d.startDriving()
        d.glucose(3.8)
        d.park()
        d.advance(60)
        d.glucose(5.1, Trend.RISING, ttlSec = 900)
        assertNotNull(d.state.glucoseTimerStartMs)
        val started = d.now
        // 저장 뒤 39분 쉬어 값이 끝까지 살아 있는 상태에서 공백이 시작된다
        repeat(7) {
            d.advance(300)
            d.glucose(5.2, Trend.RISING)
        }
        d.spoken.clear()
        d.now = started + 50 * 60_000
        d.send(ProcessRestored(d.now, GapEvidence.PARKED, rebooted = true))
        assertFalse(VoiceId.V16 in d.spoken, "${d.spoken}")
        assertTrue(d.state.glucoseLatched, "값을 모르니 래치다")
        assertEquals(Level.NONE, d.state.glucose)
    }
}
