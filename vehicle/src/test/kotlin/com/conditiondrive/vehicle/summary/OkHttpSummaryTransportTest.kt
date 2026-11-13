package com.conditiondrive.vehicle.summary

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OkHttpSummaryTransportTest {
    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.close()

    private fun credentials() = SummaryCredentials(server.url("/").toString(), "cdv_test")

    @Test
    fun `토큰을 Bearer로 보내고 본문과 상태를 돌려준다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"paired":false}""").build())
        val result = OkHttpSummaryTransport(OkHttpClient()).fetchLatest(credentials())
        val response = assertIs<TransportResult.Response>(result)
        assertEquals(200, response.status)
        assertEquals("""{"paired":false}""", response.body)
        val recorded = server.takeRequest()
        assertEquals("/v1/summary/latest", recorded.url.encodedPath)
        assertEquals("Bearer cdv_test", recorded.headers["Authorization"])
    }

    @Test
    fun `Retry-After를 읽는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
        val response = assertIs<TransportResult.Response>(OkHttpSummaryTransport(OkHttpClient()).fetchLatest(credentials()))
        assertEquals(429, response.status)
        assertEquals(120L, response.retryAfterSec)
    }

    @Test
    fun `연결 실패는 NetworkError이고 예외를 던지지 않는다`() = runBlocking {
        val closed = credentials()
        server.close()
        assertEquals(TransportResult.NetworkError, OkHttpSummaryTransport(OkHttpClient()).fetchLatest(closed))
    }

    /** 본문이 헤더와 다른 때 도착하면 읽기가 소켓을 건드린다. 메인 스레드에서 읽으면 정상 응답이 통신 실패가 된다 */
    @Test
    fun `응답 본문은 호출한 스레드가 아니라 IO에서 읽는다`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        var readThread: Thread? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentLength() = body.contentLength()
                override fun contentType() = body.contentType()
                override fun source(): okio.BufferedSource {
                    readThread = Thread.currentThread()
                    return body.source()
                }
            }).build()
        }.build()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }
        try {
            kotlinx.coroutines.runBlocking(caller.asCoroutineDispatcher()) {
                assertIs<TransportResult.Response>(OkHttpSummaryTransport(client).fetchLatest(credentials()))
            }
        } finally {
            caller.shutdown()
        }
        kotlin.test.assertNotEquals("caller", readThread?.name)
        kotlin.test.assertNotNull(readThread)
    }
}
