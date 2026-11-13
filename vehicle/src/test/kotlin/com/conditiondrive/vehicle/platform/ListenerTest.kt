package com.conditiondrive.vehicle.platform

import com.conditiondrive.vehicle.port.ListenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ListenerTest {
    @Test fun partialResultsAreIgnored() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext))
        session.onReady()
        session.onUpdated("피", completed = false)
        assertFalse(session.result.isCompleted)
        session.onUpdated("피곤해", completed = true)
        assertEquals(ListenResult.Heard("피곤해"), session.result.await())
    }

    /** 실측 순서: onEnded가 먼저, completed=true가 뒤다. 끝 신호로 NoSpeech를 내면 답을 버린다 */
    @Test fun finalAfterEndIsStillHeard() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext))
        session.onReady()
        session.onEnded()
        advanceTimeBy(50); runCurrent()
        session.onUpdated("괜찮아", completed = true)
        assertEquals(ListenResult.Heard("괜찮아"), session.result.await())
    }

    @Test fun endWithoutFinalBecomesNoSpeech() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext), endGraceMs = 1000)
        session.onEnded()
        advanceTimeBy(1001); runCurrent()
        assertEquals(ListenResult.NoSpeech, session.result.await())
    }

    @Test fun blankFinalIsNoSpeech() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext))
        session.onUpdated("  ", completed = true)
        assertEquals(ListenResult.NoSpeech, session.result.await())
    }

    @Test fun errorsKeepRetryClass() = runTest {
        val fatal = ListenSession(CoroutineScope(coroutineContext))
        fatal.onError("ON_DEVICE_ENGINE_ERROR")
        assertEquals(ListenResult.Failed(retriable = false), fatal.result.await())
        val retry = ListenSession(CoroutineScope(coroutineContext))
        retry.onError("TIMEOUT")
        assertEquals(ListenResult.Failed(retriable = true), retry.result.await())
    }

    /** 마이크 경로의 stop()은 콜백을 주지 않는다(실측). 시간이 지나면 스스로 끝나야 한다 */
    @Test fun silentMicEndsAfterTimeoutMeasuredFromReady() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext), endGraceMs = 1000)
        var stops = 0
        val job = async { session.await(timeoutMs = 8000, readyTimeoutMs = 4000) { stops++ } }
        advanceTimeBy(1500); runCurrent()
        session.onReady()
        advanceTimeBy(7999); runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(0, stops)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, stops)
        advanceTimeBy(1000); runCurrent()
        assertEquals(ListenResult.NoSpeech, job.await())
    }

    @Test fun answerDuringGraceAfterTimeoutCounts() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext), endGraceMs = 1000)
        val job = async { session.await(8000, 4000) { session.onUpdated("응", completed = true) } }
        session.onReady()
        advanceTimeBy(8001); runCurrent()
        assertEquals(ListenResult.Heard("응"), job.await())
    }

    private class FakeStt(
        override val kind: SpeechEngineKind,
        private val prepared: Boolean = true,
        vararg results: ListenResult,
    ) : SttEngine {
        private val queue = ArrayDeque(results.toList())
        var listens = 0
        var stops = 0
        override suspend fun prepare() = prepared
        override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult {
            listens++
            onReady()
            return queue.removeFirstOrNull() ?: ListenResult.NoSpeech
        }
        override fun stop() { stops++ }
        override fun release() = Unit
    }

    @Test fun fatalFallsThroughSameQuestion() = runTest {
        val focus = FakeFocus()
        val pleos = FakeStt(SpeechEngineKind.PLEOS, true, ListenResult.Failed(false))
        val android = FakeStt(SpeechEngineKind.ANDROID, true, ListenResult.Heard("아니"))
        val chain = ChainListener(listOf(pleos, android), focus, CoroutineScope(coroutineContext))
        assertEquals(ListenResult.Heard("아니"), chain.listen(8000))
        assertEquals(FocusPurpose.LISTEN, focus.lastPurpose)
        assertTrue(focus.released >= 1)
        assertEquals(SpeechEngineKind.ANDROID, chain.activeEngine.value)
    }

    @Test fun retriableRetriesOnce() = runTest {
        val pleos = FakeStt(SpeechEngineKind.PLEOS, true, ListenResult.Failed(true), ListenResult.Heard("응"))
        val chain = ChainListener(listOf(pleos), FakeFocus(), CoroutineScope(coroutineContext))
        assertEquals(ListenResult.Heard("응"), chain.listen(8000))
        assertEquals(2, pleos.listens)
    }

    @Test fun noEnginesMeansOneWayGuidance() = runTest {
        val chain = ChainListener(listOf(FakeStt(SpeechEngineKind.PLEOS, false)), FakeFocus(), CoroutineScope(coroutineContext))
        assertEquals(ListenResult.Failed(retriable = false), chain.listen(8000))
        assertEquals(false, chain.available.value)
    }

    @Test fun focusDeniedStillListens() = runTest {
        val pleos = FakeStt(SpeechEngineKind.PLEOS, true, ListenResult.NoSpeech)
        val chain = ChainListener(listOf(pleos), FakeFocus(grant = false), CoroutineScope(coroutineContext))
        assertEquals(ListenResult.NoSpeech, chain.listen(8000))
        assertEquals(1, pleos.listens)
    }

    /** 8초 대기 앞에 준비 신호가 온다. "듣고 있어요"와 시작음이 여기에 걸린다 */
    @Test fun onReadyFiresAfterMicIsReady() = runTest {
        val session = ListenSession(CoroutineScope(coroutineContext))
        var ready = 0
        val job = async { session.await(8000, 4000, onReady = { ready++ }) {} }
        advanceTimeBy(1500); runCurrent()
        assertEquals(0, ready)
        session.onReady()
        runCurrent()
        assertEquals(1, ready)
        session.onUpdated("응", completed = true)
        assertEquals(ListenResult.Heard("응"), job.await())
    }

    /** 재시도해도 시작음은 한 번이다 */
    @Test fun readyCueOncePerQuestion() = runTest {
        var cues = 0
        var ready = 0
        val pleos = FakeStt(SpeechEngineKind.PLEOS, true, ListenResult.Failed(true), ListenResult.Heard("응"))
        val chain = ChainListener(listOf(pleos), FakeFocus(), CoroutineScope(coroutineContext), readyCue = { cues++ })
        assertEquals(ListenResult.Heard("응"), chain.listen(8000) { ready++ })
        assertEquals(1, cues)
        assertEquals(1, ready)
    }

    /** 권한이 생기면 다음 질문에 다시 산다 */
    @Test fun deniedEngineRevivesWhenPermissionArrives() = runTest {
        var allowed = false
        val stt = object : SttEngine {
            override val kind = SpeechEngineKind.PLEOS
            override suspend fun prepare() = allowed
            override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult = ListenResult.Heard("응")
            override fun stop() = Unit
            override fun release() = Unit
        }
        val chain = ChainListener(listOf(stt), FakeFocus(), CoroutineScope(coroutineContext), nowMs = { 0 })
        assertFalse(chain.ready())
        assertFalse(chain.available.value)
        allowed = true
        chain.revive()
        assertTrue(chain.available.value)
        assertTrue(chain.ready())
        assertEquals(ListenResult.Heard("응"), chain.listen(8000))
    }

    @Test fun failedEngineIsRetriedAfterInterval() = runTest {
        var now = 0L
        val pleos = FakeStt(SpeechEngineKind.PLEOS, true, ListenResult.Failed(false), ListenResult.Heard("응"))
        val chain = ChainListener(listOf(pleos), FakeFocus(), CoroutineScope(coroutineContext), nowMs = { now }, retryAfterMs = 60_000)
        assertEquals(ListenResult.Failed(retriable = false), chain.listen(8000))
        assertFalse(chain.ready())
        now = 61_000
        assertTrue(chain.ready())
        assertEquals(ListenResult.Heard("응"), chain.listen(8000))
    }

    /** 끝나지 않는 듣기. 실제 마이크처럼 stop을 받아야만 닫힌다 */
    private class OpenMicStt : SttEngine {
        override val kind = SpeechEngineKind.PLEOS
        var stops = 0
        override suspend fun prepare() = true
        override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult {
            onReady()
            awaitCancellation()
        }
        override fun stop() { stops++ }
        override fun release() = Unit
    }

    /** 실측 결함(2026-11-06): 온보딩 듣기 시험 중 Back이 코루틴만 취소해 Pleos 녹음이 97초 이어졌다. 취소되면 엔진 마이크를 닫아야 한다 */
    @Test fun cancellingListenStopsEngineMic() = runTest {
        val focus = FakeFocus()
        val mic = OpenMicStt()
        val chain = ChainListener(listOf(mic), focus, CoroutineScope(coroutineContext))
        val job = async { chain.listen(8000) }
        runCurrent()
        assertEquals(0, mic.stops)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, mic.stops)
        assertTrue(focus.released >= 1)
    }

    /** VoiceQueue는 끊을 때 stopNow 뒤 작업을 취소한다. 엔진 stop은 한 번만 간다 */
    @Test fun stopNowThenCancelSendsOneStop() = runTest {
        val mic = OpenMicStt()
        val chain = ChainListener(listOf(mic), FakeFocus(), CoroutineScope(coroutineContext))
        val job = async { chain.listen(8000) }
        runCurrent()
        chain.stopNow()
        job.cancel()
        runCurrent()
        assertEquals(1, mic.stops)
    }
}
