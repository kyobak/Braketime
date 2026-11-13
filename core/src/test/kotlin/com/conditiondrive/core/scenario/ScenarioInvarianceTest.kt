package com.conditiondrive.core.scenario

import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.EngineState
import com.conditiondrive.core.engine.EngineStateCodec
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** test-scenarios.md G5, G7, E1, R1, R2, R3. */
class ScenarioInvarianceTest {
    private val g5Ids = listOf("A1", "A5", "A6", "B2", "C1", "D1", "D3", "D5", "D7", "sc1", "sc2", "sc3")

    private fun allIds(): List<String> = File("src/main/resources/scenarios").list().orEmpty()
        .filter { it.endsWith(".json") }
        .map { it.removeSuffix(".json") }
        .sorted()

    @TestFactory
    fun g5SpeedInvariance(): List<DynamicTest> = g5Ids.flatMap { id ->
        val profiles = listOf(SpeedProfile.REAL_TIME to 0L, SpeedProfile.X60 to 0L, SpeedProfile.X240 to 0L) +
            (1L..5L).map { SpeedProfile.RANDOM to it }
        profiles.map { (profile, seed) ->
            dynamicTest("$id $profile seed=$seed") {
                val scenario = ScenarioRunner.load(id)
                val baseline = ScenarioRunner.run(scenario)
                val result = ScenarioRunner.runWithSpeed(scenario, profile, seed)
                assertEquals(emptyList(), result.failures)
                assertEquals(baseline.entries, result.entries)
            }
        }
    }

    @TestFactory
    fun g7CodecRoundTripAtEveryStep(): List<DynamicTest> = allIds().map { id ->
        dynamicTest(id) {
            val scenario = ScenarioRunner.load(id)
            val baseline = ScenarioRunner.run(scenario)
            val result = ScenarioRunner.runWithCodecRoundTrip(scenario)
            assertEquals(emptyList(), result.failures)
            assertEquals(baseline.entries, result.entries)
        }
    }

    @Test
    fun codecRejectsOtherVersionAndGarbage() {
        val state = Engine().initial(1_000)
        val json = EngineStateCodec.encode(state)
        assertEquals(state, EngineStateCodec.decode(json))
        assertTrue("\"schemaVersion\":${EngineState.SCHEMA_VERSION}" in json)
        assertNull(EngineStateCodec.decode(json.replace("\"schemaVersion\":${EngineState.SCHEMA_VERSION}", "\"schemaVersion\":999")))
        assertNull(EngineStateCodec.decode("not json"))
        assertNull(EngineStateCodec.decode("{}"))
    }

    @Test
    fun e1NothingFromServerMatchesA1() {
        val a1 = ScenarioRunner.load("A1")
        val noServer = a1.copy(
            steps = a1.steps.map { step ->
                step.copy(input = (step.input ?: ScenarioInput()).copy(summaryNull = true))
            },
        )
        val baseline = ScenarioRunner.run(a1)
        val result = ScenarioRunner.run(noServer)
        assertEquals(emptyList(), result.failures)
        assertEquals(baseline.entries, result.entries)
    }

    @TestFactory
    fun r2CompareMatchesOwnLog(): List<DynamicTest> = allIds().map { id ->
        dynamicTest(id) {
            val scenario = ScenarioRunner.load(id)
            val result = ScenarioRunner.run(scenario)
            assertEquals(emptyList(), ScenarioCompare.compare(scenario, result.entries))
        }
    }

    private fun sc2() = ScenarioRunner.load("sc2").let { it to ScenarioRunner.run(it).entries }

    @Test
    fun r2CompareToleratesOneSecond() {
        val (scenario, log) = sc2()
        val late = log.map { it.copy(atMs = it.atMs + 1_000) }
        assertEquals(emptyList(), ScenarioCompare.compare(scenario, late))
        val early = log.map { it.copy(atMs = it.atMs - 1_000) }
        assertEquals(emptyList(), ScenarioCompare.compare(scenario, early))
    }

    @Test
    fun r2CompareDetectsTimeShift() {
        val (scenario, log) = sc2()
        val late = log.map { it.copy(atMs = it.atMs + 2_000) }
        assertTrue(ScenarioCompare.compare(scenario, late).isNotEmpty())
    }

    @Test
    fun r2CompareDetectsMissingAndExtraVoice() {
        val (scenario, log) = sc2()
        val withV12Removed = log.filterNot { VoiceId.V12 in it.voices }
        assertTrue(ScenarioCompare.compare(scenario, withV12Removed).any { "V12" in it })
        val extra = log + TransitionEntry(log.last().atMs + 60_000, DriverState.NORMAL, DriverState.NORMAL, emptySet<Reason>(), listOf(VoiceId.V09))
        assertTrue(ScenarioCompare.compare(scenario, extra).any { "V09" in it })
    }

    @Test
    fun r2CompareDetectsWrongState() {
        val (scenario, log) = sc2()
        val wrong = log.map { if (it.to == DriverState.STOP) it.copy(to = DriverState.CAUTION) else it }
        assertTrue(ScenarioCompare.compare(scenario, wrong).any { "상태" in it })
    }

    @Test
    fun r2ReportHasResultAndLog() {
        val (scenario, log) = sc2()
        val report = ScenarioCompare.report(scenario, log)
        assertTrue("일치" in report)
        assertTrue("V12" in report)
    }

    @Test
    fun runnerDetectsWrongExpectations() {
        // 러너가 빈손으로 통과시키지 않는지 확인한다
        val a1 = ScenarioRunner.load("A1")
        val silent = a1.copy(steps = a1.steps.map { it.copy(expect = null) })
        val failures = ScenarioRunner.run(silent).failures
        assertTrue(failures.any { "V02" in it })
        assertTrue(failures.any { "V12" in it })

        val wrongState = a1.copy(
            steps = a1.steps.map {
                it.copy(expect = it.expect?.copy(state = it.expect.state?.let { DriverState.STOP }))
            },
        )
        assertTrue(ScenarioRunner.run(wrongState).failures.any { "상태" in it })
    }

    @Test
    fun scenarioParsesNegativeAndLongOffsets() {
        assertEquals(-60_000, parseOffsetMs("-00:01:00"))
        assertEquals(68_400_000, parseOffsetMs("19:00:00"))
        assertNotNull(ScenarioRunner.load("sc1"))
    }

    @Test
    fun unknownScenarioFails() {
        val failure = runCatching { ScenarioRunner.load("없음") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
