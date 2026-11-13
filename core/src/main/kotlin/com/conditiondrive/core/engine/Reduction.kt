package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import java.time.Instant
import java.time.ZoneId

/**
 * reduce 한 번의 계산. 바깥에서 보면 순수 함수이고 안에서만 값을 고쳐 나간다.
 * 3.4 순서를 그대로 메서드 순서로 옮겨 표와 대조하기 쉽게 했다.
 */
internal class Reduction(
    private val p: Policy,
    private val zone: ZoneId,
    var s: EngineState,
    private val at: Long,
) {
    val effects = mutableListOf<Effect>()
    val voices = mutableListOf<VoiceId>()
    /** 상태와 음성이 그대로여도 기록할 조작. Engine.reduce가 로그 줄에 싣는다 */
    var logEvent: LogEvent? = null
        private set

    // 마이크는 한 번에 하나다. 같은 시각의 두 번째 질문은 버린다(5.5, 6.3 합침).
    private var asked = false
    private val driveBefore = s.driveMs

    // run 앞머리에서 lastEventMs를 at으로 옮기므로 공백 시작을 먼저 잡아 둔다.
    private val gapStart = s.lastEventMs

    fun run(event: EngineEvent) {
        if (event !is ProcessRestored) accrue()
        s = s.copy(lastEventMs = at)
        when (event) {
            is Tick -> Unit
            is VehicleChanged -> onVehicle(event)
            is ConditionReceived -> onCondition(event)
            is RouteChanged -> s = s.copy(remainingRouteSec = event.remainingSec, remainingRouteAtMs = at)
            is CheckinHeard -> onCheckin(event)
            is PromptHeard -> onPrompt(event)
            is WaypointArrived -> onWaypoint()
            is ProcessRestored -> onRestored(event)
            is CheckinHold -> onCheckinHold(event)
            is GlucoseHoldReleased -> onHoldReleased()
        }
        // 시동이 꺼진 동안은 말할 수 없다. 만료는 다음 시동에서 한꺼번에 처리한다(E27~E29).
        if (s.ignition != Ignition.OFF) runDeadlines()
    }

    private fun accrue() {
        val dt = (at - s.lastEventMs).coerceAtLeast(p.CLOCK_BACKWARD_GAP)
        s = when {
            s.departPendingSinceMs != null -> s.copy(departPendingDriveMs = (s.departPendingDriveMs ?: 0) + dt)
            s.accruesDrive() -> s.copy(driveMs = s.driveMs + dt)
            else -> s
        }
    }

    private fun runDeadlines() {
        restDue()
        glucoseTimerDue()
        glucoseExpiryDue()
        departDue()
        thresholds()
        startupDue()
        checkinReaskDue()
        nightCheckin()
        repromptDue()
        glucoseRepeatDue()
    }

    // ---- 음성 ----

    private fun say(voice: VoiceId, vars: VoiceVars = VoiceVars()) {
        effects += Say(voice, vars)
        voices += voice
    }

    /** 혈당이 기준 아래면 P를 벗어난 뒤에는 어떤 경로로도 마이크를 열지 않는다(G9, 6.3). 유예 중도 같다. */
    private fun micBlocked(): Boolean =
        asked || ((s.isDriving() || s.departPendingSinceMs != null) && s.isGlucoseLow(p))

    private fun askCheckin(voice: VoiceId, postRest: Boolean = false, retried: Boolean = false) {
        if (micBlocked()) return
        val id = s.promptSeq + 1
        s = s.copy(
            promptSeq = id,
            activePromptId = id,
            activePromptKind = null,
            activePromptPostRest = postRest,
            promptRetried = retried,
        )
        if (voice == VoiceId.V03 && s.checkin != Level.NONE) {
            s = s.copy(nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms)
        }
        asked = true
        effects += AskCheckin(voice, id)
        voices += voice
    }

    private fun askStop(voice: VoiceId, kind: PromptKind, vars: VoiceVars = VoiceVars(), retried: Boolean = false) {
        if (micBlocked()) return
        val id = s.promptSeq + 1
        s = s.copy(
            promptSeq = id,
            activePromptId = id,
            activePromptKind = kind,
            activePromptPostRest = false,
            promptRetried = retried,
        )
        asked = true
        effects += AskStop(voice, kind, id, vars)
        voices += voice
    }

    private fun clearActivePrompt() {
        s = s.copy(activePromptId = null, activePromptKind = null, activePromptPostRest = false, promptRetried = false)
    }

    private fun promptIntervalSec(): Long = if (s.muted) p.MUTED_REPROMPT_SEC else p.REPROMPT_SEC

    private fun driveVars(advance: Boolean = false) = VoiceVars(
        driveSec = s.driveMs / 1000,
        advance = if (advance && s.advanceLatched) AdvanceReason.SLEEP_SHORT else null,
    )

    private fun arrivalSoon(): Boolean {
        val remaining = s.routeRemainingSec(at) ?: return false
        return remaining <= p.ARRIVAL_SOON_SEC
    }

    private fun sayArrivalSoon() {
        val remaining = s.routeRemainingSec(at) ?: return
        say(VoiceId.V22, VoiceVars(routeMinutes = ((remaining + 59) / 60).toInt()))
    }

    private fun askGlucoseCaution() {
        val trend = s.glucoseLastTrend ?: Trend.FLAT
        // 해제 판독을 기다리는 동안은 G=CAUTION이어도 지금 값은 주의 조건이 아닐 수 있다. 오르는 값에 "내려가고 있어요"는 거짓이다
        val mmol = s.glucoseValueAtLast
        if (mmol != null && !cautionCondition(mmol, trend)) return
        val voice = if (trend == Trend.FLAT) VoiceId.V08F else VoiceId.V08
        s = s.copy(glucoseCautionAnnounced = true)
        askStop(voice, PromptKind.GLUCOSE_CAUTION)
    }

    // ---- 차량 ----

    private fun onVehicle(e: VehicleChanged) {
        val before = s
        // 복원 없이 처음 들어온 신호다. 저장본이 없으면 그 전 운전 시간을 알 수 없다(F1-11).
        val fresh = before.ignition == Ignition.OFF && before.gear == Gear.UNKNOWN && before.speedKmh == null &&
            before.restStartMs == null && before.driveMs == 0L
        val wasDriving = before.isDriving()
        val wasHard = before.isHardParked()
        val wasFallback = before.isFallbackParked()
        s = s.copy(gear = e.gear, speedKmh = e.speedKmh, ignition = e.ignition)

        if (before.ignition != Ignition.OFF && e.ignition == Ignition.OFF) {
            onIgnitionOff()
            return
        }
        if (before.ignition == Ignition.OFF && e.ignition != Ignition.OFF) onIgnitionOn()
        if (e.ignition == Ignition.OFF) return

        val nowHard = s.isHardParked()
        when {
            nowHard && s.departPendingSinceMs != null ->
                // 확정 전 복귀는 주차 위치 이동이다. 휴식을 그대로 잇는다(F1-7).
                s = s.copy(departPendingSinceMs = null, departPendingDriveMs = null)
            nowHard && wasDriving -> onParked()
            nowHard && !wasHard -> if (s.restStartMs == null) s = s.copy(restStartMs = at)
            !nowHard && wasHard -> leavePark(fresh)
            s.isFallbackParked() && wasDriving -> onParked()
            s.isFallbackParked() && !wasFallback -> if (s.restStartMs == null) s = s.copy(restStartMs = at)
            wasFallback && s.isDriving() -> {
                s = s.copy(departPendingSinceMs = at, departPendingDriveMs = 0)
                confirmDepart()
            }
            s.isNoSignal() -> s = s.copy(restStartMs = null)
        }
        speedConfirm()
    }

    private fun leavePark(fresh: Boolean) {
        if (fresh) {
            if (s.isFallbackParked()) {
                s = s.copy(restStartMs = at)
            } else if (!s.isNoSignal()) {
                s = s.copy(departedThisIgnition = true, measuredSinceAppStart = true)
                effects += Trip(TripEvent.DRIVE_STARTED)
            }
            return
        }
        if (s.isFallbackParked()) return
        s = s.copy(departPendingSinceMs = at, departPendingDriveMs = 0)
    }

    private fun speedConfirm() {
        if (s.departPendingSinceMs == null) return
        val speed = s.speedKmh ?: return
        if (speed >= p.DEPART_CONFIRM_KMH) confirmDepart()
    }

    /** E03. 운전에서 정차로 넘어온 순간. */
    private fun onParked() {
        val wasStop = s.level() == Level.STOP
        val started = s.restStartMs == null
        s = s.copy(
            restStartMs = s.restStartMs ?: at,
            nextPromptAtMs = null,
            promptPausedUntilMs = null,
            nextGlucoseRepeatAtMs = null,
        )
        if (started) effects += Trip(TripEvent.REST_STARTED)
        if (!wasStop) return
        effects += CancelPendingPrompts
        clearActivePrompt()
        if (s.glucose == Level.STOP) say(VoiceId.V14) else say(VoiceId.V11, VoiceVars(remainingSec = restRemainingSec()))
    }

    private fun restRemainingSec(): Long? =
        s.restStartMs?.let { ceilSec(p.REST_RESET_SEC.ms - (at - it).coerceAtLeast(0)) }

    /** E26. */
    private fun onIgnitionOff() {
        val started = s.restStartMs == null
        s = s.copy(
            restStartMs = s.restStartMs ?: at,
            departPendingSinceMs = null,
            departPendingDriveMs = null,
            nextPromptAtMs = null,
            promptPausedUntilMs = null,
            nextGlucoseRepeatAtMs = null,
            checkinDueAtMs = null,
            restartNoticePending = false,
        )
        clearActivePrompt()
        if (started) effects += Trip(TripEvent.REST_STARTED)
        effects += CancelVoice
        effects += CancelPendingPrompts
    }

    /** E27, E28, E29. 값이 낮은지는 시동 5초 뒤(첫 요약 조회 이후)에 말하므로 여기서는 말하지 않는다. */
    private fun onIgnitionOn() {
        s = s.copy(
            departedThisIgnition = false,
            deferredCheckin = false,
            checkinDueAtMs = at + p.CHECKIN_START_DELAY_SEC.ms,
        )
        releaseExpiredAdvance()
        val rest = s.restStartMs ?: return
        if (at - rest < p.REST_RESET_SEC.ms) return

        val wasFatigued = s.fatigue != Level.NONE
        resetFatigue()
        s = s.copy(nightCheckinDone = false, ventilationOffered = false)
        effects += Trip(TripEvent.REST_COMPLETED)
        when {
            s.isGlucoseLow(p) -> Unit
            s.checkin != Level.NONE -> askCheckin(VoiceId.V03, postRest = true)
            // 혈당을 받는 사용자는 첫 요약을 본 뒤에 말한다. "출발하세요" 다음에 "미루세요"가 나오면 안 된다(규칙 5).
            wasFatigued && s.glucoseLastValidMs != null -> s = s.copy(restartNoticePending = true)
            wasFatigued -> say(VoiceId.V20)
        }
    }

    /** 시동 5초 뒤. 3.5 V01 조건, E29 V17, 재시동 혈당 재평가가 모두 여기서 결정된다. */
    private fun startupDue() {
        val due = s.checkinDueAtMs ?: return
        if (at < due) return
        val ignitionDay = dayIndex(due - p.CHECKIN_START_DELAY_SEC.ms)
        s = s.copy(checkinDueAtMs = null)

        glucoseTimerDue()
        // 공백이 아무리 길어도 래치는 여기서 풀지 않는다(D-32). 시간이 지났다고 값을 확인한 것은 아니다
        glucoseExpiryDue()
        // 끊긴 순간의 안내가 방금 나갔으면 겹쳐 말하지 않는다. 다시 알림 간격(C-35)에 걸려 아무 말도 없었으면 여기서 말한다
        val lostSaid = VoiceId.V19R in voices || VoiceId.V19 in voices

        if (s.restartNoticePending) {
            s = s.copy(restartNoticePending = false)
            when {
                s.isGlucoseLow(p) -> Unit
                s.glucose == Level.STOP -> say(VoiceId.V13)
                s.glucoseLatched -> if (!lostSaid) say(VoiceId.V19R)
                s.level() == Level.NONE && glucoseUnconfirmed() -> if (!lostSaid) say(VoiceId.V19R)
                s.level() == Level.NONE -> say(VoiceId.V20)
            }
        }

        val canAskV01 = s.gear == Gear.PARK && s.fatigue == Level.NONE && s.checkin == Level.NONE &&
            s.checkinAskedDay != ignitionDay
        if (s.glucose == Level.STOP) {
            if (s.isGlucoseLow(p) && !s.departedThisIgnition && s.gear == Gear.PARK) {
                say(VoiceId.V17)
                effects += Trip(TripEvent.CONDITION_STOP_ADVISED)
            }
            if (canAskV01) s = s.copy(deferredCheckin = true)
            return
        }
        if (canAskV01) {
            // 온보딩 중에는 묻지 않는다. 그날 체크인을 쓴 것으로 치면 온보딩 뒤에 다시 물을 수 없다
            if (s.checkinHeld) {
                s = s.copy(deferredCheckin = true)
                return
            }
            askCheckin(VoiceId.V01)
            s = s.copy(checkinAskedDay = ignitionDay)
        }
    }

    /** 온보딩 완료. 시동 5초가 이미 지났을 때만 여기서 묻는다. 안 지났으면 startupDue가 묻는다 */
    private fun onCheckinHold(e: CheckinHold) {
        val wasHeld = s.checkinHeld
        s = s.copy(checkinHeld = e.hold)
        if (e.hold || !wasHeld || !s.deferredCheckin || s.checkinDueAtMs != null) return
        val today = dayIndex(at)
        val canAsk = s.gear == Gear.PARK && s.fatigue == Level.NONE && s.checkin == Level.NONE &&
            s.glucose != Level.STOP && s.checkinAskedDay != today
        if (!canAsk) return
        s = s.copy(deferredCheckin = false, checkinAskedDay = today)
        askCheckin(VoiceId.V01)
    }

    private fun dayIndex(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
            .minusSeconds(p.DAY_BOUNDARY.toSecondOfDay().toLong())
            .toLocalDate().toEpochDay()

    private fun departDue() {
        val since = s.departPendingSinceMs ?: return
        if (at - since >= p.DEPART_CONFIRM_SEC.ms) confirmDepart()
    }

    /** E05. 유예 시간을 운전으로 소급하고 휴식을 지운다. */
    private fun confirmDepart() {
        restDue()
        val remaining = restRemainingSec()
        s = s.copy(
            driveMs = s.driveMs + (s.departPendingDriveMs ?: 0),
            departPendingSinceMs = null,
            departPendingDriveMs = null,
            restStartMs = null,
            departedThisIgnition = true,
        )
        releaseExpiredAdvance()
        effects += Trip(TripEvent.DRIVE_STARTED)

        if (s.level() != Level.STOP) {
            if (s.glucose == Level.CAUTION && !s.glucoseCautionAnnounced) askGlucoseCaution()
            return
        }
        if (s.glucose == Level.STOP) {
            departInGlucoseStop()
            if (s.fatigue == Level.STOP || s.checkin == Level.STOP) {
                s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
            }
            return
        }
        if (s.checkin == Level.STOP && s.checkinRestDone) {
            // 쉬었는데도 STOP에 갇히면 앱이 계속 재촉한다. 쉬었으면 한 단계 내린다(5.3).
            s = s.copy(
                checkin = Level.CAUTION,
                tiredStreak = 1,
                checkinRestDone = false,
                nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms,
            )
            if (s.level() != Level.STOP) return
        }
        if (remaining != null) say(VoiceId.V18, VoiceVars(remainingSec = remaining))
        s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
    }

    private fun departInGlucoseStop() {
        val timer = s.glucoseTimerStartMs
        when {
            s.isGlucoseLow(p) -> {
                sayGlucoseStop()
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_LOW_SEC.ms)
            }
            timer != null -> {
                say(VoiceId.V24, VoiceVars(remainingSec = timerRemainingSec(timer)))
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
            else -> {
                say(VoiceId.V25)
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
        }
    }

    private fun timerRemainingSec(timerStart: Long): Long =
        ceilSec(p.GLUCOSE_WAIT_SEC.ms - (at - timerStart).coerceAtLeast(0))

    private fun sayGlucoseStop() {
        effects += CancelVoice
        clearActivePrompt()
        say(VoiceId.V09)
        effects += Trip(TripEvent.CONDITION_STOP_ADVISED)
        if (!s.autoGuidanceDone) {
            s = s.copy(autoGuidanceDone = true)
            effects += StartGuidance(auto = true)
        }
    }

    /** E30. 공백 동안 무슨 일이 있었는지는 증거로만 판단한다. */
    private fun onRestored(e: ProcessRestored) {
        val gap = (at - gapStart).coerceAtLeast(p.CLOCK_BACKWARD_GAP)
        // 재부팅이면 그 값이 언제 측정됐는지 믿을 수 없다(10.1 규칙 4). 마감보다 먼저 지워야 공백에 걸린 40분이 회복으로 처리되지 않는다
        if (e.rebooted) {
            s.glucoseExpiresAtMs?.let { s = s.copy(glucoseExpiresAtMs = minOf(it, gapStart)) }
        }
        when (e.gap) {
            GapEvidence.NONE -> Unit
            GapEvidence.DROVE -> addDroveGap(gap)
            // 직전이 P나 꺼짐이면 3.2에 따라 정차로 본다. 증거 없는 공백을 운전으로 치는 건 주행 중일 때뿐이다.
            GapEvidence.UNKNOWN -> if (!s.isParked()) addDroveGap(gap)
            GapEvidence.PARKED -> if (!s.isHardParked()) {
                s = s.copy(
                    gear = Gear.PARK,
                    speedKmh = 0.0,
                    restStartMs = s.restStartMs ?: gapStart,
                    departPendingSinceMs = null,
                    departPendingDriveMs = null,
                )
            }
        }
    }

    private fun addDroveGap(gap: Long) {
        s = s.copy(
            driveMs = s.driveMs + gap,
            restStartMs = null,
            departPendingSinceMs = null,
            departPendingDriveMs = null,
        )
    }

    private fun onWaypoint() {
        val pausedUntil = s.promptPausedUntilMs ?: return
        val next = minOf(pausedUntil, at + p.WAYPOINT_GRACE_SEC.ms)
        s = s.copy(promptPausedUntilMs = next, nextPromptAtMs = next)
    }

    // ---- 피로 ----

    private fun resetFatigue() {
        s = s.copy(
            fatigue = Level.NONE,
            driveMs = 0,
            ignoredCount = 0,
            advanceLatched = false,
            muted = false,
            restStartMs = null,
            nextPromptAtMs = null,
            promptPausedUntilMs = null,
            measuredSinceAppStart = false,
        )
        refreshAdvance()
    }

    /** 쌓인 운전이 없는 새 운행에 수면 값이 없으면 앞당김을 끌고 가지 않는다. 시동 신호가 없는 차종은 재시동을 볼 수 없다 */
    private fun releaseExpiredAdvance() {
        if (!s.advanceLatched || s.driveMs > 0) return
        val expires = s.sleepExpiresAtMs
        if (s.sleepMinutes == null || (expires != null && at >= expires)) s = s.copy(advanceLatched = false)
    }

    /** 수면 값이 살아 있으면 다음 구간도 앞당긴다. 만료됐으면 임계가 기본으로 돌아간다(C7). */
    private fun refreshAdvance() {
        val minutes = s.sleepMinutes ?: return
        val expires = s.sleepExpiresAtMs
        if (expires != null && at >= expires) return
        if (minutes < p.SLEEP_SHORT_MINUTES) s = s.copy(advanceLatched = true)
    }

    /** E04. */
    private fun restDue() {
        val rest = s.restStartMs ?: return
        if (at - rest < p.REST_RESET_SEC.ms) return
        val wasFatigued = s.fatigue != Level.NONE
        resetFatigue()
        // 대체 정차는 기어 신호가 없어 다음 휴식의 시작을 달리 알 수 없다.
        if (s.isFallbackParked()) s = s.copy(restStartMs = at)
        effects += Trip(TripEvent.REST_COMPLETED)
        if (s.checkin != Level.NONE) {
            askCheckin(VoiceId.V03, postRest = true)
            return
        }
        if (!wasFatigued) return
        when {
            s.glucose == Level.STOP -> say(VoiceId.V13)
            s.glucoseLatched -> say(VoiceId.V19R)
            // 값을 받던 사용자가 지금 값이 없으면 "괜찮으면 출발하세요"라고 할 수 없다(규칙 5, D-31)
            s.level() == Level.NONE && glucoseUnconfirmed() -> say(VoiceId.V19R)
            s.level() == Level.NONE -> say(VoiceId.V12)
        }
    }

    /** E01, E02. */
    private fun thresholds() {
        val cds = s.driveMs
        val target = when {
            cds >= s.stopThresholdSec(p).ms -> Level.STOP
            cds >= s.cautionThresholdSec(p).ms -> Level.CAUTION
            else -> Level.NONE
        }
        if (target <= s.fatigue) return
        val levelBefore = s.level()
        s = s.copy(fatigue = target)
        if (!s.isDriving()) return
        if (target == Level.CAUTION) reachCaution(levelBefore) else reachStop(levelBefore)
    }

    private fun reachCaution(levelBefore: Level) {
        if (levelBefore == Level.STOP) return
        if (arrivalSoon()) {
            sayArrivalSoon()
            return
        }
        say(VoiceId.V02, driveVars(advance = true))
        if (s.glucose == Level.CAUTION) {
            // 질문을 둘 하지 않는다. 혈당이 먼저다(5.5).
            if (!s.glucoseCautionAnnounced) askGlucoseCaution()
        } else {
            askCheckin(VoiceId.V03)
        }
    }

    private fun reachStop(levelBefore: Level) {
        if (levelBefore == Level.STOP) {
            if (s.nextPromptAtMs == null) s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
            return
        }
        s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
        if (arrivalSoon()) {
            sayArrivalSoon()
            return
        }
        effects += Trip(TripEvent.STOP_ADVISED)
        askStop(VoiceId.V04, PromptKind.FATIGUE, driveVars(advance = true))
    }

    /** E14. */
    private fun repromptDue() {
        val due = s.nextPromptAtMs ?: return
        if (at < due) return
        if (s.level() != Level.STOP || !s.isDriving()) {
            s = s.copy(nextPromptAtMs = null, promptPausedUntilMs = null)
            return
        }
        s = s.copy(promptPausedUntilMs = null, nextPromptAtMs = at + promptIntervalSec().ms)
        when {
            // 혈당 안내 중에는 다른 권고를 겹치지 않는다(G > F > C). 시각만 뒤로 민다.
            s.glucose == Level.STOP -> Unit
            arrivalSoon() -> sayArrivalSoon()
            s.ignoredCount >= p.FIRM_AFTER_DECLINES -> {
                effects += Trip(TripEvent.STOP_ADVISED)
                val kind = if (s.fatigue == Level.STOP) PromptKind.FATIGUE else PromptKind.CHECKIN
                askStop(VoiceId.V05, kind, driveVars())
            }
            s.fatigue == Level.STOP -> {
                effects += Trip(TripEvent.STOP_ADVISED)
                askStop(VoiceId.V04, PromptKind.FATIGUE, driveVars())
            }
            s.checkin == Level.STOP -> {
                effects += Trip(TripEvent.STOP_ADVISED)
                askStop(VoiceId.V06, PromptKind.CHECKIN)
            }
            else -> s = s.copy(nextPromptAtMs = null)
        }
    }

    // ---- 프롬프트 응답 ----

    private fun onPrompt(e: PromptHeard) {
        val kind = s.activePromptKind
        if (e.promptId != s.activePromptId || kind == null) return
        if (e.answer == PromptAnswer.UNCLASSIFIED && !s.promptRetried) {
            askStop(VoiceId.V21S, kind, retried = true)
            return
        }
        val answer = if (e.answer == PromptAnswer.UNCLASSIFIED) PromptAnswer.NO_SPEECH else e.answer
        clearActivePrompt()
        if (kind == PromptKind.GLUCOSE_CAUTION) {
            if (answer == PromptAnswer.ACCEPT) {
                say(VoiceId.V29)
                effects += StartGuidance(auto = false)
            }
            return
        }
        when (answer) {
            PromptAnswer.ACCEPT -> {
                say(VoiceId.V29)
                effects += StartGuidance(auto = false)
                val until = at + p.ACCEPT_PAUSE_SEC.ms
                s = s.copy(promptPausedUntilMs = until, nextPromptAtMs = until)
            }
            PromptAnswer.DECLINE -> {
                s = s.copy(ignoredCount = s.ignoredCount + 1, nextPromptAtMs = at + promptIntervalSec().ms)
                effects += Trip(TripEvent.DECLINED)
                if (kind == PromptKind.FATIGUE && !s.ventilationOffered && s.glucose == Level.NONE) {
                    s = s.copy(ventilationOffered = true)
                    effects += OfferVentilation
                }
            }
            PromptAnswer.MUTE -> {
                s = s.copy(
                    ignoredCount = s.ignoredCount + 1,
                    muted = true,
                    nextPromptAtMs = at + p.MUTED_REPROMPT_SEC.ms,
                )
                effects += Trip(TripEvent.DECLINED)
                say(VoiceId.V26)
            }
            else -> s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
        }
    }

    // ---- 체크인 응답 ----

    private fun onCheckin(e: CheckinHeard) {
        if (e.promptId != s.activePromptId || s.activePromptKind != null) return
        val postRest = s.activePromptPostRest
        if (e.answer == CheckinAnswer.UNCLASSIFIED && !s.promptRetried) {
            askCheckin(VoiceId.V21, postRest = postRest, retried = true)
            return
        }
        val answer = if (e.answer == CheckinAnswer.UNCLASSIFIED) CheckinAnswer.NEUTRAL else e.answer
        val hadCheckin = s.checkin != Level.NONE
        clearActivePrompt()
        s = s.copy(lastCheckin = answer)
        // 두 번 물어도 못 알아들었으면 괜찮다는 말이 아니다. 피곤하다던 사람의 주의를 이걸로 풀지 않는다
        if (e.answer == CheckinAnswer.UNCLASSIFIED && hadCheckin) {
            checkinSilent(postRest)
            return
        }
        when (answer) {
            CheckinAnswer.GOOD, CheckinAnswer.NEUTRAL -> checkinCleared(postRest)
            CheckinAnswer.NO_SPEECH -> checkinSilent(postRest)
            else -> checkinTired(postRest)
        }
    }

    /** E08. */
    private fun checkinCleared(postRest: Boolean) {
        val wasRecover = s.driverState() == DriverState.RECOVER
        s = s.copy(checkin = Level.NONE, tiredStreak = 0, checkinRestDone = false, nextCheckinDriveMs = null)
        // 쉬지 않았는데 "15분 쉬었어요"라고 하면 안 된다. 재질문 답일 때만 말한다.
        if (!wasRecover || !postRest || s.fatigue != Level.NONE) return
        when {
            s.glucose == Level.STOP -> say(VoiceId.V13)
            s.glucoseLatched -> say(VoiceId.V19R)
            s.level() == Level.NONE && glucoseUnconfirmed() -> say(VoiceId.V19R)
            s.level() == Level.NONE -> say(VoiceId.V12)
        }
    }

    private fun checkinSilent(postRest: Boolean) {
        if (postRest) {
            s = s.copy(checkin = Level.CAUTION, tiredStreak = 1, nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms)
        } else if (s.checkin != Level.NONE) {
            s = s.copy(nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms)
        }
    }

    private fun checkinTired(postRest: Boolean) {
        if (postRest && s.isParked()) {
            s = s.copy(restStartMs = at, checkinRestDone = true)
            say(VoiceId.V23)
            return
        }
        if (s.tiredStreak + 1 < p.TIRED_STREAK_STOP) {
            // 정차 중이면 쉬기 시작한 셈이다. 15분 뒤 재질문이 돌도록 타이머를 건다.
            if (s.isParked() && s.restStartMs == null) s = s.copy(restStartMs = at)
            s = s.copy(
                checkin = Level.CAUTION,
                tiredStreak = 1,
                nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms,
            )
            say(VoiceId.V31)
            return
        }
        s = s.copy(checkin = Level.STOP, tiredStreak = p.TIRED_STREAK_STOP, nextCheckinDriveMs = null)
        // 6.3: P에서는 정차 권고를 내지 않는다. 휴식 15분 뒤 재질문으로 이어진다.
        if (!s.isDriving()) return
        s = s.copy(nextPromptAtMs = at + promptIntervalSec().ms)
        effects += Trip(TripEvent.STOP_ADVISED)
        askStop(VoiceId.V06, PromptKind.CHECKIN)
    }

    /** C=CAUTION 유지 중 운전 30분마다(3.5). */
    private fun checkinReaskDue() {
        val next = s.nextCheckinDriveMs ?: return
        if (s.checkin != Level.CAUTION || s.driveMs < next) return
        s = s.copy(nextCheckinDriveMs = s.driveMs + p.CHECKIN_REASK_DRIVE_SEC.ms)
        if (s.level() != Level.STOP && s.isDriving()) askCheckin(VoiceId.V03)
    }

    /** E32. 시간대는 임계를 바꾸지 않고 체크인만 더한다(3.3). */
    private fun nightCheckin() {
        val mark = p.NIGHT_CHECKIN_DRIVE_SEC.ms
        if (s.nightCheckinDone || driveBefore >= mark || s.driveMs < mark) return
        if (!s.isDriving()) return
        val local = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        if (local < p.NIGHT_START || local >= p.NIGHT_END) return
        s = s.copy(nightCheckinDone = true)
        if (s.level() != Level.STOP) askCheckin(VoiceId.V03)
    }

    // ---- 혈당 ----

    private fun onCondition(e: ConditionReceived) {
        if (e.unpaired) {
            onUnpaired()
            return
        }
        val input = e.input
        s = s.copy(sleepMinutes = input?.sleepMinutes, sleepExpiresAtMs = input?.sleepExpiresAtMs)
        refreshAdvance()

        val mmol = input?.glucoseMmol
        val measured = input?.glucoseMeasuredAtMs
        val expires = input?.glucoseExpiresAtMs
        if (mmol == null || measured == null || expires == null || expires <= at) {
            // 값이 사라진 처리는 만료 단계 하나로 모은다. 시동 직후에는 5초 뒤로 자연히 미뤄진다.
            if (s.glucoseExpiresAtMs != null) s = s.copy(glucoseExpiresAtMs = at)
            return
        }
        val last = s.glucoseLastMeasuredMs
        val lost = s.glucoseExpiresAtMs == null
        when {
            last == null || measured > last -> onGlucoseRead(mmol, input.glucoseTrend ?: Trend.FLAT, measured, expires, input.glucoseSimulated)
            measured == last && lost -> onGlucoseRead(mmol, input.glucoseTrend ?: Trend.FLAT, measured, expires, input.glucoseSimulated)
        }
    }

    /** E33. */
    private fun onUnpaired() {
        clearGlucoseTrack()
        s = s.copy(
            sleepMinutes = null,
            sleepExpiresAtMs = null,
            advanceLatched = false,
        )
        // 앞당김으로 오른 F는 근거가 사라졌다. 기본 임계로 다시 잰다
        val cds = s.driveMs
        val target = when {
            cds >= s.stopThresholdSec(p).ms -> Level.STOP
            cds >= s.cautionThresholdSec(p).ms -> Level.CAUTION
            else -> Level.NONE
        }
        if (target < s.fatigue) s = s.copy(fatigue = target)
    }

    /**
     * E34. 차에서 사람이 푸는 혈당 보류(D-33). 폰 쪽 해제와 401은 값 없음이라 래치가 남으므로(R-07) 차에도 푸는 길이 있어야 한다.
     * 주행 중에는 받지 않는다. 지금 유효한 값이 있으면 그 값이 판단이므로 받지 않는다.
     * 실제 낮은 값에서 온 보류는 마지막 판독에서 40분이 지나야 받는다. 혈당 공유를 끄자마자 풀면 회복 대기를 건너뛴다.
     */
    private fun onHoldReleased() {
        if (!s.isHardParked() && !s.isFallbackParked()) return
        if (!glucoseUnconfirmed()) return
        val floor = s.holdReleaseFloorMs(p) ?: return
        if (at < floor) return
        clearGlucoseTrack()
        logEvent = LogEvent.GLUCOSE_HOLD_RELEASED
    }

    /** 값을 받은 적이 있는데 지금 유효한 값이 없다. 만료 처리를 기다리는 값도 이미 무효다 */
    private fun glucoseUnconfirmed(): Boolean {
        if (s.glucoseLastValidMs == null) return false
        val expires = s.glucoseExpiresAtMs ?: return true
        return at >= expires
    }

    /** 혈당 트랙을 값을 받은 적 없는 상태로 되돌린다. 다음 값은 첫 판독으로 다시 판단한다 */
    private fun clearGlucoseTrack() {
        s = s.copy(
            glucose = Level.NONE,
            glucoseTimerStartMs = null,
            glucoseLatched = false,
            glucoseLastValidMs = null,
            glucoseLastMeasuredMs = null,
            glucoseLastTrend = null,
            glucoseCautionClearReads = 0,
            glucoseCautionEscalated = false,
            glucoseCautionAnnounced = false,
            glucoseLostAnnouncedAtMs = null,
            glucoseValueAtLast = null,
            glucoseExpiresAtMs = null,
            glucoseSimulated = false,
            glucoseLostAtMs = null,
            glucoseValidSinceMs = null,
            nextGlucoseRepeatAtMs = null,
            autoGuidanceDone = false,
            glucoseLatchFromCaution = false,
        )
    }

    private fun onGlucoseRead(mmol: Double, trend: Trend, measured: Long, expires: Long, simulated: Boolean) {
        val before = s
        val latched = before.glucoseLatched
        val wasLow = before.isGlucoseLow(p)
        s = s.copy(
            glucoseLastMeasuredMs = measured,
            glucoseExpiresAtMs = expires,
            glucoseSimulated = simulated,
            glucoseLastValidMs = at,
            glucoseValidSinceMs = before.glucoseValidSinceMs ?: at,
            glucoseValueAtLast = mmol,
            glucoseLastTrend = trend,
            glucoseLatched = false,
            glucoseLatchFromCaution = false,
        )
        when {
            latched && before.glucoseLatchFromCaution -> readAfterCautionLatch(before, mmol, trend)
            latched -> readAfterLatch(before, mmol)
            mmol < p.GLUCOSE_STOP_BELOW -> enterLow(wasLow)
            s.glucose == Level.STOP -> readInStop(mmol, wasLow)
            else -> readOutsideStop(before, mmol, trend)
        }
    }

    /** E17. */
    private fun enterLow(wasLow: Boolean) {
        s = s.copy(
            glucose = Level.STOP,
            glucoseTimerStartMs = null,
            glucoseCautionClearReads = 0,
            glucoseCautionEscalated = false,
            glucoseCautionAnnounced = false,
        )
        if (wasLow) return
        if (s.isDriving()) {
            sayGlucoseStop()
            s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_LOW_SEC.ms)
            return
        }
        // 시동 직후면 5초 시점에 V17로 한꺼번에 말한다(E29).
        if (s.ignition == Ignition.OFF || s.checkinDueAtMs != null) return
        effects += CancelVoice
        clearActivePrompt()
        if (s.departedThisIgnition) {
            say(VoiceId.V14)
        } else {
            say(VoiceId.V17)
            effects += Trip(TripEvent.CONDITION_STOP_ADVISED)
        }
    }

    /** E19, E20. */
    private fun readInStop(mmol: Double, wasLow: Boolean) {
        val driving = s.isDriving()
        if (wasLow && driving) {
            // 값이 4.0 위로 오면 반복 간격을 2분에서 10분으로 늦춘다(C-30, C-31).
            s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
        }
        val timer = s.glucoseTimerStartMs
        when {
            timer == null && mmol >= p.GLUCOSE_RECOVER_AT -> {
                s = s.copy(glucoseTimerStartMs = at)
                say(VoiceId.V15)
                if (driving) s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
            timer != null && mmol < p.GLUCOSE_RECOVER_AT -> s = s.copy(glucoseTimerStartMs = null)
        }
    }

    private fun cautionCondition(mmol: Double, trend: Trend): Boolean = when (trend) {
        Trend.FALLING_FAST -> mmol < p.CAUTION_FALLING_FAST_BELOW
        Trend.FALLING -> mmol < p.CAUTION_FALLING_BELOW
        Trend.FLAT -> mmol < p.CAUTION_FLAT_BELOW
        Trend.RISING -> false
    }

    private fun readOutsideStop(before: EngineState, mmol: Double, trend: Trend) {
        val condition = cautionCondition(mmol, trend)
        if (s.glucose == Level.NONE) {
            if (!condition) return
            val levelBefore = s.level()
            s = s.copy(glucose = Level.CAUTION, glucoseCautionClearReads = 0)
            // 같은 순간 피로 주의도 오르면 V02 다음에 V08이 나가야 해서 임계 단계로 넘긴다(5.5).
            val fatigueRising = s.driveMs >= s.cautionThresholdSec(p).ms && s.fatigue == Level.NONE
            if (s.isDriving() && levelBefore != Level.STOP && !fatigueRising) askGlucoseCaution()
            return
        }
        if (!condition) {
            val reads = s.glucoseCautionClearReads + 1
            s = if (reads >= p.CAUTION_CLEAR_READS) {
                s.copy(
                    glucose = Level.NONE,
                    glucoseCautionClearReads = 0,
                    glucoseCautionEscalated = false,
                    glucoseCautionAnnounced = false,
                )
            } else {
                s.copy(glucoseCautionClearReads = reads)
            }
            return
        }
        s = s.copy(glucoseCautionClearReads = 0)
        val trendWorse = before.glucoseLastTrend == Trend.FALLING && trend == Trend.FALLING_FAST
        val crossedRecover = (before.glucoseValueAtLast ?: Double.MAX_VALUE) >= p.GLUCOSE_RECOVER_AT &&
            mmol < p.GLUCOSE_RECOVER_AT
        if ((trendWorse || crossedRecover) && !s.glucoseCautionEscalated && s.isDriving() && s.level() != Level.STOP) {
            s = s.copy(glucoseCautionEscalated = true)
            askGlucoseCaution()
        }
    }

    /**
     * 5.2 복귀 처리, 주의에서 온 래치(D-31). 끊기기 전의 주의를 그대로 잇는다.
     * 해제는 평소처럼 새 판독 2회가 필요하다. 이미 들은 V08은 다시 묻지 않는다.
     */
    private fun readAfterCautionLatch(before: EngineState, mmol: Double, trend: Trend) {
        if (mmol < p.GLUCOSE_STOP_BELOW) {
            enterLow(wasLow = false)
            return
        }
        s = s.copy(glucose = Level.CAUTION, glucoseCautionClearReads = 0)
        // 끊긴 사이 더 나빠졌으면 들은 V08은 지난 값에 대한 것이다. 출발할 때 다시 알린다
        val prev = before.glucoseValueAtLast
        val crossed = prev != null && prev >= p.GLUCOSE_RECOVER_AT && mmol < p.GLUCOSE_RECOVER_AT
        val trendWorse = trendRank(trend) > trendRank(before.glucoseLastTrend ?: Trend.FLAT)
        if (crossed || trendWorse) s = s.copy(glucoseCautionAnnounced = false, glucoseCautionEscalated = false)
        readOutsideStop(before, mmol, trend)
        if (s.glucose == Level.CAUTION && !s.glucoseCautionAnnounced && s.isDriving() && s.level() != Level.STOP) {
            askGlucoseCaution()
        }
    }

    private fun trendRank(trend: Trend): Int = when (trend) {
        Trend.RISING -> 0
        Trend.FLAT -> 1
        Trend.FALLING -> 2
        Trend.FALLING_FAST -> 3
    }

    /** 5.2 복귀 처리, STOP에서 온 래치. */
    private fun readAfterLatch(before: EngineState, mmol: Double) {
        val driving = s.isDriving()
        when {
            mmol < p.GLUCOSE_STOP_BELOW -> {
                // 래치 중에는 G가 NONE이라 직전 값을 "낮음"으로 보지 않게 false를 넘긴다.
                enterLow(wasLow = false)
            }
            mmol < p.GLUCOSE_RECOVER_AT -> {
                s = s.copy(glucose = Level.STOP, glucoseTimerStartMs = null)
                if (driving) {
                    say(VoiceId.V25)
                    s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
                } else {
                    say(VoiceId.V14)
                }
            }
            else -> {
                val timer = before.glucoseTimerStartMs
                val lostAt = before.glucoseLostAtMs
                val resumable = timer != null && lostAt != null && at - lostAt <= p.GLUCOSE_RESUME_GAP_SEC.ms
                s = s.copy(glucose = Level.STOP, glucoseTimerStartMs = if (resumable) timer else at)
                if (!resumable) say(VoiceId.V15)
                if (driving) s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
        }
    }

    /** E21. 값을 확인하지 못한 채 40분이 지났으면 회복이 아니다(규칙 5). */
    private fun glucoseTimerDue() {
        if (s.glucose != Level.STOP || s.checkinDueAtMs != null) return
        val timer = s.glucoseTimerStartMs ?: return
        val done = timer + p.GLUCOSE_WAIT_SEC.ms
        val expires = s.glucoseExpiresAtMs ?: return
        if (at < done || expires < done) return

        val wasDriving = s.isDriving()
        s = s.copy(
            glucose = Level.NONE,
            glucoseTimerStartMs = null,
            nextGlucoseRepeatAtMs = null,
            autoGuidanceDone = false,
            glucoseCautionClearReads = 0,
            glucoseCautionEscalated = false,
            glucoseCautionAnnounced = false,
        )
        val mmol = s.glucoseValueAtLast
        val cautionNow = mmol != null && cautionCondition(mmol, s.glucoseLastTrend ?: Trend.FLAT)
        if (cautionNow) s = s.copy(glucose = Level.CAUTION)
        if (wasDriving) {
            say(VoiceId.V16D)
            if (cautionNow && s.level() != Level.STOP) askGlucoseCaution()
            return
        }
        // 값이 주의 범위면 V16을 말할 수 없다. 그래도 V15가 약속한 "40분 뒤 알림"은 지킨다
        if (s.level() == Level.NONE) say(VoiceId.V16) else if (cautionNow) say(VoiceId.V16C)
        if (s.deferredCheckin && !s.checkinHeld) {
            s = s.copy(deferredCheckin = false, checkinAskedDay = dayIndex(at))
            askCheckin(VoiceId.V01)
        }
    }

    /** E22. 처리했으면 true. */
    private fun glucoseExpiryDue(): Boolean {
        val expires = s.glucoseExpiresAtMs ?: return false
        if (s.checkinDueAtMs != null || at < expires) return false

        val announced = s.glucoseLostAnnouncedAtMs
        val validSince = s.glucoseValidSinceMs
        val rearmed = announced == null || (validSince != null && at - validSince >= p.GLUCOSE_LOST_REARM_SEC.ms)
        val wasStop = s.glucose == Level.STOP
        // 주의 중에 끊겨도 "여유"로 내려가지 않는다(D-31). 값이 더 내려갔는지 앱이 모른다
        val wasCaution = s.glucose == Level.CAUTION
        // 공백 길이와 무관하다(D-32). 풀리는 길은 유효 값 복귀, S5 연결 해제(E33), S5 보류 풀기(E34)다
        val latch = wasStop || wasCaution
        // 정차 중이면 래치가 없어도 V19R이다. 값을 받던 사용자에게 다음 말이 "출발하세요"가 되지 않게 한다(D-31 B)
        val voice = if (s.isDriving()) VoiceId.V19 else VoiceId.V19R
        s = s.copy(
            glucose = Level.NONE,
            glucoseLatched = latch,
            glucoseLatchFromCaution = wasCaution,
            glucoseTimerStartMs = if (wasStop) s.glucoseTimerStartMs else null,
            glucoseExpiresAtMs = null,
            glucoseLostAtMs = at,
            glucoseValidSinceMs = null,
            glucoseCautionClearReads = 0,
            // 주의 래치는 복귀 뒤 같은 주의를 잇는다. 이미 들은 V08을 다시 묻지 않게 둔다
            glucoseCautionEscalated = wasCaution && s.glucoseCautionEscalated,
            glucoseCautionAnnounced = wasCaution && s.glucoseCautionAnnounced,
            nextGlucoseRepeatAtMs = null,
        )
        if (wasStop) effects += CancelPendingPrompts
        if (rearmed) {
            s = s.copy(glucoseLostAnnouncedAtMs = at)
            say(voice)
        }
        return true
    }

    /** E18. */
    private fun glucoseRepeatDue() {
        val due = s.nextGlucoseRepeatAtMs ?: return
        if (at < due) return
        if (s.glucose != Level.STOP || !s.isDriving()) {
            s = s.copy(nextGlucoseRepeatAtMs = null)
            return
        }
        val timer = s.glucoseTimerStartMs
        when {
            s.isGlucoseLow(p) -> {
                say(VoiceId.V10)
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_LOW_SEC.ms)
            }
            timer != null -> {
                say(VoiceId.V24, VoiceVars(remainingSec = timerRemainingSec(timer)))
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
            else -> {
                say(VoiceId.V25)
                s = s.copy(nextGlucoseRepeatAtMs = at + p.GLUCOSE_REPEAT_RECOVERING_SEC.ms)
            }
        }
    }

    private val Long.ms: Long get() = this * 1000
}
