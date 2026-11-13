package com.conditiondrive.vehicle.platform

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U-27 지침서 ②: Android TTS 엔진 큐 안의 선행 묵음. 실제 엔진이 Pleos 에뮬레이터에 없어 큐 호출만 본다 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeadSilenceUtteranceTest {
    private class RecordingQueue(private val silenceResult: Int = TextToSpeech.SUCCESS, private val clock: () -> Long = { 0L }) : TtsQueue {
        val calls = mutableListOf<String>()
        val spokeAt = mutableListOf<Long>()
        override fun silence(durationMs: Long, queueMode: Int, utteranceId: String): Int {
            calls += "silence $durationMs $queueMode $utteranceId"
            return silenceResult
        }
        override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
            calls += "speak $text $queueMode $utteranceId"
            spokeAt += clock()
            return TextToSpeech.SUCCESS
        }
    }

    private fun TestScope.timedQueue(silenceResult: Int = TextToSpeech.SUCCESS) =
        RecordingQueue(silenceResult) { testScheduler.currentTime }

    /** 묵음을 넣었으면 기다리지 않고 바로 ADD로 붙인다. 기다리면 묵음이 두 번이다 */
    @Test fun enqueueWithSilenceDoesNotWait() = runTest {
        val queue = timedQueue()
        assertTrue(LeadSilenceUtterance("e1").enqueue(queue, "안내", LEAD_SILENCE_MS))
        assertEquals(listOf(0L), queue.spokeAt)
        assertEquals(listOf("silence 150 ${TextToSpeech.QUEUE_FLUSH} e1-silence", "speak 안내 ${TextToSpeech.QUEUE_ADD} e1"), queue.calls)
    }

    /** 묵음을 못 넣으면 ③으로 150ms 기다린 뒤 FLUSH로 말한다. 한 번만 기다린다 */
    @Test fun enqueueWithoutSilenceWaitsOnceThenFlushes() = runTest {
        val queue = timedQueue(silenceResult = TextToSpeech.ERROR)
        assertTrue(LeadSilenceUtterance("e2").enqueue(queue, "안내", LEAD_SILENCE_MS))
        assertEquals(listOf(LEAD_SILENCE_MS), queue.spokeAt)
        assertEquals("speak 안내 ${TextToSpeech.QUEUE_FLUSH} e2", queue.calls.last())
    }

    /** 묵음 0이면 묵음 호출 없이 FLUSH로 바로 말한다 */
    @Test fun enqueueWithZeroSilenceSpeaksAtOnce() = runTest {
        val queue = timedQueue()
        assertTrue(LeadSilenceUtterance("e3").enqueue(queue, "안내", 0L))
        assertEquals(listOf("speak 안내 ${TextToSpeech.QUEUE_FLUSH} e3"), queue.calls)
        assertEquals(listOf(0L), queue.spokeAt)
    }

    /** ③으로 기다리는 중 멈추면(취소) 말하지 않는다 */
    @Test fun cancelDuringFallbackWaitSkipsSpeaking() = runTest {
        val queue = timedQueue(silenceResult = TextToSpeech.ERROR)
        val job = launch { LeadSilenceUtterance("e4").enqueue(queue, "안내", LEAD_SILENCE_MS) }
        advanceTimeBy(100); runCurrent()
        job.cancel()
        advanceTimeBy(500); runCurrent()
        assertTrue(queue.spokeAt.isEmpty())
        assertEquals(1, queue.calls.size)
    }

    /** 묵음이 앞 요청을 비우고 본 발화가 그 뒤에 붙는다. 본 발화가 FLUSH면 묵음을 지운다 */
    @Test fun silenceFlushesThenSpeechIsAdded() {
        val queue = RecordingQueue()
        val utterance = LeadSilenceUtterance("u1")
        val silenced = utterance.enqueueSilence(queue, LEAD_SILENCE_MS)
        assertTrue(silenced)
        assertTrue(utterance.enqueueSpeech(queue, "안내", afterSilence = silenced))
        assertEquals(
            listOf(
                "silence 150 ${TextToSpeech.QUEUE_FLUSH} u1-silence",
                "speak 안내 ${TextToSpeech.QUEUE_ADD} u1",
            ),
            queue.calls,
        )
    }

    /** 묵음을 넣지 못한 엔진에서 ADD로 붙이면 앞 요청 뒤에 밀린다. 그때는 본 발화가 FLUSH다 */
    @Test fun failedSilenceSpeaksWithFlush() {
        val queue = RecordingQueue(silenceResult = TextToSpeech.ERROR)
        val utterance = LeadSilenceUtterance("u2")
        val silenced = utterance.enqueueSilence(queue, LEAD_SILENCE_MS)
        assertFalse(silenced)
        utterance.enqueueSpeech(queue, "안내", afterSilence = silenced)
        assertEquals("speak 안내 ${TextToSpeech.QUEUE_FLUSH} u2", queue.calls.last())
    }

    /** 묵음 발화가 끝났다고 요청이 끝난 것이 아니다. 그걸 완료로 보면 음성 큐가 본 발화 중에 다음 음성을 넣는다 */
    @Test fun onlySpeechDoneCompletes() {
        val utterance = LeadSilenceUtterance("u3")
        assertNull(utterance.onDone("u3-silence"))
        assertEquals(TtsOutcome.Done, utterance.onDone("u3"))
        assertNull(utterance.onDone("other"))
    }

    /** 묵음 중에 멈추거나 오류가 나도 그 요청의 결과다. 다른 요청의 콜백은 무시한다 */
    @Test fun stopOrErrorDuringSilenceEndsRequest() {
        val utterance = LeadSilenceUtterance("u4")
        assertEquals(TtsOutcome.Stopped, utterance.onStop("u4-silence"))
        assertEquals(TtsOutcome.Stopped, utterance.onStop("u4"))
        assertEquals(TtsOutcome.Failed(SpeechFailure.FATAL), utterance.onError("u4-silence"))
        assertNull(utterance.onStop("old"))
        assertNull(utterance.onError("old"))
    }
}
