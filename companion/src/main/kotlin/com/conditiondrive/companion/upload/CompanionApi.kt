package com.conditiondrive.companion.upload

import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.PairingClaimResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryUploadResponse

/** 오류 메시지는 일부러 담지 않는다. 13장: 서버 오류 문장을 UI로 내보내지 않는다 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(
        val status: Int,
        val code: String? = null,
        val serverTimeMs: Long? = null,
        val retryAfterSec: Long? = null,
    ) : ApiResult<Nothing>
    data object NetworkError : ApiResult<Nothing>
}

/** 폰 토큰으로 호출할 수 있는 12장 경로 전부. 폰은 요약을 읽을 수 없으므로 GET latest는 없다 */
interface CompanionApi {
    suspend fun claim(request: PairingClaimRequest): ApiResult<PairingClaimResponse>
    suspend fun pairingStatus(token: String): ApiResult<PairingStatusResponse>
    suspend fun unpair(token: String): ApiResult<Unit>
    suspend fun postSummary(token: String, summary: SummaryDto): ApiResult<SummaryUploadResponse>
    suspend fun deleteSummary(token: String): ApiResult<Unit>
    suspend fun grantConsent(token: String, type: String, version: String): ApiResult<Unit>
    suspend fun withdrawConsent(token: String, type: String): ApiResult<Unit>
}
