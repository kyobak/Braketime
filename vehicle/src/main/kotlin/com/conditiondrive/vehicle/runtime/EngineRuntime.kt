package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.AskCheckin
import com.conditiondrive.core.engine.AskStop
import com.conditiondrive.core.engine.CancelPendingPrompts
import com.conditiondrive.core.engine.CancelVoice
import com.conditiondrive.core.engine.CheckinHold
import com.conditiondrive.core.engine.ConditionInput
import com.conditiondrive.core.engine.ConditionReceived
import com.conditiondrive.core.engine.Decision
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.EngineEvent
import com.conditiondrive.core.engine.EngineState
import com.conditiondrive.core.engine.EngineStateCodec
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.GapEvidence
import com.conditiondrive.core.engine.GlucoseHoldReleased
import com.conditiondrive.core.engine.LogEvent
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.OfferVentilation
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.ProcessRestored
import com.conditiondrive.core.engine.RouteChanged
import com.conditiondrive.core.engine.Say
import com.conditiondrive.core.engine.StartGuidance
import com.conditiondrive.core.engine.Tick
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.engine.Trip
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.engine.VehicleChanged
import com.conditiondrive.core.engine.WaypointArrived
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.EngineStateRecord
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.StateStore
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.SummarySource
import com.conditiondrive.vehicle.port.TransitionLogPort
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * 10.1 런타임 규칙. reduce는 이 클래스의 단일 코루틴에서만 호출한다.
 * 신호를 흐름으로 받아 바로 reduce하지 않고 루프가 현재 값을 읽어 넣는다. 그래야 같은 시각의 입력 순서(9.4)가 코루틴 스케줄에 좌우되지 않는다.
 */
class EngineRuntime(
    private val engine: Engine,
    private val clock: Clock,
    private val signals: VehicleSignalsPort,
    private val summary: SummarySource,
    private val navi: NaviPort,
    private val store: StateStore,
    private val log: TransitionLogPort,
    private val trip: TripLogPort,
    private val odometer: OdometerPort,
    private val voice: VoiceQueue,
    private val guidance: StopGuidanceController,
    private val ventilation: VentilationController,
    scope: CoroutineScope,
    private val hooks: RuntimeHooks = RuntimeHooks(),
    private val policy: Policy = Policy.DEFAULT,
) {
    /** 루프 안에서 입력을 넣을 때 쓰는 손잡이. 시연이 한 줄의 입력을 틱 없이 한꺼번에 넣으려고 쓴다 */
    interface LoopAccess {
        fun flushInputs()
        fun dispatch(event: EngineEvent)
    }

    private sealed interface Command {
        data object Wake : Command
        data class Event(val event: EngineEvent) : Command
        class Run(val block: suspend LoopAccess.() -> Unit, val done: CompletableDeferred<Unit>) : Command
    }

    private data class VehicleInput(val gear: Gear, val speedKmh: Double?, val ignition: Ignition)

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val inner = CoroutineScope(scope.coroutineContext + job)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val persistRequests = Channel<Unit>(Channel.CONFLATED)
    private val tripEvents = Channel<Pair<TripEvent, Long>>(Channel.UNLIMITED)

    @Volatile private var state: EngineState = engine.initial(clock.nowMs())
    private var started = false
    private var startedAtMs = 0L
    private var tripLogCursor = 0L

    private var lastVehicle: VehicleInput? = VehicleInput(Gear.UNKNOWN, null, Ignition.OFF)
    private var lastRouteSec: Int? = null
    private var lastSnapshot: SummarySnapshot? = null
    private var awaitingFirstVehicle = true
    private var freshStart = true
    private var lastDistanceRefreshMs = 0L

    private var placeJob: Job? = null
    private val trackedJobs = AtomicInteger(0)
    // V04N, V06N은 운행당 처음 1회만 말한다. 대답할 수 없는 문장을 10분마다 듣게 하지 않는다
    private var noPlaceVoiceSaid = false
    private var autoGuideRunning = false

    private val _view = MutableStateFlow(engine.view(state, clock.nowMs()))
    val view: StateFlow<EngineView> = _view
    val promptPlace: StateFlow<PlaceCandidate?> = guidance.current
    val demoLabel: StateFlow<String?> = hooks.demoLabel
    /** S1 "듣고 있어요"(8.2) */
    val listening: StateFlow<Boolean> = voice.listening

    private val _speechBusy = MutableStateFlow(false)
    /** 큐의 음성과 장소 찾기·경유지 추가를 합친 값. 시연 시계는 이 동안 멈춘다(15.3) */
    val speechBusy: StateFlow<Boolean> = _speechBusy

    private val access = object : LoopAccess {
        override fun flushInputs() = this@EngineRuntime.flushInputs()
        override fun dispatch(event: EngineEvent) {
            apply(event)
        }
    }

    fun start() {
        if (started) return
        started = true
        startedAtMs = clock.nowMs()
        signals.start()
        summary.start()
        guidance.start()
        voice.start()
        watchInputs()
        inner.launch { runPersistWorker() }
        inner.launch { runTripWorker() }
        inner.launch {
            while (true) {
                delay(policy.PERSIST_SEC * 1000)
                persistRequests.trySend(Unit)
            }
        }
        inner.launch {
            restore()
            runLoop()
        }
    }

    suspend fun stop() {
        if (!started) return
        started = false
        // 꺼지기 직전까지 적립한 값을 저장해야 다음 시동의 휴식 공백이 맞다
        inLoop { dispatch(Tick(clock.nowMs())) }
        voice.stop()
        guidance.stop()
        withContext(NonCancellable) {
            ventilation.stop()
            job.cancelAndJoin()
            saveNow()
        }
        signals.stop()
        summary.stop()
    }

    /** 앞서 넣은 명령이 처리될 때까지 기다린다 */
    suspend fun sync() = inLoop {}

    /**
     * S5 "보류 풀기"(E34, D-33). 폰 쪽 해제나 401 뒤 남은 혈당 보류를 차에서 푼다.
     * 주행 중인지, 지금 유효한 값이 있는지는 엔진이 다시 본다. 화면 게이트 하나에만 기대지 않는다
     */
    suspend fun releaseGlucoseHold(): Boolean {
        var accepted = false
        // 루프가 명령 직전에 입력을 반영하므로 엔진은 지금 기어로 거른다
        inLoop {
            accepted = apply(GlucoseHoldReleased(clock.nowMs())) == LogEvent.GLUCOSE_HOLD_RELEASED
        }
        // 엔진이 거절했으면(그 사이 출발, 값 복귀, 40분 전) 화면이 "풀었어요"라고 하면 안 된다
        return accepted
    }

    suspend fun inLoop(block: suspend LoopAccess.() -> Unit) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Run(block, done))
        done.await()
    }

    /** Unconfined로 받아 깨우기만 한다. 값은 루프가 직접 읽으므로 몇 번 합쳐져도 잃는 것이 없다 */
    private fun watchInputs() {
        fun wakeOn(flow: StateFlow<*>) {
            inner.launch(Dispatchers.Unconfined) { flow.collect { commands.trySend(Command.Wake) } }
        }
        wakeOn(signals.gear)
        wakeOn(signals.speedKmh)
        wakeOn(signals.ignition)
        wakeOn(summary.snapshot)
        wakeOn(navi.route)
        wakeOn(hooks.checkinHeld)
        inner.launch(Dispatchers.Unconfined) { voice.busy.collect { updateBusy() } }
        inner.launch(Dispatchers.Unconfined) { voice.heard.collect { commands.trySend(Command.Event(it)) } }
        inner.launch(Dispatchers.Unconfined) {
            navi.waypointArrived.collect { commands.trySend(Command.Event(WaypointArrived(clock.nowMs()))) }
        }
    }

    /** 10.1 규칙 4 */
    private suspend fun restore() {
        val record = guarded { store.load() }
        val restored = record?.let { EngineStateCodec.decode(it.engineStateJson) }
        if (record == null || restored == null) {
            state = engine.initial(clock.nowMs())
            publishView()
            return
        }
        freshStart = false
        lastVehicle = null
        tripLogCursor = record.tripLogCursor
        state = restored
        val gap = gapEvidence(record.odometerKm)
        // 재부팅이면 혈당 값을 믿을 수 없다. 수면은 자기 만료까지 유효하므로 남긴다(13장). 값을 먼저 지워야 공백에 걸린 40분이 회복이 되지 않는다
        val rebooted = record.bootCount != hooks.bootCount()
        apply(ProcessRestored(clock.nowMs(), gap, rebooted))
        if (rebooted) {
            // 재부팅은 시동을 한 번 끈 것이다. 시동 신호가 없는 차종은 저장본이 ON이라 새 시동을 볼 수 없다.
            // 이걸 넣어야 다음 차량 입력에서 E27, E28을 타고 V01과 출발 보류가 산다
            apply(VehicleChanged(clock.nowMs(), state.gear, state.speedKmh, Ignition.OFF))
        }
    }

    private suspend fun gapEvidence(savedKm: Double?): GapEvidence {
        val nowKm = guarded { withTimeoutOrNull(ODOMETER_TIMEOUT_MS) { odometer.totalKm() } }
        return when {
            savedKm == null || nowKm == null -> GapEvidence.UNKNOWN
            nowKm - savedKm > policy.ODOMETER_MOVED_KM -> GapEvidence.DROVE
            else -> GapEvidence.PARKED
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runLoop() {
        while (true) {
            val wait = nextWaitMs()
            val command = select<Command?> {
                commands.onReceive { it }
                onTimeout(wait) { null }
            }
            flushInputs()
            when (command) {
                null -> tick()
                Command.Wake -> Unit
                is Command.Event -> apply(command.event)
                is Command.Run -> try {
                    guarded { command.block(access) }
                } finally {
                    command.done.complete(Unit)
                }
            }
        }
    }

    private fun tick() {
        val now = clock.nowMs()
        apply(Tick(now))
        if (now - lastDistanceRefreshMs >= DISTANCE_REFRESH_MS) {
            lastDistanceRefreshMs = now
            guidance.refreshDistance()
        }
    }

    /** 3.4. 마감을 바로 지나지 않게 깨어나되 1초보다 오래 자지 않는다 */
    private fun nextWaitMs(): Long {
        val deadline = engine.nextDeadlineMs(state) ?: return MAX_WAIT_MS
        val remaining = (deadline - clock.nowMs()).coerceAtLeast(0)
        return hooks.pacer.realDelayMs(remaining).coerceIn(0, MAX_WAIT_MS)
    }

    /** 순서는 9.4: 경로 → 차량 → 요약 */
    private fun flushInputs() {
        val now = clock.nowMs()
        val held = hooks.checkinHeld.value
        if (held != state.checkinHeld) apply(CheckinHold(now, held))
        val route = navi.route.value?.takeIf { it.isRouting }?.remainingSec
        if (route != lastRouteSec) {
            lastRouteSec = route
            apply(RouteChanged(now, route))
        }
        flushVehicle(now)
        flushSummary(now)
    }

    private fun flushVehicle(now: Long) {
        val input = VehicleInput(signals.gear.value, signals.speedKmh.value, signals.ignition.value)
        // 차량 서비스가 붙기 전의 빈 값을 첫 신호로 받으면 앱 시작 직후를 주행으로 오판한다(F1-11)
        val signalPending = awaitingFirstVehicle && input.gear == Gear.UNKNOWN && input.speedKmh == null &&
            now - startedAtMs < FIRST_SIGNAL_GRACE_MS
        if (input == lastVehicle || signalPending) return
        lastVehicle = input
        apply(VehicleChanged(now, input.gear, input.speedKmh, input.ignition))
        if (input.ignition == Ignition.OFF) persistRequests.trySend(Unit)
        if (awaitingFirstVehicle) {
            awaitingFirstVehicle = false
            if (freshStart && _view.value.measuredSinceAppStart) sayFirstRunNotice()
        }
    }

    private fun sayFirstRunNotice() {
        inner.launch {
            if (guarded { hooks.isFirstRun() } == true) {
                voice.enqueue(Say(VoiceId.V30), VoiceVars())
            }
        }
    }

    private fun flushSummary(now: Long) {
        val snapshot = summary.snapshot.value
        if (snapshot == lastSnapshot) return
        // 아직 한 번도 받지 않은 빈 값을 "값 없음"으로 넣으면 저장해 둔 혈당이 끊김으로 처리된다
        // 재시작 직후의 "연결 없음"도 같다. 차량 밖에서 끊긴 채 재시작했으면 그동안의 래치가 근거 없이 풀리면 안 된다
        val neverFetched = lastSnapshot == null && snapshot.input == null && snapshot.receivedAtMs == null && !snapshot.linkLost
        lastSnapshot = snapshot
        if (!neverFetched) apply(ConditionReceived(now, snapshot.input, snapshot.unpaired && !snapshot.linkLost))
    }

    /** 기록 줄에 실린 조작(E34 등)을 돌려준다. 받은 조작인지 호출부가 알아야 할 때 쓴다 */
    private fun apply(event: EngineEvent): LogEvent? {
        val decision = try {
            engine.reduce(state, event)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            // 판단 코드의 버그 하나로 루프가 죽으면 이후 안내가 조용히 사라진다. 그 이벤트만 버린다
            hooks.onError(e)
            return null
        }
        val before = _view.value.state
        state = decision.state
        decision.log?.let { entry -> runSafely { log.append(entry) } }
        // 효과를 내기 전에 뷰부터 갱신한다. 장소 찾기가 즉시 시작되는 디스패처에서 지난 뷰를 읽으면 안 된다
        publishView()
        runEffects(decision, event.atMs)
        val after = _view.value
        if (!after.driving) {
            voice.dropDrivingOnly()
            noPlaceVoiceSaid = false
        }
        if (after.state == DriverState.NORMAL || after.state == DriverState.RECOVER) guidance.clearPlace()
        // 상태가 그대로인 조작(E34로 RECOVER 유지 등)도 바로 저장한다. 그 사이 죽으면 기록은 "풀었다"인데 래치가 되살아난다
        val logged = decision.log?.event
        if (after.state != before || logged != null) persistRequests.trySend(Unit)
        updateBusy()
        hooks.pacer.onNextDeadline(engine.nextDeadlineMs(state))
        return logged
    }

    private fun publishView() {
        _view.value = engine.view(state, clock.nowMs())
    }

    private fun runEffects(decision: Decision, atMs: Long) {
        for (effect in decision.effects) {
            when (effect) {
                is Say -> if (needsPlace(effect.voice)) launchGlucoseVoice(effect, atMs) else voice.enqueue(effect, effect.vars, atMs)
                is AskCheckin -> voice.enqueue(effect, effect.vars, atMs)
                is AskStop -> if (needsPlace(effect.voice)) launchPrompt(effect, atMs) else voice.enqueue(effect, effect.vars, atMs)
                is StartGuidance -> launchGuidance(effect.auto)
                CancelVoice -> voice.cancelCurrent()
                CancelPendingPrompts -> {
                    placeJob?.cancel()
                    guidance.cancelRetry()
                    voice.cancelPendingPrompts()
                }
                OfferVentilation -> runSafely { ventilation.offer() }
                is Trip -> tripEvents.trySend(effect.event to atMs)
            }
        }
    }

    private fun needsPlace(voice: VoiceId): Boolean = VoiceCatalog.resolveNoPlace(voice) != voice

    /**
     * V09, V10. 안전 음성이라 측정을 기다리지 않고, 못 찾으면 N 변형을 곧바로 말한다.
     * 시속 70km 이상이면 N 변형 대신 나들목 변형(V09H, V10H)이다. 말하는 순간의 차속으로 VoiceQueue가 고른다.
     * V10은 경유지가 실제로 들어간 장소일 때만 "안내 중"이라고 말한다. 아니면 V10U로 말하고 경유지를 다시 시도한다.
     */
    private fun launchGlucoseVoice(effect: Say, atMs: Long) {
        placeJob?.cancel()
        placeJob = launchTracked {
            val repeat = effect.voice != VoiceId.V09
            val place = withTimeoutOrNull(GLUCOSE_RESOLVE_LIMIT_MS) {
                if (repeat) guidance.reconfirm() else guidance.resolveForGlucose()
            }
            val guided = guidance.isGuided(place)
            val spoken = if (repeat && place != null && !guided) effect.copy(voice = VoiceId.V10U) else effect
            voice.enqueue(spoken, withPlace(effect.vars, place), atMs)
            if (repeat && !guided) launchGuidance(auto = true)
        }
    }

    /** 6.1 장소 해석. 못 찾으면 N 변형을 말하고, 찾으면 그때 원래 문구로 묻는다 */
    private fun launchPrompt(effect: AskStop, atMs: Long) {
        placeJob?.cancel()
        guidance.cancelRetry()
        placeJob = launchTracked {
            val place = guidance.resolve()
            if (!_view.value.driving) return@launchTracked
            if (place != null || !noPlaceVoiceSaid) voice.enqueue(effect, withPlace(effect.vars, place), atMs)
            if (place == null) noPlaceVoiceSaid = true
            if (place == null) guidance.startRetry { found -> commands.trySend(Command.Run({ askWithFound(effect, found) }, CompletableDeferred())) }
        }
    }

    private fun askWithFound(effect: AskStop, found: PlaceCandidate) {
        // 그사이 다음 프롬프트로 넘어갔거나 응답이 왔거나 차를 세웠으면 이 질문은 이미 끝났다
        val current = _view.value
        if (state.activePromptId != effect.promptId || !current.driving) return
        val vars = effect.vars.copy(driveSec = effect.vars.driveSec?.let { current.continuousDriveSec })
        voice.enqueue(effect, withPlace(vars, found), clock.nowMs())
    }

    private fun launchGuidance(auto: Boolean) {
        // 내비 응답은 30초까지 걸린다(실측). 그사이 V10이 와도 겹쳐 보내지 않는다
        if (auto && autoGuideRunning) return
        if (auto) autoGuideRunning = true
        val before = placeJob
        launchTracked {
            try {
                before?.join()
                val outcome = guidance.startGuidance(auto)
                if (auto) {
                    // 장소를 못 찾았어도 자동 안내는 포기하지 않는다. 위치가 돌아오면 다시 시도한다(P12)
                    if (guidance.current.value == null) {
                        guidance.startRetry { inner.launch { runGuarded { guidance.startGuidance(auto = true) } } }
                    }
                    return@launchTracked
                }
                if (outcome !in GUIDED) sayGuidanceFailed()
            } finally {
                if (auto) autoGuideRunning = false
            }
        }
    }

    /** F3-9. 음성 안내만 남긴다. P18에 따라 혈당 자동 안내에서는 말하지 않는다. V09가 이미 장소를 말했다 */
    private fun sayGuidanceFailed() {
        val place = guidance.current.value ?: return
        val vars = withPlace(VoiceVars(), place)
        voice.enqueue(Say(VoiceId.V28, vars), vars)
    }

    /** 경로로 잰 거리가 있으면 그 값이고, 직선 거리면 "약"을 붙인다. 실제 경로는 최대 2배까지 길 수 있다 */
    private fun withPlace(vars: VoiceVars, place: PlaceCandidate?): VoiceVars =
        if (place == null) vars else vars.copy(distanceM = place.distanceM, distanceApprox = place.routeDistanceM == null, placeName = place.place.name)

    private fun launchTracked(block: suspend () -> Unit): Job {
        trackedJobs.incrementAndGet()
        updateBusy()
        val launched = inner.launch { runGuarded(block) }
        // finally 대신 쓴다. 시작 전에 취소된 코루틴은 본문이 돌지 않아 finally로는 줄어들지 않는다
        launched.invokeOnCompletion {
            trackedJobs.decrementAndGet()
            updateBusy()
        }
        return launched
    }

    private fun updateBusy() {
        val busy = voice.busy.value || trackedJobs.get() > 0
        _speechBusy.value = busy
        hooks.pacer.onSpeechBusy(busy)
    }

    private suspend fun runPersistWorker() {
        for (request in persistRequests) saveNow()
    }

    private suspend fun runTripWorker() {
        for ((event, atMs) in tripEvents) guarded { trip.record(event, atMs) }
    }

    private suspend fun saveNow() {
        val snapshot = summary.snapshot.value
        val km = guarded { withTimeoutOrNull(ODOMETER_TIMEOUT_MS) { odometer.totalKm() } }
        val record = EngineStateRecord(
            engineStateJson = EngineStateCodec.encode(state),
            bootCount = hooks.bootCount(),
            odometerKm = km,
            lastSummaryJson = snapshot.input?.let { LAST_SUMMARY_JSON.encodeToString(LastSummary.serializer(), LastSummary.of(it, snapshot.phoneName)) },
            lastSummaryReceivedAtMs = snapshot.receivedAtMs,
            tripLogCursor = tripLogCursor,
        )
        guarded { store.save(record) }
    }

    /** 저장, 기록 같은 부수 작업의 실패가 판단을 멈추면 안 된다 */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        hooks.onError(e)
        null
    }

    private suspend fun runGuarded(block: suspend () -> Unit) {
        guarded(block)
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            hooks.onError(e)
        }
    }

    /** 9장 last_summary. 판단은 엔진 저장본으로 복원하므로 이 값은 기록과 표시용이다 */
    @Serializable
    private data class LastSummary(
        val sleepMinutes: Int?,
        val sleepExpiresAtMs: Long?,
        val glucoseMmol: Double?,
        val glucoseTrend: Trend?,
        val glucoseMeasuredAtMs: Long?,
        val glucoseExpiresAtMs: Long?,
        val glucoseSimulated: Boolean,
        val phoneName: String?,
    ) {
        companion object {
            fun of(input: ConditionInput, phoneName: String?) = LastSummary(
                input.sleepMinutes, input.sleepExpiresAtMs, input.glucoseMmol, input.glucoseTrend,
                input.glucoseMeasuredAtMs, input.glucoseExpiresAtMs, input.glucoseSimulated, phoneName,
            )
        }
    }

    private companion object {
        const val MAX_WAIT_MS = 1000L                   // 3.4
        const val ODOMETER_TIMEOUT_MS = 2000L
        const val FIRST_SIGNAL_GRACE_MS = 1500L
        const val GLUCOSE_RESOLVE_LIMIT_MS = 1000L
        const val DISTANCE_REFRESH_MS = 60_000L         // 8.3 "분 단위 갱신"
        val GUIDED = setOf(GuideOutcome.WAYPOINT_ADDED, GuideOutcome.ROUTE_STARTED, GuideOutcome.ALREADY_WAYPOINT)
        val LAST_SUMMARY_JSON = Json { encodeDefaults = true }
    }
}
