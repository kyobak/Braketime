package com.conditiondrive.companion

import com.conditiondrive.companion.sleep.HealthAvailability
import com.conditiondrive.companion.sleep.HealthGateway
import com.conditiondrive.companion.sleep.HealthReadException
import com.conditiondrive.companion.sleep.HeartSample
import com.conditiondrive.companion.sleep.SleepSpan
import com.conditiondrive.companion.sleep.WorkScheduler
import com.conditiondrive.companion.storage.AesGcmTokenCipher
import com.conditiondrive.companion.storage.CompanionStore
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.storage.TokenVault
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.CompanionApi
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.PairingClaimResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryUploadResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.crypto.KeyGenerator

class MemoryStore(initial: StoredState = StoredState()) : CompanionStore {
    private val flow = MutableStateFlow(initial)
    override val state: Flow<StoredState> = flow
    override suspend fun read(): StoredState = flow.value
    override suspend fun update(transform: (StoredState) -> StoredState) { flow.value = transform(flow.value) }
    val value: StoredState get() = flow.value
}

val testCipher = AesGcmTokenCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { key } })

fun vaultOf(store: CompanionStore) = TokenVault(store, testCipher)

/** 호출 순서를 기록하고, 큐에 넣은 답을 차례로 돌려준다. 큐가 비면 성공이다 */
class FakeApi : CompanionApi {
    val calls = mutableListOf<String>()
    val summaries = mutableListOf<SummaryDto>()
    val claims = mutableListOf<PairingClaimRequest>()
    val summaryAnswers = ArrayDeque<ApiResult<SummaryUploadResponse>>()
    var claimAnswer: ApiResult<PairingClaimResponse> = ApiResult.Ok(
        PairingClaimResponse("acc_1", "dev_1", "cdp_new", "2026-11-20T00:00:00Z"),
    )
    var statusAnswer: ApiResult<PairingStatusResponse> = ApiResult.Ok(
        PairingStatusResponse(paired = true, phone = null, vehicleLastSeenAt = null, pendingCodeExpiresAt = null, serverTime = "2026-11-20T00:00:00Z"),
    )
    var unpairAnswer: ApiResult<Unit> = ApiResult.Ok(Unit)
    var withdrawAnswer: ApiResult<Unit> = ApiResult.Ok(Unit)
    var grantAnswer: ApiResult<Unit> = ApiResult.Ok(Unit)
    var deleteSummaryAnswer: ApiResult<Unit> = ApiResult.Ok(Unit)
    var usedTokens = mutableListOf<String>()

    override suspend fun claim(request: PairingClaimRequest): ApiResult<PairingClaimResponse> {
        calls += "claim"
        claims += request
        return claimAnswer
    }

    override suspend fun pairingStatus(token: String): ApiResult<PairingStatusResponse> {
        calls += "status"
        usedTokens += token
        return statusAnswer
    }

    override suspend fun unpair(token: String): ApiResult<Unit> {
        calls += "unpair"
        usedTokens += token
        return unpairAnswer
    }

    var postGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun postSummary(token: String, summary: SummaryDto): ApiResult<SummaryUploadResponse> {
        calls += "summary"
        postGate?.await()
        usedTokens += token
        summaries += summary
        return summaryAnswers.removeFirstOrNull() ?: ApiResult.Ok(
            SummaryUploadResponse(applied = true, receivedAt = null, vehicleLastSeenAt = "2026-11-20T00:00:00Z", serverTime = "2026-11-20T00:00:00Z"),
        )
    }

    override suspend fun deleteSummary(token: String): ApiResult<Unit> {
        calls += "deleteSummary"
        usedTokens += token
        return deleteSummaryAnswer
    }

    override suspend fun grantConsent(token: String, type: String, version: String): ApiResult<Unit> {
        calls += "grant:$type:$version"
        usedTokens += token
        return grantAnswer
    }

    override suspend fun withdrawConsent(token: String, type: String): ApiResult<Unit> {
        calls += "withdraw:$type"
        usedTokens += token
        return withdrawAnswer
    }
}

class FakeHealth : HealthGateway {
    var availabilityValue = HealthAvailability.AVAILABLE
    var permission = true
    var spans = emptyList<SleepSpan>()
    var heart: HeartSample? = null
    var failRead = false
    var heartReads = 0

    override fun availability() = availabilityValue
    override suspend fun hasSleepPermission() = permission
    override suspend fun readSleep(fromMs: Long, toMs: Long): List<SleepSpan> {
        if (failRead) throw HealthReadException()
        return spans
    }
    override suspend fun latestHeartRate(fromMs: Long, toMs: Long): HeartSample? {
        heartReads++
        return heart
    }
}

class FakeWork : WorkScheduler {
    var sleepScheduled = false
    var revokeRetries = 0
    override fun scheduleSleepSync() { sleepScheduled = true }
    override fun cancelSleepSync() { sleepScheduled = false }
    override fun scheduleRevokeRetry() { revokeRetries++ }
}
