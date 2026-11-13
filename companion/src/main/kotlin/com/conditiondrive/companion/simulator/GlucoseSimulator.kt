package com.conditiondrive.companion.simulator

import com.conditiondrive.companion.upload.GlucoseReading
import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.SummaryLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class GlucoseUnit { MMOL, MGDL }

data class SimulatorState(
    val running: Boolean = false,
    val hundredths: Int = DEFAULT_HUNDREDTHS,     // 2자리 소수를 정수로 둔다. 0.1씩 더해도 부동소수 오차가 값에 쌓이지 않는다
    val trend: GlucoseTrendDto? = GlucoseTrendDto.FLAT,
    val sensorCut: Boolean = false,
    val unit: GlucoseUnit = GlucoseUnit.MMOL,
    val enteredMgdl: Int? = null,                 // mg/dL로 입력하면 내림 변환 때문에 되돌린 값이 달라져, 입력한 수를 따로 둔다
    val sleepOverrideMinutes: Int? = null,
    val sleepOverrideAtMs: Long = 0,
    val scenarioId: String? = null,
    val scenarioElapsedMs: Long = 0,
    val scenarioNextInMs: Long? = null,
    val glucoseActive: Boolean = true,            // 수면 시나리오처럼 혈당이 없는 재생에서 false
) {
    val mmol: Double get() = hundredths / 100.0
    val inScenario: Boolean get() = scenarioId != null
    /** 이번 주기에 혈당값이 요약에 실리는지. 화면 문구(Copy)도 이것 하나를 봐야 "보내는 중"과 실제 전송이 어긋나지 않는다 */
    val sendsGlucose: Boolean get() = running && !sensorCut && glucoseActive

    companion object {
        const val DEFAULT_HUNDREDTHS = 540
    }
}

/**
 * 폰에서 유일한 혈당 출처다. 내보내는 source는 언제나 simulated다.
 * Health Connect 혈당은 3시간 지연이라 판단에 쓸 수 없어 읽는 코드 자체가 없다(2장).
 */
class GlucoseSimulator {
    private val _state = MutableStateFlow(SimulatorState())
    val state: StateFlow<SimulatorState> = _state.asStateFlow()

    private var timeline: SimTimeline? = null
    private var scenarioStartMs = 0L

    fun start() = _state.update { it.copy(running = true) }

    fun stop() {
        timeline = null
        _state.update {
            it.copy(
                running = false,
                sensorCut = false,
                sleepOverrideMinutes = null,
                scenarioId = null,
                scenarioElapsedMs = 0,
                scenarioNextInMs = null,
                glucoseActive = true,
            )
        }
    }

    fun setUnit(unit: GlucoseUnit) = _state.update { it.copy(unit = unit, enteredMgdl = null) }

    fun setMmolHundredths(hundredths: Int) = manual { it.copy(hundredths = clamp(hundredths), enteredMgdl = null) }

    fun nudgeMmol(deltaHundredths: Int) = manual { it.copy(hundredths = clamp(it.hundredths + deltaHundredths), enteredMgdl = null) }

    fun nudgeMgdl(delta: Int) = manual { state ->
        val shown = state.enteredMgdl ?: GlucoseUnits.toMgdl(state.mmol)
        val entered = (shown + delta).coerceIn(MIN_MGDL, MAX_MGDL)
        state.copy(hundredths = clamp(GlucoseUnits.toHundredths(entered)), enteredMgdl = entered)
    }

    fun setTrend(trend: GlucoseTrendDto?) = manual { it.copy(trend = trend) }

    /** 센서 끊김 재현. 다음 전송의 혈당이 null이 되어 차량이 13장 끊김 규칙으로 들어간다 */
    fun setSensorCut(cut: Boolean) = manual { it.copy(sensorCut = cut) }

    fun setSleepOverride(minutes: Int?, nowMs: Long) =
        _state.update { it.copy(sleepOverrideMinutes = minutes, sleepOverrideAtMs = nowMs) }

    fun play(timeline: SimTimeline, nowMs: Long) {
        this.timeline = timeline
        scenarioStartMs = nowMs
        val hasGlucose = timeline.points.any { it.glucoseMmol != null }
        _state.update {
            it.copy(
                running = true,
                scenarioId = timeline.id,
                sensorCut = false,
                enteredMgdl = null,
                glucoseActive = hasGlucose,
                sleepOverrideMinutes = null,
            )
        }
        advanceTo(nowMs)
    }

    /** 다음 변화 지점으로 건너뛴다. 값만 앞당기고 측정 시각은 실제 시각이라 서버 검증과 어긋나지 않는다 */
    fun skipToNext(nowMs: Long) {
        val current = timeline ?: return
        val elapsed = nowMs - scenarioStartMs
        val next = current.points.firstOrNull { it.atMs > elapsed } ?: return
        scenarioStartMs = nowMs - next.atMs
        advanceTo(nowMs)
    }

    /** 재생 중이면 지난 지점을 모두 반영한다. 전송 직전과 주기마다 부른다 */
    fun advanceTo(nowMs: Long) {
        val current = timeline ?: return
        val elapsed = (nowMs - scenarioStartMs).coerceAtLeast(0)
        _state.update { state ->
            current.points.filter { it.atMs <= elapsed }.fold(state) { acc, point -> apply(acc, point, nowMs) }.copy(
                scenarioElapsedMs = elapsed,
                scenarioNextInMs = current.points.firstOrNull { it.atMs > elapsed }?.let { it.atMs - elapsed },
            )
        }
    }

    /** 켜져 있지 않거나 끊김이면 null. 측정 시각은 읽는 순간이다: 시뮬레이터는 매 주기 새 측정을 낸다 */
    fun readingAt(nowMs: Long): GlucoseReading? {
        advanceTo(nowMs)
        val state = _state.value
        if (!state.sendsGlucose) return null
        return GlucoseReading(state.mmol, state.trend, nowMs, GlucoseSourceDto.SIMULATED)
    }

    /** 다음 주기까지 기다릴 시간. 시나리오 변화 지점을 주기 사이에 놓치지 않는다 */
    fun nextTickDelayMs(): Long {
        val next = _state.value.scenarioNextInMs
        return if (next == null) TICK_MS else minOf(TICK_MS, next.coerceAtLeast(MIN_TICK_MS))
    }

    private fun apply(state: SimulatorState, point: SimPoint, nowMs: Long): SimulatorState {
        var result = state
        if (point.glucoseMmol != null) {
            result = result.copy(hundredths = clamp(Math.round(point.glucoseMmol * 100).toInt()), trend = point.trend, sensorCut = false)
        }
        if (point.cut) result = result.copy(sensorCut = true)
        // 지나간 지점을 다시 접을 때 계산 시각이 매번 갱신되면 안 되므로 처음 적용할 때만 기록한다
        if (point.sleepMinutes != null && result.sleepOverrideMinutes != point.sleepMinutes) {
            result = result.copy(sleepOverrideMinutes = point.sleepMinutes, sleepOverrideAtMs = nowMs)
        }
        return result
    }

    // 손으로 만지면 재생을 끝낸다. 두 입력이 번갈아 값을 덮으면 화면의 값을 믿을 수 없다
    private fun manual(change: (SimulatorState) -> SimulatorState) {
        timeline = null
        _state.update { change(it).copy(scenarioId = null, scenarioElapsedMs = 0, scenarioNextInMs = null, glucoseActive = true) }
    }

    private fun clamp(hundredths: Int): Int = hundredths.coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS)

    companion object {
        const val TICK_MS = 30_000L                  // 11.3
        private const val MIN_TICK_MS = 1_000L
        val MIN_HUNDREDTHS = (SummaryLimits.GLUCOSE_MIN * 100).toInt()
        val MAX_HUNDREDTHS = Math.round(SummaryLimits.GLUCOSE_MAX * 100).toInt()
        const val MIN_MGDL = 19                      // 18은 내림 변환하면 1.0 아래다
        const val MAX_MGDL = 600
    }
}
