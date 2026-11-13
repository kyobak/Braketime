package com.conditiondrive.companion

import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.simulator.GlucoseUnit
import com.conditiondrive.companion.simulator.GlucoseUnits
import com.conditiondrive.companion.simulator.SimPoint
import com.conditiondrive.companion.simulator.SimScenarios
import com.conditiondrive.companion.simulator.SimTimeline
import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.scenario.Scenario
import com.conditiondrive.core.scenario.ScenarioInput
import com.conditiondrive.core.scenario.ScenarioStep
import com.conditiondrive.core.scenario.ScenarioSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlucoseSimulatorTest {
    private val sim = GlucoseSimulator()
    private val t0 = 1_000_000_000L

    @Test
    fun offUntilStarted() {
        assertNull(sim.readingAt(t0))
        sim.start()
        val reading = assertNotNull(sim.readingAt(t0))
        assertEquals(5.4, reading.mmol)
        assertEquals(t0, reading.measuredAtMs)
        assertEquals(GlucoseSourceDto.SIMULATED, reading.source)
        sim.stop()
        assertNull(sim.readingAt(t0))
    }

    /** 끊긴 동안에도 시뮬레이터가 켜져 있으면 다음 판독의 측정 시각은 새 시각이다. 11.3의 30초 주기 판독이다 */
    @Test
    fun everyReadIsANewMeasurement() {
        sim.start()
        assertEquals(t0 + 30_000, sim.readingAt(t0 + 30_000)?.measuredAtMs)
        assertEquals(t0 + 60_000, sim.readingAt(t0 + 60_000)?.measuredAtMs)
    }

    @Test
    fun manualNudgeHasNoFloatDrift() {
        sim.start()
        sim.setMmolHundredths(400)
        repeat(10) { sim.nudgeMmol(10) }
        assertEquals(5.0, sim.readingAt(t0)?.mmol)
        repeat(11) { sim.nudgeMmol(-10) }
        assertEquals(3.9, sim.readingAt(t0)?.mmol)
    }

    @Test
    fun valueStaysInsideServerRange() {
        sim.start()
        sim.setMmolHundredths(100)
        sim.nudgeMmol(-10)
        assertEquals(1.0, sim.readingAt(t0)?.mmol)
        sim.setMmolHundredths(99_999)
        assertEquals(33.3, sim.readingAt(t0)?.mmol)
    }

    @Test
    fun trendCanBeSetAndUnknown() {
        sim.start()
        sim.setTrend(GlucoseTrendDto.FALLING_FAST)
        assertEquals(GlucoseTrendDto.FALLING_FAST, sim.readingAt(t0)?.trend)
        sim.setTrend(null)
        assertNull(sim.readingAt(t0)?.trend)
    }

    @Test
    fun sensorCutSendsNoGlucose() {
        sim.start()
        sim.setSensorCut(true)
        assertNull(sim.readingAt(t0))
        sim.setSensorCut(false)
        assertNotNull(sim.readingAt(t0))
    }

    /** 4장: 5.4 → 97, 4.0 → 72, 5.0 → 90 */
    @Test
    fun unitConversionMatchesTable() {
        assertEquals(72, GlucoseUnits.toMgdl(4.0))
        assertEquals(90, GlucoseUnits.toMgdl(5.0))
        assertEquals(97, GlucoseUnits.toMgdl(5.4))
    }

    /** mg/dL로 넣은 값이 mmol에서 기준을 넘어가면 안 된다. 72 mg/dL은 4.0이 아니라 3.99다 */
    @Test
    fun mgdlInputNeverRoundsUp() {
        assertEquals(399, GlucoseUnits.toHundredths(72))
        assertEquals(499, GlucoseUnits.toHundredths(90))
        for (mgdl in GlucoseSimulator.MIN_MGDL..GlucoseSimulator.MAX_MGDL) {
            val hundredths = GlucoseUnits.toHundredths(mgdl)
            assertTrue(hundredths / 100.0 <= mgdl / 18.016, "$mgdl")
            assertTrue(hundredths >= GlucoseSimulator.MIN_HUNDREDTHS, "$mgdl 하한")
            assertTrue(hundredths <= GlucoseSimulator.MAX_HUNDREDTHS, "$mgdl 상한")
        }
    }

    @Test
    fun mgdlNudgeKeepsEnteredNumber() {
        sim.start()
        sim.setUnit(GlucoseUnit.MGDL)
        sim.setMmolHundredths(500)
        sim.nudgeMgdl(1)
        assertEquals(91, sim.state.value.enteredMgdl)
        assertEquals(5.05, sim.readingAt(t0)?.mmol)
        repeat(1000) { sim.nudgeMgdl(-1) }
        assertEquals(GlucoseSimulator.MIN_MGDL, sim.state.value.enteredMgdl)
    }

    /** 차량 시연과 같은 파일을 읽는다. 값이 파일과 어긋나면 폰이 다른 이야기를 하는 셈이다 */
    @Test
    fun sc1TimelineComesFromSharedScenarioFile() {
        val timeline = SimScenarios.load(SimScenarios.SC1)
        assertEquals("sc1", timeline.id)
        assertEquals(0L, timeline.points.first().atMs)
        assertEquals(6.4, timeline.points.first().glucoseMmol)
        val at35 = timeline.points.first { it.atMs == 36 * 60_000L }
        assertEquals(3.9, at35.glucoseMmol)
        assertEquals(GlucoseTrendDto.FALLING, at35.trend)
        assertEquals(timeline.points.sortedBy { it.atMs }, timeline.points)
        assertTrue(timeline.points.all { it.sleepMinutes == null })
    }

    @Test
    fun sc3CarriesSleepOnly() {
        val timeline = SimScenarios.load(SimScenarios.SC3)
        assertEquals(listOf(SimPoint(0, sleepMinutes = 290)), timeline.points)
        sim.play(timeline, t0)
        assertNull(sim.readingAt(t0))
        assertEquals(290, sim.state.value.sleepOverrideMinutes)
        assertEquals(t0, sim.state.value.sleepOverrideAtMs)
    }

    /** 실제 시간으로 재생한다. 지점 사이는 직전 값을 새 측정 시각으로 낸다 */
    @Test
    fun playbackFollowsRealTimeAndKeepsLastValue() {
        val timeline = SimScenarios.load(SimScenarios.SC1)
        sim.play(timeline, t0)
        assertEquals(6.4, sim.readingAt(t0)?.mmol)
        assertEquals(6.4, sim.readingAt(t0 + 20 * 60_000)?.mmol)
        val at21 = sim.readingAt(t0 + 21 * 60_000)
        assertEquals(5.6, at21?.mmol)
        assertEquals(GlucoseTrendDto.FALLING, at21?.trend)
        assertEquals(t0 + 21 * 60_000, at21?.measuredAtMs)

        val end = t0 + timeline.points.last().atMs + 3_600_000
        assertEquals(3.8, sim.readingAt(end)?.mmol)
        assertNull(sim.state.value.scenarioNextInMs)
    }

    @Test
    fun skipJumpsToNextPointWithRealMeasurementTime() {
        val timeline = SimScenarios.load(SimScenarios.SC1)
        sim.play(timeline, t0)
        sim.skipToNext(t0 + 1000)
        val reading = sim.readingAt(t0 + 1000)
        assertEquals(5.6, reading?.mmol)
        assertEquals(t0 + 1000, reading?.measuredAtMs)
        assertEquals(21 * 60_000L, sim.state.value.scenarioElapsedMs)
        // 끝에서 건너뛰어도 값이 바뀌지 않는다
        repeat(50) { sim.skipToNext(t0 + 2000) }
        assertEquals(3.8, sim.readingAt(t0 + 2000)?.mmol)
    }

    @Test
    fun manualTouchEndsPlayback() {
        sim.play(SimScenarios.load(SimScenarios.SC1), t0)
        sim.setMmolHundredths(500)
        assertFalse(sim.state.value.inScenario)
        assertEquals(5.0, sim.readingAt(t0 + 3_600_000)?.mmol)
    }

    @Test
    fun cutPointsAndForeignSourcesInScenario() {
        val scenario = Scenario(
            id = "x",
            title = "x",
            startLocal = "2026-11-20T09:00:00+09:00",
            steps = listOf(
                ScenarioStep("00:00:00", ScenarioInput(summary = ScenarioSummary(glucoseMmol = 5.0, source = "simulated"))),
                ScenarioStep("00:01:00", ScenarioInput(gear = null)),
                ScenarioStep("00:02:00", ScenarioInput(glucoseCut = true)),
                ScenarioStep("00:03:00", ScenarioInput(summary = ScenarioSummary(glucoseMmol = 3.0, source = "health_connect"))),
                ScenarioStep("00:04:00", ScenarioInput(summary = ScenarioSummary(glucoseMmol = 6.0, source = "simulated"))),
            ),
        )
        val timeline = SimScenarios.from(scenario)
        assertEquals(listOf(0L, 120_000L, 240_000L), timeline.points.map { it.atMs }) // health_connect 값은 지점이 되지 않는다
        sim.play(timeline, t0)
        assertEquals(5.0, sim.readingAt(t0)?.mmol)
        assertNull(sim.readingAt(t0 + 120_000))
        assertNull(sim.readingAt(t0 + 200_000))
        assertEquals(6.0, sim.readingAt(t0 + 240_000)?.mmol)
    }

    @Test
    fun tickWaitsForNextPointButNeverLongerThanThirtySeconds() {
        assertEquals(GlucoseSimulator.TICK_MS, sim.nextTickDelayMs())
        sim.play(SimTimeline("x", listOf(SimPoint(0, glucoseMmol = 5.0), SimPoint(10_000, glucoseMmol = 4.0))), t0)
        assertEquals(10_000, sim.nextTickDelayMs())
        sim.advanceTo(t0 + 9_500)
        assertEquals(1_000, sim.nextTickDelayMs())
        sim.advanceTo(t0 + 10_000)
        assertEquals(GlucoseSimulator.TICK_MS, sim.nextTickDelayMs())
    }

    @Test
    fun stopClearsEverythingEphemeral() {
        sim.play(SimScenarios.load(SimScenarios.SC3), t0)
        sim.stop()
        val state = sim.state.value
        assertFalse(state.running)
        assertNull(state.sleepOverrideMinutes)
        assertNull(state.scenarioId)
        assertTrue(state.glucoseActive)
    }
}
