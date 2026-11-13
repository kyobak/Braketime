package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import java.time.OffsetDateTime

/** 시나리오 JSON으로 쓰기 번거로운 경계 표를 위한 작은 구동기. */
internal class Driver(
    val engine: Engine = Engine(),
    start: String = "2026-11-20T09:00:00+09:00",
) {
    val base: Long = OffsetDateTime.parse(start).toInstant().toEpochMilli()
    var now: Long = base
    var state: EngineState = engine.initial(now)
    val spoken = mutableListOf<VoiceId>()
    val effects = mutableListOf<Effect>()

    private fun record(decision: Decision) {
        state = decision.state
        effects += decision.effects
        spoken += decision.effects.mapNotNull {
            when (it) {
                is Say -> it.voice
                is AskCheckin -> it.voice
                is AskStop -> it.voice
                else -> null
            }
        }
    }

    fun send(event: EngineEvent) = record(engine.reduce(state, event))

    fun tick(ms: Long = now) {
        now = ms
        send(Tick(now))
    }

    /** 마감에 착지하며 sec초 앞으로 간다. */
    fun advance(sec: Long) {
        val target = now + sec * 1000
        while (true) {
            val deadline = engine.nextDeadlineMs(state)
            if (deadline == null || deadline > target) break
            tick(maxOf(deadline, now))
        }
        tick(target)
    }

    fun vehicle(gear: Gear, speed: Double?, ignition: Ignition = Ignition.ON) =
        send(VehicleChanged(now, gear, speed, ignition))

    fun drive(speed: Double = 80.0) = vehicle(Gear.DRIVE, speed)
    fun park() = vehicle(Gear.PARK, 0.0)

    fun glucose(mmol: Double, trend: Trend? = Trend.FLAT, ttlSec: Long = 900, measuredAt: Long = now) = send(
        ConditionReceived(
            now,
            ConditionInput(null, null, mmol, trend, measuredAt, measuredAt + ttlSec * 1000, true),
        ),
    )

    fun sleep(minutes: Int?, ttlSec: Long = 86_400) = send(
        ConditionReceived(
            now,
            ConditionInput(minutes, minutes?.let { now + ttlSec * 1000 }, null, null, null, null, false),
        ),
    )

    fun answerCheckin(answer: CheckinAnswer) = send(CheckinHeard(now, state.activePromptId ?: -1, answer))
    fun answerPrompt(answer: PromptAnswer) = send(PromptHeard(now, state.activePromptId ?: -1, answer))

    /** P0 없이 시동을 걸고 V01까지 끝낸 뒤 D로 출발한다. */
    fun startDriving(speed: Double = 80.0) {
        park()
        advance(5)
        answerCheckin(CheckinAnswer.GOOD)
        drive(speed)
        spoken.clear()
        effects.clear()
    }

    val view: EngineView get() = engine.view(state, now)
}
