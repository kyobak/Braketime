package com.conditiondrive.vehicle.demo

import com.conditiondrive.core.engine.AdvanceReason
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.scenario.ScenarioCompare
import com.conditiondrive.core.scenario.ScenarioRunner
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.runtime.FakeListener
import com.conditiondrive.vehicle.runtime.FakeLog
import com.conditiondrive.vehicle.runtime.FakeNavi
import com.conditiondrive.vehicle.runtime.FakeProvider
import com.conditiondrive.vehicle.runtime.FakeSignals
import com.conditiondrive.vehicle.runtime.FakeSpeaker
import com.conditiondrive.vehicle.runtime.FakeStore
import com.conditiondrive.vehicle.runtime.FakeTrip
import com.conditiondrive.vehicle.runtime.FakeVentilation
import com.conditiondrive.vehicle.runtime.RuntimeGraph
import com.conditiondrive.vehicle.runtime.RuntimeHooks
import com.conditiondrive.vehicle.runtime.RuntimePorts
import com.conditiondrive.vehicle.summary.ScriptedSummarySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoControllerTest {
    private class Rig(
        val scope: TestScope,
        val signals: FakeSignals = FakeSignals(Gear.PARK, 0.0, Ignition.ON),
        listener: FakeListener? = null,
        navi: FakeNavi? = null,
        ventilation: FakeVentilation? = null,
        speakMs: Long = 2000,
    ) {
        val speaker = FakeSpeaker(speakMs)
        val store = FakeStore()
        val log = FakeLog()
        val trip = FakeTrip()
        val errors = mutableListOf<Throwable>()
        val reports = mutableMapOf<String, String>()
        val controller = DemoController(
            DemoEnvironment(signals, speaker, store, log, trip, listOf(FakeProvider(emptyList())), listener, navi, ventilation),
            scope.backgroundScope,
            elapsedMs = { scope.testScheduler.currentTime },
            reportSink = { id, report -> reports[id] = report },
            onError = { errors += it },
        )

        /** 시연은 실제 시간으로 몇 분 걸린다. 가상 시간으로 끝까지 돌린다 */
        fun runToEnd(limitMs: Long = 900_000): DemoStatus.Finished {
            var elapsed = 0L
            while (controller.status.value !is DemoStatus.Finished && elapsed < limitMs) {
                scope.advanceTimeBy(100)
                scope.runCurrent()
                elapsed += 100
            }
            return assertIs<DemoStatus.Finished>(controller.status.value, "시연이 끝나지 않았다. 상태 ${controller.status.value}")
        }
    }

    private fun assertClean(r: Rig, finished: DemoStatus.Finished) {
        assertEquals(emptyList(), finished.mismatches, finished.report)
        assertTrue(r.errors.isEmpty(), r.errors.toString())
    }

    @Test
    fun `R1 R2 SC1이 런타임을 지나도 기대와 ±1초 안에서 일치한다`() = runTest {
        val r = Rig(this)
        assertEquals(DemoStartResult.STARTED, r.controller.start("sc1"))
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertEquals(ScenarioCompare.compare(ScenarioRunner.load("sc1"), r.log.entries), emptyList())
        assertTrue(r.speaker.texts.any { it.contains("칠곡휴게소까지") })
        assertTrue(r.reports.getValue("sc1").contains("대조 결과: 일치"))
        r.controller.stop()
    }

    @Test
    fun `R1 R2 SC2가 런타임을 지나도 기대와 일치하고 V29까지 간다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue(r.speaker.texts.any { it.contains("칠곡휴게소에서 쉬어 갈까요") })
        assertTrue(r.speaker.texts.any { it.contains("안내할게요") })
        // 내비가 없으면 음성 안내만 남긴다(16장 SC2 아래 문단)
        assertTrue(r.speaker.texts.any { it.contains("경유지를 추가하지 못했어요") })
        r.controller.stop()
    }

    @Test
    fun `R1 R2 SC3가 런타임을 지나도 기대와 일치하고 앞당김 문장이 나온다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc3")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue(r.speaker.texts.any { it.startsWith("어젯밤 잠이 짧아") })
        r.controller.stop()
    }

    @Test
    fun `G5 음성 길이가 달라도 전이 로그가 같다`() = runTest {
        val short = Rig(this, speakMs = 100)
        short.controller.start("sc1")
        short.runToEnd()
        short.controller.stop()
        val long = Rig(this, speakMs = 9000)
        long.controller.start("sc1")
        long.runToEnd(limitMs = 1_800_000)
        long.controller.stop()
        assertEquals(short.log.entries, long.log.entries)
    }

    @Test
    fun `R6 음성 중에는 시계가 멈춘다`() = runTest {
        val r = Rig(this, speakMs = 8000)
        r.speaker.now = { r.controller.simulatedNowMs() ?: 0 }
        r.controller.start("sc2")
        r.runToEnd(limitMs = 1_800_000)
        assertTrue(r.speaker.spoken.size >= 6)
        // 재생 전체가 시뮬레이션 시각 0초 안에 들어간다. 8초가 32분으로 늘어나면 안 된다(15.3)
        r.speaker.spoken.forEach { assertEquals(it.atMs, it.endAtMs, it.text) }
        r.controller.stop()
    }

    @Test
    fun `15_3 시계가 빨라도 마감에 정확히 착지한다`() = runTest {
        val elapsed = { testScheduler.currentTime }
        val start = java.time.OffsetDateTime.parse("2026-11-20T09:00:00+09:00").toInstant().toEpochMilli()
        val clock = CompressedClock(start, elapsed)
        // 임계가 배속으로 나누어떨어지면 착지 없이도 우연히 맞는다. 일부러 나누어떨어지지 않는 배속을 쓴다
        clock.setSpeed(237)
        val signals = FakeSignals()
        val log = FakeLog()
        val graph = RuntimeGraph(
            RuntimePorts(
                signals, ScriptedSummarySource(), FakeNavi(), FakeStore(), log, FakeTrip(), ScriptedOdometer,
                FakeSpeaker(1000), FakeListener(), ScriptedOccupancy(), ScriptedLocation(DemoFixtures.fixAt(start)), null, emptyList(),
            ),
            clock, backgroundScope, Engine(), RuntimeHooks(pacer = CompressedClockPacer(clock)),
        )
        graph.start()
        advanceTimeBy(60_000)
        runCurrent()
        val entries = log.entries.filter { it.to != DriverState.NORMAL }
        assertEquals(start + 6_300_000, entries.first { it.to == DriverState.CAUTION }.atMs)
        assertEquals(start + 7_200_000, entries.first { it.to == DriverState.STOP }.atMs)
    }

    @Test
    fun `R5 P가 아니면 시작하지 않는다`() = runTest {
        listOf(Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN).forEach { gear ->
            val r = Rig(this, FakeSignals(gear, 0.0, Ignition.ON))
            assertEquals(DemoStartResult.NOT_PARKED, r.controller.start("sc2"), gear.name)
            assertNull(r.controller.runtime.value)
        }
    }

    @Test
    fun `기어 신호가 없는 차종은 차속 0이면 시작한다`() = runTest {
        val signals = FakeSignals(Gear.UNKNOWN, 0.0, Ignition.ON)
        signals.capabilities.value = signals.capabilities.value.copy(gear = false)
        val r = Rig(this, signals)
        assertEquals(DemoStartResult.STARTED, r.controller.start("sc2"))
        r.controller.stop()
    }

    @Test
    fun `없는 시나리오와 중복 시작을 거절한다`() = runTest {
        val r = Rig(this)
        assertEquals(DemoStartResult.UNKNOWN_SCENARIO, r.controller.start("없는시나리오"))
        assertEquals(DemoStartResult.STARTED, r.controller.start("sc2"))
        assertEquals(DemoStartResult.ALREADY_RUNNING, r.controller.start("sc3"))
        r.controller.stop()
    }

    @Test
    fun `R7 시작할 때마다 시연 저장소와 시연 기록만 지운다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, r.store.clears)
        assertEquals(1, r.trip.deletes)
        // 실사용 저장소는 이 컨트롤러에 넘어오지 않는다. 시연 상태는 넘겨받은 저장소에만 쌓인다
        advanceTimeBy(31_000)
        runCurrent()
        assertNotNull(r.store.record)
        r.controller.stop()
    }

    @Test
    fun `R4 표지가 항상 있고 배속 변경에 따라 바뀐다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        val seen = mutableSetOf<String>()
        var elapsed = 0L
        while (r.controller.status.value !is DemoStatus.Finished && elapsed < 900_000) {
            advanceTimeBy(100)
            runCurrent()
            elapsed += 100
            r.controller.label.value?.let { seen += it.substringBefore(" · 시뮬레이션 시각") }
        }
        assertTrue("시연 모드 · 시간 압축 ×240" in seen, seen.toString())
        assertTrue("시연 모드 · 시간 압축 ×120" in seen, seen.toString())
        assertTrue("시연 모드 · 시간 정지" in seen, seen.toString())
        assertTrue("시연 모드 · 실제 시간" in seen, seen.toString())
        assertNotNull(r.controller.runtime.value?.demoLabel?.value)
        r.controller.stop()
        assertNull(r.controller.label.value)
    }

    @Test
    fun `표지에 시뮬레이션 혈당과 스크립트 응답이 값에서 자동으로 붙는다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc1")
        r.runToEnd()
        val label = assertNotNull(r.controller.label.value)
        assertTrue("혈당 시뮬레이션" in label, label)
        assertTrue("음성 응답은 시나리오 입력" in label, label)
        assertTrue(ForbiddenWords.find(label).isEmpty(), label)
        r.controller.stop()
    }

    @Test
    fun `SC2 표지에는 혈당이 붙지 않는다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        r.runToEnd()
        assertFalse("혈당" in assertNotNull(r.controller.label.value))
        r.controller.stop()
    }

    @Test
    fun `표지 문구`() {
        val at = java.time.OffsetDateTime.parse("2026-11-20T09:45:00+09:00").toInstant().toEpochMilli()
        assertEquals(
            "시연 모드 · 시간 압축 ×240 · 시뮬레이션 시각 09:45 · 혈당 시뮬레이션",
            DemoController.demoLabel(240, frozen = false, finished = false, simMs = at, glucoseSimulated = true, scriptedAnswers = false),
        )
    }

    @Test
    fun `실제 STT가 분류되는 답을 들으면 그 값을 쓰고 표지를 달지 않는다`() = runTest {
        val listener = FakeListener()
        // SC2의 체크인·프롬프트 네 번에 시나리오와 같은 뜻으로 답한다
        listOf("좋아", "좋아", "아니요", "네").forEach { listener.answers += ListenResult.Heard(it) }
        val r = Rig(this, listener = listener)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertEquals(4, listener.listenAtMs.size)
        assertFalse("음성 응답은 시나리오 입력" in assertNotNull(r.controller.label.value))
        r.controller.stop()
    }

    @Test
    fun `실제 STT가 실패하면 스크립트 응답으로 끝까지 돈다`() = runTest {
        val listener = FakeListener()
        val r = Rig(this, listener = listener)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue("음성 응답은 시나리오 입력" in assertNotNull(r.controller.label.value))
        r.controller.stop()
    }

    @Test
    fun `실제 STT가 알아듣지 못한 문장이면 스크립트 응답을 쓴다`() = runTest {
        val listener = FakeListener()
        repeat(4) { listener.answers += ListenResult.Heard("음음") }
        val r = Rig(this, listener = listener)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        r.controller.stop()
    }

    @Test
    fun `SC2 내장 시연은 실제 내비로 자동 전송하지 않는다`() = runTest {
        val navi = FakeNavi()
        navi.routeM[DemoFixtures.PLACE.id] = 900_000     // 실제 위치 기준 측정값이 판단에 섞이면 안 된다
        val r = Rig(this, navi = navi)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue(navi.guided.isEmpty(), "시연 중 지도가 앱 영역을 덮으면 안 된다")
        assertFalse(r.speaker.texts.any { it.contains("경유지를 추가하지 못했어요") })
        // R11: 끝나도 자동으로 보내지 않는다. 사람이 설정 > 시연 모드에서 눌러야 간다
        r.controller.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(navi.guided.isEmpty(), "표지 없이 지도가 앱을 덮고 경로가 실사용에 남으면 안 된다")
        assertEquals(DemoFixtures.PLACE.id, r.controller.pendingPlace.value?.id)
        r.controller.sendPendingPlace()
        assertEquals(listOf(DemoFixtures.PLACE.id), navi.guided.map { it.id })
        assertNull(r.controller.pendingPlace.value, "두 번 보내지 않는다")
    }

    @Test
    fun `SC2 재시작으로 내리면 미뤄 둔 경유지를 보내지 않는다`() = runTest {
        val navi = FakeNavi()
        val r = Rig(this, navi = navi)
        r.controller.start("sc2")
        r.runToEnd()
        r.controller.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(navi.guided.isEmpty())
    }

    @Test
    fun `내장 시연은 스크립트 게이트와 동승값을 화면에 넘기고 끝나면 비운다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc1")
        val ui = assertNotNull(r.controller.ui.value)
        assertNotNull(ui.gate)
        assertEquals(false, ui.occupancy.passengerPresent.value)
        r.controller.stop()
        assertNull(r.controller.ui.value)
    }

    @Test
    fun `런타임이 비었다고 알릴 때 시연 화면 포트는 이미 비어 있다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc1")
        val seen = mutableListOf<DemoUiPorts?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            r.controller.runtime.collect { if (it == null) seen += r.controller.ui.value }
        }
        r.controller.stop()
        assertEquals(listOf<DemoUiPorts?>(null), seen)
    }

    @Test
    fun `차량 신호 시연은 실제 게이트를 쓰고 경유지를 바로 보낸다`() = runTest {
        val signals = FakeSignals(Gear.PARK, 0.0, Ignition.ON)
        val navi = FakeNavi()
        val r = Rig(this, signals, navi = navi)
        r.controller.start("sc2", realSignals = true)
        assertNull(assertNotNull(r.controller.ui.value).gate)
        val direct = ScriptedNavi(navi, deferReal = false)
        direct.guideTo(DemoFixtures.PLACE)
        assertEquals(listOf(DemoFixtures.PLACE.id), navi.guided.map { it.id })
        r.controller.stop()
    }

    @Test
    fun `SC2 환기를 지원하면 V27에 응으로 답해 바꾸고 흐름은 그대로다`() = runTest {
        val ventilation = FakeVentilation()
        val r = Rig(this, ventilation = ventilation)
        r.controller.start("sc2")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue(r.speaker.texts.any { it.contains("바깥 공기로") })
        assertEquals(com.conditiondrive.vehicle.port.AirMode.FRESH, ventilation.sets.first())
        r.controller.stop()
    }

    @Test
    fun `R8 서버 폰 키 없이 끝까지 돈다 - 포트가 없어도 크래시 없음`() = runTest {
        listOf("sc1", "sc2", "sc3").forEach { id ->
            val r = Rig(this)
            r.controller.start(id)
            assertClean(r, r.runToEnd())
            r.controller.stop()
        }
    }

    @Test
    fun `15_4 차량 신호 시연은 콘솔 기어가 들어올 때까지 시계를 멈춘다`() = runTest {
        val signals = FakeSignals(Gear.PARK, 0.0, Ignition.ON)
        val r = Rig(this, signals)
        assertEquals(DemoStartResult.STARTED, r.controller.start("sc2", realSignals = true))
        // V01 응답까지 진행하면 0:00 D 줄에서 멈춘다
        advanceTimeBy(60_000)
        runCurrent()
        val runtime = assertNotNull(r.controller.runtime.value)
        assertEquals(DriverState.NORMAL, runtime.view.value.state)
        assertFalse(runtime.view.value.driving)
        val frozenCds = runtime.view.value.continuousDriveSec
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(frozenCds, runtime.view.value.continuousDriveSec)
        assertFalse(r.controller.status.value is DemoStatus.Finished)

        signals.gear.value = Gear.DRIVE
        signals.speedKmh.value = 80.0
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(runtime.view.value.driving)

        signals.speedKmh.value = 80.0
        // 표의 P 줄까지 간 뒤 다시 기다린다
        advanceTimeBy(200_000)
        runCurrent()
        signals.gear.value = Gear.PARK
        signals.speedKmh.value = 0.0
        val finished = r.runToEnd()
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(emptyList(), finished.mismatches, finished.report)
        r.controller.stop()
    }

    @Test
    fun `정지 후 다시 시작할 수 있다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        advanceTimeBy(5_000)
        runCurrent()
        r.controller.stop()
        assertEquals(DemoStatus.Idle, r.controller.status.value)
        assertNull(r.controller.runtime.value)
        assertEquals(DemoStartResult.STARTED, r.controller.start("sc3"))
        assertClean(r, r.runToEnd())
        val view = r.controller.runtime.value!!.view.value
        assertEquals(AdvanceReason.SLEEP_SHORT, view.advanced)
        r.controller.stop()
    }

    /** 시스템 화면이 P인데 앱이 "운전 중"이라고 하는 이유를 표지가 밝힌다 */
    @Test
    fun `R4 내장 시연 표지는 차량 신호가 시나리오 입력임을 밝힌다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2")
        advanceTimeBy(100)
        runCurrent()
        assertTrue("차량 신호는 시나리오 입력(시스템 화면은 실제 P)" in assertNotNull(r.controller.label.value))
        r.controller.stop()
    }

    @Test
    fun `R4 차량 신호 시연은 콘솔 대기를 표지로 알린다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc2", realSignals = true)
        var saw = false
        var elapsed = 0L
        while (!saw && elapsed < 60_000) {
            advanceTimeBy(100)
            runCurrent()
            elapsed += 100
            val label = r.controller.label.value.orEmpty()
            saw = "콘솔에서 D로 바꿔 주세요" in label
            assertFalse("차량 신호는 시나리오 입력" in label, label)
        }
        assertTrue(saw, "기어 대기가 표지에 없다: ${r.controller.label.value}")
        r.controller.stop()
    }

    /** SC3의 "어젯밤 잠이 짧아"가 실제 웨어러블 값처럼 보이면 안 된다 */
    @Test
    fun `R4 수면 값이 있으면 표지에 수면 시연 데이터를 붙인다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc3")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue("수면 시연 데이터" in assertNotNull(r.controller.label.value))
        r.controller.stop()
    }

    @Test
    fun `R4 표지 문구에 수면과 대기가 값에서 붙는다`() {
        val label = DemoController.demoLabel(
            240, frozen = true, finished = false, simMs = 0, glucoseSimulated = false, scriptedAnswers = false,
            scriptedVehicle = false, sleepScripted = true, awaitingGear = Gear.PARK,
        )
        assertTrue("수면 시연 데이터" in label && "콘솔에서 P로 바꿔 주세요" in label, label)
        assertTrue(ForbiddenWords.find(label).isEmpty(), label)
    }

    /** 소리 없이 봐도 음성과 응답, 시동 조작이 화면에 드러난다 */
    @Test
    fun `R10 자막이 음성 응답 시동을 보여 준다`() = runTest {
        val r = Rig(this)
        val seen = mutableSetOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.caption.collect { it?.let(seen::add) } }
        r.controller.start("sc1")
        val finished = r.runToEnd()
        assertClean(r, finished)
        assertTrue(seen.any { it.startsWith("앱 음성:") && "혈당이 내려가고 있어요" in it }, seen.toString())
        assertTrue("시나리오 응답: 아니" in seen, seen.toString())
        assertTrue("시나리오: 시동 끔" in seen && "시나리오: 시동 켬" in seen, seen.toString())
        assertTrue(seen.all { ForbiddenWords.find(it).isEmpty() }, seen.toString())
        r.controller.stop()
        assertNull(r.controller.caption.value)
    }

    /** 유일한 차량 제어가 앱 화면에서도 보인다 */
    @Test
    fun `R10 공기 모드 변경을 자막으로 알린다`() = runTest {
        val r = Rig(this, ventilation = FakeVentilation())
        val seen = mutableSetOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.caption.collect { it?.let(seen::add) } }
        r.controller.start("sc2")
        r.runToEnd()
        assertTrue(seen.any { "Vehicle SDK로 공기 모드 변경: 바깥 공기" in it }, seen.toString())
        r.controller.stop()
    }

    /** 재검증: 복원이 변경 약 2초 뒤에 와서 끝까지 남는 줄이 "재순환으로 복원"뿐이었다 */
    @Test
    fun `R10 복원 뒤에도 끝까지 남는 줄에 바깥 공기 변경 사실이 있다`() = runTest {
        val ventilation = FakeVentilation()
        val r = Rig(this, ventilation = ventilation)
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.caption.collect { it?.let(seen::add) } }
        r.controller.start("sc2")
        r.runToEnd()
        assertEquals(listOf(com.conditiondrive.vehicle.port.AirMode.FRESH, com.conditiondrive.vehicle.port.AirMode.RECIRCULATE), ventilation.sets)
        val note = "Vehicle SDK로 공기 모드 변경: 바깥 공기, 이후 재순환으로 복원"
        assertTrue(seen.last { "공기 모드" in it }.contains(note), seen.toString())
        r.controller.stop()
    }

    /** 재검증: 콘솔 기본값(바깥 공기)이면 3분 실행 경로에서 SDK가 돈다는 표시가 하나도 없었다 */
    @Test
    fun `R10 이미 바깥 공기면 SDK로 읽은 값을 자막에 적고 다음 음성 뒤에도 남긴다`() = runTest {
        val ventilation = FakeVentilation(mode = com.conditiondrive.vehicle.port.AirMode.FRESH)
        val r = Rig(this, ventilation = ventilation)
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.caption.collect { it?.let(seen::add) } }
        r.controller.start("sc2")
        assertClean(r, r.runToEnd())
        val note = "Vehicle SDK로 공기 모드 읽음: 이미 바깥 공기라 묻지 않아요"
        assertTrue(seen.any { "앱 음성:" in it && note in it }, seen.toString())
        assertTrue(ventilation.sets.isEmpty())
        assertFalse(r.speaker.texts.any { "바깥 공기로" in it })
        r.controller.stop()
    }

    /** 재검증: 콘솔 하트비트 없이 "응"이면 값이 그대로인데 자막과 안내가 없었다 */
    @Test
    fun `R10 공기 모드가 안 바뀌면 자막과 음성으로 알리고 흐름은 그대로다`() = runTest {
        val r = Rig(this, ventilation = FakeVentilation(setWorks = false))
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.caption.collect { it?.let(seen::add) } }
        r.controller.start("sc2")
        assertClean(r, r.runToEnd())
        assertTrue(seen.any { "Vehicle SDK로 공기 모드 변경 요청: 값이 그대로예요" in it }, seen.toString())
        assertTrue(r.speaker.texts.any { "바꾸지 못했어요" in it }, r.speaker.texts.toString())
        assertFalse(seen.any { "공기 모드 변경: 바깥 공기" in it }, seen.toString())
        r.controller.stop()
    }

    /** 재검증: tools/vc.sh의 고정 시간표가 응답 대기 중에 P를 넣어 대조가 어긋났다. 앱이 기다리는 기어를 그때 알린다 */
    @Test
    fun `15_4 차량 신호 시연은 기다리는 기어를 차례로 알리고 끝을 알린다`() = runTest {
        val signals = FakeSignals(Gear.PARK, 0.0, Ignition.ON)
        val r = Rig(this, signals)
        val cues = mutableListOf<ConsoleCue>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.controller.consoleCue.collect { it?.let(cues::add) } }
        r.controller.start("sc3", realSignals = true)
        fun awaited() = cues.lastOrNull()?.step
        var elapsed = 0L
        while (awaited() != "DRIVE" && elapsed < 120_000) { advanceTimeBy(100); runCurrent(); elapsed += 100 }
        assertEquals("DRIVE", awaited())
        // 콘솔이 넣기 전에는 P를 달라고 하지 않는다
        advanceTimeBy(30_000); runCurrent()
        assertEquals("DRIVE", awaited())
        signals.gear.value = Gear.DRIVE
        signals.speedKmh.value = 80.0
        elapsed = 0
        while (awaited() != "PARK" && elapsed < 600_000) { advanceTimeBy(100); runCurrent(); elapsed += 100 }
        assertEquals("PARK", awaited())
        // P 줄까지 V04 응답이 모두 끝났다. 이 시점에 넣은 P는 대조를 흐트러뜨리지 않는다
        assertEquals(DriverState.STOP, r.controller.runtime.value?.view?.value?.state)
        signals.speedKmh.value = 0.0
        signals.gear.value = Gear.PARK
        val finished = r.runToEnd()
        assertEquals(emptyList(), finished.mismatches, finished.report)
        runCurrent()
        assertEquals("done", awaited())
        assertTrue(cues.all { it.scenarioId == "sc3" && it.run == cues.first().run })
        assertEquals("run=${cues.first().run} id=sc3 await=done", cues.last().line())
        r.controller.stop()
        assertEquals("done", awaited(), "끝난 뒤 내려도 결과를 지우지 않는다")
    }

    @Test
    fun `15_4 내장 시연은 콘솔을 기다리지 않아 신호가 없고, 차량 신호 시연을 끊으면 stopped다`() = runTest {
        val builtIn = Rig(this)
        builtIn.controller.start("sc3")
        advanceTimeBy(30_000); runCurrent()
        assertNull(builtIn.controller.consoleCue.value)
        builtIn.controller.stop()

        val real = Rig(this)
        real.controller.start("sc3", realSignals = true)
        advanceTimeBy(5_000); runCurrent()
        real.controller.stop()
        assertEquals(ConsoleCue.STOPPED, real.controller.consoleCue.value?.step)
    }

    /** 재검증 회귀: S5가 시나리오 요약을 폰 연결로 읽었다. 조립은 실제 연결을 넘겨야 한다 */
    @Test
    fun `시연 중에도 화면의 폰 연결은 실제 연결이고 요약만 시나리오다`() = runTest {
        val r = Rig(this)
        r.controller.start("sc1")
        advanceTimeBy(5_000); runCurrent()
        val shown = assertNotNull(r.controller.ui.value)
        assertEquals(com.conditiondrive.vehicle.port.LinkState.FRESH, shown.summary.link.value)
        val realPhone = com.conditiondrive.vehicle.runtime.FakeSummary().apply { link.value = com.conditiondrive.vehicle.port.LinkState.UNPAIRED }
        val source = com.conditiondrive.vehicle.ui.vehicleUiSource(
            assertNotNull(r.controller.runtime.value), assertNotNull(shown.gate), shown.navi, shown.occupancy, shown.summary,
            shown.trips, shown.clock, kotlinx.coroutines.flow.MutableStateFlow(com.conditiondrive.vehicle.ui.SettingsFacts()),
            object : com.conditiondrive.vehicle.ui.SettingsActions {}, shown.caption, phoneLink = realPhone,
        )
        assertEquals(com.conditiondrive.vehicle.port.LinkState.UNPAIRED, source.link.value)
        assertNull(source.linkedSummary.value.phoneName)
        assertEquals(shown.summary.snapshot.value, source.summary.value)
        r.controller.stop()
    }

    /** 기록 탭이 시뮬레이션 날짜로 읽어야 "운전 0분, 기록 없음"이 뜨지 않는다 */
    @Test
    fun `R7 기록은 시뮬레이션 시각의 보기를 쓴다`() = runTest {
        var clockAt: (() -> Long)? = null
        val trip = FakeTrip()
        val signals = FakeSignals(Gear.PARK, 0.0, Ignition.ON)
        val controller = DemoController(
            DemoEnvironment(
                signals, FakeSpeaker(), FakeStore(), FakeLog(), FakeTrip(), listOf(FakeProvider(emptyList())),
                tripAt = { now ->
                    clockAt = now
                    trip
                },
            ),
            backgroundScope,
            elapsedMs = { testScheduler.currentTime },
        )
        controller.start("sc2")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, trip.deletes, "시작할 때 이전 시연 기록을 지운다")
        assertEquals(trip, assertNotNull(controller.ui.value).trips)
        val simulated = assertNotNull(clockAt).invoke()
        assertEquals(controller.simulatedNowMs(), simulated)
        controller.stop()
    }
}
