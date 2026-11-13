package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.AskCheckin
import com.conditiondrive.core.engine.AskStop
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.CheckinHeard
import com.conditiondrive.core.engine.EngineEvent
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.PromptAnswer
import com.conditiondrive.core.engine.PromptHeard
import com.conditiondrive.core.engine.PromptKind
import com.conditiondrive.core.engine.Say
import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.service.StopAdviceLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceQueueTest {
    private class Rig(scope: TestScope, speed: Double? = 80.0) {
        val clock = scope.virtualClock()
        val speaker = FakeSpeaker().also { it.now = clock::nowMs }
        val listener = FakeListener().also { it.now = clock::nowMs }
        val occupancy = FakeOccupancy()
        val signals = FakeSignals(speed = speed)
        val queue = VoiceQueue(speaker, listener, occupancy, signals, clock, scope.backgroundScope)
        val heard = mutableListOf<EngineEvent>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { queue.heard.collect { heard += it } }
            queue.start()
        }

        fun line(voice: VoiceId, vars: VoiceVars = VoiceVars()) =
            VoiceCatalog.render(voice, vars, PrivacyContext(passengerPresentOrUnknown = false))

        fun say(voice: VoiceId, vars: VoiceVars = VoiceVars(), atMs: Long = clock.nowMs()) =
            queue.enqueue(Say(voice, vars), vars, atMs)
    }

    private val drive = VoiceVars(driveSec = 6300)
    private val placed = VoiceVars(driveSec = 7200, distanceM = 3200, placeName = "칠곡졸음쉼터")

    @Test
    fun `V1 같은 시각 V02 V03은 순서대로`() = runTest {
        val r = Rig(this)
        val at = r.clock.nowMs()
        r.say(VoiceId.V02, drive, at)
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars(), at)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V02, drive), r.line(VoiceId.V03)), r.speaker.texts)
    }

    @Test
    fun `V2 V04 재생 중 V09가 오면 V04를 끊고 V09를 말한다`() = runTest {
        val r = Rig(this)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed)
        advanceTimeBy(500)
        runCurrent()
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200)
        r.say(VoiceId.V09, v09)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V04, placed), r.line(VoiceId.V09, v09)), r.speaker.texts)
        assertTrue(r.speaker.stops >= 1)
        // 끊긴 프롬프트는 폐기한다. 답을 듣지도, 응답을 만들지도 않는다
        assertTrue(r.heard.isEmpty())
        assertTrue(r.listener.listenAtMs.isEmpty())
    }

    @Test
    fun `V2 듣는 중에도 V09가 끊는다`() = runTest {
        val r = Rig(this)
        r.listener.listenMs = 8000
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars())
        advanceTimeBy(3000)
        runCurrent()
        assertTrue(r.queue.listening.value)
        r.say(VoiceId.V09N)
        runCurrent()
        assertFalse(r.queue.listening.value)
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(r.heard.isEmpty())
        assertEquals(r.line(VoiceId.V09N), r.speaker.texts.last())
    }

    @Test
    fun `V3 같은 시각 F 정차와 G 값 미만은 V09만`() = runTest {
        val r = Rig(this)
        r.say(VoiceId.V11, atMs = r.clock.nowMs() - 1)
        runCurrent()
        val at = r.clock.nowMs()
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed, at)
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200)
        r.say(VoiceId.V09, v09, at)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(r.line(VoiceId.V04, placed) in r.speaker.texts)
        assertTrue(r.line(VoiceId.V09, v09) in r.speaker.texts)

        // 순서가 반대여도 같다
        val later = r.clock.nowMs()
        r.say(VoiceId.V09, v09, later)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 2), placed, later)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(r.line(VoiceId.V04, placed) in r.speaker.texts)
    }

    @Test
    fun `합침 같은 시각 다른 순위는 높은 쪽 하나만`() = runTest {
        val r = Rig(this)
        r.say(VoiceId.V11, atMs = r.clock.nowMs() - 1)   // 재생을 하나 물려 대기 줄을 만든다
        runCurrent()
        val at = r.clock.nowMs()
        r.say(VoiceId.V31, atMs = at)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed, at)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(r.line(VoiceId.V31) in r.speaker.texts)
        assertTrue(r.line(VoiceId.V04, placed) in r.speaker.texts)
    }

    @Test
    fun `합침 예외 V16 다음 V01과 V02 다음 V08은 차례로 말한다`() = runTest {
        val r = Rig(this)
        r.say(VoiceId.V11, atMs = r.clock.nowMs() - 1)
        runCurrent()
        val at = r.clock.nowMs()
        r.say(VoiceId.V16, atMs = at)
        r.queue.enqueue(AskCheckin(VoiceId.V01, 1), VoiceVars(), at)
        advanceTimeBy(5000)
        runCurrent()
        val spoken = r.speaker.texts
        assertEquals(listOf(r.line(VoiceId.V16), r.line(VoiceId.V01)), spoken.drop(1).take(2))
    }

    @Test
    fun `V4 V02가 2분 넘게 밀리면 폐기하고 프롬프트는 폐기하지 않는다`() = runTest {
        val r = Rig(this)
        val long = FakeSpeaker(speakMs = 130_000).also { it.now = r.clock::nowMs }
        val queue = VoiceQueue(long, r.listener, r.occupancy, r.signals, r.clock, backgroundScope)
        queue.start()
        queue.enqueue(Say(VoiceId.V11), VoiceVars(remainingSec = 900))
        runCurrent()
        val at = r.clock.nowMs() + 1
        advanceTimeBy(1)
        queue.enqueue(Say(VoiceId.V02, drive), drive, at)
        advanceTimeBy(1)
        queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed)
        advanceTimeBy(140_000)
        runCurrent()
        assertFalse(r.line(VoiceId.V02, drive) in long.texts)
        assertTrue(r.line(VoiceId.V04, placed) in long.texts)
    }

    @Test
    fun `V5 R 기어면 멈추고 벗어나면 재개한다`() = runTest {
        val r = Rig(this)
        r.say(VoiceId.V31)
        advanceTimeBy(500)
        runCurrent()
        r.signals.gear.value = Gear.REVERSE
        runCurrent()
        assertTrue(r.speaker.stops >= 1)
        r.say(VoiceId.V12)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, r.speaker.spoken.size)      // R 동안 새로 말하지 않는다

        r.signals.gear.value = Gear.DRIVE
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(r.line(VoiceId.V31), r.speaker.texts[1])
        assertTrue(r.line(VoiceId.V12) in r.speaker.texts)
    }

    @Test
    fun `V6 혈당 STOP 계열은 마이크를 열지 않는다`() = runTest {
        val r = Rig(this)
        val vars = VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200)
        listOf(VoiceId.V09, VoiceId.V10, VoiceId.V17).forEach { r.say(it, vars) ; advanceTimeBy(5000) }
        runCurrent()
        assertEquals(3, r.speaker.spoken.size)
        assertTrue(r.listener.listenAtMs.isEmpty())
    }

    @Test
    fun `V7 TTS 완료 후 300ms 전에는 STT를 시작하지 않는다`() = runTest {
        val r = Rig(this)
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars())
        advanceTimeBy(2299)
        runCurrent()
        assertTrue(r.listener.listenAtMs.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, r.listener.listenAtMs.size)
        val spoken = r.speaker.spoken.single()
        assertEquals(spoken.endAtMs!! + 300, r.listener.listenAtMs.single())
    }

    @Test
    fun `V9 포커스 손실은 다시 큐에 넣어 말한다`() = runTest {
        val r = Rig(this)
        r.speaker.results += SpeakResult.INTERRUPTED
        r.say(VoiceId.V31)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V31), r.line(VoiceId.V31)), r.speaker.texts)
        assertFalse(r.queue.busy.value)
    }

    @Test
    fun `6_3 혈당 STOP 계열 포커스 거부는 헤드업을 한 번 내고 계속 시도한다`() = runTest {
        val r = Rig(this)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.speaker.results += SpeakResult.INTERRUPTED
        r.speaker.results += SpeakResult.INTERRUPTED
        r.say(VoiceId.V09N)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(VoiceId.V09N), undelivered)
        assertEquals(3, r.speaker.spoken.size)
        assertFalse(r.queue.busy.value)
    }

    @Test
    fun `V10 시동 OFF면 멈추고 버리며 다시 켜도 자동 재생하지 않는다`() = runTest {
        val r = Rig(this)
        r.say(VoiceId.V31)
        r.say(VoiceId.V12, atMs = r.clock.nowMs() + 1)
        advanceTimeBy(500)
        runCurrent()
        r.signals.ignition.value = Ignition.OFF
        runCurrent()
        assertFalse(r.queue.busy.value)
        // 꺼진 동안 도착한 음성도 받지 않는다
        r.say(VoiceId.V20)
        r.signals.ignition.value = Ignition.ON
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V31)), r.speaker.texts)
    }

    @Test
    fun `응답을 분류해 CheckinHeard와 PromptHeard로 낸다`() = runTest {
        val r = Rig(this)
        r.listener.answers += ListenResult.Heard("좀 피곤해")
        r.queue.enqueue(AskCheckin(VoiceId.V03, 7), VoiceVars())
        advanceTimeBy(5000)
        runCurrent()
        r.listener.answers += ListenResult.Heard("아니")
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 8), placed)
        advanceTimeBy(5000)
        runCurrent()
        val checkin = r.heard[0] as CheckinHeard
        assertEquals(7L, checkin.promptId)
        assertEquals(CheckinAnswer.TIRED, checkin.answer)
        val prompt = r.heard[1] as PromptHeard
        assertEquals(8L, prompt.promptId)
        assertEquals(PromptAnswer.DECLINE, prompt.answer)
    }

    @Test
    fun `E3 TTS와 STT 실패는 체크인과 프롬프트 모두 응답 없음이다`() = runTest {
        val r = Rig(this)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.speaker.results += SpeakResult.FAILED
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(CheckinAnswer.NO_SPEECH, (r.heard.single() as CheckinHeard).answer)
        assertEquals(listOf(VoiceId.V03), undelivered)

        r.listener.available.value = false
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 2), placed)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(PromptAnswer.NO_SPEECH, (r.heard.last() as PromptHeard).answer)
    }

    @Test
    fun `재시도 가능한 듣기 실패는 한 번 더 듣는다`() = runTest {
        val r = Rig(this)
        r.listener.answers += ListenResult.Failed(retriable = true)
        r.listener.answers += ListenResult.Heard("괜찮아")
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars())
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(2, r.listener.listenAtMs.size)
        assertEquals(CheckinAnswer.GOOD, (r.heard.single() as CheckinHeard).answer)
    }

    @Test
    fun `V11 장소 없는 프롬프트는 N 변형을 말하고 마이크를 열지 않는다`() = runTest {
        val r = Rig(this)
        val noPlace = VoiceVars(driveSec = 7200)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), noPlace)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V04N, noPlace)), r.speaker.texts)
        assertTrue(r.listener.listenAtMs.isEmpty())
        assertTrue(r.heard.isEmpty())
    }

    @Test
    fun `F11 동승 있음이나 모름이면 동승 변형, 혈당 STOP은 그대로`() = runTest {
        val r = Rig(this)
        val alone = VoiceCatalog.render(VoiceId.V14, VoiceVars(), PrivacyContext(false))
        val shared = VoiceCatalog.render(VoiceId.V14, VoiceVars(), PrivacyContext(true))
        r.say(VoiceId.V14)
        advanceTimeBy(3000)
        r.occupancy.passengerPresent.value = true
        r.say(VoiceId.V14)
        advanceTimeBy(3000)
        r.occupancy.passengerPresent.value = null
        r.say(VoiceId.V14)
        advanceTimeBy(3000)
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200)
        r.say(VoiceId.V09, v09)
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(listOf(alone, shared, shared, r.line(VoiceId.V09, v09)), r.speaker.texts)
    }

    @Test
    fun `P17 장소명의 괄호는 음성에서 지운다`() = runTest {
        val r = Rig(this)
        val vars = VoiceVars(driveSec = 7200, distanceM = 3200, placeName = "칠곡졸음쉼터(부산방향)")
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), vars)
        advanceTimeBy(3000)
        runCurrent()
        assertTrue("칠곡졸음쉼터에서" in r.speaker.texts.single())
        assertFalse("부산방향" in r.speaker.texts.single())
    }

    @Test
    fun `프롬프트 취소는 재생 중과 대기 중을 모두 끊는다`() = runTest {
        val r = Rig(this)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed)
        r.queue.enqueue(AskStop(VoiceId.V05, PromptKind.FATIGUE, 2), placed, r.clock.nowMs() + 5)
        advanceTimeBy(500)
        runCurrent()
        r.queue.cancelPendingPrompts()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, r.speaker.spoken.size)
        assertTrue(r.heard.isEmpty())
        assertFalse(r.queue.busy.value)
    }

    @Test
    fun `포트 예외로 큐가 멈추지 않는다`() = runTest {
        val r = Rig(this)
        val exploding = object : com.conditiondrive.vehicle.port.SpeakerPort by r.speaker {
            var first = true
            override suspend fun speak(text: String, priority: com.conditiondrive.core.voice.VoicePriority): SpeakResult {
                if (first) {
                    first = false
                    error("엔진 오류")
                }
                return r.speaker.speak(text, priority)
            }
        }
        val queue = VoiceQueue(exploding, r.listener, r.occupancy, r.signals, r.clock, backgroundScope)
        queue.start()
        queue.enqueue(Say(VoiceId.V31), VoiceVars())
        advanceTimeBy(1)
        queue.enqueue(Say(VoiceId.V12), VoiceVars())
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V12)), r.speaker.texts)
    }

    /** V6, D-21. V03이 대기 중인 채 V09가 와도 V09 뒤에 질문이 나오고 마이크가 열리면 안 된다. 시각이 달라도 같다 */
    @Test
    fun `V6 대기 중인 질문은 V09가 시각과 무관하게 지운다`() = runTest {
        val r = Rig(this)
        val at = r.clock.nowMs()
        r.say(VoiceId.V02, drive, at)
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars(), at)
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 2), placed, at)
        var yesNo = 0
        r.queue.askYesNo(VoiceId.V27, at) { yesNo++ }
        advanceTimeBy(500)
        runCurrent()
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터")
        r.say(VoiceId.V09, v09, at + 500)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(r.line(VoiceId.V09, v09), r.speaker.texts.last())
        assertTrue(r.listener.listenAtMs.isEmpty(), "V09 뒤에 마이크가 열렸다")
        assertEquals(0, yesNo)
        assertFalse(r.speaker.texts.any { "컨디션" in it })
    }

    /** 유일한 끊김 안내가 같은 시각의 V01에 지워지면 운전자는 혈당 감시가 켜져 있다고 믿고 달린다 */
    @Test
    fun `V18 V19 뒤의 음성은 순서대로 이어 말한다`() = runTest {
        for (next in listOf(VoiceId.V01, VoiceId.V20)) {
            val r = Rig(this)
            val at = r.clock.nowMs() + 60_000
            r.say(VoiceId.V19, atMs = at)
            if (next == VoiceId.V01) r.queue.enqueue(AskCheckin(next, 1), VoiceVars(), at) else r.say(next, atMs = at)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(listOf(r.line(VoiceId.V19), r.line(next)), r.speaker.texts.takeLast(2))
            r.queue.stop()
        }
    }

    @Test
    fun `V18 V19R 뒤의 V01도 이어 말한다`() = runTest {
        val r = Rig(this)
        val at = r.clock.nowMs() + 60_000
        r.say(VoiceId.V19R, atMs = at)
        r.queue.enqueue(AskCheckin(VoiceId.V01, 1), VoiceVars(), at)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V19R), r.line(VoiceId.V01)), r.speaker.texts)
    }

    /** 3분 통화 중 만료돼도 끊김 안내는 2분 폐기에 걸리지 않는다 */
    @Test
    fun `V18 V19는 폐기되지 않는다`() = runTest {
        val r = Rig(this)
        r.speaker.results += SpeakResult.DENIED
        r.speaker.results += SpeakResult.DENIED
        val at = r.clock.nowMs()
        r.say(VoiceId.V19, atMs = at)
        advanceTimeBy(1_500)
        runCurrent()
        // 2분이 지나도록 밀렸다고 보고 시각을 옛날로 넣어도 남는다
        r.say(VoiceId.V19R, atMs = at - 200_000)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(r.speaker.texts.count { it == r.line(VoiceId.V19) } >= 1)
        assertEquals(r.line(VoiceId.V19R), r.speaker.texts.last())
    }

    /** 통화 중 V09를 받아도 사라지지 않는다. 첫 거부에 헤드업 1회, 끝나면 말한다 */
    @Test
    fun `V9 V09 거부는 헤드업 1회 뒤 재시도한다`() = runTest {
        val r = Rig(this)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.speaker.results += SpeakResult.DENIED
        r.speaker.results += SpeakResult.DENIED
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터")
        r.say(VoiceId.V09, v09)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(VoiceId.V09), undelivered)
        assertEquals(3, r.speaker.texts.size)
        assertEquals(SpeakResult.DONE, r.speaker.spoken.last().result)
    }

    /** V17은 엔진 반복이 없다. 거부로 버리면 출발 보류를 영영 듣지 못한다 */
    @Test
    fun `V9 V17 거부도 재시도하고 헤드업을 알린다`() = runTest {
        val r = Rig(this)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.speaker.results += SpeakResult.DENIED
        r.say(VoiceId.V17)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(VoiceId.V17), undelivered)
        assertEquals(2, r.speaker.texts.size)
        assertEquals(r.line(VoiceId.V17), r.speaker.texts.last())
    }

    @Test
    fun `통화 중 쌓인 V10은 대기 중인 V09를 넘어 줄줄이 나오지 않는다`() = runTest {
        val r = Rig(this)
        repeat(3) { r.speaker.results += SpeakResult.DENIED }
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터")
        r.say(VoiceId.V09, v09)
        advanceTimeBy(500)
        runCurrent()
        r.say(VoiceId.V10, v09)
        r.say(VoiceId.V10, v09)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(r.line(VoiceId.V09, v09), r.speaker.texts.last())
        assertFalse(r.speaker.texts.any { it == r.line(VoiceId.V10, v09) })
    }

    /** 10.2 듣지 못하면 묻지 않는다. 대답이 언제나 버려지는 질문을 하면 안 된다 */
    @Test
    fun `V17 듣기 불가면 프롬프트를 평서문으로 말한다`() = runTest {
        val r = Rig(this)
        r.listener.available.value = false
        r.queue.enqueue(AskStop(VoiceId.V04, PromptKind.FATIGUE, 1), placed)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V04O, placed)), r.speaker.texts)
        assertFalse("?" in r.speaker.texts.single())
        assertTrue(r.listener.listenAtMs.isEmpty())
        assertEquals(PromptAnswer.NO_SPEECH, (r.heard.single() as PromptHeard).answer)
    }

    @Test
    fun `V17 듣기 불가면 체크인은 말하지 않고 응답 없음으로 끝낸다`() = runTest {
        val r = Rig(this)
        r.listener.available.value = false
        r.queue.enqueue(AskCheckin(VoiceId.V01, 1), VoiceVars())
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.speaker.texts.isEmpty(), "${r.speaker.texts}")
        assertEquals(CheckinAnswer.NO_SPEECH, (r.heard.single() as CheckinHeard).answer)
    }

    /** 물었지만 인식이 실패했다. 듣지 못한 답을 "보통"으로 적으면 S2가 없는 응답을 보여 준다 */
    @Test
    fun `듣다가 인식이 실패한 체크인은 보통이 아니라 응답 없음이다`() = runTest {
        val r = Rig(this)
        r.listener.answers += ListenResult.Failed(retriable = false)
        r.queue.enqueue(AskCheckin(VoiceId.V01, 1), VoiceVars())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CheckinAnswer.NO_SPEECH, (r.heard.single() as CheckinHeard).answer)
    }

    @Test
    fun `V17 듣기 불가면 V27은 묻지 않는다`() = runTest {
        val r = Rig(this)
        r.listener.available.value = false
        var answer: PromptAnswer? = null
        r.queue.askYesNo(VoiceId.V27) { answer = it }
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.speaker.texts.isEmpty())
        assertEquals(PromptAnswer.NO_SPEECH, answer)
    }

    /** 마이크 준비에 1~2초 걸린다. 그 전에 "듣고 있어요"를 띄우면 안 된다 */
    @Test
    fun `V7 듣고 있어요는 마이크 준비 뒤에 켜진다`() = runTest {
        val r = Rig(this)
        r.listener.readyAfterMs = 1500
        r.listener.listenMs = 2000
        r.queue.enqueue(AskCheckin(VoiceId.V03, 1), VoiceVars())
        advanceTimeBy(2_000 + 300 + 1000)
        runCurrent()
        assertEquals(1, r.listener.listenAtMs.size)
        assertFalse(r.queue.listening.value, "마이크 준비 전이다")
        advanceTimeBy(600)
        runCurrent()
        assertTrue(r.queue.listening.value)
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(r.queue.listening.value)
    }

    /** V21. 통화로 밀린 사이 차속이 바뀔 수 있다. 큐에 넣을 때가 아니라 말하는 순간의 차속으로 고른다 */
    @Test
    fun `V21 장소 없는 혈당 정차 음성은 말하는 순간의 차속으로 고속 변형을 고른다`() = runTest {
        val r = Rig(this, speed = 30.0)
        r.speaker.results += SpeakResult.INTERRUPTED
        r.say(VoiceId.V09)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V09N)), r.speaker.texts)
        r.signals.speedKmh.value = 80.0
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(r.line(VoiceId.V09N), r.line(VoiceId.V09H)), r.speaker.texts)

        r.say(VoiceId.V10U)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last())

        // 차속 신호가 잠깐 끊겨도 마지막 판단을 둔다. 고속도로 위에서 일반 도로 문구로 바뀌면 안 된다
        r.signals.speedKmh.value = null
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last())
    }

    /** 처음부터 차속을 모르면 일반 도로로 본다. 일반 도로에서 나들목을 찾으라고 하면 정차가 늦어진다 */
    @Test
    fun `V21 차속을 처음부터 모르면 N 변형이다`() = runTest {
        val r = Rig(this, speed = null)
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10N), r.speaker.texts.last())
    }

    /** V-07. 고속도로가 막혀 60km/h로 떨어져도 고속 문구를 유지한다. 70 언저리를 오가도 지시가 번갈아 바뀌지 않는다 */
    @Test
    fun `V21 80에서 60으로 정체돼도 V10H를 유지한다`() = runTest {
        val r = Rig(this, speed = 80.0)
        r.signals.speedKmh.value = 60.0
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last())
        for (speed in listOf(69.9, 71.0, 65.0, 35.0)) {
            r.signals.speedKmh.value = speed
            advanceTimeBy(2 * 60_000L)
            r.say(VoiceId.V10)
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last(), "$speed")
        }
    }

    /** 30km/h 미만이 5분 이어지면 고속도로를 벗어났다고 본다 */
    @Test
    fun `V21 저속이 5분 이어지면 N 변형으로 돌아간다`() = runTest {
        val r = Rig(this, speed = 90.0)
        r.signals.speedKmh.value = 20.0
        advanceTimeBy(HIGHWAY_RELEASE_MS - 10_000)
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10H), r.speaker.texts.last(), "4분 55초")
        advanceTimeBy(10_000)
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10N), r.speaker.texts.last(), "5분 지남")
    }

    /** P에 들어가면 고속 판정을 푼다. 다시 출발하면 그때 차속으로 판단한다 */
    @Test
    fun `V21 P에 들어가면 고속 판정이 풀린다`() = runTest {
        val r = Rig(this, speed = 100.0)
        r.signals.gear.value = Gear.PARK
        r.signals.speedKmh.value = 0.0
        runCurrent()
        r.signals.gear.value = Gear.DRIVE
        r.signals.speedKmh.value = 50.0
        r.say(VoiceId.V10)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V10N), r.speaker.texts.last())
    }

    /** 장소가 있으면 고속이어도 원래 문구다. 피로 N 변형은 차속과 무관하다 */
    @Test
    fun `V21 장소가 있거나 피로 음성이면 차속과 무관하다`() = runTest {
        val r = Rig(this)
        r.signals.speedKmh.value = 100.0
        val v09 = VoiceVars(placeName = "칠곡졸음쉼터", distanceM = 3200)
        r.say(VoiceId.V09, v09)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V09, v09), r.speaker.texts.last())
        r.say(VoiceId.V04N, drive)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(r.line(VoiceId.V04N, drive), r.speaker.texts.last())
    }

    @Test
    fun `V21 말하지 못한 고속 변형도 헤드업 대상으로 나간다`() = runTest {
        val r = Rig(this)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.signals.speedKmh.value = 90.0
        r.speaker.results += SpeakResult.FAILED
        r.say(VoiceId.V09)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(VoiceId.V09H), undelivered)
        assertTrue(VoiceId.V09H in StopAdviceLog.STOP_ADVICE)
        assertTrue(VoiceId.V10H in StopAdviceLog.STOP_ADVICE)
        assertEquals(setOf(VoiceId.V09H, VoiceId.V10H), StopAdviceLog.HIGHWAY_STOP)
    }

    /** 통화로 포커스를 잃어 헤드업으로 대신할 때도 고른 변형을 넘긴다. 헤드업이 "나간 뒤"를 적을 수 있게 한다 */
    @Test
    fun `V21 포커스를 잃은 고속 변형은 고른 변형으로 헤드업에 넘긴다`() = runTest {
        val r = Rig(this, speed = 90.0)
        val undelivered = mutableListOf<VoiceId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.queue.undelivered.collect { undelivered += it } }
        r.speaker.results += SpeakResult.DENIED
        r.say(VoiceId.V10)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(VoiceId.V10H), undelivered)
    }
}
