package com.conditiondrive.vehicle.demo

import android.os.SystemClock
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.GlucoseHoldReleased
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.Tick
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.engine.WaypointArrived
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.PromptAnswer
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.core.scenario.Scenario
import com.conditiondrive.core.scenario.ScenarioCompare
import com.conditiondrive.core.scenario.ScenarioRunner
import com.conditiondrive.core.scenario.ScenarioStep
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.VoiceClassifier
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.poi.ScriptedPlaceProvider
import com.conditiondrive.vehicle.port.DrivingGatePort
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.port.StateStore
import com.conditiondrive.vehicle.port.SummarySource
import com.conditiondrive.vehicle.port.TransitionLogPort
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import com.conditiondrive.vehicle.port.VentilationPort
import com.conditiondrive.vehicle.runtime.EngineRuntime
import com.conditiondrive.vehicle.runtime.RuntimeGraph
import com.conditiondrive.vehicle.runtime.RuntimeHooks
import com.conditiondrive.vehicle.runtime.RuntimePorts
import com.conditiondrive.vehicle.summary.ScriptedSummarySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/** C-55. :core에 시연 심볼을 둘 수 없어 여기 둔다(2장). */
object DemoSpeeds {
    const val DRIVING = 240
    const val RESTING = 120
    const val GLUCOSE = 60
    const val AROUND_GEAR = 1
    const val AROUND_GEAR_SIM_MS = 3000L    // 기어 조작 앞뒤 3초를 실제 속도로 보여 준다
}

/** 시연 시작에 필요한 실제 포트. 없는 것은 null이고 그 기능만 빠진 채 끝까지 돈다(15.2) */
class DemoEnvironment(
    val signals: VehicleSignalsPort,
    val speaker: SpeakerPort,
    val store: StateStore,          // demo_state. 실사용 저장소와 다른 파일이어야 한다(R7)
    val log: TransitionLogPort,
    val trip: TripLogPort,
    val places: List<PlaceProvider>,
    val listener: ListenerPort? = null,
    val navi: NaviPort? = null,
    val ventilation: VentilationPort? = null,
    val realGate: DrivingGatePort? = null,  // 내장 시연 화면 게이트의 바닥. 없으면 시나리오 기어만 본다
    val tripAt: ((() -> Long) -> TripLogPort)? = null,   // 기록을 시뮬레이션 시각으로 읽는 보기. 없으면 trip 그대로다
)

/** gate가 null이면 차량 신호 시연이라 실제 게이트를 그대로 쓴다 */
class DemoUiPorts(
    val gate: DrivingGatePort?,
    val navi: NaviPort,
    val occupancy: OccupancyPort,
    val summary: SummarySource,
    val trips: TripLogPort,
    val clock: Clock,       // "측정 3분 전" 같은 표시도 시뮬레이션 시각으로 재야 한다
    val caption: StateFlow<String?>,
)

sealed interface DemoStatus {
    data object Idle : DemoStatus
    data class Running(val scenarioId: String, val step: Int, val steps: Int) : DemoStatus
    data class Finished(val scenarioId: String, val report: String, val mismatches: List<String>) : DemoStatus
}

enum class DemoStartResult { STARTED, NOT_PARKED, UNKNOWN_SCENARIO, ALREADY_RUNNING }

/**
 * 차량 신호 시연이 콘솔에 기다리는 것. tools/vc.sh가 로그로 읽어 그때 기어를 넣는다.
 * 지도가 앱을 덮으면 화면 표지를 읽을 수 없고, 음성 길이에 따라 줄의 실제 시각이 달라 고정 시간표로는 맞출 수 없다.
 * step은 기어 이름, 기다리는 것이 없으면 none, 끝나면 done(대조 리포트 있음) 또는 stopped다.
 */
data class ConsoleCue(val run: Long, val scenarioId: String, val step: String) {
    fun line(): String = "run=$run id=$scenarioId await=$step"

    companion object {
        const val NONE = "none"
        const val DONE = "done"
        const val STOPPED = "stopped"
    }
}

/** 시나리오에 고정된 경부고속도로 지점(16장 SC2). 내장 POI의 실제 휴게소 3.2km 북쪽에서 남쪽으로 달린다 */
object DemoFixtures {
    private const val KM_PER_DEGREE_LAT = 111.195
    private const val LEAD_KM = 3.2
    const val LEAD_M = 3200
    const val LEAD_ETA_SEC = 144

    val PLACE = Place(
        id = "ra-e7ad2b2b63d2",
        name = "칠곡휴게소(부산방향)",
        latitude = 36.019795,
        longitude = 128.428517,
        kind = PlaceKind.REST_AREA,
        direction = "부산방향",
    )

    fun fixAt(atMs: Long) = GeoFix(
        latitude = PLACE.latitude + LEAD_KM / KM_PER_DEGREE_LAT,
        longitude = PLACE.longitude,
        headingDeg = 180.0,
        atMs = atMs,
    )
}

/**
 * 15장. 시나리오 JSON으로 시계와 입력 포트만 움직인다. 판단은 실제 엔진이 하고, 시연 전용 분기는 :core에 없다(규칙 6).
 * 버튼은 화면 몫이고 여기서는 시작, 정지, 표지와 자막 문구, 대조 리포트만 낸다.
 */
class DemoController(
    private val env: DemoEnvironment,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val reportSink: suspend (scenarioId: String, report: String) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
) {
    private val _status = MutableStateFlow<DemoStatus>(DemoStatus.Idle)
    val status: StateFlow<DemoStatus> = _status

    private val _runtime = MutableStateFlow<EngineRuntime?>(null)
    /** 시연 중에는 화면이 이 런타임을 본다 */
    val runtime: StateFlow<EngineRuntime?> = _runtime

    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label

    private val _caption = MutableStateFlow<String?>(null)
    /** 소리 없이도 시연 내용을 알 수 있게 표지 아래에 보이는 한 줄(R10) */
    val caption: StateFlow<String?> = _caption

    private val _pendingPlace = MutableStateFlow<Place?>(null)
    /** 내장 시연이 실제 내비로 보내지 않고 남겨 둔 장소. 시연이 내려가도 사람이 정할 때까지 남는다 */
    val pendingPlace: StateFlow<Place?> = _pendingPlace

    private val _ui = MutableStateFlow<DemoUiPorts?>(null)
    /** 시연 중 화면이 볼 포트. 실제 포트를 보면 동승값과 요약이 런타임이 판단한 값과 갈라진다 */
    val ui: StateFlow<DemoUiPorts?> = _ui

    private val _consoleCue = MutableStateFlow<ConsoleCue?>(null)
    /** 차량 신호 시연에서만 값이 있다 */
    val consoleCue: StateFlow<ConsoleCue?> = _consoleCue

    private var session: Session? = null
    private var job: Job? = null

    /** R5. 시연 입력이 실제 주행과 섞이면 안 되므로 P에서만 시작한다. 기어 신호가 없는 차종은 차속으로 본다 */
    private fun parkedNow(): Boolean {
        val signals = env.signals
        return signals.gear.value == Gear.PARK ||
            (!signals.capabilities.value.gear && (signals.speedKmh.value ?: 0.0) <= 0.0)
    }

    fun start(scenarioId: String, realSignals: Boolean = false): DemoStartResult {
        if (job?.isActive == true) return DemoStartResult.ALREADY_RUNNING
        if (!parkedNow()) return DemoStartResult.NOT_PARKED
        val scenario = try {
            ScenarioRunner.load(scenarioId)
        } catch (_: IllegalArgumentException) {
            return DemoStartResult.UNKNOWN_SCENARIO
        }
        // 프로세스 종료·복원 줄은 시연 대상이 아니다(16장 시나리오 3개). 넣으면 시계와 저장이 어긋난다
        if (scenario.steps.any { it.input?.killProcess == true || it.input?.restoreGap != null }) {
            return DemoStartResult.UNKNOWN_SCENARIO
        }
        _pendingPlace.value = null
        _caption.value = null
        val created = Session(scenario, realSignals)
        session = created
        _ui.value = created.uiPorts
        created.uiPorts.gate?.start()
        _runtime.value = created.graph.runtime
        if (realSignals) _consoleCue.value = ConsoleCue(elapsedMs(), scenarioId, ConsoleCue.NONE)
        job = scope.launch {
            val finished = created.run()
            _status.value = finished
            reportSink(finished.scenarioId, finished.report)
            markCue(ConsoleCue.DONE)
        }
        return DemoStartResult.STARTED
    }

    /** 재시작 버튼, 화면 이탈. 실제 내비에는 아무것도 보내지 않는다(15.3 규칙 5) */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        session?.graph?.stop()
        session?.uiPorts?.gate?.stop()
        session = null
        if (_consoleCue.value?.step != ConsoleCue.DONE) markCue(ConsoleCue.STOPPED)
        // 런타임이 비는 순간 조립이 실사용 화면을 설치한다. 먼저 비우지 않으면 끝난 시연의 게이트와 요약이 화면에 남는다
        _ui.value = null
        _runtime.value = null
        _label.value = null
        _caption.value = null
        _status.value = DemoStatus.Idle
    }

    private fun markCue(step: String) {
        _consoleCue.value = _consoleCue.value?.copy(step = step)
    }

    /** 사람이 설정 > 시연 모드에서 눌렀을 때만 실제 내비로 보낸다. 지도가 앱을 덮고 경로가 남는다는 것을 알고 누른다 */
    suspend fun sendPendingPlace(): GuideOutcome? {
        val place = _pendingPlace.value ?: return null
        _pendingPlace.value = null
        return env.navi?.guideTo(place)
    }

    /** 시뮬레이션 시각. 시연 중이 아니면 null. 시나리오 대조와 테스트가 쓴다 */
    fun simulatedNowMs(): Long? = session?.simNowMs

    private inner class Session(private val scenario: Scenario, private val realSignals: Boolean) {
        private val base = OffsetDateTime.parse(scenario.startLocal).toInstant().toEpochMilli()
        private val steps = scenario.steps
        private val startMs = timeOf(steps.first())
        private val clock = CompressedClock(startMs, elapsedMs)

        private val scriptedSignals = ScriptedVehicleSignals()
        private val hybridSignals = if (realSignals) HybridVehicleSignals(env.signals) else null
        private val summary = ScriptedSummarySource()
        private val navi = ScriptedNavi(env.navi, deferReal = !realSignals) { place -> _pendingPlace.value = place }
        private val listener = ScriptedListener(env.listener) { answer -> showCaption("시나리오 응답: $answer") }
        private val log = CollectingLog(env.log)
        private val trip = env.tripAt?.invoke(clock::nowMs) ?: env.trip

        private val gearTimes = steps.filter { it.input?.gear != null }.map(::timeOf)
        private val hasGlucose = steps.any { it.input?.summary?.glucoseMmol != null }

        @Volatile private var holding = false
        @Volatile private var nextIndex = 0
        @Volatile private var awaitingGear: Gear? = null
        private var keepAliveAt: Long? = null

        private val pacer = CompressedClockPacer(clock)

        val simNowMs: Long get() = clock.nowMs()

        private val occupancy = ScriptedOccupancy()
        val uiPorts = DemoUiPorts(
            gate = if (realSignals) null else DemoDrivingGate(scriptedSignals, env.realGate, scope),
            navi = navi,
            occupancy = occupancy,
            summary = summary,
            trips = trip,
            clock = clock,
            caption = _caption,
        )

        val graph = RuntimeGraph(
            RuntimePorts(
                signals = hybridSignals ?: scriptedSignals,
                summary = summary,
                navi = navi,
                store = env.store,
                log = log,
                trip = trip,
                odometer = ScriptedOdometer,
                speaker = CaptionSpeaker(env.speaker) { text -> showCaption("앱 음성: $text") },
                listener = listener,
                occupancy = occupancy,
                location = ScriptedLocation(DemoFixtures.fixAt(startMs)),
                ventilation = env.ventilation?.let { CaptionVentilation(it, ::showSdkNote) },
                providers = listOf(ScriptedPlaceProvider(listOf(DemoFixtures.PLACE))) + env.places,
            ),
            clock,
            scope,
            Engine(),
            RuntimeHooks(pacer = pacer, demoLabel = _label, onError = onError),
        )

        init {
            listener.planner = ::planAnswer
        }

        private fun timeOf(step: ScenarioStep): Long = base + offsetMs(step.t)

        @Volatile private var lastCaption: String? = null
        @Volatile private var sdkNote: String? = null

        /** 음성 자막은 다음 음성에 바로 덮인다. SDK 줄은 그 아래에 남겨 V04까지의 몇 초 안에 놓쳐도 시연 끝까지 보이게 한다 */
        private fun showCaption(text: String?) {
            lastCaption = text
            _caption.value = listOfNotNull(text, sdkNote).joinToString("\n").ifEmpty { null }
        }

        private fun showSdkNote(note: String) {
            sdkNote = note
            showCaption(lastCaption)
        }

        private val speechBusy: Boolean get() = graph.runtime.speechBusy.value

        private fun hold(on: Boolean) {
            holding = on
            pacer.setHeld(on)
        }

        suspend fun run(): DemoStatus.Finished {
            // 이전 시연 기록이 같은 날짜로 합쳐지면 안 된다. 실사용 기록은 다른 DB다(R7)
            env.store.clear()
            trip.deleteAll()
            hold(true)
            graph.start()
            graph.runtime.sync()
            hold(false)
            for ((index, step) in steps.withIndex()) {
                nextIndex = index
                _status.value = DemoStatus.Running(scenario.id, index, steps.size)
                val target = timeOf(step)
                advanceWithKeepAlive(target)
                advance(target, awaitsAnswer = answerOf(step) != null)
                applyStep(step, target)
            }
            nextIndex = steps.size
            while (speechBusy) {
                refreshPace()
                delay(POLL_MS)
            }
            hold(true)
            refreshLabel(finished = true)
            if (_pendingPlace.value != null) showCaption("실제 내비로 보내려면 설정 > 시연 모드에서 고르세요")
            val entries = log.entries.toList()
            return DemoStatus.Finished(
                scenario.id,
                ScenarioCompare.report(scenario, entries),
                ScenarioCompare.compare(scenario, entries),
            )
        }

        private suspend fun advanceWithKeepAlive(target: Long) {
            while (true) {
                val due = keepAliveAt ?: return
                if (due >= target) return
                advance(due, awaitsAnswer = false)
                injectKeepAlive(due)
            }
        }

        /** 같은 시각이면 입력이 먼저라(3.4) 1ms 앞에서 멈췄다가 applyStep이 넘어간다 */
        private suspend fun advance(target: Long, awaitsAnswer: Boolean) {
            val stopAt = target - 1
            clock.holdAt(stopAt)
            while (true) {
                refreshPace()
                answerVentilation()
                if (awaitsAnswer && listener.awaiting.value) return
                if (clock.nowMs() >= stopAt && !speechBusy) return
                delay(POLL_MS)
            }
        }

        private fun answerVentilation() {
            if (listener.awaiting.value && graph.voice.current.value == VoiceId.V27) listener.deliver(VENTILATION_ANSWER)
        }

        private suspend fun applyStep(step: ScenarioStep, target: Long) {
            val input = step.input
            hold(true)
            input?.gear?.let { awaitConsoleGear(it, target) }
            val answer = answerOf(step)
            graph.runtime.inLoop {
                clock.jumpTo(target)
                clock.holdAt(target)
                if (input != null) {
                    input.routeRemainingSec?.let(navi::setRemaining)
                    input.ignition?.let { ignitionCaption(it) }
                    if (hybridSignals != null) {
                        input.ignition?.let(hybridSignals::setIgnition)
                    } else {
                        scriptedSignals.set(input.gear, input.speedKmh, input.ignition)
                    }
                    applySummary(step, target)
                    flushInputs()
                    // 러너와 같은 자리다: 요약 다음, 응답 앞(D16, D28)
                    if (input.glucoseHoldRelease == true) dispatch(GlucoseHoldReleased(target))
                }
                if (answer == null) finishStep(step, target)
            }
            if (answer != null) {
                deliverAnswer(answer)
                graph.runtime.inLoop { finishStep(step, target) }
            }
            hold(false)
        }

        private fun EngineRuntime.LoopAccess.finishStep(step: ScenarioStep, target: Long) {
            if (step.input?.waypointArrived == true) dispatch(WaypointArrived(target))
            dispatch(Tick(target))
        }

        /** 시나리오의 시동 끔/켬은 화면으로 드러나지 않는다. 91 mg/dL 뒤 곧바로 68 mg/dL이 나오는 이유를 알려 준다 */
        private fun ignitionCaption(ignition: Ignition) {
            if (ignition == Ignition.ON && nextIndex == 0) return
            showCaption(if (ignition == Ignition.OFF) "시나리오: 시동 끔" else "시나리오: 시동 켬")
        }

        /** 15.4. 차량 신호 시연의 기어 줄은 시계를 멈추고 콘솔 입력을 기다린다. 무엇을 기다리는지 표지에 보인다 */
        private suspend fun awaitConsoleGear(gear: Gear, target: Long) {
            if (!realSignals) return
            // 콘솔 입력은 루프가 바로 읽는다. 시계를 먼저 옮겨 두어야 그 줄의 시각으로 기록된다
            clock.jumpTo(target)
            clock.holdAt(target)
            awaitingGear = gear
            markCue(gear.name)
            while (env.signals.gear.value != gear) {
                refreshLabel(finished = false)
                delay(POLL_MS)
            }
            awaitingGear = null
            markCue(ConsoleCue.NONE)
        }

        /** 듣고 있지 않으면 건넬 곳이 없다. 음성이 끝나면 그 답은 버리고 진행한다 */
        private suspend fun deliverAnswer(text: String) {
            while (speechBusy && !listener.awaiting.value) delay(POLL_MS)
            listener.deliver(text)
            while (listener.awaiting.value) delay(POLL_MS)
            // 응답이 큐에서 루프로 넘어갈 틈을 준다. 시계는 멈춰 있으므로 전이 시각은 그대로다
            delay(POLL_MS)
            graph.runtime.sync()
        }

        private fun answerOf(step: ScenarioStep): String? = step.input?.checkinText ?: step.input?.promptText

        /** 마이크가 열리는 순간 다음 줄의 답을 본다. 답이 없으면 침묵으로 끝나 시계가 멈춰 있지 않는다 */
        private fun planAnswer(): ScriptedListener.PlannedAnswer? {
            if (graph.voice.current.value == VoiceId.V27) {
                return ScriptedListener.PlannedAnswer(VENTILATION_ANSWER) { VoiceClassifier.yesNo(it) in CLEAR_PROMPT }
            }
            val now = clock.nowMs()
            for (index in nextIndex until steps.size) {
                val step = steps[index]
                step.input?.checkinText?.let { text ->
                    return ScriptedListener.PlannedAnswer(text) { VoiceClassifier.checkin(it) in CLEAR_CHECKIN }
                }
                step.input?.promptText?.let { text ->
                    return ScriptedListener.PlannedAnswer(text) { VoiceClassifier.stopPrompt(it) in CLEAR_PROMPT }
                }
                // 콘솔 입력은 시각이 조금 어긋날 수 있다. 바로 다음 줄의 음성을 위한 마이크면 그 뒤 줄의 답을 본다
                if (timeOf(step) > now + PLAN_SLACK_MS) return null
            }
            return null
        }

        private fun applySummary(step: ScenarioStep, target: Long) {
            val input = step.input ?: return
            if (input.unpaired == true) {
                summary.pushUnpaired()
                keepAliveAt = null
            }
            if (input.summaryNull == true) {
                summary.pushNull(target)
                keepAliveAt = null
            }
            val explicit = input.summary
            if (explicit != null) {
                summary.push(explicit, target, explicit.measuredAt?.let { base + offsetMs(it) })
                if (scenario.keepGlucoseAlive && summary.hasGlucose) keepAliveAt = target + KEEP_ALIVE_MS
            } else if (keepAliveAt == target && input.glucoseCut != true) {
                refreshKeepAlive(target)
            }
            if (input.glucoseCut == true) keepAliveAt = null
        }

        private suspend fun injectKeepAlive(at: Long) {
            hold(true)
            graph.runtime.inLoop {
                clock.jumpTo(at)
                clock.holdAt(at)
                refreshKeepAlive(at)
                flushInputs()
                dispatch(Tick(at))
            }
            hold(false)
        }

        private fun refreshKeepAlive(at: Long) {
            // 시동이 꺼지면 폴링도 멈춘다. 켜진 뒤 첫 주기에 이어진다(ScenarioRunner와 같은 규칙)
            val ignition = hybridSignals?.ignition?.value ?: scriptedSignals.ignition.value
            if (ignition != Ignition.OFF) summary.refreshGlucose(at)
            keepAliveAt = at + KEEP_ALIVE_MS
        }

        private fun refreshPace() {
            clock.setSpeed(speedAt(clock.nowMs()))
            refreshLabel(finished = false)
        }

        private fun speedAt(now: Long): Int = when {
            gearTimes.any { kotlin.math.abs(it - now) <= DemoSpeeds.AROUND_GEAR_SIM_MS } -> DemoSpeeds.AROUND_GEAR
            !graph.runtime.view.value.driving -> DemoSpeeds.RESTING
            hasGlucose -> DemoSpeeds.GLUCOSE
            else -> DemoSpeeds.DRIVING
        }

        private fun refreshLabel(finished: Boolean) {
            _label.value = demoLabel(
                speed = clock.speed,
                frozen = clock.isFrozen,
                finished = finished,
                simMs = clock.nowMs(),
                glucoseSimulated = graph.runtime.view.value.glucoseSimulated,
                scriptedAnswers = listener.usedScript.value,
                scriptedVehicle = !realSignals,
                sleepScripted = summary.snapshot.value.input?.sleepMinutes != null,
                awaitingGear = awaitingGear,
            )
        }
    }

    private class CaptionSpeaker(private val delegate: SpeakerPort, private val caption: (String) -> Unit) : SpeakerPort by delegate {
        override suspend fun speak(text: String, priority: VoicePriority): SpeakResult {
            caption(text)
            return delegate.speak(text, priority)
        }
    }

    /**
     * 공기 모드는 시스템 화면의 아이콘으로만 드러난다. 실제 SDK 호출과 그 결과를 앱 화면에도 적는다.
     * 콘솔 기본값(바깥 공기)이면 V27을 묻지 않고, 콘솔 하트비트가 없으면 값이 안 바뀐다. 어느 쪽이든 왜 변경 장면이 없는지 보여야 한다.
     * 결과를 꾸며 내지 않으려고 값은 포트가 돌려준 것만 적는다.
     */
    private class CaptionVentilation(private val delegate: VentilationPort, private val note: (String) -> Unit) : VentilationPort by delegate {
        override suspend fun supported(): Boolean {
            val ok = delegate.supported()
            if (!ok) note("Vehicle SDK 공기 모드: 이 환경에서 쓸 수 없어요")
            return ok
        }

        override suspend fun current(): AirMode? {
            val mode = delegate.current()
            if (mode == AirMode.FRESH) note("Vehicle SDK로 공기 모드 읽음: 이미 바깥 공기라 묻지 않아요")
            return mode
        }

        // ×240이면 복원이 변경 약 2초 뒤라, 복원 문구가 변경 사실을 지우면 끝까지 남는 줄에서 변경 장면이 사라진다
        private var changedToFresh = false

        override suspend fun set(mode: AirMode): Boolean {
            val done = delegate.set(mode)
            if (done && mode == AirMode.FRESH) changedToFresh = true
            note(
                when {
                    mode == AirMode.FRESH && done -> "Vehicle SDK로 공기 모드 변경: 바깥 공기"
                    changedToFresh && done -> "Vehicle SDK로 공기 모드 변경: 바깥 공기, 이후 재순환으로 복원"
                    changedToFresh -> "Vehicle SDK로 공기 모드 변경: 바깥 공기, 재순환 복원은 값이 그대로예요"
                    !done -> "Vehicle SDK로 공기 모드 변경 요청: 값이 그대로예요"
                    else -> "Vehicle SDK로 공기 모드 변경: 재순환으로 복원"
                },
            )
            return done
        }
    }

    private class CollectingLog(private val delegate: TransitionLogPort) : TransitionLogPort {
        val entries = CopyOnWriteArrayList<TransitionEntry>()
        override fun append(entry: TransitionEntry) {
            entries += entry
            delegate.append(entry)
        }
        override suspend fun export(): java.io.File = delegate.export()
    }

    companion object {
        /** 16장 시나리오 3개. S5 목록에 쓴다 */
        val SCENARIOS: List<String> = listOf("sc1", "sc2", "sc3")

        private const val POLL_MS = 50L
        private const val KEEP_ALIVE_MS = 5 * 60 * 1000L
        private const val PLAN_SLACK_MS = 1000L
        private const val VENTILATION_ANSWER = "응"     // 16장 SC2 표. V27은 엔진 음성이 아니라 JSON에 줄이 없다
        private val CLEAR_CHECKIN = setOf(CheckinAnswer.GOOD, CheckinAnswer.NEUTRAL, CheckinAnswer.TIRED)
        private val CLEAR_PROMPT = setOf(PromptAnswer.ACCEPT, PromptAnswer.DECLINE, PromptAnswer.MUTE)
        private val SIM_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"))

        private fun offsetMs(t: String): Long {
            val negative = t.startsWith("-")
            val parts = t.removePrefix("-").split(":").map { it.toLong() }
            val ms = (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            return if (negative) -ms else ms
        }

        private fun gearLetter(gear: Gear): String = when (gear) {
            Gear.PARK -> "P"
            Gear.REVERSE -> "R"
            Gear.NEUTRAL -> "N"
            Gear.DRIVE, Gear.UNKNOWN -> "D"
        }

        /** 8.3 시연 표지. 혈당 시뮬레이션은 값에서 자동으로 붙는다(15.2). 하드코딩하지 않는다 */
        internal fun demoLabel(
            speed: Int,
            frozen: Boolean,
            finished: Boolean,
            simMs: Long,
            glucoseSimulated: Boolean,
            scriptedAnswers: Boolean,
            scriptedVehicle: Boolean = false,
            sleepScripted: Boolean = false,
            awaitingGear: Gear? = null,
        ): String = buildList {
            add("시연 모드")
            add(
                when {
                    finished -> "시연 끝"
                    frozen -> "시간 정지"
                    speed == 1 -> "실제 시간"
                    else -> "시간 압축 ×$speed"
                },
            )
            add("시뮬레이션 시각 ${SIM_TIME.format(Instant.ofEpochMilli(simMs))}")
            if (glucoseSimulated) add("혈당 시뮬레이션")
            if (sleepScripted) add("수면 시연 데이터")
            if (scriptedAnswers) add("음성 응답은 시나리오 입력")
            // 시스템 화면은 P인데 앱은 "2시간째 운전 중"이라고 말한다. 왜 그런지 표지가 밝힌다
            if (scriptedVehicle) add("차량 신호는 시나리오 입력(시스템 화면은 실제 P)")
            awaitingGear?.let { add("콘솔에서 ${gearLetter(it)}로 바꿔 주세요") }
        }.joinToString(" · ")
    }
}
