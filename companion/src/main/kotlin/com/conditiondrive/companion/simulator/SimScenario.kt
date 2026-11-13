package com.conditiondrive.companion.simulator

import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.scenario.Scenario
import com.conditiondrive.core.scenario.ScenarioRunner

/** 재생 시작 기준의 한 지점. 값이 없는 지점은 그 항목을 건드리지 않는다 */
data class SimPoint(
    val atMs: Long,
    val glucoseMmol: Double? = null,
    val trend: GlucoseTrendDto? = null,
    val sleepMinutes: Int? = null,
    val cut: Boolean = false,
)

data class SimTimeline(val id: String, val points: List<SimPoint>)

/**
 * 차량 쪽 JVM 테스트와 시연이 쓰는 시나리오 파일을 그대로 읽어 혈당·수면 입력만 뽑는다.
 * 배속은 없다: 서버는 실제 시각만 다룬다(15.3). 시간을 압축한 40분 회복은 내장 시연이 맡는다.
 */
object SimScenarios {
    const val SC1 = "sc1"
    const val SC3 = "sc3"
    private const val SIMULATED = "simulated"

    fun load(id: String): SimTimeline = from(ScenarioRunner.load(id))

    fun from(scenario: Scenario): SimTimeline {
        val raw = scenario.steps.mapNotNull { step ->
            val input = step.input ?: return@mapNotNull null
            val summary = input.summary
            val cut = input.summaryNull == true || input.glucoseCut == true
            // 다른 source를 넣는 표는 차량 거절 시험용이다. 폰은 simulated 외의 값을 만들지 않는다
            val usable = summary?.takeIf { it.source == null || it.source == SIMULATED }
            val glucose = usable?.glucoseMmol
            val sleep = summary?.sleepMinutes
            if (glucose == null && sleep == null && !cut) return@mapNotNull null
            SimPoint(
                atMs = parseOffsetMs(step.t),
                glucoseMmol = glucose,
                trend = usable?.trend?.toDto(),
                sleepMinutes = sleep,
                cut = cut,
            )
        }
        val base = raw.minOfOrNull { it.atMs } ?: 0L
        return SimTimeline(scenario.id, raw.map { it.copy(atMs = it.atMs - base) }.sortedBy { it.atMs })
    }

    private fun parseOffsetMs(t: String): Long {
        val negative = t.startsWith("-")
        val parts = t.removePrefix("-").split(':').map(String::toLong)
        val seconds = parts.fold(0L) { acc, part -> acc * 60 + part }
        return (if (negative) -seconds else seconds) * 1000
    }

    private fun Trend.toDto(): GlucoseTrendDto = when (this) {
        Trend.FALLING_FAST -> GlucoseTrendDto.FALLING_FAST
        Trend.FALLING -> GlucoseTrendDto.FALLING
        Trend.FLAT -> GlucoseTrendDto.FLAT
        Trend.RISING -> GlucoseTrendDto.RISING
    }
}
