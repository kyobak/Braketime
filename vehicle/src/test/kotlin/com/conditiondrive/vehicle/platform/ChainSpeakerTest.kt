package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.vehicle.port.SpeakResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeTts(
    override val kind: SpeechEngineKind,
    private val prepared: Boolean = true,
    private val outcomes: ArrayDeque<TtsOutcome?> = ArrayDeque(),
    // 실제 엔진과 같은 배정: Pleos는 ③ 재생 지연, Android TTS는 ② 엔진 큐 묵음
    override val leadSilence: LeadSilence = if (kind == SpeechEngineKind.ANDROID) LeadSilence.IN_ENGINE else LeadSilence.PLAYER_DELAY,
    private val clock: () -> Long = { 0L },
) : TtsEngine {
    override val ready: StateFlow<Boolean> = MutableStateFlow(prepared)
    var said = 0
    var stops = 0
    var released = 0
    val texts = mutableListOf<String>()
    val silences = mutableListOf<Long>()
    val saidAt = mutableListOf<Long>()
    private var pending: CompletableDeferred<TtsOutcome>? = null

    override suspend fun prepare() = prepared

    override suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome {
        said++
        texts += text
        silences += leadSilenceMs
        saidAt += clock()
        outcomes.removeFirstOrNull()?.let { return it }
        val d = CompletableDeferred<TtsOutcome>()
        pending = d
        return d.await()
    }

    override fun stop() {
        stops++
        pending?.complete(TtsOutcome.Stopped)
    }

    override fun release() {
        released++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChainSpeakerTest {
    private fun TestScope.chain(vararg engines: TtsEngine, focus: FocusPort = FakeFocus()) =
        ChainSpeaker(engines.toList(), focus, CoroutineScope(coroutineContext))

    @Test fun doneAndFocusReleased() = runTest {
        val focus = FakeFocus()
        val tts = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Done)))
        val result = chain(tts, focus = focus).speak("안내", VoicePriority.NOTICE)
        assertEquals(SpeakResult.DONE, result)
        assertEquals(FocusPurpose.SPEAK, focus.lastPurpose)
        assertEquals(1, focus.acquired)
        assertTrue(focus.released >= 1)
    }

    /** ③ Pleos는 오디오를 만질 수 없어 요청을 150ms 늦춘다. 엔진에는 묵음을 넘기지 않는다(두 번 들어가면 300ms다) */
    @Test fun playerDelayEngineWaitsBeforeSpeaking() = runTest {
        val tts = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(tts)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(149); runCurrent()
        assertEquals(0, tts.said)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, tts.said)
        assertEquals(listOf(0L), tts.silences)
        speaker.stopNow()
        job.await()
    }

    /** ② Android TTS는 엔진 큐 안에서 묵음을 넣는다. 체인은 기다리지 않고 묵음 길이를 넘긴다 */
    @Test fun inEngineSilenceIsPassedNotWaited() = runTest {
        val tts = FakeTts(SpeechEngineKind.ANDROID, clock = { testScheduler.currentTime })
        val speaker = chain(tts)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        runCurrent()
        assertEquals(1, tts.said)
        assertEquals(listOf(0L), tts.saidAt)
        assertEquals(listOf(LEAD_SILENCE_MS), tts.silences)
        speaker.stopNow()
        job.await()
    }

    /** 한 발화는 나누지 않고 한 번에 넘긴다. 문장 사이에 묵음이 끼지 않는다(연속 음성에는 추가 묵음 없음) */
    @Test fun oneUtteranceGetsOneLeadSilence() = runTest {
        val text = "2시간째 운전 중이에요. 3.2킬로미터 앞 칠곡휴게소에서 쉬어 갈까요?"
        val tts = FakeTts(SpeechEngineKind.ANDROID, outcomes = ArrayDeque(listOf(TtsOutcome.Done)))
        assertEquals(SpeakResult.DONE, chain(tts).speak(text, VoicePriority.NOTICE))
        assertEquals(listOf(text), tts.texts)
        assertEquals(listOf(LEAD_SILENCE_MS), tts.silences)
    }

    /** 새 발화는 새 세션이라 다시 넣는다. 대체 엔진으로 넘어가도 그 엔진의 방식으로 한 번 더 넣는다 */
    @Test fun eachPlaybackStartGetsItsOwnSilence() = runTest {
        val now = { testScheduler.currentTime }
        val pleos = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.FATAL))), clock = now)
        val android = FakeTts(SpeechEngineKind.ANDROID, outcomes = ArrayDeque(listOf(TtsOutcome.Done, TtsOutcome.Done)), clock = now)
        val speaker = chain(pleos, android)
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(listOf(150L), pleos.saidAt)
        // Pleos가 기다린 150ms에 더해 기다리지 않는다. Android 엔진이 자기 큐에서 넣는다
        assertEquals(listOf(150L), android.saidAt)
        assertEquals(listOf(LEAD_SILENCE_MS), android.silences)
        assertEquals(SpeakResult.DONE, speaker.speak("다음 안내", VoicePriority.NOTICE))
        assertEquals(listOf(LEAD_SILENCE_MS, LEAD_SILENCE_MS), android.silences)
    }

    /** 재시도는 앞 재생이 끝난 뒤의 새 재생이다 */
    @Test fun retryWaitsAgain() = runTest {
        val pleos = FakeTts(
            SpeechEngineKind.PLEOS,
            outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.RETRY_ONCE), TtsOutcome.Done)),
            clock = { testScheduler.currentTime },
        )
        assertEquals(SpeakResult.DONE, chain(pleos).speak("안내", VoicePriority.NOTICE))
        assertEquals(listOf(150L, 300L), pleos.saidAt)
    }

    /** 묵음을 기다리는 중에 멈추면 말하지 않고 바로 끝난다. 150ms를 다 기다리지 않는다 */
    @Test fun stopDuringLeadSilenceSkipsSpeaking() = runTest {
        val tts = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(tts)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(100); runCurrent()
        speaker.stopNow()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(SpeakResult.INTERRUPTED, job.await())
        assertEquals(0, tts.said)
        // 아직 speak하지 않은 엔진에 stop을 보내지 않는다. Pleos는 재생이 없어도 onStop을 보내 다음 발화를 끊을 수 있다
        assertEquals(0, tts.stops)
        assertEquals(100L, testScheduler.currentTime)
    }

    /** ② 엔진의 재시도도 새 재생이라 엔진 큐 묵음을 한 번씩 받는다. 체인은 기다리지 않는다 */
    @Test fun inEngineRetryGetsSilenceAgain() = runTest {
        val android = FakeTts(
            SpeechEngineKind.ANDROID,
            outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.RETRY_ONCE), TtsOutcome.Done)),
            clock = { testScheduler.currentTime },
        )
        assertEquals(SpeakResult.DONE, chain(android).speak("안내", VoicePriority.NOTICE))
        assertEquals(listOf(LEAD_SILENCE_MS, LEAD_SILENCE_MS), android.silences)
        assertEquals(listOf(0L, 0L), android.saidAt)
    }

    /** ② 엔진이 말하는 중 멈추면 그 엔진을 멈추고 INTERRUPTED로 끝난다 */
    @Test fun stopDuringInEngineUtterance() = runTest {
        val android = FakeTts(SpeechEngineKind.ANDROID)
        val speaker = chain(android)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        runCurrent()
        assertEquals(1, android.said)
        speaker.stopNow()
        runCurrent()
        assertEquals(SpeakResult.INTERRUPTED, job.await())
        assertTrue(android.stops >= 1)
    }

    /** 부른 쪽이 말하기 코루틴을 취소하면(온보딩 듣기 시험 중 Back) 엔진도 멈추고 포커스를 놓는다. 포커스만 놓으면 소리가 겹친다 */
    @Test fun cancellingSpeakStopsEngine() = runTest {
        val focus = FakeFocus()
        val pleos = FakeTts(SpeechEngineKind.PLEOS)
        val job = async { chain(pleos, focus = focus).speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(LEAD_SILENCE_MS); runCurrent()
        assertEquals(1, pleos.said)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, pleos.stops)
        assertTrue(focus.released >= 1)
    }

    /** 묵음 대기 중 취소는 아직 말하지 않았으므로 stop을 보내지 않는다(늦은 onStop이 다음 발화를 끊지 않게) */
    @Test fun cancellingDuringLeadSilenceSendsNoStop() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS)
        val job = async { chain(pleos).speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(100); runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(0, pleos.said)
        assertEquals(0, pleos.stops)
    }

    /** VoiceQueue는 끊을 때 stopNow 뒤 작업을 취소한다. 엔진 stop은 한 번만 간다(Pleos는 stop마다 onStop을 보낸다) */
    @Test fun stopNowThenCancelSendsOneStop() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(pleos)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(LEAD_SILENCE_MS); runCurrent()
        assertEquals(1, pleos.said)
        speaker.stopNow()
        job.cancel()
        runCurrent()
        assertEquals(1, pleos.stops)
    }

    /** 재시도의 묵음 대기 중 멈추면 앞 시도의 엔진이 남아 있어도 아직 말하지 않은 엔진에 stop을 보내지 않는다 */
    @Test fun stopDuringRetryLeadSilenceSendsNoStop() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.RETRY_ONCE))))
        val speaker = chain(pleos)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(LEAD_SILENCE_MS + 50); runCurrent()
        assertEquals(1, pleos.said)
        speaker.stopNow()
        runCurrent()
        assertEquals(SpeakResult.INTERRUPTED, job.await())
        assertEquals(1, pleos.said)
        assertEquals(0, pleos.stops)
    }

    /** 지침서 범위 150~200ms 밖의 값으로는 만들 수 없다 */
    @Test fun leadSilenceOutsideGuideRangeIsRejected() = runTest {
        for (ms in listOf(0L, 149L, 201L)) {
            assertFailsWith<IllegalArgumentException> {
                ChainSpeaker(emptyList(), FakeFocus(), CoroutineScope(coroutineContext), leadSilenceMs = ms)
            }
        }
        for (ms in listOf(150L, 200L)) ChainSpeaker(emptyList(), FakeFocus(), CoroutineScope(coroutineContext), leadSilenceMs = ms)
        assertTrue(LEAD_SILENCE_MS in LEAD_SILENCE_RANGE)
    }

    @Test fun fatalFallsThroughToNextEngineAndStaysDead() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.FATAL))))
        val android = FakeTts(SpeechEngineKind.ANDROID, outcomes = ArrayDeque(listOf(TtsOutcome.Done, TtsOutcome.Done)))
        val speaker = chain(pleos, android)
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(1, pleos.released)
        assertEquals(SpeechEngineKind.ANDROID, speaker.activeEngine.value)
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(1, pleos.said)
        assertEquals(2, android.said)
    }

    @Test fun networkErrorRetriesOnceOnSameEngine() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.RETRY_ONCE), TtsOutcome.Done)))
        assertEquals(SpeakResult.DONE, chain(pleos).speak("안내", VoicePriority.NOTICE))
        assertEquals(2, pleos.said)
    }

    @Test fun secondRetryFailureFallsThrough() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.RETRY_ONCE), TtsOutcome.Failed(SpeechFailure.RETRY_ONCE))))
        val android = FakeTts(SpeechEngineKind.ANDROID, outcomes = ArrayDeque(listOf(TtsOutcome.Done)))
        assertEquals(SpeakResult.DONE, chain(pleos, android).speak("안내", VoicePriority.NOTICE))
        assertEquals(2, pleos.said)
    }

    @Test fun allEnginesDeadIsFailedAndUnavailable() = runTest {
        val speaker = chain(FakeTts(SpeechEngineKind.PLEOS, prepared = false), FakeTts(SpeechEngineKind.ANDROID, prepared = false))
        assertEquals(SpeakResult.FAILED, speaker.speak("안내", VoicePriority.GLUCOSE_STOP))
        assertEquals(false, speaker.available.value)
        assertEquals(SpeechEngineKind.NONE, speaker.activeEngine.value)
    }

    /** V9 LOSS_TRANSIENT: 정지, 포기, 재큐잉(큐는 INTERRUPTED를 보고 다시 넣는다) */
    @Test fun focusLossStopsAndReturnsInterrupted() = runTest {
        val focus = FakeFocus()
        val tts = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(tts, focus = focus)
        val job = async { speaker.speak("안내", VoicePriority.PROMPT) }
        advanceTimeBy(200); runCurrent()
        focus.onLoss!!(FocusLoss.TRANSIENT)
        assertEquals(SpeakResult.INTERRUPTED, job.await())
        assertTrue(tts.stops >= 1)
        assertTrue(focus.released >= 1)
    }

    /** 6.3 거부는 엔진 실패가 아니다. STOP 계열도 큐가 다시 시도할 수 있게 구분해 돌려준다 */
    @Test fun focusDeniedIsDistinctFromFailure() = runTest {
        val focus = FakeFocus(grant = false)
        val tts = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(tts, focus = focus)
        assertEquals(SpeakResult.DENIED, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(SpeakResult.DENIED, speaker.speak("안내", VoicePriority.GLUCOSE_STOP))
        assertEquals(0, tts.said)
    }

    /** 대체 엔진이 없는 환경(실측)에서 오류 한 번으로 영구히 말하지 못하면 안 된다 */
    @Test fun failedEngineIsRetriedAfterInterval() = runTest {
        var now = 0L
        val tts = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.FATAL), TtsOutcome.Done)))
        val speaker = ChainSpeaker(listOf(tts), FakeFocus(), CoroutineScope(coroutineContext), nowMs = { now }, retryAfterMs = 60_000)
        assertEquals(SpeakResult.FAILED, speaker.speak("안내", VoicePriority.NOTICE))
        now = 59_000
        assertEquals(SpeakResult.FAILED, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(1, tts.said)
        now = 61_000
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(SpeechEngineKind.PLEOS, speaker.activeEngine.value)
    }

    /** 시동 ON에는 기다리지 않고 다시 한다 */
    @Test fun reviveRestoresImmediately() = runTest {
        val now = 0L
        val tts = FakeTts(SpeechEngineKind.PLEOS, outcomes = ArrayDeque(listOf(TtsOutcome.Failed(SpeechFailure.FATAL), TtsOutcome.Done)))
        val speaker = ChainSpeaker(listOf(tts), FakeFocus(), CoroutineScope(coroutineContext), nowMs = { now })
        assertEquals(SpeakResult.FAILED, speaker.speak("안내", VoicePriority.NOTICE))
        speaker.revive()
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
    }

    /** 초기화가 5초를 넘는 것은 오류가 아니라 늦는 것이다. 해제하면 곧 끝날 초기화까지 잃는다 */
    @Test fun slowPrepareDoesNotReleaseEngine() = runTest {
        var now = 0L
        var prepared = false
        val tts = object : TtsEngine {
            override val kind = SpeechEngineKind.PLEOS
            override val ready: StateFlow<Boolean> = MutableStateFlow(false)
            override val leadSilence = LeadSilence.PLAYER_DELAY
            var released = 0
            override suspend fun prepare() = prepared
            override suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome = TtsOutcome.Done
            override fun stop() = Unit
            override fun release() { released++ }
        }
        val speaker = ChainSpeaker(listOf(tts), FakeFocus(), CoroutineScope(coroutineContext), nowMs = { now }, retryAfterMs = 60_000)
        assertEquals(SpeakResult.FAILED, speaker.speak("안내", VoicePriority.NOTICE))
        assertEquals(0, tts.released)
        prepared = true
        now = 61_000
        assertEquals(SpeakResult.DONE, speaker.speak("안내", VoicePriority.NOTICE))
    }

    @Test fun stopNowInterrupts() = runTest {
        val tts = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(tts)
        val job = async { speaker.speak("안내", VoicePriority.NOTICE) }
        advanceTimeBy(200); runCurrent()
        speaker.stopNow()
        assertEquals(SpeakResult.INTERRUPTED, job.await())
    }

    /** 완료 콜백이 오지 않는 SDK에서 음성 큐가 멈추면 이후 안내가 모두 사라진다 */
    @Test fun watchdogEndsSilentEngine() = runTest {
        val pleos = FakeTts(SpeechEngineKind.PLEOS)
        val speaker = chain(pleos)
        val job = async { speaker.speak("가나다", VoicePriority.NOTICE) }
        advanceTimeBy(150 + 3 * 350 + 10_000 + 1); runCurrent()
        assertEquals(SpeakResult.FAILED, job.await())
        assertTrue(pleos.stops >= 1)
    }

    @Test fun throwingEngineIsContained() = runTest {
        val broken = object : TtsEngine {
            override val kind = SpeechEngineKind.PLEOS
            override val ready: StateFlow<Boolean> = MutableStateFlow(false)
            override val leadSilence = LeadSilence.PLAYER_DELAY
            override suspend fun prepare(): Boolean = throw NoClassDefFoundError("sdk")
            override suspend fun say(text: String, leadSilenceMs: Long): TtsOutcome = TtsOutcome.Done
            override fun stop() = Unit
            override fun release() = throw SecurityException("no")
        }
        val android = FakeTts(SpeechEngineKind.ANDROID, outcomes = ArrayDeque(listOf(TtsOutcome.Done)))
        assertEquals(SpeakResult.DONE, chain(broken, android).speak("안내", VoicePriority.NOTICE))
    }
}
