package com.conditiondrive.companion.upload

import com.conditiondrive.core.contract.ApiErrorEnvelope
import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.PairingClaimResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryUploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/** 12.10 PUT 본문. 응답을 읽지 않아 11.2에 없는 이 요청만 여기 둔다 */
@Serializable
internal data class ConsentGrantRequest(val version: String)

/** 로깅 인터셉터를 달지 않는다(11.3). 요약 값과 토큰이 logcat으로 새는 경로를 처음부터 만들지 않는다 */
class OkHttpCompanionApi(
    private val baseUrl: suspend () -> String,
    private val client: OkHttpClient = defaultClient(),
) : CompanionApi {

    override suspend fun claim(request: PairingClaimRequest): ApiResult<PairingClaimResponse> = call(
        path = ApiPaths.PAIRING_CLAIM,
        token = null,
        method = "POST",
        body = jsonBody(ContractJson.Strict.encodeToString(PairingClaimRequest.serializer(), request)),
        success = PairingClaimResponse.serializer(),
    )

    override suspend fun pairingStatus(token: String): ApiResult<PairingStatusResponse> =
        call(ApiPaths.PAIRING, token, "GET", null, PairingStatusResponse.serializer())

    override suspend fun unpair(token: String): ApiResult<Unit> = callForStatus(ApiPaths.PAIRING, token)

    override suspend fun postSummary(token: String, summary: SummaryDto): ApiResult<SummaryUploadResponse> = call(
        path = ApiPaths.SUMMARY,
        token = token,
        method = "POST",
        // Strict는 null 키도 쓴다. 서버가 9개 키를 모두 요구한다(12.7 표 5번)
        body = jsonBody(ContractJson.Strict.encodeToString(SummaryDto.serializer(), summary)),
        success = SummaryUploadResponse.serializer(),
    )

    override suspend fun deleteSummary(token: String): ApiResult<Unit> = callForStatus(ApiPaths.SUMMARY, token)

    override suspend fun grantConsent(token: String, type: String, version: String): ApiResult<Unit> =
        exchange("${ApiPaths.CONSENTS}/$type", token, "PUT", jsonBody(ContractJson.Strict.encodeToString(ConsentGrantRequest.serializer(), ConsentGrantRequest(version)))) { response ->
            if (response.isSuccessful) ApiResult.Ok(Unit) else failure(response)
        }

    override suspend fun withdrawConsent(token: String, type: String): ApiResult<Unit> =
        callForStatus("${ApiPaths.CONSENTS}/$type", token)

    private fun jsonBody(json: String): RequestBody = json.toRequestBody(JSON)

    private suspend fun callForStatus(path: String, token: String): ApiResult<Unit> =
        exchange(path, token, "DELETE", null) { response ->
            if (response.isSuccessful) ApiResult.Ok(Unit) else failure(response)
        }

    private suspend fun <T> call(
        path: String,
        token: String?,
        method: String,
        body: RequestBody?,
        success: DeserializationStrategy<T>,
    ): ApiResult<T> = exchange(path, token, method, body) { response ->
        if (!response.isSuccessful) {
            failure(response)
        } else {
            try {
                ApiResult.Ok(ContractJson.Lenient.decodeFromString(success, response.body.string()))
            } catch (_: SerializationException) {
                ApiResult.Failed(response.code)
            } catch (_: IllegalArgumentException) {
                ApiResult.Failed(response.code)
            }
        }
    }

    private suspend fun <T> exchange(
        path: String,
        token: String?,
        method: String,
        body: RequestBody?,
        read: (Response) -> ApiResult<T>,
    ): ApiResult<T> {
        val base = baseUrl()
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(base + path).method(method, body).apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                client.newCall(request).execute().use(read)
            } catch (_: IOException) {
                ApiResult.NetworkError
            } catch (_: IllegalArgumentException) {
                ApiResult.NetworkError // 저장된 주소가 URL로 못 읽히는 경우. 값은 어디에도 남기지 않는다
            }
        }
    }

    private fun failure(response: Response): ApiResult.Failed {
        val error = try {
            ContractJson.Lenient.decodeFromString(ApiErrorEnvelope.serializer(), response.body.string()).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
        return ApiResult.Failed(
            status = response.code,
            code = error?.code,
            serverTimeMs = error?.serverTime?.let(::parseMs),
            retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull(),
        )
    }

    private fun parseMs(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** C-45 */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3_000, TimeUnit.MILLISECONDS)
            .callTimeout(8_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false) // 재시도는 슬롯 규칙(11.3)만 따른다
            .build()
    }
}
