package com.conditiondrive.vehicle.service

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.PairingCodeResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.VehicleRegisterRequest
import com.conditiondrive.core.contract.VehicleRegisterResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** S5 연결(12.3~12.6)과 삭제(12.13). 서버 오류 내용은 어디에도 내보내지 않고 실패만 알린다(13장) */
internal class PairingClient(client: OkHttpClient, private val appVersion: String) {
    private val http = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val json = ContractJson.Lenient

    suspend fun register(baseUrl: String): String? = call {
        val body = json.encodeToString(VehicleRegisterRequest(appVersion)).toRequestBody(JSON)
        val response = http.newCall(Request.Builder().url(baseUrl + ApiPaths.DEVICES_VEHICLE).post(body).build()).execute()
        response.use { if (it.isSuccessful) json.decodeFromString<VehicleRegisterResponse>(it.body.string()).vehicleToken else null }
    }

    class IssuedCode(val token: String, val code: PairingCodeResponse)

    /**
     * 서버에서 토큰이 사라졌으면(DB 초기화, 다른 서버 주소) 다시 등록한다.
     * 그러지 않으면 연결이 끊긴 차량은 폴링도 하지 않아 401을 볼 일이 없고, 코드를 영영 받지 못한다.
     */
    suspend fun issueCode(baseUrl: String, stored: String?): IssuedCode? {
        if (stored != null) {
            when (val first = newCode(baseUrl, stored)) {
                is CodeResult.Issued -> return IssuedCode(stored, first.response)
                CodeResult.Failed -> return null
                CodeResult.Unauthorized -> Unit
            }
        }
        val token = register(baseUrl) ?: return null
        val second = newCode(baseUrl, token) as? CodeResult.Issued ?: return null
        return IssuedCode(token, second.response)
    }

    private sealed interface CodeResult {
        class Issued(val response: PairingCodeResponse) : CodeResult
        data object Unauthorized : CodeResult
        data object Failed : CodeResult
    }

    private suspend fun newCode(baseUrl: String, token: String): CodeResult = call {
        val request = authed(baseUrl + ApiPaths.PAIRING_CODES, token).post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(request).execute().use {
            when {
                it.isSuccessful -> CodeResult.Issued(json.decodeFromString<PairingCodeResponse>(it.body.string()))
                it.code == 401 -> CodeResult.Unauthorized
                else -> CodeResult.Failed
            }
        }
    } ?: CodeResult.Failed

    suspend fun status(baseUrl: String, token: String): PairingStatusResponse? = call {
        http.newCall(authed(baseUrl + ApiPaths.PAIRING, token).get().build()).execute().use {
            if (it.isSuccessful) json.decodeFromString<PairingStatusResponse>(it.body.string()) else null
        }
    }

    suspend fun unpair(baseUrl: String, token: String): Boolean = delete(baseUrl + ApiPaths.PAIRING, token)

    suspend fun deleteAccount(baseUrl: String, token: String): Boolean = delete(baseUrl + ApiPaths.ACCOUNT, token)

    /** 401, 404는 이미 지워진 것이라 바라던 상태와 같다. 다시 해도 결과가 같으니 끝난 것으로 본다 */
    private suspend fun delete(url: String, token: String): Boolean = call {
        http.newCall(authed(url, token).delete().build()).execute().use { it.isSuccessful || it.code == 401 || it.code == 404 }
    } ?: false

    private fun authed(url: String, token: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

    private suspend fun <T> call(block: () -> T?): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
