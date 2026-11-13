package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.AskCheckin
import com.conditiondrive.core.engine.AskStop
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.engine.CheckinHeard
import com.conditiondrive.core.engine.Effect
import com.conditiondrive.core.engine.EngineEvent
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.PromptAnswer
import com.conditiondrive.core.engine.PromptHeard
import com.conditiondrive.core.engine.Say
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceClassifier
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 6.3 음성 큐. 엔진은 무엇을 말할지만 정하고, 언제 끊고 언제 버릴지는 전부 여기서 정한다. */
class VoiceQueue(
    private val speaker: SpeakerPort,
    private val listener: ListenerPort,
    private val occupancy: OccupancyPort,
    private val signals: VehicleSignalsPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val policy: Policy = Policy.DEFAULT,
    val highway: HighwayGuess = HighwayGuess(clock),
) {
    private sealed interface Reply {
        data object None : Reply
        data class Checkin(val promptId: Long) : Reply
        data class Prompt(val promptId: Long) : Reply
        data class YesNo(val onAnswer: (PromptAnswer) -> Unit) : Reply
    }

    private class Item(
        val voice: VoiceId,
        val vars: VoiceVars,
        val reply: Reply,
        val atMs: Long,
        val seq: Long,
        var rank: VoicePriority,
        var escalated: Boolean = false,
        // 마지막으로 고른 변형(V09H 등). 말하지 못해 헤드업으로 대신할 때 같은 지시를 보이려는 것이다
        var resolved: VoiceId = voice,
    )

    private enum class Interrupt { PREEMPTED, CANCELLED, REVERSE }

    private val lock = Any()
    private val pending = mutableListOf<Item>()
    private var playing: Item? = null
    private var playJob: Job? = null
    private var interrupt: Interrupt? = null
    private var nextSeq = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val jobs = mutableListOf<Job>()

    private val _heard = MutableSharedFlow<EngineEvent>(extraBufferCapacity = HEARD_BUFFER)
    val heard: SharedFlow<EngineEvent> = _heard

    private val _busy = MutableStateFlow(false)
    /** 큐에 남았거나 재생·듣기 중이면 true. 시연 시계를 멈추는 기준이다 */
    val busy: StateFlow<Boolean> = _busy

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val _current = MutableStateFlow<VoiceId?>(null)
    val current: StateFlow<VoiceId?> = _current

    private val _undelivered = MutableSharedFlow<VoiceId>(extraBufferCapacity = HEARD_BUFFER)
    /** 끝내 말하지 못한 음성. 서비스가 헤드업 알림으로 대신한다(13장 TTS 실패) */
    val undelivered: SharedFlow<VoiceId> = _undelivered

    fun start() {
        if (jobs.isNotEmpty()) return
        occupancy.start()
        // 기어와 시동은 스케줄을 기다리지 않고 바로 끊어야 해서 Unconfined로 받는다
        jobs += scope.launch(Dispatchers.Unconfined) { signals.gear.collect(::onGear) }
        jobs += scope.launch(Dispatchers.Unconfined) {
            signals.ignition.collect {
                highway.onIgnition(it)
                if (it == Ignition.OFF) cancelAll()
            }
        }
        jobs += scope.launch(Dispatchers.Unconfined) { signals.speedKmh.collect { highway.onSpeed(it) } }
        jobs += scope.launch { runWorker() }
    }

    fun stop() {
        cancelAll()
        jobs.forEach { it.cancel() }
        jobs.clear()
        occupancy.stop()
    }

    /** resolvedVars는 런타임이 장소까지 채운 값이다. atMs가 같은 음성끼리만 합친다 */
    fun enqueue(effect: Effect, resolvedVars: VoiceVars, atMs: Long = clock.nowMs()) {
        when (effect) {
            is Say -> add(effect.voice, resolvedVars, Reply.None, atMs)
            is AskCheckin -> add(effect.voice, resolvedVars, Reply.Checkin(effect.promptId), atMs)
            is AskStop -> add(effect.voice, resolvedVars, Reply.Prompt(effect.promptId), atMs)
            else -> Unit
        }
    }

    /** V27처럼 엔진 전이와 무관한 질문. 답은 엔진으로 가지 않는다(17장) */
    fun askYesNo(voice: VoiceId, atMs: Long = clock.nowMs(), onAnswer: (PromptAnswer) -> Unit) {
        add(voice, VoiceVars(), Reply.YesNo(onAnswer), atMs)
    }

    fun cancelAll() {
        synchronized(lock) {
            pending.clear()
            publish()
        }
        interruptPlaying(Interrupt.CANCELLED)
    }

    /** CancelVoice. 대기 중인 음성은 건드리지 않는다. 바로 뒤의 V09가 ①로 대기 중인 질문까지 정리한다 */
    fun cancelCurrent() = interruptPlaying(Interrupt.CANCELLED)

    /** CancelPendingPrompts. 재생 중인 프롬프트도 끊는다. 답을 들어도 엔진이 이미 버린 질문이다 */
    fun cancelPendingPrompts() {
        val cutPlaying = synchronized(lock) {
            pending.removeAll { it.reply is Reply.Prompt || it.reply is Reply.YesNo }
            publish()
            playing?.reply.let { it is Reply.Prompt || it is Reply.YesNo }
        }
        if (cutPlaying) interruptPlaying(Interrupt.CANCELLED)
    }

    /** 6.3 P 상태. 주행 중 전용 음성이 줄에 남아 정차한 뒤 나오면 안 된다 */
    fun dropDrivingOnly() {
        synchronized(lock) {
            if (pending.removeAll { it.voice in DRIVING_ONLY }) publish()
        }
    }

    private fun add(voice: VoiceId, vars: VoiceVars, reply: Reply, atMs: Long) {
        // 재개 시 자동 재생 금지(6.3). 시동 꺼진 동안 도착한 음성은 받지 않는다
        if (signals.ignition.value == Ignition.OFF) return
        val rank = VoiceCatalog.priority(voice)
        val preempt = synchronized(lock) {
            val item = Item(voice, vars, reply, atMs, nextSeq++, rank)
            val added = if (rank == VoicePriority.GLUCOSE_STOP) addGlucoseStop(item) else addMerged(item)
            publish()
            added && rank == VoicePriority.GLUCOSE_STOP && playing?.rank.let { it != null && it != VoicePriority.GLUCOSE_STOP }
        }
        if (preempt) interruptPlaying(Interrupt.PREEMPTED)
        wake.trySend(Unit)
    }

    private fun addGlucoseStop(item: Item): Boolean {
        // 통화로 V09가 밀리는 동안 2분마다 V10이 쌓이면 끝나고 줄줄이 나온다. 대기 중인 ①이 먼저다
        if (item.voice in GLUCOSE_REPEATS && pending.any { it.rank == VoicePriority.GLUCOSE_STOP }) return false
        // 시각이 달라도 대기 중인 질문은 지운다. V09 뒤에 "컨디션은 어떠세요?"가 나오고 마이크가 열리면 안 된다(D-21)
        pending.removeAll { it.reply !is Reply.None || (it.atMs == item.atMs && it.rank != VoicePriority.GLUCOSE_STOP) }
        pending += item
        return true
    }

    /** 6.3 합침. 같은 시각의 ②~④는 하나로 줄인다. 차례로 말해야 하는 짝은 앞 음성의 순위를 물려받아 순서를 지킨다 */
    private fun addMerged(item: Item): Boolean {
        val sameTime = pending.filter { it.atMs == item.atMs }
        val stopAtSameTime = sameTime.any { it.rank == VoicePriority.GLUCOSE_STOP } ||
            playing?.let { it.atMs == item.atMs && it.rank == VoicePriority.GLUCOSE_STOP } == true
        if (stopAtSameTime) return false
        for (other in sameTime) {
            when {
                // 끊김 안내는 같은 시각의 어떤 음성에도 지워지면 안 된다(6.3). 뒤 음성이 이어서 나온다
                other.voice in LOSS_NOTICES || (other.voice to item.voice) in SEQUENTIAL -> item.rank = other.rank
                other.rank < item.rank -> return false
                else -> pending -= other     // 순위가 같으면 나중 음성이 더 새로운 사실이다
            }
        }
        pending += item
        return true
    }

    private fun interruptPlaying(reason: Interrupt) {
        val job = synchronized(lock) {
            if (playing == null) return
            interrupt = reason
            playJob
        }
        speaker.stopNow()
        listener.stopNow()
        job?.cancel()
    }

    private fun onGear(gear: Gear) {
        highway.onGear(gear)
        if (gear == Gear.REVERSE) interruptPlaying(Interrupt.REVERSE) else wake.trySend(Unit)
    }

    private suspend fun runWorker() {
        while (true) {
            val item = awaitNext()
            when (play(item)) {
                PlayEnd.FINISHED -> Unit
                PlayEnd.REQUEUE -> requeue(item)
                PlayEnd.FOCUS_LOST -> {
                    // 6.3 거부: 대기, STOP 계열이면 헤드업 알림. 음성이 막혀도 정차 안내는 눈으로라도 닿아야 한다
                    if (item.rank == VoicePriority.GLUCOSE_STOP && !item.escalated) {
                        item.escalated = true
                        _undelivered.tryEmit(item.resolved)
                    }
                    // 통화나 내비 안내가 끝나기를 기다린다. 바로 재시도하면 같은 손실만 반복한다
                    requeue(item)
                    delay(FOCUS_RETRY_MS)
                }
            }
        }
    }

    private fun requeue(item: Item) {
        synchronized(lock) {
            pending += item
            publish()
        }
    }

    private suspend fun awaitNext(): Item {
        while (true) {
            val next = synchronized(lock) {
                dropStale()
                if (signals.gear.value == Gear.REVERSE) {
                    null
                } else {
                    pending.minWithOrNull(compareBy({ it.rank.ordinal }, { it.seq }))?.also {
                        pending -= it
                        playing = it
                        interrupt = null
                        publish()
                    }
                }
            }
            if (next != null) return next
            wake.receive()
        }
    }

    /** 6.3 폐기. ③, ④만 버린다. ①, ②는 늦어도 말해야 한다 */
    private fun dropStale() {
        val now = clock.nowMs()
        val limit = policy.QUEUE_STALE_SEC * 1000
        val removed = pending.removeAll {
            (it.rank == VoicePriority.NOTICE || it.rank == VoicePriority.INFO) && now - it.atMs > limit
        }
        if (removed) publish()
    }

    private enum class PlayEnd { FINISHED, REQUEUE, FOCUS_LOST }

    private suspend fun play(item: Item): PlayEnd {
        var focusLost = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                focusLost = perform(item)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // 포트 예외로 큐 전체가 멈추면 이후 안내를 모두 잃는다. 실패와 같게 처리한다
                _undelivered.tryEmit(item.resolved)
                replyFailed(item)
            }
        }
        val cancelledEarly = synchronized(lock) {
            playJob = job
            interrupt != null
        }
        if (cancelledEarly) job.cancel()
        job.start()
        job.join()
        _listening.value = false
        return synchronized(lock) {
            val reason = interrupt
            playing = null
            playJob = null
            interrupt = null
            publish()
            when {
                reason == Interrupt.REVERSE -> PlayEnd.REQUEUE
                reason != null -> PlayEnd.FINISHED      // 끊긴 음성은 폐기하고 엔진의 재안내 시각에 맡긴다
                focusLost -> PlayEnd.FOCUS_LOST
                else -> PlayEnd.FINISHED
            }
        }
    }

    /** 포커스를 잃었으면 true */
    private suspend fun perform(item: Item): Boolean {
        // 장소 없는 혈당 정차 안내는 말하는 순간의 판정으로 고른다. 통화로 밀린 사이 고속도로에 올랐을 수 있다(V-07).
        // 판정은 히스테리시스가 있어 정체로 잠깐 느려져도 고속 문구가 유지된다(HighwayGuess)
        val onHighway = highway.likely()
        val vars = item.vars.copy(highwayLikely = onHighway)
        val resolved = if (vars.placeName == null) VoiceCatalog.resolveNoPlace(item.voice, onHighway) else item.voice
        item.resolved = resolved
        // N 변형은 묻지 않는 문장이다. 장소를 찾으면 런타임이 원래 음성으로 다시 넣는다
        val question = item.reply !is Reply.None && resolved == item.voice
        val micReady = question && listener.ready()
        // 듣지 못하면 묻지 않는다. 질문만 하고 대답을 버리면 운전자는 답했는데 무시당한다(10.2)
        val oneWay = question && !micReady
        val actual = if (oneWay) {
            val statement = VoiceCatalog.oneWay(resolved)
            if (statement == null) {
                replyFailed(item)
                return false
            }
            statement
        } else {
            resolved
        }
        val privacy = PrivacyContext(passengerPresentOrUnknown = occupancy.passengerPresent.value != false)
        val text = VoiceCatalog.render(actual, vars, privacy)
        if (text.isBlank()) {
            if (question) replyFailed(item)
            return false
        }
        return when (speaker.speak(text, VoiceCatalog.priority(actual))) {
            SpeakResult.DONE -> {
                when {
                    micReady -> {
                        delay(policy.TTS_TO_STT_MS)
                        replyHeard(item, listen())
                    }
                    oneWay -> replyFailed(item)
                }
                false
            }
            // 우리가 끊었으면 interrupt가 먼저 설정돼 있다. 아니면 포커스 문제라 재큐잉한다
            SpeakResult.INTERRUPTED, SpeakResult.DENIED -> true
            SpeakResult.FAILED -> {
                _undelivered.tryEmit(actual)
                if (question) replyFailed(item)
                false
            }
        }
    }

    private suspend fun listen(): ListenResult {
        if (!listener.available.value) return ListenResult.Failed(retriable = false)
        // "듣고 있어요"는 마이크가 실제로 열린 뒤에 켠다. 준비까지 1~2초 걸린다(실측)
        val onReady = { _listening.value = true }
        try {
            val first = listener.listen(policy.LISTEN_TIMEOUT_MS, onReady)
            return if (first is ListenResult.Failed && first.retriable) listener.listen(policy.LISTEN_TIMEOUT_MS, onReady) else first
        } finally {
            _listening.value = false
        }
    }

    private fun replyHeard(item: Item, result: ListenResult) {
        val text = (result as? ListenResult.Heard)?.text
        when (val reply = item.reply) {
            Reply.None -> Unit
            // 인식이 실패했으면 text가 없어 NO_SPEECH다. 듣지 못한 답을 "보통"으로 적으면 S2가 없는 응답을 보여 준다
            is Reply.Checkin -> emitCheckin(reply, VoiceClassifier.checkin(text))
            is Reply.Prompt -> emitPrompt(reply, VoiceClassifier.stopPrompt(text))
            is Reply.YesNo -> reply.onAnswer(VoiceClassifier.yesNo(text))
        }
    }

    /** 10.2. 음성으로 물을 수 없으면 일방 안내다. 체크인은 응답 없음, 프롬프트는 카운트 없이 재안내(E3) */
    private fun replyFailed(item: Item) {
        when (val reply = item.reply) {
            Reply.None -> Unit
            is Reply.Checkin -> emitCheckin(reply, CheckinAnswer.NO_SPEECH)
            is Reply.Prompt -> emitPrompt(reply, PromptAnswer.NO_SPEECH)
            is Reply.YesNo -> reply.onAnswer(PromptAnswer.NO_SPEECH)
        }
    }

    private fun emitCheckin(reply: Reply.Checkin, answer: CheckinAnswer) {
        _heard.tryEmit(CheckinHeard(clock.nowMs(), reply.promptId, answer))
    }

    private fun emitPrompt(reply: Reply.Prompt, answer: PromptAnswer) {
        _heard.tryEmit(PromptHeard(clock.nowMs(), reply.promptId, answer))
    }

    private fun publish() {
        _busy.value = playing != null || pending.isNotEmpty()
        _current.value = playing?.voice
    }

    private companion object {
        const val HEARD_BUFFER = 16
        const val FOCUS_RETRY_MS = 1000L

        // 6.3 예외에 5.5의 V02 다음 V08을 더했다. V03을 생략하는 대신 혈당 질문이 이어져야 한다
        val SEQUENTIAL = setOf(
            VoiceId.V02 to VoiceId.V03,
            VoiceId.V16 to VoiceId.V01,
            VoiceId.V16C to VoiceId.V01,
            VoiceId.V19 to VoiceId.V01,
            VoiceId.V19 to VoiceId.V20,
            VoiceId.V19R to VoiceId.V01,
            VoiceId.V02 to VoiceId.V08,
            VoiceId.V02 to VoiceId.V08F,
        )
        val DRIVING_ONLY = setOf(VoiceId.V02, VoiceId.V04, VoiceId.V05, VoiceId.V06, VoiceId.V08, VoiceId.V08F)
        val LOSS_NOTICES = setOf(VoiceId.V19, VoiceId.V19R)
        val GLUCOSE_REPEATS = setOf(VoiceId.V10, VoiceId.V10N, VoiceId.V10U, VoiceId.V10H)
    }
}
