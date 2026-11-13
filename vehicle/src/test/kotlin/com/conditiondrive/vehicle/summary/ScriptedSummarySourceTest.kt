package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.scenario.ScenarioSummary
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.runtime.SCENARIO_NOON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptedSummarySourceTest {
    private val at = SCENARIO_NOON

    @Test
    fun `같은 파서를 지나 러너와 같은 입력을 만든다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(sleepMinutes = 290, glucoseMmol = 5.4, trend = Trend.FALLING, source = "simulated"), at, null)
        val input = assertNotNull(source.snapshot.value.input)
        assertEquals(5.4, input.glucoseMmol)
        assertEquals(Trend.FALLING, input.glucoseTrend)
        assertEquals(at, input.glucoseMeasuredAtMs)
        assertEquals(at + 900_000, input.glucoseExpiresAtMs)
        assertEquals(290, input.sleepMinutes)
        assertEquals(at + 86_400_000, input.sleepExpiresAtMs)
        assertTrue(input.glucoseSimulated)
        assertEquals(LinkState.FRESH, source.link.value)
    }

    @Test
    fun `생략한 항목은 직전 값을 그대로 쓴다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(sleepMinutes = 290), at, null)
        source.push(ScenarioSummary(glucoseMmol = 6.4, trend = Trend.FLAT), at + 60_000, null)
        val input = assertNotNull(source.snapshot.value.input)
        assertEquals(290, input.sleepMinutes)
        assertEquals(at + 86_400_000, input.sleepExpiresAtMs)
        assertEquals(6.4, input.glucoseMmol)
    }

    @Test
    fun `F7-9 허용 안 되는 source면 혈당 없음`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(glucoseMmol = 3.5, source = "health_connect"), at, null)
        assertNull(source.snapshot.value.input?.glucoseMmol)
        assertFalse(source.hasGlucose)
    }

    @Test
    fun `같은 측정 재수신은 측정 시각이 그대로다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(glucoseMmol = 5.4), at, null)
        source.push(ScenarioSummary(glucoseMmol = 5.4), at + 300_000, at)
        assertEquals(at, source.snapshot.value.input?.glucoseMeasuredAtMs)
    }

    @Test
    fun `유지 입력은 같은 값을 새 측정 시각으로 넣는다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(glucoseMmol = 6.4, trend = Trend.RISING), at, null)
        source.refreshGlucose(at + 300_000)
        val input = assertNotNull(source.snapshot.value.input)
        assertEquals(6.4, input.glucoseMmol)
        assertEquals(Trend.RISING, input.glucoseTrend)
        assertEquals(at + 300_000, input.glucoseMeasuredAtMs)
        assertEquals(at + 1_200_000, input.glucoseExpiresAtMs)
    }

    @Test
    fun `지난 혈당을 수면과 함께 다시 보내지 않는다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(glucoseMmol = 6.4), at, null)
        source.push(ScenarioSummary(sleepMinutes = 400), at + 1_000_000, null)
        val input = assertNotNull(source.snapshot.value.input)
        assertNull(input.glucoseMmol)
        assertEquals(400, input.sleepMinutes)
    }

    @Test
    fun `값 없음과 연결 해제를 구분한다`() {
        val source = ScriptedSummarySource()
        source.push(ScenarioSummary(glucoseMmol = 6.4), at, null)
        source.pushNull(at + 1000)
        assertNull(source.snapshot.value.input)
        assertFalse(source.snapshot.value.unpaired)
        source.pushUnpaired()
        assertTrue(source.snapshot.value.unpaired)
        assertEquals(LinkState.UNPAIRED, source.link.value)
    }
}
