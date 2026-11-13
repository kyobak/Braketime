package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.engine.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 코루틴이 취소되면 호출도 끊는다. 3초 제한이 지난 뒤까지 소켓을 붙들지 않게 한다 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = continuation.resume(response)
    })
}

class OkHttpSummaryTransport(client: OkHttpClient) : SummaryTransport {
    private val http = client.newBuilder()
        .connectTimeout(Policy.DEFAULT.HTTP_TIMEOUT_MS.connect, TimeUnit.MILLISECONDS)
        .callTimeout(Policy.DEFAULT.HTTP_TIMEOUT_MS.call, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetchLatest(credentials: SummaryCredentials): TransportResult {
        val request = Request.Builder()
            .url(credentials.baseUrl.trimEnd('/') + ApiPaths.SUMMARY_LATEST)
            .header("Authorization", "Bearer ${credentials.vehicleToken}")
            .build()
        return try {
            val response = http.newCall(request).await()
            // await는 헤더까지만 받는다. 본문은 소켓을 읽으므로 메인 스레드에서 읽으면 NetworkOnMainThreadException이다
            withContext(Dispatchers.IO) {
                response.use {
                    TransportResult.Response(
                        status = it.code,
                        body = it.body.string(),
                        retryAfterSec = it.header("Retry-After")?.toLongOrNull(),
                    )
                }
            }
        } catch (_: IOException) {
            TransportResult.NetworkError
        }
    }
}
