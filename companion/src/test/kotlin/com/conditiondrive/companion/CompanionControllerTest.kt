package com.conditiondrive.companion

import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.pairing.ConsentPolicy
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.simulator.SimScenarios
import com.conditiondrive.companion.sleep.HealthAvailability
import com.conditiondrive.companion.sleep.HeartSample
import com.conditiondrive.companion.sleep.SleepSpan
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.core.contract.SummaryValidator
import com.conditiondrive.core.time.ManualClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionControllerTest {
    private val t0 = OffsetDateTime.parse("2026-11-20T08:12:00+09:00").toInstant().toEpochMilli()
    private val clock = ManualClock(t0)
    private val api = FakeApi()
    private val health = FakeHealth()
    private val work = FakeWork()
    private val simulator = GlucoseSimulator()
    private var serviceStarts = 0
    private val pairedState = StoredState(consentVersion = ConsentPolicy.VERSION, tokenBlob = testCipher.seal("cdp_token"))

    private fun controller(scope: TestScope, initial: StoredState = pairedState): Pair<CompanionController, MemoryStore> {
        val store = MemoryStore(initial)
        val vault = vaultOf(store)
        val pairing = PairingService(api, store, vault, clock, { "Galaxy" }, "0.1.0")
        lateinit var created: CompanionController
        val uploads = UploadCoordinator(
            api, store, vault, clock, { ZoneId.of("Asia/Seoul") },
            onUnauthorized = { created.onUnauthorized() },
            onConsentRequired = { created.onConsentRequired() },
        )
        created = CompanionController(
            store, pairing, uploads, simulator, health, work, { serviceStarts++ }, clock, scope.backgroundScope,
            defaultServerUrl = "http://10.0.2.2:8080",
        )
        created.start()
        return created to store
    }

    /** 12.1: 동의도 연결도 없는 사용자는 앱을 열어도 호출 0회 */
    @Test
    fun freshInstallMakesNoNetworkCalls() = runTest {
        val (controller, store) = controller(this, StoredState())
        runCurrent()
        controller.onForeground()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(emptyList(), api.calls)
        assertFalse(work.sleepScheduled)
        assertFalse(store.value.tokenBlob != null)
    }

    /** 딥링크를 받아도 사용자가 연결을 누르기 전에는 어디로도 나가지 않는다 */
    @Test
    fun deepLinkOnlyPrefillsCode() = runTest {
        val (controller, _) = controller(this, StoredState(consentVersion = ConsentPolicy.VERSION))
        controller.onDeepLink("conditiondrive://pair?v=1&code=K7M2QX9T&server=https://evil.example")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("K7M2QX9T", controller.ui.value.pair.prefilledCode)
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun foreignDeepLinkIsIgnored() = runTest {
        val (controller, _) = controller(this, StoredState(consentVersion = ConsentPolicy.VERSION))
        controller.onDeepLink("https://evil.example/pair?v=1&code=K7M2QX9T")
        runCurrent()
        assertNull(controller.ui.value.pair.prefilledCode)
    }

    @Test
    fun claimThenFirstUploadAndSleepWork() = runTest {
        health.spans = listOf(SleepSpan(t0 - 290 * 60_000, t0))
        val (controller, store) = controller(this, StoredState(consentVersion = ConsentPolicy.VERSION))
        controller.onDeepLink("conditiondrive://pair?v=1&code=K7M2QX9T")
        controller.claim("K7M2QX9T")
        runCurrent()
        assertEquals(ClaimResult.PAIRED, controller.ui.value.pair.result)
        assertNull(controller.ui.value.pair.prefilledCode)
        assertTrue(work.sleepScheduled)
        assertEquals(listOf("claim", "summary"), api.calls)
        assertEquals(290, api.summaries.single().sleepMinutesLastNight)
        assertNull(api.summaries.single().glucoseMmol)
        assertNotNull(store.value.tokenBlob)
    }

    /** F6-6: 요약만 간다. 세션과 심박은 어디에도 없다 */
    @Test
    fun rawDataNeverLeavesPhoneOrStorage() = runTest {
        health.spans = listOf(SleepSpan(t0 - 7 * 3_600_000, t0 - 5 * 3_600_000), SleepSpan(t0 - 3 * 3_600_000, t0))
        health.heart = HeartSample(71, t0)
        val (controller, store) = controller(this)
        controller.onForeground()
        runCurrent()
        val sent = api.summaries.single().toString()
        assertEquals(300, api.summaries.single().sleepMinutesLastNight)
        assertFalse("71" in sent)
        assertFalse("71" in store.value.toString().replace(store.value.tokenBlob!!, ""))
        assertEquals(71, controller.ui.value.health.heart?.bpm) // 화면 표시만
    }

    /** 사용자가 권한을 거두면 그 값을 더 보내지 않는다 */
    @Test
    fun revokedSleepPermissionClearsCachedValue() = runTest {
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        health.permission = false
        controller.onForeground()
        runCurrent()
        assertNull(store.value.sleep)
        assertNull(api.summaries.single().sleepMinutesLastNight)
    }

    @Test
    fun transientHealthFailureKeepsValue() = runTest {
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        health.failRead = true
        controller.onForeground()
        runCurrent()
        assertEquals(290, store.value.sleep?.minutes)
        assertEquals(290, api.summaries.single().sleepMinutesLastNight)
    }

    @Test
    fun noSessionsSendsNullSleep() = runTest {
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0 - 1000)))
        health.spans = emptyList()
        controller.onForeground()
        runCurrent()
        assertNull(store.value.sleep)
        assertNull(api.summaries.single().sleepMinutesLastNight)
    }

    /** 심박은 화면에서만 읽는다. 백그라운드 동기화는 건드리지 않는다 */
    @Test
    fun backgroundSyncReadsSleepOnly() = runTest {
        health.spans = listOf(SleepSpan(t0 - 400 * 60_000, t0))
        val (controller, store) = controller(this)
        runCurrent()
        assertTrue(controller.backgroundSleepSync())
        assertEquals(0, health.heartReads)
        assertEquals(400, store.value.sleep?.minutes)
        assertEquals(400, api.summaries.single().sleepMinutesLastNight)
    }

    /** 백그라운드 권한만 없을 때 답을 믿고 값을 지우면 앱을 안 연 날 수면이 사라진다 */
    @Test
    fun backgroundDoesNotClearOnPermissionAnswer() = runTest {
        health.permission = false
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        runCurrent()
        assertTrue(controller.backgroundSleepSync())
        assertEquals(290, store.value.sleep?.minutes)
    }

    @Test
    fun backgroundSyncAsksForRetryOnNetworkFailure() = runTest {
        val (controller, _) = controller(this)
        runCurrent()
        api.summaryAnswers.addLast(ApiResult.NetworkError)
        assertFalse(controller.backgroundSleepSync())
    }

    @Test
    fun backgroundSyncDoesNothingWhenPausedOrUnpaired() = runTest {
        val (paused, _) = controller(this, pairedState.copy(uploadPaused = true))
        runCurrent()
        assertTrue(paused.backgroundSleepSync())
        assertEquals(emptyList(), api.calls)
    }

    /** 12.7 전체 교체. 수면 경로가 켜져 있는 시뮬레이터 값을 null로 덮으면 차량이 끊김으로 안다 */
    @Test
    fun sleepSyncCarriesRunningSimulatorValue() = runTest {
        val (controller, _) = controller(this)
        controller.startSimulator()
        runCurrent()
        api.summaries.clear()
        health.spans = listOf(SleepSpan(t0 - 400 * 60_000, t0))
        controller.backgroundSleepSync()
        val last = api.summaries.single()
        assertEquals(5.4, last.glucoseMmol)
        assertEquals(400, last.sleepMinutesLastNight)
    }

    @Test
    fun simulatorTicksEveryThirtySeconds() = runTest {
        val (controller, _) = controller(this)
        controller.startSimulator()
        runCurrent()
        assertEquals(1, serviceStarts)
        val first = api.summaries.size
        val ticks = launch { controller.runSimulatorTicks() }
        runCurrent()
        repeat(3) {
            clock.advance(30_000)
            advanceTimeBy(30_000)
            runCurrent()
        }
        // 틱 시작 1건(3초 간격 뒤) + 30초마다 3건
        assertEquals(4, api.summaries.size - first)
        api.summaries.forEach {
            assertEquals(com.conditiondrive.core.contract.GlucoseSourceDto.SIMULATED, it.glucoseSource)
        }
        val last = api.summaries.last()
        assertEquals(emptyList(), SummaryValidator.validate(last, clock.nowMs(), allowPartner = false))
        assertEquals(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock.nowMs()), ZoneId.of("Asia/Seoul")).toString().take(19), last.glucoseMeasuredAt?.take(19))
        controller.stopSimulator()
        runCurrent()
        assertTrue(ticks.isCompleted)
    }

    /** 끄면 혈당 null을 바로 보낸다. 15분을 기다리지 않는다 */
    @Test
    fun stoppingSimulatorPushesNullGlucose() = runTest {
        val (controller, _) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        controller.startSimulator()
        runCurrent()
        controller.stopSimulator()
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        val last = api.summaries.last()
        assertNull(last.glucoseMmol)
        assertEquals(290, last.sleepMinutesLastNight)
    }

    @Test
    fun sensorCutPushesNullGlucoseButSimulatorKeepsRunning() = runTest {
        val (controller, _) = controller(this)
        controller.startSimulator()
        runCurrent()
        controller.setSensorCut(true)
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        assertNull(api.summaries.last().glucoseMmol)
        assertTrue(simulator.state.value.running)
    }

    @Test
    fun manualChangeSendsSoon() = runTest {
        val (controller, _) = controller(this)
        controller.startSimulator()
        runCurrent()
        controller.setMmol(380)
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(3.8, api.summaries.last().glucoseMmol)
    }

    @Test
    fun adjustingWhileOffSendsNothing() = runTest {
        val (controller, _) = controller(this)
        runCurrent()
        controller.setMmol(380)
        controller.setTrend(null)
        controller.setSleepOverride(290)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(emptyList(), api.calls)
    }

    /** SC3: 폰에서 수면 290, 요약 전송 */
    @Test
    fun sc3SendsSimulatedSleepOverRealValue() = runTest {
        val (controller, _) = controller(this, pairedState.copy(sleep = SleepValue(500, t0)))
        controller.playScenario(SimScenarios.SC3)
        runCurrent()
        val sent = api.summaries.single()
        assertEquals(290, sent.sleepMinutesLastNight)
        assertEquals(true, sent.sleepShort)
        assertNull(sent.glucoseMmol)
    }

    @Test
    fun stoppingRestoresRealSleep() = runTest {
        val (controller, _) = controller(this, pairedState.copy(sleep = SleepValue(500, t0)))
        controller.playScenario(SimScenarios.SC3)
        runCurrent()
        controller.stopSimulator()
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(500, api.summaries.last().sleepMinutesLastNight)
    }

    @Test
    fun skipPointSendsNextValue() = runTest {
        val (controller, _) = controller(this)
        controller.playScenario(SimScenarios.SC1)
        runCurrent()
        assertEquals(6.4, api.summaries.last().glucoseMmol)
        controller.skipScenarioPoint()
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(5.6, api.summaries.last().glucoseMmol)
    }

    @Test
    fun pauseStopsSimulatorAndSending() = runTest {
        val (controller, store) = controller(this)
        controller.startSimulator()
        runCurrent()
        api.summaries.clear()
        controller.pauseUploads()
        runCurrent()
        assertTrue(store.value.uploadPaused)
        assertFalse(simulator.state.value.running)
        controller.onForeground()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(emptyList(), api.summaries)
        controller.resumeUploads()
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(1, api.summaries.size)
    }

    @Test
    fun deleteSummaryPausesFirstSoItStaysDeleted() = runTest {
        val (controller, store) = controller(this)
        controller.startSimulator()
        runCurrent()
        api.calls.clear()
        controller.deleteServerSummary()
        runCurrent()
        assertTrue(store.value.uploadPaused)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("deleteSummary"), api.calls)
        assertEquals(Notice.SUMMARY_DELETED, controller.ui.value.notice)
    }

    /** 13장 401: 토큰과 캐시를 지우고 시뮬레이터를 멈춘다 */
    @Test
    fun unauthorizedDuringUploadReturnsToUnpaired() = runTest {
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        api.summaryAnswers.addLast(ApiResult.Failed(401, "device_revoked"))
        controller.startSimulator()
        runCurrent()
        assertNull(store.value.tokenBlob)
        assertNull(store.value.sleep)
        assertFalse(simulator.state.value.running)
        assertFalse(work.sleepScheduled)
        assertEquals(Notice.TOKEN_REVOKED, controller.ui.value.notice)
        assertEquals(ConsentPolicy.VERSION, store.value.consentVersion)
    }

    @Test
    fun revokedTokenFoundOnAppOpen() = runTest {
        api.statusAnswer = ApiResult.Failed(401, "invalid_token")
        val (controller, store) = controller(this)
        controller.onForeground()
        runCurrent()
        assertNull(store.value.tokenBlob)
        assertEquals(Notice.TOKEN_REVOKED, controller.ui.value.notice)
        assertEquals(listOf("status"), api.calls)
    }

    @Test
    fun consentRequiredReturnsToConsent() = runTest {
        api.summaryAnswers.addLast(ApiResult.Failed(403, "consent_required"))
        val (controller, store) = controller(this)
        controller.startSimulator()
        runCurrent()
        assertNull(store.value.consentVersion)
        assertFalse(simulator.state.value.running)
        assertEquals(Notice.CONSENT_LOST, controller.ui.value.notice)
    }

    @Test
    fun withdrawStopsEverythingBeforeCallingServer() = runTest {
        val (controller, store) = controller(this, pairedState.copy(sleep = SleepValue(290, t0)))
        controller.startSimulator()
        runCurrent()
        api.calls.clear()
        api.withdrawAnswer = ApiResult.NetworkError
        controller.withdrawConsent()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(simulator.state.value.running)
        assertNull(store.value.consentVersion)
        assertNull(store.value.tokenBlob)
        assertNull(store.value.sleep)
        assertEquals(1, work.revokeRetries)
        assertFalse(work.sleepScheduled)
        assertEquals(Notice.WITHDRAWN_PENDING, controller.ui.value.notice)
        assertTrue("summary" !in api.calls)
    }

    @Test
    fun pendingRevokesRetryOnNextStart() = runTest {
        val (controller, store) = controller(this)
        api.unpairAnswer = ApiResult.NetworkError
        controller.unpair()
        runCurrent()
        assertEquals(1, store.value.pendingRevokes.size)
        api.unpairAnswer = ApiResult.Ok(Unit)
        assertTrue(controller.drainRevokes())
        assertEquals(0, store.value.pendingRevokes.size)
    }

    @Test
    fun providerUnavailableShowsNoSleep() = runTest {
        health.availabilityValue = HealthAvailability.UNAVAILABLE
        val (controller, _) = controller(this)
        controller.onForeground()
        runCurrent()
        assertEquals(HealthAvailability.UNAVAILABLE, controller.ui.value.health.availability)
        assertNull(api.summaries.single().sleepMinutesLastNight)
    }

    /** 실기 C2: 숨김 설정의 주소 칸이 비어 있어 지금 어디로 가는지 알 수 없었다 */
    @Test
    fun serverFieldShowsTheAddressInUse() = runTest {
        val (controller, _) = controller(this, StoredState())
        runCurrent()
        assertEquals("http://10.0.2.2:8080", controller.ui.value.serverUrl)
        assertTrue(controller.setServerUrl("https://api.example.com/"))
        runCurrent()
        assertEquals("https://api.example.com", controller.ui.value.serverUrl)
    }

    @Test
    fun serverUrlRules() = runTest {
        val (controller, store) = controller(this, StoredState())
        assertFalse(controller.setServerUrl("http://evil.example:8080"))
        assertTrue(controller.setServerUrl("https://api.example.com/"))
        runCurrent()
        assertEquals("https://api.example.com", store.value.serverUrl)
    }
}
