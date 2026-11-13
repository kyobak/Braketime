package com.conditiondrive.companion

import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.pairing.PairingLink
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.pairing.RemoteDeletion
import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.simulator.SimScenarios
import com.conditiondrive.companion.simulator.SimTimeline
import com.conditiondrive.companion.simulator.SimulatorState
import com.conditiondrive.companion.sleep.HealthAvailability
import com.conditiondrive.companion.sleep.HealthGateway
import com.conditiondrive.companion.sleep.HealthReadException
import com.conditiondrive.companion.sleep.HeartSample
import com.conditiondrive.companion.sleep.SleepCalculator
import com.conditiondrive.companion.sleep.WorkScheduler
import com.conditiondrive.companion.storage.CompanionStore
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.upload.ServerAddress
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.companion.upload.SummaryInput
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.companion.upload.UploadStatus
import com.conditiondrive.companion.upload.UploadStep
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HealthUi(
    val availability: HealthAvailability = HealthAvailability.UNAVAILABLE,
    val sleepPermission: Boolean = false,
    val heart: HeartSample? = null,
)

/** 화면에 잠깐 알리는 결과. 서버 문장을 담지 않는다 */
enum class Notice { UNPAIRED_DONE, UNPAIRED_PENDING, WITHDRAWN_DONE, WITHDRAWN_PENDING, SUMMARY_DELETED, SUMMARY_DELETE_FAILED, TOKEN_REVOKED, CONSENT_LOST }

data class PairUi(
    val prefilledCode: String? = null,
    val busy: Boolean = false,
    val result: ClaimResult? = null,
)

data class AppUi(
    val loaded: Boolean = false,
    val stored: StoredState = StoredState(),
    val upload: UploadStatus = UploadStatus.Idle,
    val health: HealthUi = HealthUi(),
    val simulator: SimulatorState = SimulatorState(),
    val pair: PairUi = PairUi(),
    val notice: Notice? = null,
    val defaultServerUrl: String = "",
) {
    val consented: Boolean get() = stored.consentVersion != null
    val paired: Boolean get() = stored.tokenBlob != null
    val serverUrl: String get() = stored.serverUrl ?: defaultServerUrl
}

/** 서비스를 띄우는 일만 플랫폼에 맡겨 나머지를 JVM에서 시험한다 */
fun interface ServiceStarter { fun startSimulatorService() }

/** 화면, 서비스, 워커가 모두 이 하나로 들어온다. 같은 슬롯과 같은 시뮬레이터를 봐야 전송이 서로 덮지 않는다 */
class CompanionController(
    private val store: CompanionStore,
    private val pairing: PairingService,
    private val uploads: UploadCoordinator,
    val simulator: GlucoseSimulator,
    private val health: HealthGateway,
    private val work: WorkScheduler,
    private val services: ServiceStarter,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val defaultServerUrl: String = "",
) {
    private val healthUi = MutableStateFlow(HealthUi())
    private val pairUi = MutableStateFlow(PairUi())
    private val notice = MutableStateFlow<Notice?>(null)

    val ui: StateFlow<AppUi> = combine(store.state, uploads.status, healthUi, simulator.state, combine(pairUi, notice, ::Pair)) {
            stored, upload, healthState, sim, (pair, currentNotice) ->
        AppUi(loaded = true, stored = stored, upload = upload, health = healthState, simulator = sim, pair = pair, notice = currentNotice, defaultServerUrl = defaultServerUrl)
    }.stateIn(scope, SharingStarted.Eagerly, AppUi())

    fun start() {
        scope.launch { uploads.run() }
        scope.launch {
            val state = store.read()
            if (state.tokenBlob != null) work.scheduleSleepSync()
            if (state.pendingRevokes.isNotEmpty()) drainRevokes()
        }
    }

    /** 앱 실행과 복귀(11.3). 미연결이면 Health Connect만 보고 네트워크는 쓰지 않는다 */
    suspend fun onForeground() {
        refreshHealth(foreground = true)
        val state = store.read()
        if (state.tokenBlob == null) return
        if (pairing.isRevoked()) {
            onTokenRevoked()
            return
        }
        offerCurrent()
    }

    // ---- 동의와 연결

    fun acceptConsent() = launchUnit { pairing.recordConsent() }

    fun onDeepLink(text: String) {
        val code = PairingLink.parse(text) ?: return
        pairUi.update { it.copy(prefilledCode = code, result = null) }
    }

    fun clearPairResult() = pairUi.update { it.copy(result = null) }

    fun cancelReplace() = pairUi.update { PairUi() }

    /** 스캔이나 딥링크는 코드만 채운다. 악성 링크가 눌러 보지도 않은 차량으로 요약을 보내게 하면 안 된다 */
    fun claim(code: String) {
        if (pairUi.value.busy) return
        pairUi.update { it.copy(busy = true, result = null) }
        scope.launch {
            val result = pairing.claim(code)
            pairUi.update {
                PairUi(prefilledCode = if (result == ClaimResult.PAIRED) null else it.prefilledCode, busy = false, result = result)
            }
            if (result == ClaimResult.PAIRED) {
                work.scheduleSleepSync()
                if (store.read().pendingRevokes.isNotEmpty()) drainRevokes()
                refreshHealth(foreground = true)
                offerCurrent()
            }
        }
    }

    fun setServerUrl(text: String): Boolean {
        val normalized = ServerAddress.normalize(text) ?: return false
        launchUnit { store.update { it.copy(serverUrl = normalized) } }
        return true
    }

    fun unpair() = launchUnit {
        stopLocalSending()
        uploads.clearAndWait()
        report(pairing.unpair(), Notice.UNPAIRED_DONE, Notice.UNPAIRED_PENDING)
    }

    fun withdrawConsent() = launchUnit {
        stopLocalSending()
        uploads.clearAndWait()
        report(pairing.withdraw(), Notice.WITHDRAWN_DONE, Notice.WITHDRAWN_PENDING)
    }

    /** 다음 전송이 바로 다시 쌓지 않도록 먼저 멈춘다 */
    fun deleteServerSummary() = launchUnit {
        pauseSending()
        uploads.clearAndWait()
        val result = pairing.deleteServerSummary()
        notice.value = if (result == RemoteDeletion.DONE) Notice.SUMMARY_DELETED else Notice.SUMMARY_DELETE_FAILED
    }

    fun dismissNotice() { notice.value = null }

    private suspend fun report(result: RemoteDeletion, done: Notice, pending: Notice) {
        healthUi.update { it.copy(heart = null) }
        if (result == RemoteDeletion.PENDING) work.scheduleRevokeRetry()
        notice.value = if (result == RemoteDeletion.DONE) done else pending
    }

    private fun stopLocalSending() {
        simulator.stop()
        uploads.clear()
        work.cancelSleepSync()
    }

    private suspend fun onTokenRevoked() {
        stopLocalSending()
        healthUi.update { it.copy(heart = null) }
        store.update { it.copy(tokenBlob = null, deviceId = null, sleep = null, lastUploadAtMs = null, vehicleLastSeenAtMs = null) }
        notice.value = Notice.TOKEN_REVOKED
    }

    /** UploadCoordinator 콜백. 13장 401: 토큰과 요약을 지우고 미연결로 */
    suspend fun onUnauthorized() = onTokenRevoked()

    /** 서버에 동의 기록이 없으면 다시 묻는다 */
    suspend fun onConsentRequired() {
        stopLocalSending()
        store.update { it.copy(consentVersion = null, consentGrantedAtMs = null) }
        notice.value = Notice.CONSENT_LOST
    }

    // ---- 전송 중단

    fun pauseUploads() = launchUnit { pauseSending() }

    private suspend fun pauseSending() {
        simulator.stop()
        store.update { it.copy(uploadPaused = true) }
        uploads.clear()
    }

    fun resumeUploads() = launchUnit {
        store.update { it.copy(uploadPaused = false) }
        offerCurrent()
    }

    // ---- 시뮬레이터

    fun startSimulator() = launchUnit {
        store.update { it.copy(uploadPaused = false) }  // 켠 사람이 보내려는 것이다
        simulator.start()
        services.startSimulatorService()
        offerCurrent()
    }

    /** 끈 뒤 혈당 null을 한 번 보낸다. 값이 없어졌음을 15분 뒤가 아니라 바로 차량이 안다(12.7) */
    fun stopSimulator() {
        simulator.stop()
        launchUnit { offerCurrent() }
    }

    fun playScenario(id: String) = playTimeline(SimScenarios.load(id))

    fun playTimeline(timeline: SimTimeline) = launchUnit {
        store.update { it.copy(uploadPaused = false) }
        simulator.play(timeline, correctedNow(store.read()))
        services.startSimulatorService()
        offerCurrent()
    }

    fun skipScenarioPoint() = launchUnit {
        simulator.skipToNext(correctedNow(store.read()))
        offerCurrent()
    }

    fun nudgeMmol(delta: Int) = afterSim { simulator.nudgeMmol(delta) }
    fun nudgeMgdl(delta: Int) = afterSim { simulator.nudgeMgdl(delta) }
    fun setMmol(hundredths: Int) = afterSim { simulator.setMmolHundredths(hundredths) }
    fun setTrend(trend: GlucoseTrendDto?) = afterSim { simulator.setTrend(trend) }
    fun setSensorCut(cut: Boolean) = afterSim { simulator.setSensorCut(cut) }

    fun setSleepOverride(minutes: Int?) = launchUnit {
        simulator.setSleepOverride(minutes, correctedNow(store.read()))
        if (simulator.state.value.running) offerCurrent()
    }

    // 꺼 둔 채 값을 맞춰 보는 동안에는 보내지 않는다. 켜야 나간다
    private fun afterSim(change: () -> Unit) {
        change()
        if (simulator.state.value.running) launchUnit { offerCurrent() }
    }

    /** 서비스가 부른다. 시뮬레이터가 꺼지면 돌아온다 */
    suspend fun runSimulatorTicks() {
        while (simulator.state.value.running) {
            offerCurrent()
            // 끄면 다음 주기를 기다리지 않고 바로 빠져나와 서비스 알림이 남지 않게 한다
            withTimeoutOrNull(simulator.nextTickDelayMs()) { simulator.state.first { !it.running } }
        }
    }

    // ---- 건강 데이터

    /** 권한 화면에서 돌아왔을 때, 사용자가 동기화를 눌렀을 때 */
    fun syncSleepNow() = launchUnit {
        val changed = refreshHealth(foreground = true)
        if (changed) offerCurrent()
    }

    /** 다 끝났으면 true. WorkManager가 재시도할지 정한다 */
    suspend fun backgroundSleepSync(): Boolean {
        val state = store.read()
        if (state.tokenBlob == null || state.uploadPaused) return true
        refreshHealth(foreground = false)
        offerCurrent()
        return uploads.attempt() !is UploadStep.Wait
    }

    suspend fun drainRevokes(): Boolean {
        val done = pairing.drainRevokes()
        if (!done) work.scheduleRevokeRetry()
        return done
    }

    /** 수면 값이 달라졌으면 true */
    private suspend fun refreshHealth(foreground: Boolean): Boolean {
        val availability = health.availability()
        val before = store.read()
        val now = correctedNow(before)
        val windowStart = now - SleepCalculator.WINDOW_MS
        var heart: HeartSample? = null
        var permitted = false
        var sleep = before.sleep
        if (availability == HealthAvailability.AVAILABLE) {
            try {
                permitted = health.hasSleepPermission()
                when {
                    permitted -> {
                        sleep = SleepCalculator.valueFrom(health.readSleep(windowStart, now), now)
                        if (foreground) heart = health.latestHeartRate(windowStart, now)
                    }
                    // 화면에서 확인한 철회다. 사용자가 거둔 값을 더 보내지 않는다.
                    // 백그라운드에서는 권한이 없다는 답을 믿을 수 없어 값을 둔다
                    foreground -> sleep = null
                }
            } catch (_: HealthReadException) {
                // 일시적 실패. 값은 자기 만료까지 쓴다(13장)
            }
        } else if (foreground) {
            sleep = null
        }
        if (foreground) healthUi.value = HealthUi(availability, permitted, heart)
        val changed = sleep?.minutes != before.sleep?.minutes
        if (sleep != before.sleep) store.update { it.copy(sleep = sleep) }
        return changed
    }

    // ---- 스냅샷

    /** 어느 경로든 수면과 혈당을 함께 담는다. 수면만 보내면 켜져 있는 시뮬레이터 값을 null로 덮는다(12.7 전체 교체) */
    private suspend fun offerCurrent() {
        val state = store.read()
        if (state.tokenBlob == null || state.consentVersion == null || state.uploadPaused) return
        val now = correctedNow(state)
        val glucose = simulator.readingAt(now)
        uploads.offer(SummaryInput(effectiveSleep(state, simulator.state.value), glucose))
    }

    private fun correctedNow(state: StoredState): Long = clock.nowMs() + state.clockOffsetMs

    private fun launchUnit(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    companion object {
        /** 시뮬레이터가 켜진 동안의 수면 시연 값이 실제 값보다 앞선다. 화면에 시뮬레이션으로 표시한다 */
        fun effectiveSleep(state: StoredState, sim: SimulatorState): SleepValue? {
            val override = sim.sleepOverrideMinutes
            return if (sim.running && override != null) SleepValue(override, sim.sleepOverrideAtMs) else state.sleep
        }
    }
}
