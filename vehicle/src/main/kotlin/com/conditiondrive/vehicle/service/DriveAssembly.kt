package com.conditiondrive.vehicle.service

import android.content.Context
import android.net.ConnectivityManager
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.vehicle.demo.DemoController
import com.conditiondrive.vehicle.demo.DemoEnvironment
import com.conditiondrive.vehicle.demo.DemoStartResult
import com.conditiondrive.vehicle.demo.DemoStatus
import com.conditiondrive.vehicle.platform.PlatformPorts
import com.conditiondrive.vehicle.platform.PlatformSupport
import com.conditiondrive.vehicle.platform.SpeechEngineChoice
import com.conditiondrive.vehicle.platform.SharedVehicleSignals
import com.conditiondrive.vehicle.platform.guarded
import com.conditiondrive.vehicle.platform.logDemoConsole
import com.conditiondrive.vehicle.platform.logDemoReport
import com.conditiondrive.vehicle.platform.logFailure
import com.conditiondrive.vehicle.platform.logInfo
import com.conditiondrive.vehicle.poi.BundledPlaceProvider
import com.conditiondrive.vehicle.runtime.EngineRuntime
import com.conditiondrive.vehicle.runtime.RuntimeGraph
import com.conditiondrive.vehicle.runtime.RuntimeHooks
import com.conditiondrive.vehicle.runtime.RuntimePorts
import com.conditiondrive.vehicle.storage.DataStoreStateStore
import com.conditiondrive.vehicle.storage.SummaryLinkStore
import com.conditiondrive.vehicle.storage.TransitionLogFile
import com.conditiondrive.vehicle.storage.TripLogDb
import com.conditiondrive.vehicle.summary.OkHttpSummaryTransport
import com.conditiondrive.vehicle.summary.RemoteSummarySource
import com.conditiondrive.vehicle.ui.DataNotice
import com.conditiondrive.vehicle.ui.HoldRelease
import com.conditiondrive.vehicle.ui.DemoFacts
import com.conditiondrive.vehicle.ui.EngineKind
import com.conditiondrive.vehicle.ui.PairingFacts
import com.conditiondrive.vehicle.ui.SettingsActions
import com.conditiondrive.vehicle.ui.SettingsFacts
import com.conditiondrive.vehicle.ui.SupportFacts
import com.conditiondrive.vehicle.ui.UiGraph
import com.conditiondrive.vehicle.ui.UiPrefs
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import com.conditiondrive.vehicle.ui.vehicleUiSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File

/**
 * 실사용 조립의 한곳. 포트 구현만 고르고 판단과 음성은 RuntimeGraph 몫이다.
 * 시연은 실사용 런타임을 내린 뒤 띄운다. 둘이 함께 돌면 같은 스피커로 두 엔진이 말한다.
 */
internal class DriveAssembly(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notifier: DriveNotifier,
) {
    private val ports = PlatformPorts.get(context)
    private val clock = Clock { System.currentTimeMillis() }
    private val http = OkHttpClient()
    private val link = SummaryLinkStore(context)
    private val pairingClient = PairingClient(http, APP_VERSION)
    // EngineRuntime이 stop에서 신호까지 내리면 게이트와 시연 진입 판정이 눈을 감는다
    private val signals = SharedVehicleSignals(ports.signals)
    private val summary = RemoteSummarySource(OkHttpSummaryTransport(http), link, ports.signals.ignition, clock, scope)
    private val liveLogFile = TransitionLogFile(File(context.filesDir, "logs/${TransitionLogFile.LIVE_FILE}"), File(context.cacheDir, "exports"))
    private val demoLogFile = TransitionLogFile(File(context.filesDir, "logs/${TransitionLogFile.DEMO_FILE}"), File(context.cacheDir, "exports"))
    private val liveLog = adviceLog(liveLogFile)
    private val trips = TripLogDb.open(context, clock::nowMs)
    private val demoTrips = TripLogDb.open(context, clock::nowMs, TripLogDb.DEMO_DB)
    private val places = listOf(BundledPlaceProvider.fromAssets(context))
    private val prefs = UiPrefs(context)
    private val onboardingPending = MutableStateFlow(!prefs.onboardingDone)
    private val onboardingWatch = prefs.watchOnboarding { done -> onboardingPending.value = !done }
    private val deletion = ServerDeletion(
        remote = { kind, base, token ->
            when (kind) {
                DeletionKind.UNPAIR -> pairingClient.unpair(base, token)
                DeletionKind.ACCOUNT -> pairingClient.deleteAccount(base, token)
            }
        },
        store = object : DeletionStore {
            override var pending: DeletionKind?
                get() = link.pendingDeletion?.let { name -> DeletionKind.entries.firstOrNull { it.name == name } }
                set(value) { link.pendingDeletion = value?.name }
            override val baseUrl: String get() = link.baseUrl
            override val token: String? get() = link.vehicleToken
            override fun onAccountDeleted() = link.clearAll()
        },
        scope = scope,
    )

    private val demo = DemoController(
        DemoEnvironment(
            signals = signals,
            speaker = ports.speaker,
            store = DataStoreStateStore.get(context, DataStoreStateStore.DEMO_FILE),
            log = adviceLog(demoLogFile),
            trip = demoTrips,
            places = places,
            listener = ports.listener,
            navi = ports.navi,
            ventilation = ports.ventilation,
            realGate = ports.gate,
            tripAt = demoTrips::viewedAt,
        ),
        scope,
        reportSink = { id, report -> writeReport(id, report) },
        onError = { logFailure("demo", it) },
    )

    private var live: RuntimeGraph? = null
    private var installed: EngineRuntime? = null   // 화면이 보고 있는 런타임. S5 조작은 실사용과 시연 중 이쪽으로 간다
    private var demoWatch: Job? = null
    private var demoSwitching = false   // 시연 재시작 중에는 런타임이 잠시 비어도 실사용으로 돌아가지 않는다
    private var lastDemo: Pair<String, Boolean>? = null
    private var pairingJob: Job? = null
    private val _pairing = MutableStateFlow<PairingFacts?>(null)
    private val _pairingFailed = MutableStateFlow(false)
    private val _dataNotice = MutableStateFlow<DataNotice?>(null)
    private val _demoSendOffline = MutableStateFlow(false)

    private val settings: StateFlow<SettingsFacts> = combine(
        combine(_pairing, _pairingFailed, _dataNotice) { p, f, n -> Triple(p, f, n) },
        combine(demo.status, demo.pendingPlace, _demoSendOffline) { status, place, offline ->
            DemoFacts(
                running = status is DemoStatus.Running || status is DemoStatus.Finished,
                pendingPlace = place?.name,
                sendOffline = offline,
            )
        },
        ports.support,
        link.serverUrl,
        DriveServiceState.gap,
    ) { (pairing, failed, notice), demoFacts, support, url, gap ->
        SettingsFacts(
            pairing = pairing,
            pairingFailed = failed,
            dataNotice = notice,
            demo = demoFacts,
            support = support.toFacts(),
            serverUrl = url,
            measurementGap = gap,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SettingsFacts())

    private fun adviceLog(file: TransitionLogFile) = StopAdviceLog(
        file,
        onStopAdvice = { if (!notifier.appOnScreen()) notifier.showStopAdvice() },
        onLeftStop = { notifier.clearStopAdvice() },
        onHoldAdvice = { if (!notifier.appOnScreen()) notifier.showHoldAdvice() },
        onLeftHold = { notifier.clearHoldAdvice() },
    )

    fun start() {
        DriveServiceState.load(context)
        ports.start()
        startLive()
        // C-54. 전이 로그에는 혈당 사유 줄이 있다. 트립 DB처럼 30일이 지나면 지운다
        scope.launch { liveLogFile.prune(clock.nowMs() - LOG_RETENTION_MS) }
        deletion.resume()
        scope.launch {
            deletion.state.collect { state ->
                when (state) {
                    DeletionState.Idle -> Unit
                    is DeletionState.Pending -> _dataNotice.value = DataNotice.SERVER_PENDING
                    is DeletionState.Done -> _dataNotice.value = when (state.kind) {
                        DeletionKind.UNPAIR -> DataNotice.UNPAIRED
                        DeletionKind.ACCOUNT -> DataNotice.SERVER_DELETED
                    }
                }
            }
        }
        // 시연이 끝나 런타임이 사라지면 실사용으로 돌아온다
        demoWatch = scope.launch {
            demo.runtime.collectLatest { running ->
                if (running != null) install(running) else if (live == null && !demoSwitching) startLive()
            }
        }
        // 끝난 시연을 붙들고 있으면 실제 운전을 재지 못한다. 마지막 화면만 잠시 보여 주고 실사용으로 돌아간다(15.2 종료)
        // 실제 내비로는 보내지 않는다. 지도가 앱을 덮고 경로가 실사용에 남는다(15.3 규칙 5)
        scope.launch {
            demo.status.collectLatest { status ->
                if (status is DemoStatus.Finished) {
                    delay(DEMO_END_HOLD_MS)
                    demo.stop()
                }
            }
        }
        scope.launch {
            demo.consoleCue.collect { cue -> cue?.let { logDemoConsole(it.line()) } }
        }
        // 내장 시연 중 실제 차가 움직이면 시연을 버린다. 스크립트 입력과 실제 주행이 섞이면 안 된다(R5)
        scope.launch {
            signals.gear.collect { gear ->
                if (demo.ui.value?.gate != null && gear != Gear.PARK && gear != Gear.UNKNOWN) {
                    logInfo("demo aborted: vehicle left park")
                    demo.stop()
                }
            }
        }
    }

    private fun online(): Boolean = guarded("network state", false) {
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    }

    private fun startLive() {
        val graph = RuntimeGraph(
            RuntimePorts(
                signals = signals,
                summary = summary,
                navi = ports.navi,
                store = DataStoreStateStore.get(context),
                log = liveLog,
                trip = trips,
                odometer = ports.odometer,
                speaker = ports.speaker,
                listener = ports.listener,
                occupancy = ports.occupancy,
                location = ports.location,
                ventilation = ports.ventilation,
                providers = places,
            ),
            clock,
            scope,
            hooks = RuntimeHooks(
                bootCount = { DataStoreStateStore.currentBootCount(context) },
                isFirstRun = { !prefs.onboardingDone },
                checkinHeld = onboardingPending,
                onError = { logFailure("runtime", it) },
            ),
        )
        live = graph
        graph.start()
        // STOP 계열을 말하지 못했으면 화면이 있어도 헤드업을 낸다(13장)
        scope.launch {
            graph.voice.undelivered.collect { voice ->
                if (voice in StopAdviceLog.STOP_ADVICE) notifier.showStopAdvice(highway = voice in StopAdviceLog.HIGHWAY_STOP)
                if (voice == VoiceId.V17) notifier.showHoldAdvice()
            }
        }
        install(graph.runtime)
    }

    private fun install(runtime: EngineRuntime) {
        installed = runtime
        val shown = demo.ui.value
        UiGraph.install(
            vehicleUiSource(
                runtime,
                shown?.gate ?: ports.gate,
                shown?.navi ?: ports.navi,
                shown?.occupancy ?: ports.occupancy,
                shown?.summary ?: summary,
                shown?.trips ?: trips,
                shown?.clock ?: clock,
                settings,
                actions,
                shown?.caption ?: MutableStateFlow(null),
                phoneLink = summary,
            ),
        )
    }

    suspend fun stop() {
        demoWatch?.cancel()
        pairingJob?.cancel()
        prefs.stopWatching(onboardingWatch)
        demo.stop()
        live?.stop()
        live = null
        installed = null
        ports.stopForIgnitionOff()
        UiGraph.install(UiGraph.unconnected())
    }

    private fun writeReport(id: String, report: String) {
        try {
            val dir = File(context.filesDir, "reports").apply { mkdirs() }
            File(dir, "$id.txt").writeText(report)
            logDemoReport(id, report)
        } catch (t: Exception) {
            logFailure("demo report", t)
        }
    }

    private val actions = object : SettingsActions {
        override fun requestPairingCode() {
            pairingJob?.cancel()
            _pairingFailed.value = false
            pairingJob = scope.launch { runPairing() }
        }

        /** 차 안의 값은 바로 지운다(E33). 토큰은 서버가 받은 뒤에 정리된다 */
        override fun unpair() {
            pairingJob?.cancel()
            _pairing.value = null
            link.setPhonePaired(false)
            scope.launch { liveLogFile.removeGlucoseEntries() }
            deletion.request(DeletionKind.UNPAIR)
        }

        /**
         * U-30. 화면이 보는 런타임(실사용 또는 시연)에 E34를 넣는다. 기록은 엔진이 전이 로그에 남긴다.
         * 엔진이 받았는지까지 돌려준다. 루프가 명령을 처리했다는 것만으로 "풀었어요"라고 하면 기록과 화면이 어긋난다
         */
        override suspend fun releaseGlucoseHold(): HoldRelease {
            val runtime = installed
            if (runtime == null) {
                logInfo("glucose hold release from S5: no runtime")
                return HoldRelease.UNAVAILABLE
            }
            // 시연이 막 끝나 멈춘 런타임이면 루프가 명령을 받지 않는다. 기다리다 묶이지 않게 끊는다
            // 루프에 명령을 넣고 답을 기다릴 뿐이라 부른 쪽 스레드에서 기다려도 된다
            val accepted = withTimeoutOrNull(HOLD_RELEASE_TIMEOUT_MS) { runtime.releaseGlucoseHold() }
            val result = when (accepted) {
                null -> HoldRelease.UNAVAILABLE
                true -> HoldRelease.RELEASED
                false -> HoldRelease.REFUSED
            }
            logInfo("glucose hold release from S5: $result")
            return result
        }

        override fun clearTrips() {
            scope.launch {
                clearLocalRecords()
                _dataNotice.value = DataNotice.TRIPS_CLEARED
            }
        }

        override fun deleteServerData() {
            pairingJob?.cancel()
            _pairing.value = null
            link.setPhonePaired(false)
            scope.launch { clearLocalRecords() }
            deletion.request(DeletionKind.ACCOUNT)
        }

        override fun setServerUrl(url: String) = link.setServerUrl(url)

        override fun startDemo(scenarioId: String, realSignals: Boolean) {
            scope.launch {
                // 진입 조건을 먼저 본다. 시작이 거절됐는데 실사용만 내려가면 측정이 끊긴다
                val running = live
                live = null
                running?.stop()
                demoSwitching = true
                // 끝난 시연의 런타임을 내려야 재시작 버튼이 같은 스피커에 엔진을 둘 띄우지 않는다
                if (demo.status.value is DemoStatus.Finished) demo.stop()
                val result = demo.start(scenarioId, realSignals)
                demoSwitching = false
                logInfo("demo start: $result")
                if (result == DemoStartResult.STARTED) lastDemo = scenarioId to realSignals
                if (result != DemoStartResult.STARTED && result != DemoStartResult.ALREADY_RUNNING) startLive()
            }
        }

        override fun stopDemo() {
            scope.launch { demo.stop() }
        }

        override fun restartDemo() {
            val (id, real) = lastDemo ?: return
            scope.launch {
                demoSwitching = true
                demo.stop()
                val result = demo.start(id, real)
                demoSwitching = false
                logInfo("demo restart: $result")
                if (result != DemoStartResult.STARTED) startLive()
            }
        }

        /** 망이 없으면 내비가 경로 탐색 오류 창을 띄운다(실측). 보내지 않고 남겨 둔다 */
        override fun sendDemoPlace() {
            if (!online()) {
                _demoSendOffline.value = true
                return
            }
            _demoSendOffline.value = false
            scope.launch { demo.sendPendingPlace() }
        }

        override suspend fun tryVoice(onListening: () -> Unit): VoiceTrial =
            VoiceTrialRunner(ports.speaker, ports.listener).run(onListening)
    }

    private suspend fun clearLocalRecords() {
        trips.deleteAll()
        demoTrips.deleteAll()
        liveLogFile.clear()
        demoLogFile.clear()
    }

    /** 12.3~12.6. 토큰이 없을 때만 등록하고 코드 수명 동안 3초마다 연결을 확인한다 */
    private suspend fun runPairing() {
        // 해제를 못 전한 옛 폰이 남아 있으면 새 코드 없이 그 폰으로 다시 연결된다
        if (!deletion.drain()) {
            _pairingFailed.value = true
            return
        }
        val base = link.baseUrl
        val issued = pairingClient.issueCode(base, link.vehicleToken)
        if (issued == null) {
            _pairingFailed.value = true
            return
        }
        val token = issued.token
        val code = issued.code
        if (token != link.vehicleToken) link.setToken(token)
        _pairing.value = PairingFacts(code.code, code.qrPayload)
        repeat((code.ttlSec / PAIRING_POLL_SEC).toInt()) {
            delay(PAIRING_POLL_SEC * 1000)
            val status = pairingClient.status(base, token)
            if (status != null && PairingRule.consumed(code, status)) {
                link.setPhonePaired(true)
                _pairing.value = null
                return
            }
        }
        _pairing.value = null
    }

    private fun PlatformSupport.toFacts() = SupportFacts(
        vehicleName = null,     // CarInfo는 VIN, CRN만 준다. 식별값이라 표시하지 않는다(10.2)
        capabilities = capabilities,
        naviAvailable = naviAvailable,
        waypointSupported = waypointSupported,
        speaker = speaker.toKind(),
        listener = listener.toKind(),
        location = location,
        ventilationSupported = ventilationSupported,
        occupancySupported = occupancySupported,
    )

    private fun SpeechEngineChoice.toKind() = when (this) {
        SpeechEngineChoice.VEHICLE -> EngineKind.VEHICLE
        SpeechEngineChoice.DEVICE -> EngineKind.DEVICE
        SpeechEngineChoice.NONE -> EngineKind.NONE
    }

    private companion object {
        const val APP_VERSION = "0.1.0"
        const val PAIRING_POLL_SEC = 3L
        const val DEMO_END_HOLD_MS = 15_000L
        const val HOLD_RELEASE_TIMEOUT_MS = 5_000L
        const val LOG_RETENTION_MS = 30L * 24 * 60 * 60 * 1000     // C-54
    }
}
