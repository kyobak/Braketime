package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.ConditionInput
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.EngineStateCodec
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.LogEvent
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.engine.VehicleChanged
import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.EngineStateRecord
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.SummarySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EngineRuntimeTest {
    private class Rig(
        val scope: TestScope,
        signals: FakeSignals = FakeSignals(Gear.UNKNOWN, null, Ignition.OFF),
        store: FakeStore = FakeStore(),
        summary: FakeSummary = FakeSummary(),
        places: List<com.conditiondrive.core.poi.Place> = listOf(placeSouth("a", 3.2, "칠곡졸음쉼터(부산방향)")),
        fix: com.conditiondrive.core.poi.GeoFix? = TEST_FIX,
        val odometer: FakeOdometer = FakeOdometer(),
        bootCount: Int = 0,
        firstRun: Boolean = false,
        ventilation: FakeVentilation? = null,
        startMs: Long = SCENARIO_NOON,
    ) {
        val clock = scope.virtualClock(startMs)
        val signals = signals
        val store = store
        val summary = summary
        val speaker = FakeSpeaker(1000).also { it.now = clock::nowMs }
        val listener = FakeListener().also { it.now = clock::nowMs }
        val navi = FakeNavi()
        val location = FakeLocation(fix)
        val log = FakeLog()
        val trip = FakeTrip()
        val provider = FakeProvider(places)
        val errors = mutableListOf<Throwable>()
        val ports = RuntimePorts(
            signals, summary, navi, store, log, trip, odometer, speaker, listener, FakeOccupancy(), location, ventilation, listOf(provider),
        )
        val graph = RuntimeGraph(
            ports, clock, scope.backgroundScope, Engine(),
            RuntimeHooks(bootCount = { bootCount }, isFirstRun = { firstRun }, onError = { errors += it }),
        )
        val runtime get() = graph.runtime

        fun start() {
            graph.start()
            scope.runCurrent()
        }

        fun ignitionOnParked() {
            signals.ignition.value = Ignition.ON
            signals.gear.value = Gear.PARK
            signals.speedKmh.value = 0.0
            scope.runCurrent()
        }

        fun drive(speed: Double = 80.0) {
            signals.gear.value = Gear.DRIVE
            signals.speedKmh.value = speed
            scope.runCurrent()
        }

        fun park() {
            signals.gear.value = Gear.PARK
            signals.speedKmh.value = 0.0
            scope.runCurrent()
        }

        fun line(voice: VoiceId, vars: VoiceVars = VoiceVars()) =
            VoiceCatalog.render(voice, vars, PrivacyContext(false))
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `A1 기본 흐름이 응답 없이도 끝까지 돈다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(5_001)
        assertEquals(listOf(r.line(VoiceId.V01)), r.speaker.texts)
        advance(2_000)
        r.drive()
        advance(hour + 45 * minute)
        assertEquals(DriverState.CAUTION, r.runtime.view.value.state)
        assertEquals(setOf(Reason.FATIGUE), r.runtime.view.value.reasons)
        assertEquals(6300, r.runtime.view.value.continuousDriveSec)
        advance(15 * minute)
        assertEquals(DriverState.STOP, r.runtime.view.value.state)
        val texts = r.speaker.texts
        assertTrue(r.line(VoiceId.V02, VoiceVars(driveSec = 6300)) in texts)
        assertTrue(texts.last().contains("칠곡졸음쉼터"))
        assertFalse(texts.last().contains("부산방향"))
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `마감에 정확히 착지해 그 시각의 전이로 기록한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        r.drive()
        val start = r.clock.nowMs()
        advance(2 * hour)
        val caution = r.log.entries.first { it.to == DriverState.CAUTION }
        val fromDepart = caution.atMs - start
        assertEquals(6300_000L, fromDepart)
    }

    @Test
    fun `V3 런타임에서 F 정차 뒤 값 미만이면 V09를 선점으로 말한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(2 * hour + 500)
        val v04At = r.speaker.spoken.indexOfLast { it.text.contains("연속 운전했어요") }
        assertTrue(v04At >= 0)
        val listensBefore = r.listener.listenAtMs.size
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(3_000)
        val last = r.speaker.spoken.last()
        assertTrue(last.text.contains("혈당이 기준보다 낮아요"), last.text)
        assertEquals(DriverState.STOP, r.runtime.view.value.state)
        // 끊긴 V04의 듣기가 뒤늦게 열리지 않는다(V6, G9)
        assertEquals(listensBefore, r.listener.listenAtMs.size)
    }

    @Test
    fun `F3-12 값 미만이면 응답 없이 경유지를 넣고 V09에 장소가 들어간다`() = runTest {
        val r = Rig(this)
        r.navi.routeM["a"] = 3300
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(2_000)
        assertEquals(listOf("a"), r.navi.guided.map { it.id })
        val v09 = r.speaker.spoken.first { it.text.contains("차를 세워 주세요") }
        assertTrue(v09.text.contains("칠곡졸음쉼터까지"))
    }

    @Test
    fun `P18 혈당 자동 안내에서 경유지가 차도 V09는 그대로이고 V28을 말하지 않는다`() = runTest {
        val r = Rig(this)
        r.navi.outcome = GuideOutcome.SLOTS_FULL
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(5_000)
        assertTrue(r.speaker.texts.any { it.contains("차를 세워 주세요") })
        assertFalse(r.speaker.texts.any { it.contains("경유지를 추가하지 못했어요") })
    }

    @Test
    fun `혈당 안내는 장소를 찾지 못해도 늦지 않게 V09N을 말하고 위치가 돌아오면 경유지를 넣는다`() = runTest {
        val r = Rig(this, fix = null)
        r.navi.routeM["a"] = 3300
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive(speed = 50.0)
        advance(10 * minute)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(1_000)
        assertEquals(r.line(VoiceId.V09N), r.speaker.texts.last())
        assertTrue(r.navi.guided.isEmpty())
        r.location.fix.value = TEST_FIX
        advance(10)
        assertEquals(listOf("a"), r.navi.guided.map { it.id })
    }

    /** P22, V-07. 고속도로에서 "정차하세요"는 갓길 정차로 들린다. 장소가 없으면 빠져나가라고만 한다 */
    @Test
    fun `P22 시속 70km 이상에서 장소가 없으면 V09H와 V10H로 나들목을 안내하고 갓길을 말하지 않는다`() = runTest {
        val r = Rig(this, fix = null)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive(speed = 80.0)
        advance(10 * minute)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(1_000)
        assertEquals(r.line(VoiceId.V09H), r.speaker.texts.last())
        // 다음 값이 오기 전에 끊기지 않게 값을 살려 둔다
        r.summary.glucose(3.7, r.clock.nowMs())
        advance(2 * minute + 5_000)
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last())
        val stopLines = r.speaker.texts.filter { "나들목" in it }
        assertEquals(2, stopLines.size, r.speaker.texts.toString())
        assertTrue(r.speaker.texts.none { "갓길" in it || "차로" in it }, r.speaker.texts.toString())
    }

    /** V-07. 고속도로가 막혀 60km/h가 되어도 반복 음성은 고속 변형 그대로다. 갓길로 들리는 문구로 바뀌지 않는다 */
    @Test
    fun `P22 80에서 60으로 정체돼도 V10H를 유지한다`() = runTest {
        val r = Rig(this, fix = null)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive(speed = 80.0)
        advance(10 * minute)
        r.drive(speed = 60.0)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(1_000)
        assertEquals(r.line(VoiceId.V09H), r.speaker.texts.last())
        r.summary.glucose(3.7, r.clock.nowMs())
        advance(2 * minute + 5_000)
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last())
        assertTrue(r.speaker.texts.none { it == r.line(VoiceId.V10N) || it == r.line(VoiceId.V09N) }, r.speaker.texts.toString())
    }

    @Test
    fun `P22 경계 시속 70km는 고속 변형이고 69_9km는 N 변형이다`() = runTest {
        for ((speed, expected) in listOf(70.0 to VoiceId.V09H, 69.9 to VoiceId.V09N)) {
            val r = Rig(this, fix = null)
            r.start()
            r.ignitionOnParked()
            advance(6_000)
            r.drive(speed = speed)
            advance(10 * minute)
            r.summary.glucose(3.8, r.clock.nowMs())
            advance(1_000)
            assertEquals(r.line(expected), r.speaker.texts.last(), "$speed")
        }
    }

    /** 경유지가 안 들어갔는데 "안내 중이에요"라고 말하면 안 된다. 다음 시도로 들어가면 원래 문구로 돌아온다 */
    @Test
    fun `P18 경유지를 넣지 못하면 V10U로 말하고 다음 반복에 다시 시도한다`() = runTest {
        val r = Rig(this)
        r.navi.routeM["a"] = 3300
        r.navi.outcome = GuideOutcome.SLOTS_FULL
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        val at = r.clock.nowMs()
        repeat(2) { r.summary.glucose(3.8, at + it); advance(1_000) }
        val attempts = r.navi.guided.size
        // 다음 값이 오기 전에 끊기지 않게 값을 살려 둔다
        r.summary.glucose(3.7, r.clock.nowMs())
        advance(2 * minute + 5_000)
        val v10u = VoiceCatalog.render(VoiceId.V10U, VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200), PrivacyContext(false)).substringBefore("칠곡")
        assertTrue(r.speaker.texts.last().startsWith(v10u), r.speaker.texts.toString())
        assertFalse("안내 중" in r.speaker.texts.last(), r.speaker.texts.last())
        assertTrue(r.navi.guided.size > attempts, "다시 시도하지 않았다")

        r.navi.outcome = GuideOutcome.WAYPOINT_ADDED
        r.summary.glucose(3.7, r.clock.nowMs())
        advance(2 * minute + 5_000)
        r.summary.glucose(3.7, r.clock.nowMs())
        advance(2 * minute + 5_000)
        assertTrue("안내 중이에요" in r.speaker.texts.last(), r.speaker.texts.toString())
    }

    /** V04N은 대답할 수 없는 문장이다. 10분마다 같은 말을 듣게 하지 않는다 */
    @Test
    fun `V11 장소를 못 찾은 안내는 운행당 처음 한 번만 말한다`() = runTest {
        val r = Rig(this, places = emptyList())
        driveToStopPrompt(r)
        advance(5_000)
        val v04n = r.speaker.texts.count { "근처에 쉴 곳이 안 보여요" in it }
        assertEquals(1, v04n, r.speaker.texts.toString())
        advance(25 * minute)
        assertEquals(1, r.speaker.texts.count { "근처에 쉴 곳이 안 보여요" in it }, r.speaker.texts.toString())
    }

    /** 온보딩 중에 "출발 전에 컨디션은 어떠세요?"가 나오면 안 된다. 끝나면 그날 체크인을 묻는다 */
    @Test
    fun `B11 온보딩이 끝날 때까지 V01을 미룬다`() = runTest {
        val held = MutableStateFlow(true)
        val clock = virtualClock()
        val speaker = FakeSpeaker(1000).also { it.now = clock::nowMs }
        val listener = FakeListener().also { it.now = clock::nowMs }
        val signals = FakeSignals(Gear.UNKNOWN, null, Ignition.OFF)
        val graph = RuntimeGraph(
            RuntimePorts(
                signals, FakeSummary(), FakeNavi(), FakeStore(), FakeLog(), FakeTrip(), FakeOdometer(), speaker, listener,
                FakeOccupancy(), FakeLocation(TEST_FIX), null, listOf(FakeProvider(emptyList())),
            ),
            clock, backgroundScope, Engine(), RuntimeHooks(checkinHeld = held),
        )
        graph.start()
        runCurrent()
        signals.ignition.value = Ignition.ON
        signals.gear.value = Gear.PARK
        signals.speedKmh.value = 0.0
        runCurrent()
        advance(30_000)
        assertTrue(speaker.texts.isEmpty(), speaker.texts.toString())
        assertTrue(listener.listenAtMs.isEmpty(), "온보딩 중 마이크가 열렸다")
        held.value = false
        advance(100)
        assertEquals(listOf(VoiceCatalog.render(VoiceId.V01, VoiceVars(), PrivacyContext(false))), speaker.texts)
        graph.stop()
    }

    /** 재부팅은 시동을 한 번 끈 것이다. 시동 신호가 없는 플랫폼은 저장본이 ON이라 새 시동을 볼 수 없었다 */
    @Test
    fun `재부팅 뒤 시동 신호가 없어도 새 시동으로 보고 V01을 묻는다`() = runTest {
        val first = Rig(this, bootCount = 1)
        first.signals.capabilities.value = first.signals.capabilities.value.copy(ignition = false)
        first.start()
        first.ignitionOnParked()
        advance(6_000)
        first.graph.stop()
        val saved = first.store.record
        assertNotNull(saved)
        val signals = FakeSignals(Gear.PARK, 0.0, Ignition.ON)
        signals.capabilities.value = signals.capabilities.value.copy(ignition = false)
        val second = Rig(this, signals = signals, store = FakeStore(saved), bootCount = 2, startMs = SCENARIO_NOON + 25 * hour)
        second.start()
        advance(6_000)
        assertTrue(second.line(VoiceId.V01) in second.speaker.texts, second.speaker.texts.toString())
    }

    /** 폰 쪽 해제나 401은 값을 모르게 된 것이지 회복이 아니다(D-14) */
    @Test
    fun `V19 폰 쪽에서 끊기면 정차 안내가 음성 없이 사라지지 않는다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.summary.glucose(3.6, r.clock.nowMs())
        advance(3_000)
        assertEquals(DriverState.STOP, r.runtime.view.value.state)
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null, linkLost = true)
        advance(3_000)
        val view = r.runtime.view.value
        assertTrue(Reason.GLUCOSE_UNCONFIRMED in view.reasons, "${view.reasons}")
        assertEquals(DriverState.CAUTION, view.state)
        assertEquals(r.line(VoiceId.V19), r.speaker.texts.last())
    }

    /** 폰 쪽 해제 뒤 남은 보류를 S5에서 푼다(E34, D-33). 음성 없이 풀리고 기록에 조작이 남는다 */
    @Test
    fun `E34 S5 보류 풀기는 폰 쪽 해제 뒤 래치를 음성 없이 풀고 기록한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.summary.glucose(3.6, r.clock.nowMs())
        advance(3_000)
        r.park()
        advance(3_000)
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null, linkLost = true)
        advance(3_000)
        assertTrue(Reason.GLUCOSE_UNCONFIRMED in r.runtime.view.value.reasons)
        // 3.6을 받은 지 몇 초다. 낮은 값 뒤의 보류는 마지막 판독에서 40분 전에는 받지 않는다(D-33)
        assertFalse(r.runtime.releaseGlucoseHold())
        assertTrue(r.log.entries.none { it.event == LogEvent.GLUCOSE_HOLD_RELEASED })
        val releasableAt = assertNotNull(r.runtime.view.value.glucoseHoldReleasableAtMs)
        advance(releasableAt - r.clock.nowMs())
        val spoken = r.speaker.texts.size
        assertTrue(r.runtime.releaseGlucoseHold())
        advance(1)
        val view = r.runtime.view.value
        assertEquals(DriverState.NORMAL, view.state)
        assertFalse(view.glucoseUnavailable)
        assertEquals(spoken, r.speaker.texts.size)
        assertEquals(LogEvent.GLUCOSE_HOLD_RELEASED, r.log.entries.last().event)
        // 상태가 바뀌었든 아니든 받은 조작은 바로 저장한다. 그 사이 죽으면 래치가 되살아난다
        val saved = assertNotNull(EngineStateCodec.decode(assertNotNull(r.store.record).engineStateJson))
        assertFalse(saved.glucoseLatched)
    }

    /** 값이 NONE에서 끊긴 보류(D-31 B)는 풀어도 NORMAL 그대로다. 상태가 그대로여도 받은 E34는 바로 저장한다 */
    @Test
    fun `E34 상태가 그대로여도 바로 저장한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.summary.glucose(6.4, r.clock.nowMs())
        advance(3_000)
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null, linkLost = true)
        advance(3_000)
        assertTrue(r.runtime.view.value.glucoseUnavailable)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        val before = r.store.saves
        assertTrue(r.runtime.releaseGlucoseHold())
        advance(1)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        assertTrue(r.store.saves > before, "저장 횟수 ${r.store.saves}")
        val saved = assertNotNull(EngineStateCodec.decode(assertNotNull(r.store.record).engineStateJson))
        assertNull(saved.glucoseLastValidMs, "저장본도 값을 받은 적 없는 상태다")
    }

    @Test
    fun `E34 주행 중에는 보류 풀기를 받지 않는다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.summary.glucose(3.6, r.clock.nowMs())
        advance(3_000)
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null, linkLost = true)
        advance(3_000)
        assertFalse(r.runtime.releaseGlucoseHold(), "엔진이 거절하면 화면에 풀었다고 하지 않게 false다")
        advance(1)
        assertTrue(Reason.GLUCOSE_UNCONFIRMED in r.runtime.view.value.reasons)
        assertTrue(r.log.entries.none { it.event == LogEvent.GLUCOSE_HOLD_RELEASED })
    }

    /** S5에서 직접 끊은 것은 사용자의 결정이다. 값과 래치를 함께 지운다(E33) */
    @Test
    fun `E33 직접 해제는 음성 없이 초기화한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.summary.glucose(3.6, r.clock.nowMs())
        advance(3_000)
        val spoken = r.speaker.texts.size
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)
        advance(3_000)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        assertEquals(spoken, r.speaker.texts.size)
    }

    /** 경로로 재지 못한 거리는 단정하지 않는다 */
    @Test
    fun `프롬프트 거리는 측정값이면 그대로 못 재면 약을 붙인다`() = runTest {
        val r = Rig(this)
        r.navi.routeM["a"] = 4100
        driveToStopPrompt(r)
        advance(5_000)
        assertTrue(r.speaker.texts.any { "4.1킬로미터 앞" in it && "약" !in it }, r.speaker.texts.toString())
        val other = Rig(this)
        driveToStopPrompt(other)
        advance(5_000)
        assertTrue(other.speaker.texts.any { "약 3.2킬로미터 앞" in it }, other.speaker.texts.toString())
    }

    /** 2:00에 V04를 듣는 지점까지 간다 */
    private fun TestScope.driveToStopPrompt(r: Rig) {
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(2 * hour - 1)
    }

    @Test
    fun `F3-7 응이면 V29와 함께 경유지를 추가한다`() = runTest {
        val r = Rig(this)
        driveToStopPrompt(r)
        r.listener.answers += ListenResult.Heard("응")
        advance(5_000)
        assertEquals(listOf("a"), r.navi.guided.map { it.id })
        assertTrue(r.line(VoiceId.V29) in r.speaker.texts)
        assertFalse(r.speaker.texts.any { it.contains("경유지를 추가하지 못했어요") })
        assertEquals(DriverState.STOP, r.runtime.view.value.state)
    }

    @Test
    fun `P13 경유지 2개가 차면 V28을 말하고 기존 경유지를 건드리지 않는다`() = runTest {
        val r = Rig(this)
        r.navi.outcome = GuideOutcome.SLOTS_FULL
        driveToStopPrompt(r)
        r.listener.answers += ListenResult.Heard("응")
        advance(8_000)
        val v28 = r.speaker.texts.last()
        assertTrue(v28.contains("경유지를 추가하지 못했어요"), v28)
        assertTrue(v28.contains("칠곡졸음쉼터까지"), v28)
    }

    @Test
    fun `P16 30초 무응답이면 V28`() = runTest {
        val r = Rig(this)
        r.navi.guideDelayMs = 60_000
        driveToStopPrompt(r)
        r.listener.answers += ListenResult.Heard("응")
        advance(40_000)
        assertTrue(r.speaker.texts.last().contains("경유지를 추가하지 못했어요"))
    }

    @Test
    fun `V11 장소가 없으면 N 변형을 말하고 찾으면 원래 문구로 다시 묻는다`() = runTest {
        val provider = object : com.conditiondrive.vehicle.port.PlaceProvider {
            override val id = "later"
            var places = emptyList<com.conditiondrive.core.poi.Place>()
            override suspend fun nearby(query: com.conditiondrive.vehicle.port.PlaceQuery) = places
        }
        val clock = virtualClock()
        val signals = FakeSignals(Gear.UNKNOWN, null, Ignition.OFF)
        val speaker = FakeSpeaker(1000).also { it.now = clock::nowMs }
        val listener = FakeListener().also { it.now = clock::nowMs }
        val graph = RuntimeGraph(
            RuntimePorts(signals, FakeSummary(), FakeNavi(), FakeStore(), FakeLog(), FakeTrip(), FakeOdometer(), speaker, listener, FakeOccupancy(), FakeLocation(TEST_FIX), null, listOf(provider)),
            clock, backgroundScope,
        )
        graph.start()
        runCurrent()
        signals.ignition.value = Ignition.ON
        signals.gear.value = Gear.PARK
        signals.speedKmh.value = 0.0
        runCurrent()
        advance(6_000)
        signals.gear.value = Gear.DRIVE
        signals.speedKmh.value = 80.0
        runCurrent()
        advance(2 * hour + 2_000)
        val n = speaker.texts.last()
        assertTrue(n.contains("쉴 곳이 안 보여요"), n)
        val listensBefore = listener.listenAtMs.size

        provider.places = listOf(placeSouth("a", 3.2, "칠곡졸음쉼터"))
        listener.answers += ListenResult.Heard("아니")
        advance(32_000)
        val asked = speaker.texts.last()
        assertTrue(asked.contains("칠곡졸음쉼터에서 쉬어 갈까요"), asked)
        assertEquals(listensBefore + 1, listener.listenAtMs.size)
        assertEquals(1, graph.runtime.view.value.ignoredCount)
    }

    @Test
    fun `P에 세우면 재검색과 대기 중인 프롬프트를 끊는다`() = runTest {
        val r = Rig(this, places = emptyList())
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(2 * hour + 2_000)
        val before = r.provider.queries
        r.park()
        advance(2_000)
        assertEquals(DriverState.RECOVER, r.runtime.view.value.state)
        assertEquals(before, r.provider.queries)
        advance(2 * minute)
        assertEquals(before, r.provider.queries)
        assertNull(r.runtime.promptPlace.value)
    }

    @Test
    fun `V12 F10 첫 거절에 V27을 묻고 응이면 바깥 공기로 바꾼다`() = runTest {
        val port = FakeVentilation()
        val r = Rig(this, ventilation = port)
        driveToStopPrompt(r)
        r.listener.answers += ListenResult.Heard("아니")
        r.listener.answers += ListenResult.Heard("응")
        advance(8_000)
        assertTrue(r.line(VoiceId.V27) in r.speaker.texts)
        assertEquals(listOf(com.conditiondrive.vehicle.port.AirMode.FRESH), port.sets)
        assertEquals(1, r.runtime.view.value.ignoredCount)
    }

    @Test
    fun `V10 시동 OFF면 음성을 끊고 저장하며 다시 켜도 재생하지 않는다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(5_200)
        assertTrue(r.speaker.spoken.isNotEmpty())
        val spokenBefore = r.speaker.spoken.size
        val savesBefore = r.store.saves
        r.signals.ignition.value = Ignition.OFF
        advance(10)
        assertTrue(r.store.saves > savesBefore)
        assertFalse(r.graph.voice.busy.value)
        advance(minute)
        r.signals.ignition.value = Ignition.ON
        advance(4_000)
        assertEquals(spokenBefore, r.speaker.spoken.size)
    }

    @Test
    fun `C-38 30초마다 저장하고 상태가 바뀌어도 저장한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        val base = r.store.saves
        advance(31_000)
        assertEquals(base + 1, r.store.saves)
        assertNotNull(r.store.record)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(1_000)
        assertEquals(DriverState.RECOVER, r.runtime.view.value.state)
        assertEquals(base + 2, r.store.saves)
    }

    private fun savedRecord(bootCount: Int, odometerKm: Double?, atMs: Long, configure: (Engine) -> com.conditiondrive.core.engine.EngineState): EngineStateRecord =
        EngineStateRecord(EngineStateCodec.encode(configure(Engine())), bootCount, odometerKm, null, null, 0).also { check(atMs > 0) }

    @Test
    fun `E30 주행 저장본에서 Odometer가 움직였으면 공백을 운전으로 친다`() = runTest {
        val clock = virtualClock()
        val saved = 30 * minute
        val record = savedRecord(1, 100.0, clock.nowMs()) { engine ->
            var s = engine.initial(clock.nowMs() - saved)
            s = engine.reduce(s, VehicleChanged(clock.nowMs() - saved, Gear.DRIVE, 80.0, Ignition.ON)).state
            s
        }
        val r = Rig(this, FakeSignals(), FakeStore(record), odometer = FakeOdometer(120.0), bootCount = 1)
        r.start()
        advance(1)
        assertEquals(saved / 1000, r.runtime.view.value.continuousDriveSec)
    }

    @Test
    fun `E30 Odometer가 그대로면 공백은 휴식이다`() = runTest {
        val clock = virtualClock()
        val saved = 30 * minute
        val record = savedRecord(1, 100.0, clock.nowMs()) { engine ->
            val s = engine.initial(clock.nowMs() - saved)
            engine.reduce(s, VehicleChanged(clock.nowMs() - saved, Gear.DRIVE, 80.0, Ignition.ON)).state
        }
        val r = Rig(this, FakeSignals(Gear.PARK, 0.0), FakeStore(record), odometer = FakeOdometer(100.2), bootCount = 1)
        r.start()
        advance(1)
        assertEquals(0, r.runtime.view.value.continuousDriveSec)
    }

    @Test
    fun `E30 Odometer를 모르면 운전으로 본다`() = runTest {
        val clock = virtualClock()
        val saved = 30 * minute
        val record = savedRecord(1, null, clock.nowMs()) { engine ->
            val s = engine.initial(clock.nowMs() - saved)
            engine.reduce(s, VehicleChanged(clock.nowMs() - saved, Gear.DRIVE, 80.0, Ignition.ON)).state
        }
        val r = Rig(this, FakeSignals(), FakeStore(record), odometer = FakeOdometer(null), bootCount = 1)
        r.start()
        advance(1)
        assertEquals(saved / 1000, r.runtime.view.value.continuousDriveSec)
    }

    @Test
    fun `V13 BOOT_COUNT가 다르면 혈당은 무효이고 수면은 남는다`() = runTest {
        val clock = virtualClock()
        val now = clock.nowMs()
        val record = savedRecord(1, 100.0, now) { engine ->
            var s = engine.initial(now - 60_000)
            s = engine.reduce(s, VehicleChanged(now - 60_000, Gear.PARK, 0.0, Ignition.ON)).state
            val input = ConditionInput(290, now + hour, 6.4, null, now - 60_000, now + 10 * minute, true)
            s = engine.reduce(s, com.conditiondrive.core.engine.ConditionReceived(now - 50_000, input)).state
            s
        }
        val r = Rig(this, FakeSignals(Gear.PARK, 0.0), FakeStore(record), odometer = FakeOdometer(100.0), bootCount = 2)
        r.start()
        advance(1)
        val view = r.runtime.view.value
        assertNull(view.glucoseMmol)
        assertEquals(290, view.sleepMinutes)
    }

    @Test
    fun `부팅이 같으면 혈당을 그대로 복원한다`() = runTest {
        val clock = virtualClock()
        val now = clock.nowMs()
        val record = savedRecord(2, 100.0, now) { engine ->
            var s = engine.initial(now - 60_000)
            s = engine.reduce(s, VehicleChanged(now - 60_000, Gear.PARK, 0.0, Ignition.ON)).state
            val input = ConditionInput(290, now + hour, 6.4, null, now - 60_000, now + 10 * minute, true)
            s = engine.reduce(s, com.conditiondrive.core.engine.ConditionReceived(now - 50_000, input)).state
            s
        }
        val r = Rig(this, FakeSignals(Gear.PARK, 0.0), FakeStore(record), odometer = FakeOdometer(100.0), bootCount = 2)
        r.start()
        advance(1)
        assertEquals(6.4, r.runtime.view.value.glucoseMmol)
    }

    @Test
    fun `저장본이 깨졌으면 새로 시작한다`() = runTest {
        val r = Rig(this, FakeSignals(Gear.PARK, 0.0), FakeStore(EngineStateRecord("{깨짐", 0, null, null, null, 0)))
        r.start()
        advance(100)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        assertEquals(0, r.runtime.view.value.continuousDriveSec)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `첫 요약 전의 빈 스냅샷은 값 없음으로 넣지 않는다`() = runTest {
        val clock = virtualClock()
        val now = clock.nowMs()
        val record = savedRecord(0, 100.0, now) { engine ->
            var s = engine.initial(now - 60_000)
            s = engine.reduce(s, VehicleChanged(now - 60_000, Gear.PARK, 0.0, Ignition.ON)).state
            val input = ConditionInput(null, null, 6.4, null, now - 60_000, now + 10 * minute, true)
            s = engine.reduce(s, com.conditiondrive.core.engine.ConditionReceived(now - 50_000, input)).state
            s
        }
        val r = Rig(this, FakeSignals(Gear.PARK, 0.0), FakeStore(record), odometer = FakeOdometer(100.0))
        r.start()
        advance(30_000)
        assertEquals(6.4, r.runtime.view.value.glucoseMmol)
        assertFalse(r.speaker.texts.any { it.contains("끊겼어요") })
    }

    @Test
    fun `S5 연결 해제면 G를 지우고 말하지 않는다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(minute)
        r.summary.glucose(3.8, r.clock.nowMs())
        advance(3_000)
        assertEquals(DriverState.STOP, r.runtime.view.value.state)
        val spoken = r.speaker.spoken.size
        r.summary.snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)
        advance(1)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        assertEquals(spoken, r.speaker.spoken.size)
    }

    @Test
    fun `V30 온보딩 전 주행 중 첫 실행이면 안내하고 측정 시작을 표시한다`() = runTest {
        val r = Rig(this, FakeSignals(), firstRun = true)
        r.start()
        advance(3_000)
        assertTrue(r.runtime.view.value.measuredSinceAppStart)
        assertEquals(listOf(r.line(VoiceId.V30)), r.speaker.texts)
    }

    @Test
    fun `차량 서비스가 붙기 전의 빈 신호를 주행으로 오판하지 않는다`() = runTest {
        val signals = FakeSignals(Gear.UNKNOWN, null, Ignition.UNKNOWN)
        val r = Rig(this, signals, firstRun = true)
        r.start()
        advance(500)
        signals.gear.value = Gear.PARK
        signals.speedKmh.value = 0.0
        signals.ignition.value = Ignition.ON
        advance(1_000)
        assertFalse(r.runtime.view.value.measuredSinceAppStart)
        assertTrue(r.speaker.texts.isEmpty())
    }

    @Test
    fun `RouteChanged로 V22를 판단한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(hour + 44 * minute)
        r.navi.route.value = com.conditiondrive.vehicle.port.RouteSnapshot(true, 600, 8000, 0)
        advance(minute + 500)
        val last = r.speaker.texts.last()
        assertTrue(last.contains("목적지까지"), last)
    }

    @Test
    fun `E31 경유지 도착을 엔진에 넘긴다`() = runTest {
        val r = Rig(this)
        driveToStopPrompt(r)
        r.listener.answers += ListenResult.Heard("응")
        advance(5_000)
        val spoken = r.speaker.spoken.size
        r.navi.waypointArrived.tryEmit(Unit)
        advance(3 * minute + 2_000)
        // 3분 뒤 재안내(F3-13). 20분을 기다리지 않는다
        assertTrue(r.speaker.spoken.size > spoken)
    }

    @Test
    fun `F8 Trip 효과는 순서대로 기록한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(31_000)
        r.park()
        advance(100)
        assertEquals(TripEvent.DRIVE_STARTED, r.trip.events.first())
        assertEquals(TripEvent.REST_STARTED, r.trip.events.last())
    }

    @Test
    fun `저장 실패로 판단이 멈추지 않는다`() = runTest {
        val broken = object : com.conditiondrive.vehicle.port.StateStore {
            override suspend fun load() = null
            override suspend fun save(record: EngineStateRecord) = error("디스크 가득")
            override suspend fun clear() = Unit
        }
        val clock = virtualClock()
        val signals = FakeSignals()
        val errors = mutableListOf<Throwable>()
        val graph = RuntimeGraph(
            RuntimePorts(signals, FakeSummary(), FakeNavi(), broken, FakeLog(), FakeTrip(), FakeOdometer(), FakeSpeaker(), FakeListener(), FakeOccupancy(), FakeLocation(TEST_FIX), null, emptyList()),
            clock, backgroundScope, Engine(), RuntimeHooks(onError = { errors += it }),
        )
        graph.start()
        advance(2 * hour)
        assertEquals(DriverState.STOP, graph.runtime.view.value.state)
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `stop은 그 시각까지 적립해 저장한다`() = runTest {
        val r = Rig(this)
        r.start()
        r.ignitionOnParked()
        advance(6_000)
        r.drive()
        advance(10 * minute)
        r.graph.stop()
        val saved = assertNotNull(EngineStateCodec.decode(assertNotNull(r.store.record).engineStateJson))
        assertEquals(10 * minute, saved.driveMs)
    }

    /** 재검증: 마이크 없이 묻지도 답하지도 않았는데 S2가 "컨디션 응답: 보통"이었다 */
    @Test
    fun `마이크가 없으면 V01을 말하지 않고 체크인 응답은 없음으로 남는다`() = runTest {
        val r = Rig(this)
        r.listener.available.value = false
        r.start()
        r.ignitionOnParked()
        advance(5_001)
        advance(2_000)
        assertTrue(r.speaker.texts.isEmpty(), r.speaker.texts.toString())
        assertEquals(CheckinAnswer.NO_SPEECH, r.runtime.view.value.lastCheckin)
        assertEquals(DriverState.NORMAL, r.runtime.view.value.state)
        assertTrue(r.errors.isEmpty(), r.errors.toString())
    }
}
