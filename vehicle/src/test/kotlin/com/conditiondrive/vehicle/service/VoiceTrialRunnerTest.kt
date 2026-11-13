package com.conditiondrive.vehicle.service

import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.runtime.FakeListener
import com.conditiondrive.vehicle.runtime.FakeSpeaker
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O3 "말해 보기". 재검증: 마이크 권한이 없는데 "지금 컨디션은 어떠세요?"를 말한 뒤 "알아듣지 못했어요"라고 답했다 */
class VoiceTrialRunnerTest {
    private val speaker = FakeSpeaker(0)
    private val listener = FakeListener()
    private val runner = VoiceTrialRunner(speaker, listener, listenDelayMs = 0)

    @Test
    fun `들을 수 없으면 묻지 않는다`() = runTest {
        listener.available.value = false
        var opened = false
        assertEquals(VoiceTrial.Unavailable, runner.run { opened = true })
        assertTrue(speaker.texts.isEmpty(), speaker.texts.toString())
        assertTrue(listener.listenAtMs.isEmpty())
        assertTrue(!opened)
    }

    @Test
    fun `들을 수 있으면 묻고 분류한다`() = runTest {
        listener.answers += ListenResult.Heard("피곤해")
        var opened = false
        assertEquals(VoiceTrial.Heard(CheckinAnswer.TIRED), runner.run { opened = true })
        assertEquals(1, speaker.texts.size)
        assertTrue("어떠세요" in speaker.texts.single())
        assertTrue(opened)
    }

    @Test
    fun `물었는데 답이 없으면 결과 없음이다`() = runTest {
        assertEquals(VoiceTrial.Heard(null), runner.run {})
    }

    @Test
    fun `말하지 못하면 듣지 않는다`() = runTest {
        speaker.results += SpeakResult.FAILED
        assertEquals(VoiceTrial.Heard(null), runner.run {})
        assertTrue(listener.listenAtMs.isEmpty())
    }
}
