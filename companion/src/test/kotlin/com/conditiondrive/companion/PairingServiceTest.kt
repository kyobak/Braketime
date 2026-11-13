package com.conditiondrive.companion

import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.pairing.ConsentPolicy
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.pairing.RemoteDeletion
import com.conditiondrive.companion.storage.PendingRevoke
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.core.contract.ConsentItem
import com.conditiondrive.core.time.ManualClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingServiceTest {
    private val api = FakeApi()
    private val store = MemoryStore(StoredState(consentVersion = ConsentPolicy.VERSION))
    private val service = PairingService(api, store, vaultOf(store), ManualClock(1000), { "Galaxy S25" }, "0.1.0")

    private suspend fun paired() = store.update {
        it.copy(tokenBlob = testCipher.seal("cdp_old"), deviceId = "dev_old", sleep = SleepValue(290, 1), lastUploadAtMs = 5)
    }

    @Test
    fun claimStoresOnlyEncryptedToken() = runTest {
        assertEquals(ClaimResult.PAIRED, service.claim("k7m2-qx9t"))
        val request = api.claims.single()
        assertEquals("K7M2QX9T", request.code)
        assertEquals("Galaxy S25", request.deviceName)
        assertEquals("0.1.0", request.appVersion)
        assertEquals(listOf(ConsentItem("health_summary", "2026-11-v1")), request.consents)
        val blob = assertNotNull(store.value.tokenBlob)
        assertFalse("cdp_new" in blob)
        assertEquals("cdp_new", testCipher.open(blob))
        assertEquals("dev_1", store.value.deviceId)
    }

    /** 14장: 동의 없이는 서버 호출이 나가지 않는다 */
    @Test
    fun noConsentNoCall() = runTest {
        store.update { it.copy(consentVersion = null) }
        assertEquals(ClaimResult.CONSENT_MISSING, service.claim("K7M2QX9T"))
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun badFormatDoesNotBurnServerFailureLimit() = runTest {
        assertEquals(ClaimResult.CODE_FORMAT, service.claim("ABC"))
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun claimErrorsMap() = runTest {
        val table = listOf(
            ApiResult.Failed(404, "pairing_code_invalid") to ClaimResult.CODE_INVALID,
            ApiResult.Failed(410, "pairing_code_expired") to ClaimResult.CODE_EXPIRED,
            ApiResult.Failed(403, "consent_required") to ClaimResult.CONSENT_MISSING,
            ApiResult.Failed(429, "rate_limited") to ClaimResult.RATE_LIMITED,
            ApiResult.Failed(503) to ClaimResult.NOT_REACHED,
            ApiResult.NetworkError to ClaimResult.NOT_REACHED,
            ApiResult.Failed(422, "validation_failed") to ClaimResult.FAILED,
        )
        for ((answer, expected) in table) {
            api.claimAnswer = answer
            assertEquals(expected, service.claim("K7M2QX9T"), answer.toString())
            assertNull(store.value.tokenBlob)
        }
    }

    /** 새 코드가 실패하면 지금 연결을 잃지 않고, 성공해야 옛 토큰을 폐기한다 */
    @Test
    fun replacingKeepsOldUntilClaimSucceeds() = runTest {
        paired()
        api.claimAnswer = ApiResult.Failed(404, "pairing_code_invalid")
        service.claim("K7M2QX9T")
        assertEquals("cdp_old", testCipher.open(store.value.tokenBlob!!))
        assertEquals(emptyList(), store.value.pendingRevokes)

        api.claimAnswer = ApiResult.Ok(com.conditiondrive.core.contract.PairingClaimResponse("a", "dev_2", "cdp_new", "2026-11-20T00:00:00Z"))
        service.claim("K7M2QX9T")
        assertEquals("cdp_new", testCipher.open(store.value.tokenBlob!!))
        assertEquals(1, store.value.pendingRevokes.size)
        assertTrue(service.drainRevokes())
        assertEquals("cdp_old", api.usedTokens.last())
        assertEquals(emptyList(), store.value.pendingRevokes)
    }

    @Test
    fun unpairWipesLocalAndServer() = runTest {
        paired()
        assertEquals(RemoteDeletion.DONE, service.unpair())
        assertEquals(listOf("unpair"), api.calls)
        assertEquals("cdp_old", api.usedTokens.single())
        val state = store.value
        assertNull(state.tokenBlob)
        assertNull(state.sleep)
        assertNull(state.lastUploadAtMs)
        assertNull(state.deviceId)
        assertEquals(ConsentPolicy.VERSION, state.consentVersion) // 해제는 동의 철회가 아니다
        assertEquals(emptyList(), state.pendingRevokes)
    }

    /** 네트워크가 없어도 폰은 즉시 비워지고, 서버 삭제는 남겨 둔다 */
    @Test
    fun unpairOfflineKeepsPendingAndRetries() = runTest {
        paired()
        api.unpairAnswer = ApiResult.NetworkError
        assertEquals(RemoteDeletion.PENDING, service.unpair())
        assertNull(store.value.tokenBlob)
        assertNull(store.value.sleep)
        assertEquals(1, store.value.pendingRevokes.size)
        assertFalse(service.drainRevokes())
        assertEquals(1, store.value.pendingRevokes.size)

        api.unpairAnswer = ApiResult.Ok(Unit)
        assertTrue(service.drainRevokes())
        assertEquals(emptyList(), store.value.pendingRevokes)
    }

    /** 이미 폐기된 토큰이면 원하는 상태다. 재시도를 끝낸다 */
    @Test
    fun unpairWithRevokedTokenIsDone() = runTest {
        paired()
        api.unpairAnswer = ApiResult.Failed(401, "device_revoked")
        assertEquals(RemoteDeletion.DONE, service.unpair())
    }

    @Test
    fun rateLimitedRevokeIsRetried() = runTest {
        paired()
        api.unpairAnswer = ApiResult.Failed(429, "rate_limited", retryAfterSec = 5)
        assertEquals(RemoteDeletion.PENDING, service.unpair())
    }

    /** 14장: 철회 즉시 요약 삭제 */
    @Test
    fun withdrawDeletesEverything() = runTest {
        paired()
        assertEquals(RemoteDeletion.DONE, service.withdraw())
        assertEquals(listOf("withdraw:health_summary", "unpair"), api.calls)
        val state = store.value
        assertNull(state.consentVersion)
        assertNull(state.consentGrantedAtMs)
        assertNull(state.tokenBlob)
        assertNull(state.sleep)
    }

    /** P1 동의 API가 아직 없는 서버여도 페어링 삭제가 요약을 지운다 */
    @Test
    fun withdrawFallsBackToUnpairOn404() = runTest {
        paired()
        api.withdrawAnswer = ApiResult.Failed(404, "not_found")
        assertEquals(RemoteDeletion.DONE, service.withdraw())
        assertEquals(listOf("withdraw:health_summary", "unpair"), api.calls)
    }

    @Test
    fun withdrawOfflineRemembersConsentPart() = runTest {
        paired()
        api.withdrawAnswer = ApiResult.NetworkError
        assertEquals(RemoteDeletion.PENDING, service.withdraw())
        assertNull(store.value.consentVersion)
        assertEquals(listOf("withdraw:health_summary"), api.calls)
        assertTrue(store.value.pendingRevokes.single().withdrawConsent)
        api.withdrawAnswer = ApiResult.Ok(Unit)
        assertTrue(service.drainRevokes())
        assertEquals(listOf("withdraw:health_summary", "withdraw:health_summary", "unpair"), api.calls)
    }

    @Test
    fun unpairWhenNotPairedMakesNoCall() = runTest {
        assertEquals(RemoteDeletion.DONE, service.unpair())
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun undecryptableRevokeIsDropped() = runTest {
        store.update { it.copy(pendingRevokes = listOf(PendingRevoke("garbage", false))) }
        assertTrue(service.drainRevokes())
        assertEquals(emptyList(), api.calls)
    }

    @Test
    fun deleteSummaryResults() = runTest {
        paired()
        assertEquals(RemoteDeletion.DONE, service.deleteServerSummary())
        assertEquals(listOf("deleteSummary"), api.calls)
        api.deleteSummaryAnswer = ApiResult.NetworkError
        assertEquals(RemoteDeletion.PENDING, service.deleteServerSummary())
    }

    @Test
    fun revokedDetection() = runTest {
        assertFalse(service.isRevoked())
        assertEquals(emptyList(), api.calls) // 미연결은 호출 없음
        paired()
        assertFalse(service.isRevoked())
        api.statusAnswer = ApiResult.Failed(401, "device_revoked")
        assertTrue(service.isRevoked())
        api.statusAnswer = ApiResult.NetworkError
        assertFalse(service.isRevoked()) // 통신 실패를 해제로 착각하지 않는다
    }

    /** 12.5: device_name 1~40자 */
    @Test
    fun deviceNameIsSanitized() {
        assertEquals("Galaxy S25", PairingService.sanitizedDeviceName(" Galaxy S25 "))
        assertEquals(PairingService.FALLBACK_DEVICE_NAME, PairingService.sanitizedDeviceName("   "))
        assertEquals(40, PairingService.sanitizedDeviceName("x".repeat(100)).length)
        assertEquals("ab", PairingService.sanitizedDeviceName("a\u0000b\n"))
    }

    @Test
    fun recordConsentStoresVersionAndTime() = runTest {
        store.update { StoredState() }
        service.recordConsent()
        assertEquals("2026-11-v1", store.value.consentVersion)
        assertEquals(1000, store.value.consentGrantedAtMs)
        assertEquals(emptyList(), api.calls) // 1단계 사용자: 동의만으로는 네트워크를 쓰지 않는다
    }

    /** 폰 밖에서(차량 토큰 API) 철회된 뒤 폰에서 다시 동의하면 서버 동의도 되살린다 */
    @Test
    fun reconsentWhilePairedGrantsOnServer() = runTest {
        paired()
        service.recordConsent()
        assertEquals(listOf("grant:health_summary:2026-11-v1"), api.calls)
        assertNotNull(store.value.tokenBlob)
    }

    /** 서버가 동의를 받지 못하면 전송과 재동의를 끝없이 오가지 않게 다시 연결로 보낸다 */
    @Test
    fun reconsentRejectedSendsBackToPairing() = runTest {
        paired()
        api.grantAnswer = ApiResult.Failed(404, "not_found")
        service.recordConsent()
        assertNull(store.value.tokenBlob)
        assertEquals(ConsentPolicy.VERSION, store.value.consentVersion)
        assertEquals(1, store.value.pendingRevokes.size)
    }

    @Test
    fun reconsentOfflineKeepsConnection() = runTest {
        paired()
        api.grantAnswer = ApiResult.NetworkError
        service.recordConsent()
        assertNotNull(store.value.tokenBlob)
    }
}
