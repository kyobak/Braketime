package com.conditiondrive.vehicle.demo

import com.conditiondrive.core.time.Clock
import com.conditiondrive.vehicle.runtime.RuntimePacer

/**
 * 15.3 시간 압축. 엔진은 보통 시계로 알고 쓴다.
 * 마감과 시나리오 줄을 넘어가지 않으므로 ×240이어도 정확히 그 시각에 전이한다.
 */
class CompressedClock(startMs: Long, private val baseClock: () -> Long) : Clock {
    private var anchorReal = baseClock()
    private var anchorSim = startMs
    private var multiplier = 1
    private var frozen = false
    private var deadline: Long? = null
    private var hold: Long? = null

    val speed: Int @Synchronized get() = multiplier
    val isFrozen: Boolean @Synchronized get() = frozen

    @Synchronized
    fun setSpeed(multiplier: Int) {
        settle()
        this.multiplier = multiplier
    }

    @Synchronized
    fun freeze() {
        settle()
        frozen = true
    }

    @Synchronized
    fun unfreeze() {
        settle()
        frozen = false
    }

    /** 다음 마감을 넘어가지 않음. null이면 마감이 없다 */
    @Synchronized
    fun landAt(deadlineMs: Long?) {
        settle()
        deadline = deadlineMs
    }

    /** 다음 시나리오 줄 앞에서 멈춘다. 같은 시각이면 입력이 마감보다 먼저라서(3.4) 컨트롤러가 직접 넘어간다 */
    @Synchronized
    fun holdAt(ms: Long?) {
        settle()
        hold = ms
    }

    /** 앞으로만 간다. 스크립트 응답을 그 줄의 시각에 넣으려고 음성 정지 중에 시계를 옮길 때 쓴다 */
    @Synchronized
    fun jumpTo(ms: Long) {
        settle()
        if (ms > anchorSim) anchorSim = ms
    }

    /** 엔진 시각 차이를 지금 배속으로 실제 시간으로 바꾼다 */
    @Synchronized
    fun realDelayMs(simDeltaMs: Long): Long = when {
        frozen -> FROZEN_POLL_MS
        else -> ((simDeltaMs + multiplier - 1) / multiplier).coerceAtLeast(MIN_POLL_MS)
    }

    @Synchronized
    override fun nowMs(): Long {
        settle()
        return anchorSim
    }

    /** 천장에 닿으면 넘친 시간은 버린다. 천장이 다시 올라가도 그 순간부터 이어 가야 시각이 튀지 않는다 */
    private fun settle() {
        val real = baseClock()
        if (!frozen) {
            val raw = anchorSim + (real - anchorReal) * multiplier
            val ceiling = listOfNotNull(deadline, hold).minOrNull()
            anchorSim = if (ceiling == null) raw else maxOf(anchorSim, minOf(raw, ceiling))
        }
        anchorReal = real
    }

    private companion object {
        const val FROZEN_POLL_MS = 250L
        const val MIN_POLL_MS = 20L     // 천장 1ms 앞에서 틱이 공회전하지 않게 한다
    }
}

/**
 * 시연 시계를 런타임에 잇는다. 엔진이 다음 마감을 알려 주면 그 앞에서 멈추고, 음성 중에는 시계를 세운다.
 * 시나리오 줄을 넣는 동안의 정지(setHeld)와 음성 정지는 따로 켜지므로 둘 다 꺼져야 시계가 간다.
 */
class CompressedClockPacer(private val clock: CompressedClock) : RuntimePacer {
    @Volatile private var speechBusy = false
    @Volatile private var held = false

    override fun realDelayMs(engineDeltaMs: Long): Long = clock.realDelayMs(engineDeltaMs)

    override fun onNextDeadline(deadlineMs: Long?) = clock.landAt(deadlineMs)

    override fun onSpeechBusy(busy: Boolean) {
        speechBusy = busy
        apply()
    }

    fun setHeld(on: Boolean) {
        held = on
        apply()
    }

    private fun apply() {
        if (speechBusy || held) clock.freeze() else clock.unfreeze()
    }
}
