package com.conditiondrive.vehicle.service

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairingClientTest {
    private val server = MockWebServer()
    private val client = PairingClient(OkHttpClient(), "0.1.0")
    private val codeBody =
        """{"code":"K7M2QX9T","qr_payload":"cd://pair?c=K7M2QX9T","expires_at":"2026-11-01T00:05:00Z","ttl_sec":300,"replaces_phone":false,"server_time":"2026-11-01T00:00:00Z"}"""
    private val registerBody = """{"account_id":"a","device_id":"d","vehicle_token":"new-token","server_time":"2026-11-01T00:00:00Z"}"""

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun enqueue(status: Int, body: String = "{}") {
        server.enqueue(MockResponse.Builder().code(status).body(body).build())
    }

    @Before
    fun up() = server.start()

    @After
    fun down() = server.close()

    @Test
    fun `저장된 토큰이 유효하면 그대로 쓴다`() = runBlocking {
        enqueue(201, codeBody)
        val issued = assertNotNull(client.issueCode(base, "old-token"))
        assertEquals("old-token", issued.token)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `서버 DB가 초기화되어 401이면 다시 등록하고 코드를 받는다`() = runBlocking {
        enqueue(401)
        enqueue(201, registerBody)
        enqueue(201, codeBody)
        val issued = assertNotNull(client.issueCode(base, "old-token"))
        assertEquals("new-token", issued.token)
        assertEquals("K7M2QX9T", issued.code.code)
    }

    @Test
    fun `서버 오류는 토큰을 버리지 않고 실패로 끝난다`() = runBlocking {
        enqueue(500)
        assertNull(client.issueCode(base, "old-token"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `토큰이 없으면 등록부터 한다`() = runBlocking {
        enqueue(201, registerBody)
        enqueue(201, codeBody)
        assertEquals("new-token", client.issueCode(base, null)?.token)
    }

    /** 이미 지워졌으면 바라던 상태다. 다시 시도할 이유가 없다 */
    @Test
    fun `삭제는 2xx 401 404를 끝으로 보고 그 외는 실패다`() = runBlocking {
        enqueue(200)
        kotlin.test.assertTrue(client.unpair(base, "t"))
        enqueue(401)
        kotlin.test.assertTrue(client.deleteAccount(base, "t"))
        enqueue(404)
        kotlin.test.assertTrue(client.unpair(base, "t"))
        enqueue(500)
        kotlin.test.assertFalse(client.deleteAccount(base, "t"))
    }
}
