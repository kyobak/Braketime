package com.conditiondrive.companion

import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.OkHttpCompanionApi
import com.conditiondrive.core.contract.ConsentItem
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryLimits
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkHttpCompanionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpCompanionApi

    private val summary = SummaryDto(
        generatedAt = "2026-11-20T08:12:00+09:00",
        validUntil = "2026-11-20T08:27:00+09:00",
        sleepMinutesLastNight = null,
        sleepShort = null,
        sleepValidUntil = null,
        glucoseMmol = null,
        glucoseTrend = null,
        glucoseSource = null,
        glucoseMeasuredAt = null,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = OkHttpCompanionApi(baseUrl = { base })
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "", header: Pair<String, String>? = null) {
        val builder = MockResponse.Builder().code(code).body(body)
        if (header != null) builder.addHeader(header.first, header.second)
        server.enqueue(builder.build())
    }

    @Test
    fun postSummarySendsAllNineKeysEvenWhenNull() = runTest {
        respond(200, """{"applied":true,"received_at":"2026-11-19T23:12:01Z","vehicle_last_seen_at":null,"server_time":"2026-11-19T23:12:01Z"}""")
        val result = api.postSummary("cdp_abc", summary)
        assertTrue(assertIs<ApiResult.Ok<*>>(result).value != null)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/summary", request.target)
        assertEquals("Bearer cdp_abc", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
        val body = ContractJson.Strict.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(SummaryLimits.ALLOWED_KEYS, body.keys)
    }

    @Test
    fun claimHasNoAuthorizationAndCarriesConsent() = runTest {
        respond(201, """{"account_id":"acc_1","device_id":"dev_1","phone_token":"cdp_x","server_time":"2026-11-19T23:13:10Z"}""")
        val result = api.claim(PairingClaimRequest("K7M2QX9T", "Galaxy", "0.1.0", listOf(ConsentItem("health_summary", "2026-11-v1"))))
        assertEquals("cdp_x", assertIs<ApiResult.Ok<*>>(result).let { (it.value as com.conditiondrive.core.contract.PairingClaimResponse).phoneToken })
        val request = server.takeRequest()
        assertEquals("/v1/pairing/claim", request.target)
        assertNull(request.headers["Authorization"])
        val body = request.body!!.utf8()
        assertTrue("\"device_name\":\"Galaxy\"" in body)
        assertTrue("\"consents\":[{\"type\":\"health_summary\",\"version\":\"2026-11-v1\"}]" in body)
    }

    @Test
    fun deleteRoutes() = runTest {
        repeat(3) { respond(200, "{}") }
        assertEquals(ApiResult.Ok(Unit), api.unpair("t"))
        assertEquals(ApiResult.Ok(Unit), api.deleteSummary("t"))
        assertEquals(ApiResult.Ok(Unit), api.withdrawConsent("t", "health_summary"))
        val first = server.takeRequest()
        assertEquals("DELETE" to "/v1/pairing", first.method to first.target)
        val second = server.takeRequest()
        assertEquals("DELETE" to "/v1/summary", second.method to second.target)
        val third = server.takeRequest()
        assertEquals("DELETE" to "/v1/consents/health_summary", third.method to third.target)
    }

    /** 12.10 */
    @Test
    fun grantConsentRoute() = runTest {
        respond(200, "{}")
        assertEquals(ApiResult.Ok(Unit), api.grantConsent("t", "health_summary", "2026-11-v1"))
        val request = server.takeRequest()
        assertEquals("PUT" to "/v1/consents/health_summary", request.method to request.target)
        assertEquals("""{"version":"2026-11-v1"}""", request.body!!.utf8())
    }

    /** 오류 본문에서 code와 시각만 꺼낸다. 메시지는 결과 타입에 담길 자리가 없다 */
    @Test
    fun errorEnvelopeIsReducedToCodeAndTime() = runTest {
        respond(
            422,
            """{"error":{"code":"clock_skew","message":"요청 값을 확인해 주세요.","fields":[],"request_id":"req_1","server_time":"2026-11-19T23:12:05Z"}}""",
        )
        val failed = assertIs<ApiResult.Failed>(api.postSummary("t", summary))
        assertEquals(422, failed.status)
        assertEquals("clock_skew", failed.code)
        assertEquals(java.time.Instant.parse("2026-11-19T23:12:05Z").toEpochMilli(), failed.serverTimeMs)
        assertTrue("요청" !in failed.toString())
    }

    @Test
    fun retryAfterHeaderIsRead() = runTest {
        respond(429, """{"error":{"code":"rate_limited","message":"x","request_id":"r","server_time":"2026-11-19T23:12:05Z"}}""", "Retry-After" to "42")
        assertEquals(42, assertIs<ApiResult.Failed>(api.postSummary("t", summary)).retryAfterSec)
    }

    @Test
    fun nonJsonErrorStillClassifiesByStatus() = runTest {
        respond(503, "<html>down</html>")
        val failed = assertIs<ApiResult.Failed>(api.postSummary("t", summary))
        assertEquals(503, failed.status)
        assertNull(failed.code)
    }

    /** 응답에 필드가 늘어도 설치된 APK가 깨지지 않는다(S20) */
    @Test
    fun unknownResponseFieldsAreIgnored() = runTest {
        respond(200, """{"applied":true,"server_time":"2026-11-19T23:12:01Z","future_field":{"a":1}}""")
        assertIs<ApiResult.Ok<*>>(api.postSummary("t", summary))
    }

    @Test
    fun brokenSuccessBodyIsAFailureNotACrash() = runTest {
        respond(200, "not json")
        assertEquals(200, assertIs<ApiResult.Failed>(api.postSummary("t", summary)).status)
    }

    @Test
    fun unreachableServerIsNetworkError() = runTest {
        val port = server.port
        server.close()
        val closed = OkHttpCompanionApi(baseUrl = { "http://127.0.0.1:$port" })
        assertEquals(ApiResult.NetworkError, closed.postSummary("t", summary))
    }

    @Test
    fun badStoredAddressIsNetworkErrorNotCrash() = runTest {
        val broken = OkHttpCompanionApi(baseUrl = { "not a url" })
        assertEquals(ApiResult.NetworkError, broken.postSummary("t", summary))
    }

    /** C-45 */
    @Test
    fun clientTimeoutsMatchPolicy() {
        val client = OkHttpCompanionApi.defaultClient()
        assertEquals(3_000, client.connectTimeoutMillis)
        assertEquals(8_000, client.callTimeoutMillis)
        assertTrue(client.interceptors.isEmpty())           // 로깅 인터셉터 없음(11.3)
        assertTrue(client.networkInterceptors.isEmpty())
    }
}
