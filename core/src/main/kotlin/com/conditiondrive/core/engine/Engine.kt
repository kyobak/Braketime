package com.conditiondrive.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Engine(val policy: Policy = Policy.DEFAULT, val zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")) {

    // 시동 신호를 받기 전까지는 꺼짐으로 둔다. 그래야 첫 신호가 "시동 켜짐"으로 처리돼 V01 시계가 돈다.
    fun initial(nowMs: Long): EngineState = EngineState(lastEventMs = nowMs, ignition = Ignition.OFF)

    fun reduce(state: EngineState, event: EngineEvent): Decision {
        val from = state.driverState()
        val reduction = Reduction(policy, zone, state, event.atMs)
        reduction.run(event)
        val next = reduction.s
        val to = next.driverState()
        val logEvent = reduction.logEvent
        val log = if (from != to || reduction.voices.isNotEmpty() || logEvent != null) {
            TransitionEntry(event.atMs, from, to, next.reasons(), reduction.voices.toList(), logEvent)
        } else {
            null
        }
        return Decision(next, reduction.effects.toList(), log)
    }

    /** 시계가 넘어가면 안 되는 다음 시각. 시동이 꺼져 있으면 말할 수 없으므로 없다. */
    fun nextDeadlineMs(state: EngineState): Long? {
        if (state.ignition == Ignition.OFF) return null
        val p = policy
        val deadlines = mutableListOf<Long>()
        state.departPendingSinceMs?.let { deadlines += it + p.DEPART_CONFIRM_SEC * 1000 }
        state.restStartMs?.let { deadlines += it + p.REST_RESET_SEC * 1000 }

        val startup = state.checkinDueAtMs
        if (startup != null) {
            deadlines += startup
        } else {
            state.glucoseExpiresAtMs?.let { deadlines += it }
            if (state.glucose == Level.STOP) {
                state.glucoseTimerStartMs?.let { deadlines += it + p.GLUCOSE_WAIT_SEC * 1000 }
            }
        }

        if (state.accruesDrive()) {
            val driveDeadline = { targetMs: Long -> state.lastEventMs + (targetMs - state.driveMs) }
            when (state.fatigue) {
                Level.NONE -> deadlines += driveDeadline(state.cautionThresholdSec(p) * 1000)
                Level.CAUTION -> deadlines += driveDeadline(state.stopThresholdSec(p) * 1000)
                Level.STOP -> Unit
            }
            if (state.checkin == Level.CAUTION) state.nextCheckinDriveMs?.let { deadlines += driveDeadline(it) }
            val night = p.NIGHT_CHECKIN_DRIVE_SEC * 1000
            if (!state.nightCheckinDone && state.driveMs < night) deadlines += driveDeadline(night)
        }
        // 보류를 풀 수 있게 되는 순간 뷰를 다시 만들어 S5가 버튼을 보이게 한다(D-33). 지난 시각이면 넣지 않아야 틱이 돌지 않는다
        if (state.glucoseExpiresAtMs == null) {
            state.holdReleaseFloorMs(p)?.takeIf { it > state.lastEventMs }?.let { deadlines += it }
        }
        if (state.isDriving()) {
            if (state.level() == Level.STOP) state.nextPromptAtMs?.let { deadlines += it }
            if (state.glucose == Level.STOP) state.nextGlucoseRepeatAtMs?.let { deadlines += it }
        }
        return deadlines.minOrNull()
    }

    fun view(state: EngineState, nowMs: Long): EngineView {
        val p = policy
        val elapsed = (nowMs - state.lastEventMs).coerceAtLeast(0)
        val driveMs = state.driveMs + if (state.accruesDrive()) elapsed else 0
        val cds = driveMs / 1000
        val stopSec = state.stopThresholdSec(p)
        val restElapsedMs = state.restStartMs?.let { (nowMs - it).coerceAtLeast(0) }
        val glucoseValid = state.glucoseExpiresAtMs?.let { nowMs < it } ?: false
        val sleepValid = state.sleepMinutes != null && (state.sleepExpiresAtMs?.let { nowMs < it } ?: true)
        return EngineView(
            state = state.driverState(),
            reasons = state.reasons(),
            continuousDriveSec = cds,
            cautionThresholdSec = state.cautionThresholdSec(p),
            stopThresholdSec = stopSec,
            advanced = if (state.advanceLatched) AdvanceReason.SLEEP_SHORT else null,
            remainingToRestSec = (stopSec - cds).takeIf { it > 0 },
            restElapsedSec = restElapsedMs?.let { it / 1000 },
            restRemainingSec = restElapsedMs?.let { ceilSec(p.REST_RESET_SEC * 1000 - it) },
            glucoseRecoveryRemainingSec = state.glucoseTimerStartMs
                ?.takeIf { state.glucose == Level.STOP }
                ?.let { ceilSec(p.GLUCOSE_WAIT_SEC * 1000 - (nowMs - it).coerceAtLeast(0)) },
            preDepartureHold = state.preDepartureHold(),
            ignoredCount = state.ignoredCount,
            glucoseMmol = state.glucoseValueAtLast.takeIf { glucoseValid },
            glucoseTrend = state.glucoseLastTrend.takeIf { glucoseValid },
            glucoseMeasuredAtMs = state.glucoseLastMeasuredMs.takeIf { glucoseValid },
            glucoseSimulated = glucoseValid && state.glucoseSimulated,
            glucoseUnavailable = state.glucoseLastValidMs != null && !glucoseValid,
            sleepMinutes = state.sleepMinutes.takeIf { sleepValid },
            lastCheckin = state.lastCheckin,
            measuredSinceAppStart = state.measuredSinceAppStart,
            driving = state.isDriving(),
            noDriveSignal = state.isNoSignal(),
            glucoseHoldReleasableAtMs = state.holdReleaseFloorMs(p)?.takeIf { !glucoseValid },
        )
    }
}

object EngineStateCodec {
    // 기본값까지 적어야 필드 기본값이 나중에 바뀌어도 저장본 의미가 그대로다.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(state: EngineState): String = json.encodeToString(EngineState.serializer(), state)

    /** 버전이 다르거나 깨진 저장본은 null이다. 호출부가 새 트립으로 시작한다. */
    fun decode(json: String): EngineState? = runCatching {
        val version = this.json.parseToJsonElement(json).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        if (version != EngineState.SCHEMA_VERSION) return null
        this.json.decodeFromString(EngineState.serializer(), json)
    }.getOrNull()
}
