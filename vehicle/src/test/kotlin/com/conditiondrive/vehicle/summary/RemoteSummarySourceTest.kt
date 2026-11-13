package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.runtime.SCENARIO_NOON
import com.conditiondrive.vehicle.runtime.virtualClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSummarySourceTest {
    private class FakeLink(credentials: SummaryCredentials?) : SummaryLink {
        override val credentials = MutableStateFlow(credentials)
        var revoked = 0
        var unpaired = 0
        override suspend fun onTokenRevoked() {
            revoked++
            credentials.value = null
        }
        override suspend fun onPhoneUnpaired() {
            unpaired++
            credentials.value = null
        }
    }

    private val creds = SummaryCredentials("http://10.0.2.2:8080", "cdv_test")

    private class Rig(scope: TestScope, credentials: SummaryCredentials?, ignition: Ignition = Ignition.ON, random: Random = Random(1)) {
        val calls = mutableListOf<Long>()
        val responses = ArrayDeque<TransportResult>()
        var fallback: TransportResult = TransportResult.NetworkError
        var hangMs = 0L
        val clock = scope.virtualClock()
        val link = FakeLink(credentials)
        val ignition = MutableStateFlow(ignition)
        val transport = SummaryTransport {
            calls += clock.nowMs()
            if (hangMs > 0) delay(hangMs)
            responses.removeFirstOrNull() ?: fallback
        }
        val source = RemoteSummarySource(transport, link, this.ignition, clock, scope.backgroundScope, random)
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** 서버 시계가 serverNow인 응답. 혈당은 측정 후 ttl 동안 유효 */
    private fun body(serverNow: Long, mmol: Double? = 6.4, ttlSec: Long = 870, source: String = "simulated", paired: Boolean = true): String {
        val measured = serverNow - 30_000
        val generated = serverNow - 20_000
        val glucose = if (mmol == null) {
            """"glucose_mmol":null,"glucose_trend":null,"glucose_source":null,"glucose_measured_at":null"""
        } else {
            """"glucose_mmol":$mmol,"glucose_trend":"falling","glucose_source":"$source","glucose_measured_at":"${iso(measured)}""""
        }
        return """{"paired":$paired,"phone_name":"폰","server_time":"${iso(serverNow)}","received_at":"${iso(serverNow - 10_000)}","summary":{
            "generated_at":"${iso(generated)}","valid_until":"${iso(measured + ttlSec * 1000)}",
            "sleep_minutes_last_night":290,"sleep_short":true,"sleep_valid_until":"${iso(generated + 3_600_000)}",$glucose}}"""
    }

    @Test
    fun `V16 시동 OFF면 호출 0회, 켜지면 즉시 1회`() = runTest {
        val r = Rig(this, creds, Ignition.OFF)
        r.source.start()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(r.calls.isEmpty())
        r.ignition.value = Ignition.ON
        runCurrent()
        assertEquals(1, r.calls.size)
    }

    @Test
    fun `V16 E7 미연결이면 호출 0회이고 1단계로 표시`() = runTest {
        val r = Rig(this, null)
        r.source.start()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(r.calls.isEmpty())
        assertTrue(r.source.snapshot.value.unpaired)
        assertEquals(LinkState.UNPAIRED, r.source.link.value)
    }

    @Test
    fun `V16 실패 간격은 15 30 60 60`() = runTest {
        val r = Rig(this, creds)
        r.source.start()
        runCurrent()
        advanceTimeBy(180_000)
        runCurrent()
        val gaps = r.calls.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(15L, 30L, 60L, 60L), gaps.take(4))
        assertEquals(LinkState.OFFLINE, r.source.link.value)
    }

    @Test
    fun `성공하면 60초 ±5초로 돌아간다`() = runTest {
        val r = Rig(this, creds)
        r.fallback = TransportResult.Response(200, body(SCENARIO_NOON))
        r.source.start()
        runCurrent()
        advanceTimeBy(600_000)
        runCurrent()
        val gaps = r.calls.zipWithNext { a, b -> (b - a) / 1000 }
        assertTrue(gaps.size >= 8)
        assertTrue(gaps.all { it in 55..65 }, gaps.toString())
        assertTrue(gaps.toSet().size > 1, "지터가 있어야 한다")
    }

    @Test
    fun `첫 조회는 3초 제한이다`() = runTest {
        val r = Rig(this, creds)
        r.hangMs = 60_000
        r.source.start()
        runCurrent()
        assertEquals(1, r.calls.size)
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(LinkState.OFFLINE, r.source.link.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, r.calls.size)
    }

    @Test
    fun `V14 만료는 기기 시계와 무관하게 valid_until - server_time으로 계산한다`() = runTest {
        // 서버 시계가 기기(엔진)보다 3일 앞서 있어도 남은 시간은 같다
        val skew = 3 * 86_400_000L
        val r = Rig(this, creds)
        val serverNow = SCENARIO_NOON + skew
        r.responses += TransportResult.Response(200, body(serverNow))
        r.source.start()
        runCurrent()
        val input = assertNotNull(r.source.snapshot.value.input)
        val engineNow = r.clock.nowMs()
        assertEquals(engineNow + (870_000 - 30_000), input.glucoseExpiresAtMs)
        assertEquals(engineNow - 30_000, input.glucoseMeasuredAtMs)
        assertEquals(engineNow - 20_000 + 3_600_000, input.sleepExpiresAtMs)
        assertEquals(290, input.sleepMinutes)
        assertEquals(engineNow - 10_000, r.source.snapshot.value.receivedAtMs)
        assertEquals("폰", r.source.snapshot.value.phoneName)
        assertEquals(LinkState.FRESH, r.source.link.value)
    }

    @Test
    fun `V15 401이면 토큰과 요약을 지우고 1단계로 간다`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(200, body(SCENARIO_NOON))
        r.fallback = TransportResult.Response(401, "")
        r.source.start()
        runCurrent()
        assertNotNull(r.source.snapshot.value.input)
        advanceTimeBy(70_000)
        runCurrent()
        assertEquals(1, r.link.revoked)
        assertNull(r.source.snapshot.value.input)
        assertTrue(r.source.snapshot.value.unpaired)
        val calls = r.calls.size
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(calls, r.calls.size)
    }

    @Test
    fun `paired=false면 폰 연결만 끊고 호출을 멈춘다`() = runTest {
        val r = Rig(this, creds)
        r.fallback = TransportResult.Response(200, """{"paired":false,"server_time":"${iso(SCENARIO_NOON)}","summary":null}""")
        r.source.start()
        runCurrent()
        assertEquals(1, r.link.unpaired)
        assertTrue(r.source.snapshot.value.unpaired)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, r.calls.size)
    }

    @Test
    fun `통신 끊김은 상태 변화 없음, 마지막 값 유지`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(200, body(SCENARIO_NOON))
        r.source.start()
        runCurrent()
        val before = r.source.snapshot.value
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(before, r.source.snapshot.value)
        assertEquals(LinkState.OFFLINE, r.source.link.value)
    }

    @Test
    fun `429는 Retry-After를 지키고 최대 5분이다`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(429, "", retryAfterSec = 120)
        r.responses += TransportResult.Response(429, "", retryAfterSec = 9999)
        r.fallback = TransportResult.Response(200, body(SCENARIO_NOON))
        r.source.start()
        runCurrent()
        advanceTimeBy(500_000)
        runCurrent()
        val gaps = r.calls.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(120L, 300L), gaps.take(2))
    }

    @Test
    fun `5xx는 백오프다`() = runTest {
        val r = Rig(this, creds)
        r.fallback = TransportResult.Response(503, "")
        r.source.start()
        runCurrent()
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(2, r.calls.size)
    }

    @Test
    fun `summary null은 값 없음으로 전달한다`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(200, body(SCENARIO_NOON))
        r.responses += TransportResult.Response(200, """{"paired":true,"phone_name":"폰","server_time":"${iso(SCENARIO_NOON + 60_000)}","received_at":null,"summary":null}""")
        r.source.start()
        runCurrent()
        assertNotNull(r.source.snapshot.value.input)
        advanceTimeBy(70_000)
        runCurrent()
        assertNull(r.source.snapshot.value.input)
        assertFalse(r.source.snapshot.value.unpaired)
    }

    @Test
    fun `F7-9 S20 알 수 없는 source면 혈당만 지우고 수면은 살린다`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(200, body(SCENARIO_NOON, source = "health_connect"))
        r.source.start()
        runCurrent()
        val input = assertNotNull(r.source.snapshot.value.input)
        assertNull(input.glucoseMmol)
        assertEquals(290, input.sleepMinutes)
    }

    @Test
    fun `응답에 새 필드가 있어도 읽는다`() = runTest {
        val r = Rig(this, creds)
        val withExtra = body(SCENARIO_NOON).replaceFirst("{", """{"future_field":1,""")
        r.responses += TransportResult.Response(200, withExtra)
        r.source.start()
        runCurrent()
        assertEquals(6.4, r.source.snapshot.value.input?.glucoseMmol)
    }

    @Test
    fun `깨진 응답은 끊김과 같게 보고 값 없음으로 만들지 않는다`() = runTest {
        val r = Rig(this, creds)
        r.responses += TransportResult.Response(200, body(SCENARIO_NOON))
        r.responses += TransportResult.Response(200, "{깨짐")
        r.source.start()
        runCurrent()
        val before = r.source.snapshot.value
        advanceTimeBy(70_000)
        runCurrent()
        assertEquals(before, r.source.snapshot.value)
        assertEquals(LinkState.OFFLINE, r.source.link.value)
    }

    @Test
    fun `시동이 꺼지면 폴링을 멈춘다`() = runTest {
        val r = Rig(this, creds)
        r.fallback = TransportResult.Response(200, body(SCENARIO_NOON))
        r.source.start()
        runCurrent()
        val calls = r.calls.size
        r.ignition.value = Ignition.OFF
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(calls, r.calls.size)
    }

    @Test
    fun `S5에서 연결하면 호출을 시작한다`() = runTest {
        val r = Rig(this, null)
        r.fallback = TransportResult.Response(200, body(SCENARIO_NOON))
        r.source.start()
        runCurrent()
        r.link.credentials.value = creds
        runCurrent()
        assertEquals(1, r.calls.size)
        assertEquals(6.4, r.source.snapshot.value.input?.glucoseMmol)
    }

    /** 폰 쪽 해제와 401은 사용자가 직접 끊은 것이 아니다. 엔진이 값 없음 경로로 처리하게 표시한다 */
    @Test
    fun `V19 paired=false와 401은 linkLost로 표시한다`() = runTest {
        for (status in listOf(401, 200)) {
            val r = Rig(this, creds)
            val text = if (status == 200) """{"paired":false,"server_time":"${iso(r.clock.nowMs())}"}""" else ""
            r.responses += TransportResult.Response(status, text)
            r.source.start()
            advanceTimeBy(1)
            runCurrent()
            assertTrue(r.source.snapshot.value.linkLost, "status=$status")
            assertEquals(LinkState.UNPAIRED, r.source.link.value)
            r.source.stop()
        }
    }

    @Test
    fun `E33 처음부터 연결이 없으면 linkLost가 아니다`() = runTest {
        val r = Rig(this, null)
        r.source.start()
        runCurrent()
        assertFalse(r.source.snapshot.value.linkLost)
        assertTrue(r.source.snapshot.value.unpaired)
        r.source.stop()
    }

    @Test
    fun `E33 S5에서 끊으면 linkLost가 아니다`() = runTest {
        val r = Rig(this, creds)
        r.fallback = TransportResult.Response(200, body(r.clock.nowMs()))
        r.source.start()
        advanceTimeBy(1)
        runCurrent()
        r.link.credentials.value = null
        runCurrent()
        assertFalse(r.source.snapshot.value.linkLost)
        assertTrue(r.source.snapshot.value.unpaired)
        r.source.stop()
    }
}
