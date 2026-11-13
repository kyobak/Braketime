package com.conditiondrive.companion

import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.GlucoseReading
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.companion.upload.SummaryInput
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.companion.upload.UploadStatus
import com.conditiondrive.companion.upload.UploadStep
import com.conditiondrive.core.contract.SummaryValidator
import com.conditiondrive.core.time.ManualClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadCoordinatorTest {
    private val t0 = OffsetDateTime.parse("2026-11-20T08:12:00+09:00").toInstant().toEpochMilli()
    private val clock = ManualClock(t0)
    private val api = FakeApi()
    private val store = MemoryStore(
        StoredState(consentVersion = "2026-11-v1", tokenBlob = testCipher.seal("cdp_token")),
    )
    private var unauthorized = 0
    private var consentLost = 0
    private val coordinator = UploadCoordinator(
        api = api,
        store = store,
        tokens = vaultOf(store),
        clock = clock,
        zone = { ZoneId.of("Asia/Seoul") },
        onUnauthorized = { unauthorized++ },
        onConsentRequired = { consentLost++ },
    )

    private fun input(mmol: Double = 5.4, at: Long = t0) = SummaryInput(SleepValue(290, t0), GlucoseReading(mmol, null, at))

    private fun failed(status: Int, code: String? = null, serverTimeMs: Long? = null, retryAfterSec: Long? = null) =
        ApiResult.Failed(status, code, serverTimeMs, retryAfterSec)

    @Test
    fun sendsValidatedSnapshotWithPhoneToken() = runTest {
        coordinator.offer(input())
        assertEquals(UploadStep.Done, coordinator.attempt())
        val sent = api.summaries.single()
        assertEquals(emptyList(), SummaryValidator.validate(sent, t0, allowPartner = false))
        assertEquals(listOf("cdp_token"), api.usedTokens)
        assertEquals(t0, store.value.lastUploadAtMs)
        assertEquals(UploadStatus.Delivered, coordinator.status.value)
        assertEquals(UploadStep.Nothing, coordinator.attempt())
    }

    /** 12.1: 미연결이면 호출 0회 */
    @Test
    fun noTokenMeansNoNetwork() = runTest {
        store.update { it.copy(tokenBlob = null) }
        coordinator.offer(input())
        assertEquals(UploadStep.Nothing, coordinator.attempt())
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun pausedMeansNoNetworkAndDropsSlot() = runTest {
        store.update { it.copy(uploadPaused = true) }
        coordinator.offer(input())
        assertEquals(UploadStep.Nothing, coordinator.attempt())
        assertEquals(emptyList(), api.calls)
        store.update { it.copy(uploadPaused = false) }
        assertEquals(UploadStep.Nothing, coordinator.attempt())  // 중단 전 값을 나중에 몰래 보내지 않는다
    }

    /** 11.3 슬롯은 최신 1건 */
    @Test
    fun onlyLatestSurvives() = runTest {
        coordinator.offer(input(4.0))
        coordinator.offer(input(4.5))
        coordinator.offer(input(5.0))
        coordinator.attempt()
        assertEquals(listOf(5.0), api.summaries.map { it.glucoseMmol })
    }

    @Test
    fun networkFailureBacksOff1530606060() = runTest {
        val waits = mutableListOf<Long>()
        repeat(5) {
            api.summaryAnswers.addLast(ApiResult.NetworkError)
            coordinator.offer(input())
            waits += (coordinator.attempt() as UploadStep.Wait).delayMs
        }
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L), waits)
        assertEquals(UploadStatus.Retrying, coordinator.status.value)
        coordinator.attempt()
        assertEquals(UploadStatus.Delivered, coordinator.status.value)
        api.summaryAnswers.addLast(ApiResult.NetworkError)
        coordinator.offer(input())
        assertEquals(UploadStep.Wait(15_000), coordinator.attempt())  // 성공하면 처음부터
    }

    @Test
    fun serverErrorsBackOffToo() = runTest {
        for (status in listOf(500, 502, 503)) {
            coordinator.clear()
            api.summaryAnswers.addLast(failed(status))
            coordinator.offer(input())
            assertEquals(UploadStep.Wait(15_000), coordinator.attempt(), "$status")
        }
    }

    /** 전송 직전에 만료됐으면 혈당을 null로 다시 만든다. 낡은 값으로 재시도하지 않는다 */
    @Test
    fun retryRebuildsAndDropsExpiredGlucose() = runTest {
        api.summaryAnswers.addLast(ApiResult.NetworkError)
        coordinator.offer(input())
        coordinator.attempt()
        assertEquals(5.4, api.summaries.last().glucoseMmol)

        clock.advance(899_000)
        api.summaryAnswers.addLast(ApiResult.NetworkError)
        coordinator.attempt()
        assertEquals(5.4, api.summaries.last().glucoseMmol)

        clock.advance(1_000)
        coordinator.attempt()
        val last = api.summaries.last()
        assertNull(last.glucoseMmol)
        assertNull(last.glucoseMeasuredAt)
        assertEquals(290, last.sleepMinutesLastNight)
        assertEquals(emptyList(), SummaryValidator.validate(last, clock.nowMs(), allowPartner = false))
    }

    @Test
    fun rateLimitHonoursRetryAfterWithFiveMinuteCap() = runTest {
        api.summaryAnswers.addLast(failed(429, "rate_limited", retryAfterSec = 42))
        coordinator.offer(input())
        assertEquals(UploadStep.Wait(42_000), coordinator.attempt())
        api.summaryAnswers.addLast(failed(429, "rate_limited", retryAfterSec = 3600))
        assertEquals(UploadStep.Wait(300_000), coordinator.attempt())
        api.summaryAnswers.addLast(failed(429, "rate_limited"))
        assertEquals(UploadStep.Wait(60_000), coordinator.attempt())
    }

    /** 폰 시계가 1시간 느린 경우. 보정 후 1회 재시도하고, 이미 잰 값도 같이 옮겨 버려지지 않는다 */
    @Test
    fun clockSkewCorrectsOnceAndKeepsReading() = runTest {
        val serverNow = t0 + 3_600_000
        api.summaryAnswers.addLast(failed(422, "clock_skew", serverTimeMs = serverNow))
        coordinator.offer(input())
        assertEquals(UploadStep.Done, coordinator.attempt())
        assertEquals(2, api.summaries.size)
        assertEquals(3_600_000, store.value.clockOffsetMs)
        val retried = api.summaries.last()
        assertEquals(5.4, retried.glucoseMmol)
        assertEquals(290, retried.sleepMinutesLastNight)
        assertEquals(emptyList(), SummaryValidator.validate(retried, serverNow, allowPartner = false))
        assertEquals(UploadStatus.Delivered, coordinator.status.value)
    }

    @Test
    fun clockSkewRetriesOnlyOnce() = runTest {
        api.summaryAnswers.addLast(failed(422, "clock_skew", serverTimeMs = t0 + 3_600_000))
        api.summaryAnswers.addLast(failed(422, "clock_skew", serverTimeMs = t0 + 3_600_000))
        coordinator.offer(input())
        assertEquals(UploadStep.Done, coordinator.attempt())
        assertEquals(2, api.summaries.size)
        assertEquals(UploadStatus.ClockOff, coordinator.status.value)
        assertEquals(UploadStep.Nothing, coordinator.attempt())
    }

    @Test
    fun clockSkewWithoutServerTimeDoesNotRetry() = runTest {
        api.summaryAnswers.addLast(failed(422, "clock_skew"))
        coordinator.offer(input())
        coordinator.attempt()
        assertEquals(1, api.summaries.size)
        assertEquals(0, store.value.clockOffsetMs)
    }

    /** 보정값은 다음 전송에도 쓴다 */
    @Test
    fun correctedOffsetIsUsedByLaterSnapshots() = runTest {
        store.update { it.copy(clockOffsetMs = 3_600_000) }
        coordinator.offer(SummaryInput(null, null))
        coordinator.attempt()
        assertEquals("2026-11-20T09:12:00+09:00", api.summaries.single().generatedAt)
    }

    /** 12.1 표: 재시도 금지 */
    @Test
    fun terminalErrorsAreNotRetried() = runTest {
        for ((status, code) in listOf(
            400 to "malformed_json", 403 to "wrong_device_role", 413 to "payload_too_large", 415 to "unsupported_media_type",
            422 to "validation_failed", 422 to "unknown_field", 422 to "raw_health_data_rejected",
            422 to "glucose_source_not_allowed", 422 to "summary_expired",
        )) {
            api.summaries.clear()
            api.summaryAnswers.addLast(failed(status, code))
            coordinator.offer(input())
            assertEquals(UploadStep.Done, coordinator.attempt(), code)
            assertEquals(UploadStatus.Rejected, coordinator.status.value, code)
            assertEquals(UploadStep.Nothing, coordinator.attempt(), code)
            assertEquals(1, api.summaries.size, code)
        }
        assertEquals(0, unauthorized)
        assertEquals(0, consentLost)
    }

    /** 13장 401 */
    @Test
    fun unauthorizedNotifiesAndStops() = runTest {
        api.summaryAnswers.addLast(failed(401, "device_revoked"))
        coordinator.offer(input())
        coordinator.attempt()
        assertEquals(1, unauthorized)
        assertEquals(UploadStep.Nothing, coordinator.attempt())
    }

    @Test
    fun consentRequiredNotifiesAndStops() = runTest {
        api.summaryAnswers.addLast(failed(403, "consent_required"))
        coordinator.offer(input())
        coordinator.attempt()
        assertEquals(1, consentLost)
        assertEquals(UploadStep.Nothing, coordinator.attempt())
    }

    @Test
    fun notNewerIsNotAFailure() = runTest {
        api.summaryAnswers.addLast(
            ApiResult.Ok(com.conditiondrive.core.contract.SummaryUploadResponse(applied = false, reason = "not_newer", serverTime = "2026-11-20T00:00:00Z")),
        )
        coordinator.offer(input())
        assertEquals(UploadStep.Done, coordinator.attempt())
    }

    /** 조작이 몰려도 서버 제한 20건/분(3초 간격)에 닿지 않는다 */
    @Test
    fun runLoopCoalescesAndRespectsMinimumInterval() = runTest {
        val job = launch { coordinator.run() }
        coordinator.offer(input(4.0))
        runCurrent()
        assertEquals(1, api.summaries.size)
        repeat(10) {
            coordinator.offer(input(4.0 + it / 10.0))
            advanceTimeBy(100)
        }
        assertEquals(1, api.summaries.size)
        advanceTimeBy(UploadCoordinator.MIN_INTERVAL_MS)
        runCurrent()
        assertEquals(2, api.summaries.size)
        assertEquals(4.9, api.summaries.last().glucoseMmol)
        job.cancel()
    }

    @Test
    fun runLoopWaitsOutBackoff() = runTest {
        api.summaryAnswers.addLast(ApiResult.NetworkError)
        val job = launch { coordinator.run() }
        coordinator.offer(input())
        runCurrent()
        assertEquals(1, api.summaries.size)
        advanceTimeBy(14_999)
        runCurrent()
        assertEquals(1, api.summaries.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, api.summaries.size)
        job.cancel()
    }

    @Test
    fun runLoopIdleWithoutOffer() = runTest {
        val job = launch { coordinator.run() }
        advanceTimeBy(3_600_000)
        assertTrue(api.calls.isEmpty())
        job.cancel()
    }

    /** 전송 중에 서버의 요약을 지우면 전송이 뒤늦게 값을 되살린다. 끝날 때까지 기다려야 한다 */
    @Test
    fun clearAndWaitWaitsForInFlightUpload() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.postGate = gate
        coordinator.offer(input())
        val sending = launch { coordinator.attempt() }
        runCurrent()
        assertEquals(listOf("summary"), api.calls)
        var cleared = false
        val clearing = launch {
            coordinator.clearAndWait()
            cleared = true
        }
        runCurrent()
        assertEquals(false, cleared, "전송이 끝나기 전에 돌아왔다")
        gate.complete(Unit)
        sending.join()
        clearing.join()
        assertEquals(true, cleared)
        assertEquals(UploadStep.Nothing, coordinator.attempt())
    }
}
