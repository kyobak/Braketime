package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.ListenResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V12의 F10 표. F10-5, F10-6은 엔진이 효과를 내지 않는 것으로 TransitionTableTest가 본다 */
@OptIn(ExperimentalCoroutinesApi::class)
class VentilationControllerTest {
    private val v27 = VoiceCatalog.render(VoiceId.V27, VoiceVars(), PrivacyContext(false))

    private class Rig(scope: TestScope, val port: FakeVentilation?) {
        val clock = scope.virtualClock()
        val speaker = FakeSpeaker().also { it.now = clock::nowMs }
        val listener = FakeListener().also { it.now = clock::nowMs }
        val voice = VoiceQueue(speaker, listener, FakeOccupancy(), FakeSignals(), clock, scope.backgroundScope).also { it.start() }
        val controller = VentilationController(port, voice, clock, scope.backgroundScope)
    }

    @Test
    fun `F10-1 지원하면 V27로 묻는다`() = runTest {
        val r = Rig(this, FakeVentilation())
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(v27), r.speaker.texts)
        assertEquals(1, r.listener.listenAtMs.size)
        assertEquals(true, r.controller.supported.value)
    }

    @Test
    fun `F10-2 응이면 FRESH로 바꾸고 5분 뒤 복원`() = runTest {
        val port = FakeVentilation()
        val r = Rig(this, port)
        r.listener.answers += ListenResult.Heard("응")
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(AirMode.FRESH), port.sets)
        // 답을 받은 시각은 재생 2초 + 0.3초 + 듣기 0.1초다. 거기서 5분을 잰다
        advanceTimeBy(297_300)
        runCurrent()
        assertEquals(listOf(AirMode.FRESH), port.sets)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf(AirMode.FRESH, AirMode.RECIRCULATE), port.sets)
    }

    @Test
    fun `F10-3 운전자가 직접 바꾸면 복원하지 않는다`() = runTest {
        val port = FakeVentilation()
        val r = Rig(this, port)
        r.listener.answers += ListenResult.Heard("응")
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        port.changes.tryEmit(AirMode.RECIRCULATE)
        runCurrent()
        advanceTimeBy(400_000)
        runCurrent()
        assertEquals(listOf(AirMode.FRESH), port.sets)
    }

    @Test
    fun `F10-4 미지원이면 묻지 않고 S5 표시만 남긴다`() = runTest {
        val r = Rig(this, FakeVentilation(isSupported = false))
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(r.speaker.texts.isEmpty())
        assertEquals(false, r.controller.supported.value)
    }

    @Test
    fun `포트가 없어도 조용히 지원 안 함`() = runTest {
        val r = Rig(this, null)
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(r.speaker.texts.isEmpty())
        assertEquals(false, r.controller.supported.value)
    }

    @Test
    fun `무응답과 거절이면 아무것도 바꾸지 않는다`() = runTest {
        val port = FakeVentilation()
        val r = Rig(this, port)
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        r.listener.answers += ListenResult.Heard("아니")
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(port.sets.isEmpty())
    }

    @Test
    fun `이미 바깥 공기면 묻지 않는다`() = runTest {
        val r = Rig(this, FakeVentilation(mode = AirMode.FRESH))
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        assertFalse(v27 in r.speaker.texts)
    }

    @Test
    fun `시동이 꺼지면 복원을 기다리지 않고 되돌린다`() = runTest {
        val port = FakeVentilation()
        val r = Rig(this, port)
        r.listener.answers += ListenResult.Heard("응")
        r.controller.offer()
        advanceTimeBy(5000)
        runCurrent()
        r.controller.stop()
        assertEquals(listOf(AirMode.FRESH, AirMode.RECIRCULATE), port.sets)
    }

    /** 재검증: 콘솔 하트비트가 없을 때 "응"이라고 답해도 값이 그대로이고 아무 안내가 없었다 */
    @Test
    fun `응 뒤에 값이 안 바뀌면 V27F로 알리고 복원하지 않는다`() = runTest {
        val port = FakeVentilation(setWorks = false)
        val r = Rig(this, port)
        r.listener.answers += ListenResult.Heard("응")
        r.controller.offer()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(v27, VoiceCatalog.render(VoiceId.V27F, VoiceVars(), PrivacyContext(false))), r.speaker.texts)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(listOf(AirMode.FRESH), port.sets)
    }
}
